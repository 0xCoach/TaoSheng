package app.resound.capture

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.graphics.Rect
import android.media.AudioManager
import android.media.AudioRecordingConfiguration
import android.os.Build
import android.view.WindowInsets
import android.view.WindowManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import app.resound.AppGraph
import app.resound.audio.OwnRecordingSessions
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

/** 仅在语音窗口及本次识字期间读取节点；关闭、暂停、锁屏都立即丢弃内存快照。 */
class VoiceInputService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val ocr by lazy { AndroidVoiceInputOcr(this) }
    private var engine: VoiceInputEngine? = null
    private var manager: AudioManager? = null
    private var registered = false
    private var running = false
    private val capture get() = if (AppGraph.isVoiceInputReady) AppGraph.voiceInput as? AndroidVoiceInputCapture else null
    private fun allowed(): Boolean = capture?.settings?.value?.enabled == true && AppGraph.isReady &&
        AppGraph.capture.status.value.state in setOf(CaptureState.Listening, CaptureState.Recording, CaptureState.MicBusy) &&
        getSystemService(PowerManager::class.java).isInteractive && !getSystemService(KeyguardManager::class.java).isKeyguardLocked
    private val callback = object : AudioManager.AudioRecordingCallback() {
        override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>?) { sample(configs.orEmpty()) }
    }
    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            if (!allowed()) engine?.reset()
            else sample(runCatching { manager?.activeRecordingConfigurations.orEmpty() }.getOrDefault(emptyList()))
            handler.postDelayed(this, 250)
        }
    }
    override fun onServiceConnected() {
        super.onServiceConnected()
        connected.value = true
        engine = VoiceInputEngine(::requestScreenshot) { capture?.accept(it) }
        manager = getSystemService(AudioManager::class.java)
        capture?.let { c -> scope.launch {
            combine(c.settings, AppGraph.capture.status) { settings, state -> settings to (state.state in setOf(CaptureState.Listening, CaptureState.Recording, CaptureState.MicBusy)) }
                .distinctUntilChanged().collect {
                    engine?.reset()
                    if (it.first.enabled) startMonitoring() else stopMonitoring()
                }
        } }
    }
    private fun startMonitoring() {
        if (running) return
        running = true
        registered = runCatching { manager?.registerAudioRecordingCallback(callback, handler); true }.getOrDefault(false)
        handler.post(tick)
    }
    private fun stopMonitoring() {
        running = false; handler.removeCallbacks(tick)
        if (registered) runCatching { manager?.unregisterAudioRecordingCallback(callback) }
        registered = false; engine?.reset()
    }
    private fun sample(configs: List<AudioRecordingConfiguration>) {
        if (!allowed()) { engine?.reset(); return }
        val other = configs.any { !it.isClientSilenced && !OwnRecordingSessions.contains(it.clientAudioSessionId) }
        val e = engine ?: return
        val screen = if (other || e.needsScreen) safeSnapshot(null) else null
        e.recording(other, System.currentTimeMillis(), screen, capture!!.settings.value)
    }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!allowed()) { engine?.reset(); return }
        val e = engine ?: return
        // 先查录音状态，防止文字事件早于系统录音回调；不从 event.text 读取密码或被排除应用。
        val configs = runCatching { manager?.activeRecordingConfigurations.orEmpty() }.getOrDefault(emptyList())
        val other = configs.any { !it.isClientSilenced && !OwnRecordingSessions.contains(it.clientAudioSessionId) }
        if (!other && !e.needsScreen) return
        val screen = safeSnapshot(event)
        e.recording(other, System.currentTimeMillis(), screen, capture!!.settings.value)
    }
    private fun safeSnapshot(event: AccessibilityEvent?): InputScreen? = runCatching { snapshot(event) }.getOrNull()
    private fun snapshot(event: AccessibilityEvent?): InputScreen? {
        // 输入法浮窗出现时仍选择应用窗口，不能把键盘候选词当上下文。
        val visibleWindows = windows
        val root = try {
            visibleWindows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_APPLICATION && (it.isActive || it.isFocused) }?.root
                ?: rootInActiveWindow
        } finally { @Suppress("DEPRECATION") visibleWindows.forEach { it.recycle() } } ?: return null
        try {
            val pkg = root.packageName?.toString() ?: return null
            val settings = capture!!.settings.value
            if (!VoiceInputText.allowed(settings, AppGraph.capture.status.value.state, pkg, packageName, false)) return null
            // 有的应用（如微信）在语音面板弹出时 findFocus 拿不到输入框：退而在界面里找“有焦点的可编辑框”，再不行用唯一的可编辑框
            val focus = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.takeIf { it.isEditable } ?: findEditable(root)
            dbgSnap("界面：有输入框=${focus != null} 可编辑=${focus?.isEditable == true} 可见=${focus?.isVisibleToUser == true}")
            try {
                if (focus?.isPassword == true) return null
                val editable = focus?.takeIf { it.isEditable && it.isVisibleToUser }
                val field = editable?.let { "${it.windowId}:${it.viewIdResourceName}:${it.hashCode()}" }
                val text = editable?.text?.toString()?.take(8000)
                val lines = mutableListOf<String>()
                var title: String? = null
                var visited = 0
                val rootBounds = Rect(); root.getBoundsInScreen(rootBounds)
                fun walk(node: AccessibilityNodeInfo, depth: Int) {
                    if (++visited > 400 || depth > 30 || !node.isVisibleToUser || node.isPassword || node.isEditable) return
                    val cls = node.className?.toString().orEmpty()
                    if (cls.contains("Button") || cls.contains("ImageView") || node.isCheckable) return
                    val value = node.text?.toString()?.trim()?.take(1500)
                    val rect = Rect(); node.getBoundsInScreen(rect)
                    if (!value.isNullOrBlank() && VoiceInputChrome.keep(value)) {
                        val id = node.viewIdResourceName.orEmpty().lowercase()
                        if (title == null && value.length <= 80 && (node.isHeading || id.contains("title") ||
                            rect.bottom < rootBounds.top + rootBounds.height() / 6)) title = value
                        else if (!node.isClickable || value.length > 12) lines.add(value)
                    }
                    for (i in 0 until node.childCount) node.getChild(i)?.let { child ->
                        try { walk(child, depth + 1) } finally { recycle(child) }
                    }
                }
                // title 也是上下文；关闭上下文时不遍历整棵树。
                if (settings.context) walk(root, 0)
                val label = runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
                // 标题是猜的（滚动、弹出语音面板都会变），不能用来判断是否换了页面
                val page = "$pkg:${root.windowId}:${root.className}"
                var send = false
                if (event?.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED && event.packageName?.toString() == pkg) {
                    event.source?.let { node -> try {
                        if (!node.isPassword && !node.isEditable) send = node.text?.toString() in VoiceInputChrome.send || node.contentDescription?.toString() in VoiceInputChrome.send
                    } finally { recycle(node) } }
                }
                return InputScreen(pkg, label, page, title, field, text, lines = lines.takeLast(20), send = send)
            } finally { focus?.let(::recycle) }
        } finally { recycle(root) }
    }
    /** 找输入框：按层找（输入框一般在底部，逐个深挖会先耗在长长的聊天记录里），优先有焦点的，没有就取唯一一个 */
    private fun findEditable(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val found = mutableListOf<AccessibilityNodeInfo>()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(AccessibilityNodeInfo.obtain(root))
        var visited = 0
        while (queue.isNotEmpty() && visited < 3000) {
            val node = queue.removeFirst(); visited++
            if (node.isEditable && node.isVisibleToUser && !node.isPassword) found += node
            for (i in 0 until node.childCount) node.getChild(i)?.let(queue::add)
            if (node !in found) recycle(node)
        }
        queue.forEach(::recycle)
        val pick = found.firstOrNull { it.isFocused } ?: found.singleOrNull()
        dbgSnap("找输入框：看了 $visited 个元素，可编辑 ${found.size} 个，选中=${pick != null}")
        found.filter { it !== pick }.forEach(::recycle)
        return pick
    }
    private fun requestScreenshot(request: OcrRequest) {
        val e = engine ?: return
        fun valid(): Boolean {
            if (!allowed() || capture?.settings?.value?.screenOcr != true || !e.accepts(request)) return false
            val current = safeSnapshot(null) ?: return false
            return !current.password && current.packageName == request.screen.packageName && current.page == request.screen.page
        }
        if (Build.VERSION.SDK_INT < 30 || !valid()) { e.recognized(request, null); return }
        val layout = runCatching {
            val metrics = getSystemService(WindowManager::class.java).maximumWindowMetrics
            val insets = metrics.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            val visible = windows
            val ime = try {
                visible.filter { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }.mapNotNull { w ->
                    val bounds = Rect(); w.getBoundsInScreen(bounds)
                    bounds.top.takeIf { bounds.width() > 0 && bounds.height() > 0 && it > insets.top }
                }.minOrNull()
            } finally { @Suppress("DEPRECATION") visible.forEach { it.recycle() } }
            OcrLayout(metrics.bounds.width(), metrics.bounds.height(), insets.top,
                metrics.bounds.height() - insets.bottom, ime)
        }.getOrNull()
        if (layout == null) { e.recognized(request, null); return }
        ocr.capture(layout, ::valid) { result -> e.recognized(request, result) }
    }

    private var lastSnapLog = ""
    private fun dbgSnap(msg: String) { if (msg != lastSnapLog) { lastSnapLog = msg; runCatching { android.util.Log.d("VoiceInputDbg", msg) } } }

    override fun onInterrupt() { engine?.reset() }
    override fun onDestroy() { connected.value = false; stopMonitoring(); scope.cancel(); super.onDestroy() }
    @Suppress("DEPRECATION") private fun recycle(node: AccessibilityNodeInfo) { node.recycle() }
    companion object {
        /** 服务是否真的在运行：小米上常见“设置里开着、系统却没启动服务” */
        val connected = kotlinx.coroutines.flow.MutableStateFlow(false)
    }
}
