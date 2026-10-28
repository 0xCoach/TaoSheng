package app.resound.capture

import app.resound.transcribe.VoiceInputMeta
import java.util.UUID

/** 不持有无障碍节点；全部时钟由调用者传入，便于验证释放宽限与单次截图。 */
internal data class InputScreen(
    val packageName: String, val app: String, val page: String, val title: String?,
    val field: String?, val text: String?, val password: Boolean = false,
    val lines: List<String> = emptyList(), val send: Boolean = false,
    /** 这次是应用界面变了（文字、内容变化）触发的，用来察觉键盘发送键之类看不到的发送 */
    val changed: Boolean = false,
)
internal data class InputRecord(val token: String, val at: Long, val text: String, val meta: VoiceInputMeta,
    val replyUntil: Long?)
internal sealed interface InputChange {
    data class Save(val record: InputRecord) : InputChange
    data class Finish(val token: String, val after: String?) : InputChange
    data class Sent(val token: String, val until: Long) : InputChange
}
internal object VoiceInputText {
    fun inserted(before: String, after: String): String {
        if (before == after || after.isEmpty()) return ""
        var left = 0
        while (left < minOf(before.length, after.length) && before[left] == after[left]) left++
        var right = 0
        while (right < minOf(before.length - left, after.length - left) &&
            before[before.lastIndex - right] == after[after.lastIndex - right]) right++
        // 只接受纯插入，避免把删除/替换旧草稿误认为语音结果。
        if (left + right != before.length) return ""
        return after.substring(left, after.length - right).trim()
    }
    fun context(lines: List<String>): String? = lines.map(String::trim).filter(String::isNotBlank)
        .takeLast(20).joinToString("\n").takeLast(1500).ifBlank { null }
    fun normalized(text: String) = text.filter(Char::isLetterOrDigit).lowercase()
    fun similar(a: String, b: String): Boolean {
        val x = normalized(a); val y = normalized(b)
        if (x.isEmpty() || y.isEmpty()) return false
        if (x == y) return true
        if (maxOf(x.length, y.length) > 2000) return false
        if (minOf(x.length, y.length) < 6 || kotlin.math.abs(x.length - y.length) > maxOf(x.length, y.length) / 5) return false
        var prev = IntArray(y.length + 1) { it }
        for (i in x.indices) {
            val next = IntArray(y.length + 1); next[0] = i + 1
            for (j in y.indices) next[j + 1] = minOf(next[j] + 1, prev[j + 1] + 1, prev[j] + if (x[i] == y[j]) 0 else 1)
            prev = next
        }
        return prev[y.length].toDouble() / maxOf(x.length, y.length) <= .15
    }
    fun allowed(settings: VoiceInputSettings, state: CaptureState, pkg: String, own: String, password: Boolean) =
        settings.enabled && state in setOf(CaptureState.Listening, CaptureState.Recording, CaptureState.MicBusy) &&
            pkg != own && pkg !in settings.excludedPackages && !password
}

/** 截图识字时，每次停下说话后几毫秒截图看输入框：0.5、2、5 秒盖住最常见的发送时间，15 秒兜底；之后靠“点发送 / 界面变了”再截 */
internal val SHOTS = longArrayOf(500, 2000, 5000, 15_000)
/** 点了发送或界面变了，等这么久再截（让气泡先出来）；两张之间至少隔开这么久 */
internal const val SEND_SHOT_DELAY = 500L
internal const val SHOT_GAP = 1500L
/** 界面一直在变（AI 正在出字、终端在刷屏）时，一条消息最多因“界面变了”截这么多张；点发送按钮不限 */
internal const val CHANGE_SHOTS = 5
/** 读得到原文时，停下来这么久没再说、也没发送，就算这条说完了 */
internal const val IDLE_MS = 60_000L
/** 读界面拿到的上下文少于这么多行（微信的聊天气泡读不到），就截一张图补上下文 */
internal const val CONTEXT_MIN_LINES = 3

internal data class OcrRequest(val token: String, val at: Long, val screen: InputScreen, val context: Boolean, val shot: Int = 0)

internal class VoiceInputEngine(
    private val requestOcr: ((OcrRequest) -> Unit)?,
    private val output: (InputChange) -> Unit,
) {
    constructor(output: (InputChange) -> Unit) : this(null, output)

    private data class Window(val at: Long, val screen: InputScreen, val settings: VoiceInputSettings,
        var last: String?, var cleared: Boolean = false, var sendHint: Boolean = false, var saved: Boolean = false,
        /** 这条消息里见过的最长文字：一下子清空 = 发送；一点点删到空 = 删掉了 */
        var peak: Int = 0)
    private var recording = false
    private var releaseAt: Long? = null
    private var window: Window? = null
    /** 截图识字的一条消息：分几段说都算这一条，点发送（或离开、60 秒没动静）才收尾 */
    private class OcrJob(val token: String, val at: Long, val screen: InputScreen, val context: Boolean, var active: Long) {
        var seq = 0
        var changeShots = 0
        val waiting = mutableSetOf<Int>()
        val planned = sortedSetOf<Long>()
        var lastIssued = Long.MIN_VALUE / 2
        var now = active
        var recording = false
        var closed = false
        /** 第一张识出的：用来分辨哪些气泡原来就有 */
        var first: OcrInput? = null
        /** 输入框里最全的文字，和取到它的那张 */
        var box: String? = null
        var boxShot: OcrInput? = null
        /** 发出去的那句（新出现的右侧气泡），和发送后的那张 */
        var sent: String? = null
        var sentShot: OcrInput? = null
        /** 输入框刚空、还没看到发出的气泡：再截一张确认（发得快时截到的可能只是残渣） */
        var emptyShot: OcrInput? = null
        var emptied = false
        /** 开始说下一段时截的那张：看上一段发出去没有 */
        var boundary = -1
        var boundaryAt = 0L
        fun plan(from: Long) { planned.clear(); SHOTS.forEach { planned += from + it } }
    }
    private var job: OcrJob? = null
    /** 原文已读到、正等截图补上下文的记录；截图没回来也照原样存，不能丢 */
    private val contextWaits = linkedMapOf<String, InputRecord>()
    val needsScreen: Boolean get() = recording || window != null || job != null

    fun reset() {
        contextWaits.values.toList().also { contextWaits.clear() }.forEach { output(InputChange.Save(it)) }
        window = null; job = null; recording = false; releaseAt = null
    }
    private fun same(a: InputScreen, b: InputScreen?) = b != null && !b.password &&
        a.packageName == b.packageName && a.page == b.page

    fun recording(active: Boolean, now: Long, screen: InputScreen?, settings: VoiceInputSettings) {
        // 新录音优先取消异步识字，不能在新窗口开始时再截上一窗口。
        if (active && !recording) {
            val j = job
            // 截图识字的应用里停顿后接着说：还没发送，还是同一条消息
            if (j != null && !j.closed && same(j.screen, screen)) {
                j.recording = true; j.planned.clear(); recording = true; releaseAt = null
                // 连着发好几段时，上一段可能已经发出去了：先截一张看看，发了就收尾、这段另起一条
                j.boundaryAt = now; j.boundary = j.seq; issue(j, now)
                dbg("接着说：先看上一段发出去没有")
                tick(now, screen, settings); return
            }
            val w = window
            // 同一个输入框里停顿后接着说：还是同一条消息，不收尾
            if (w != null && !w.saved && w.screen.field != null && same(w.screen, screen) && screen!!.field == w.screen.field) {
                releaseAt = null; recording = true
                dbg("接着说：同一条消息")
                tick(now, screen, settings); return
            }
            finishWindow()
            job?.let(::finishJob)
            window = screen?.takeIf { !it.password }?.let { Window(now, it, settings, it.text) }
            releaseAt = null
            dbg("语音窗口开始：有界面=${screen != null} 有输入框=${screen?.field != null}")
        }
        tick(now, screen, settings)
        if (!active && recording) {
            releaseAt = now; dbg("停止录音")
            job?.takeIf { it.recording }?.let { it.recording = false; it.active = now; it.plan(now) }
        }
        recording = active
    }
    fun tick(now: Long, screen: InputScreen?, settings: VoiceInputSettings) {
        if (!settings.enabled) { reset(); return }
        job?.let { j ->
            j.now = now
            if (!same(j.screen, screen) || !settings.screenOcr || screen!!.packageName in settings.excludedPackages) {
                // 离开了：已识出的照用，没截的不再截
                j.closed = true; maybeFinish(j)
            } else if (!j.recording) {
                // 点了发送（按钮或键盘发送键都会让界面变）：等气泡出来再截一张
                val byChange = screen.changed && j.changeShots < CHANGE_SHOTS && now - j.lastIssued >= SHOT_GAP
                if ((screen.send || byChange) && j.box != null && j.planned.none { it <= now + SEND_SHOT_DELAY }) {
                    if (!screen.send) j.changeShots++
                    j.planned += now + SEND_SHOT_DELAY
                }
                if (now >= j.active + IDLE_MS) { j.closed = true; maybeFinish(j) }
                else j.planned.firstOrNull()?.takeIf { it <= now }?.let { j.planned.remove(it); issue(j, now) }
            }
        }
        window?.let { w ->
            if (!same(w.screen, screen) || screen!!.packageName in settings.excludedPackages) {
                // 已读到的原文沿用卡 048 收尾；截图候选离开后直接丢弃。
                finishWindow(); return
            }
            if (!w.saved && (w.screen.field == null || w.screen.text == null) &&
                screen.field != null && !screen.text.isNullOrBlank()) {
                // 面板退去后节点恢复时直接采用原文，不再截图。
                window = w.copy(screen = screen.copy(text = ""), last = screen.text)
                tick(now, screen, settings)
                return
            }
            if (!w.saved && w.screen.field != null && w.screen.text != null) {
                if (screen.field != null && screen.field != w.screen.field) { window = null; return }
                w.sendHint = w.sendHint || screen.send
                if (screen.field == w.screen.field && screen.text != null) {
                    if (screen.text.isEmpty() && !w.last.isNullOrEmpty()) w.cleared = true
                    else { w.last = screen.text; w.cleared = false; w.peak = maxOf(w.peak, screen.text.length) }
                }
                val inserted = VoiceInputText.inserted(w.screen.text, w.last.orEmpty())
                val echoed = inserted.isNotBlank() && screen.lines.any {
                    it !in w.screen.lines && (VoiceInputText.similar(it, w.last.orEmpty()) || VoiceInputText.similar(it, inserted))
                }
                // 很多应用的发送键没写“发送”（ChatGPT、Gemini 是图标）：输入框一下子清空就算发出去了；一点点删光才算删掉
                if (w.cleared) {
                    val sentLike = w.sendHint || echoed || (w.last?.length ?: 0) * 2 >= w.peak
                    if (sentLike) save(w) else { dbg("删光了，不记"); window = null; return }
                }
            }
            val stopped = releaseAt ?: return
            val unreadable = w.screen.field == null || w.screen.text == null || screen.field == null ||
                screen.text == null || (w.screen.text.isEmpty() && w.last.isNullOrEmpty())
            val hasOriginal = w.screen.text != null && VoiceInputText.inserted(w.screen.text, w.last.orEmpty()).isNotBlank()
            if (now >= stopped + 500 && unreadable && !hasOriginal && !w.saved && !w.cleared) {
                window = null; releaseAt = null // 请求前消费窗口；失败也不重试。
                if (settings.screenOcr && w.settings.screenOcr) {
                    val j = OcrJob(UUID.randomUUID().toString(), w.at, screen, w.settings.context && settings.context, stopped)
                    j.now = now; j.plan(stopped)
                    job = j
                    j.planned.remove(j.planned.first()); issue(j, now)
                }
            } else if (unreadable && now >= stopped + 3000 || now >= stopped + IDLE_MS) finishWindow()
        }
    }
    private fun issue(j: OcrJob, now: Long) {
        if (j.closed) return
        val shot = j.seq++
        j.waiting += shot; j.lastIssued = now
        dbg("截屏识字：第 ${shot + 1} 张")
        requestOcr?.invoke(OcrRequest(j.token, j.at, j.screen, j.context, shot))
    }
    fun accepts(request: OcrRequest): Boolean = request.token in contextWaits ||
        job?.let { it.token == request.token && request.shot in it.waiting } == true
    fun recognized(request: OcrRequest, result: OcrInput?) {
        contextWaits.remove(request.token)?.let { record ->
            val shot = result?.context.orEmpty()
            val read = record.meta.before?.lines().orEmpty()
            val meta = if (shot.size > read.size) record.meta.copy(before = VoiceInputText.context(shot),
                title = record.meta.title ?: result?.title) else record.meta
            dbg("补上下文：读界面 ${read.size} 行，截图 ${shot.size} 行，采用=${if (meta === record.meta) "读界面" else "截图"}")
            output(InputChange.Save(record.copy(meta = meta)))
            return
        }
        val j = job?.takeIf { accepts(request) } ?: return
        j.waiting -= request.shot
        // 终端只认提示符那一行；占位提示说明输入框已空
        val accepted = if (j.screen.packageName in VoiceInputOcr.TERMINAL)
            result?.takeIf { it.prompt && !VoiceInputOcr.terminalPlaceholder(it.text) }?.copy(bubble = null)
        else result
        val first = j.first
        // 同一应用里换了聊天（微信的聊天和列表在系统看来常是同一页面）：标题变了就当已离开，这张不用
        if (first?.title != null && accepted?.title != null && first.title != accepted.title) {
            dbg("截屏识字：标题变了，当作已离开")
            j.closed = true; maybeFinish(j); return
        }
        if (first == null && accepted != null) j.first = accepted
        val t = VoiceInputOcr.clean(accepted?.text)
        val fresh = if (first == null) null else freshBubble(first, accepted)
        val prev = j.box
        dbg("识字结果：第 ${request.shot + 1} 张 截到=${result != null} 输入框字数=${t?.length ?: 0} 原输入框字数=${prev?.length ?: 0} 新气泡字数=${fresh?.length ?: 0}${if (request.shot == j.boundary) " （开始说下一段时）" else ""}")
        if (request.shot == j.boundary) {
            j.boundary = -1
            val gone = prev != null && result != null && (fresh != null || t == null || !continuesBox(prev, t))
            if (gone) { split(j, accepted, fresh, t); return }
        }
        // 输入框里换成了不相干的新话：上一段已经发出去了（没看到发送也一样），收尾、新话另起一条
        if (prev != null && t != null && !continuesBox(prev, t) && !prev.startsWith(t) &&
            !VoiceInputText.normalized(prev).contains(VoiceInputText.normalized(t))) { split(j, accepted, fresh, t); return }
        when {
            fresh != null && (prev == null || VoiceInputText.similar(fresh, prev) ||
                VoiceInputText.normalized(fresh).contains(VoiceInputText.normalized(prev))) -> {
                j.sent = fresh; j.sentShot = accepted; j.closed = true
            }
            t != null -> {
                // 越说越长取新的；变短且是前一张的一部分，说明正在发出或没认全，留前一张
                val keep = prev != null && t.length < prev.length &&
                    (prev.startsWith(t) || VoiceInputText.normalized(prev).contains(VoiceInputText.normalized(t)))
                if (!keep) { if (t != prev) j.active = j.now; j.box = t; j.boxShot = accepted }
                j.emptied = false
            }
            // 截到了、输入框却空了：再看一张，还没有新气泡（发送键是图标或键盘回车、气泡没认出）也算发出去了
            prev != null && result != null -> {
                if (accepted?.context.orEmpty().isNotEmpty()) j.emptyShot = accepted
                if (j.emptied) { j.sentShot = accepted; j.closed = true }
                else { j.emptied = true; j.planned += j.now + SEND_SHOT_DELAY * 2 }
            }
        }
        maybeFinish(j)
    }
    private fun continuesBox(prev: String, now: String) = now.startsWith(prev) || VoiceInputText.similar(now, prev) ||
        VoiceInputDedup.continues(prev, now) || VoiceInputText.normalized(now).contains(VoiceInputText.normalized(prev))
    /** 上一段已经发出去了：上一段用这张收尾，正在说的这段另起一条（这张当它的底图，上一段的气泡就不算新的） */
    private fun split(j: OcrJob, shot: OcrInput?, fresh: String?, box: String?) {
        val prev = j.box
        if (fresh != null && prev != null && (VoiceInputText.similar(fresh, prev) ||
                VoiceInputText.normalized(fresh).contains(VoiceInputText.normalized(prev)))) j.sent = fresh
        j.sentShot = shot; j.closed = true
        // 新一条从最近一次开始说算起
        val next = OcrJob(UUID.randomUUID().toString(), maxOf(j.boundaryAt, j.at), j.screen, j.context, j.active)
        next.first = shot; next.recording = j.recording; next.now = j.now; next.planned += j.planned
        // 这张里已经是新话：直接当新一条的输入框
        if (box != null && (j.box == null || !continuesBox(j.box!!, box))) { next.box = box; next.boxShot = shot }
        dbg("上一段已发出：收尾，这段另起一条")
        finishJob(j)
        job = next
    }
    private fun maybeFinish(j: OcrJob) { if (j.closed && j.waiting.isEmpty()) finishJob(j) }
    /** 这张图里最下面靠右的气泡，且第一张里没有（是新发出去的） */
    private fun freshBubble(first: OcrInput, r: OcrInput?): String? {
        if (first === r) return null
        val b = VoiceInputOcr.clean(r?.bubble) ?: return null
        val key = VoiceInputText.normalized(b)
        val seen = (first.context + listOfNotNull(first.bubble)).any { VoiceInputText.normalized(it).let { n -> n == key || n.endsWith(key) } }
        return b.takeIf { !seen }
    }
    private fun finishJob(j: OcrJob) {
        if (job === j) job = null
        val text = j.sent ?: j.box
        // 上下文用发送后那张（几段话已拼成一条发出去）；那张没有就用输入框那张
        val base = j.sentShot?.takeIf { it.context.isNotEmpty() } ?: j.emptyShot ?: j.boxShot ?: j.first
        dbg("截屏识字合成：张数=${j.seq} 输入框字数=${j.box?.length ?: 0} 发出气泡字数=${j.sent?.length ?: 0} 发送后截到=${j.sentShot != null} 采用=${if (text == null) "无" else if (text == j.sent) "气泡" else "输入框"}")
        if (text.isNullOrBlank() || base == null) return
        // 发出去的这句自己不算上下文
        val own = VoiceInputText.normalized(text)
        val context = base.context.filterNot { VoiceInputText.normalized(it.substringAfter('：')) == own }
        output(InputChange.Save(InputRecord(j.token, j.at, text,
            VoiceInputMeta(j.screen.app, j.screen.packageName, if (j.context) base.title else null,
                if (j.context) VoiceInputText.context(context) else null, null), null)))
    }
    private fun finishWindow() {
        window?.let { if (!it.saved && !it.cleared) save(it) }
        window = null; releaseAt = null
    }
    private fun save(w: Window) {
        w.saved = true
        val text = w.screen.text?.let { VoiceInputText.inserted(it, w.last.orEmpty()) }.orEmpty()
        dbg("收尾：新增字数=${text.length}")
        if (text.isBlank()) return
        val record = InputRecord(UUID.randomUUID().toString(), w.at, text,
            VoiceInputMeta(w.screen.app, w.screen.packageName, w.screen.title?.takeIf { w.screen.packageName in VoiceInputOcr.TITLED },
                if (w.settings.context) VoiceInputText.context(w.screen.lines) else null, null), null)
        val ocr = requestOcr
        // 原文照读界面的；上下文读不全时另截一张图，离开了或截不成就照原样存
        if (ocr == null || !w.settings.context || !w.settings.screenOcr || w.screen.lines.size >= CONTEXT_MIN_LINES) {
            output(InputChange.Save(record)); return
        }
        contextWaits[record.token] = record
        ocr(OcrRequest(record.token, w.at, w.screen, true))
    }
}

/** 诊断日志：只写步骤和数字，绝不写文字内容。 */
internal fun dbg(msg: String) { VoiceInputTrace.add(msg); runCatching { android.util.Log.d("VoiceInputDbg", msg) } }

/** 最近几百步语音输入判断，随“导出诊断记录”一起导出；同 dbg 一样只有步骤和数字，没有文字内容 */
object VoiceInputTrace {
    private val lines = ArrayDeque<String>()
    private val time = java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm:ss.SSS").withZone(java.time.ZoneId.systemDefault())
    @Synchronized fun add(msg: String) {
        lines.addLast("${time.format(java.time.Instant.now())} $msg")
        while (lines.size > 600) lines.removeFirst()
    }
    @Synchronized fun dump(): String = if (lines.isEmpty()) "语音输入：本次启动后还没有记录" else lines.joinToString("\n") { "语音输入 $it" }
}
