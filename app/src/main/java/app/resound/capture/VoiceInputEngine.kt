package app.resound.capture

import app.resound.transcribe.VoiceInputMeta
import java.util.UUID

/** 不持有无障碍节点；全部时钟由调用者传入，便于验证释放宽限与单次截图。 */
internal data class InputScreen(
    val packageName: String, val app: String, val page: String, val title: String?,
    val field: String?, val text: String?, val password: Boolean = false,
    val lines: List<String> = emptyList(), val send: Boolean = false,
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

/** shot：0 = 停止录音后 0.5 秒，1 = 1 秒，2 = 3 秒（负责人定：三张最保险） */
internal data class OcrRequest(val token: String, val at: Long, val screen: InputScreen, val context: Boolean, val shot: Int = 0)

internal class VoiceInputEngine(
    private val requestOcr: (OcrRequest) -> Unit,
    private val output: (InputChange) -> Unit,
) {
    constructor(output: (InputChange) -> Unit) : this({}, output)

    private data class Window(val at: Long, val screen: InputScreen, val settings: VoiceInputSettings,
        var last: String?, var cleared: Boolean = false, var sendHint: Boolean = false, var saved: Boolean = false)
    private var recording = false
    private var releaseAt: Long? = null
    private var window: Window? = null
    /** 一次语音输入的截图识字：三张的结果凑齐（或不能再截）后合成一条 */
    private class OcrJob(val token: String, val at: Long, val screen: InputScreen, val context: Boolean, val stopped: Long) {
        val issued = BooleanArray(3); val done = BooleanArray(3); val results = arrayOfNulls<OcrInput>(3)
        var closed = false
    }
    private var job: OcrJob? = null
    val needsScreen: Boolean get() = recording || window != null || job != null

    fun reset() { window = null; job = null; recording = false; releaseAt = null }
    private fun same(a: InputScreen, b: InputScreen?) = b != null && !b.password &&
        a.packageName == b.packageName && a.page == b.page

    fun recording(active: Boolean, now: Long, screen: InputScreen?, settings: VoiceInputSettings) {
        // 新录音优先取消异步识字，不能在新窗口开始时再截上一窗口。
        if (active && !recording) {
            finishWindow()
            job?.let(::finishJob)
            window = screen?.takeIf { !it.password }?.let { Window(now, it, settings, it.text) }
            releaseAt = null
            dbg("语音窗口开始：有界面=${screen != null} 有输入框=${screen?.field != null}")
        }
        tick(now, screen, settings)
        if (!active && recording) { releaseAt = now; dbg("停止录音：1 秒检查识字，3 秒收尾原文") }
        recording = active
    }
    fun tick(now: Long, screen: InputScreen?, settings: VoiceInputSettings) {
        if (!settings.enabled) { reset(); return }
        job?.let { j ->
            if (!same(j.screen, screen) || !settings.screenOcr || screen!!.packageName in settings.excludedPackages) {
                // 离开了：已识出的照用，没截的不再截
                j.closed = true; maybeFinish(j)
            } else {
                if (now >= j.stopped + 1000) issue(j, 1)
                if (now >= j.stopped + 3000) issue(j, 2)
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
                    else { w.last = screen.text; w.cleared = false }
                }
                val inserted = VoiceInputText.inserted(w.screen.text, w.last.orEmpty())
                val echoed = inserted.isNotBlank() && screen.lines.any {
                    it !in w.screen.lines && (VoiceInputText.similar(it, w.last.orEmpty()) || VoiceInputText.similar(it, inserted))
                }
                if (w.cleared && (w.sendHint || echoed)) save(w)
            }
            val stopped = releaseAt ?: return
            val unreadable = w.screen.field == null || w.screen.text == null || screen.field == null ||
                screen.text == null || (w.screen.text.isEmpty() && w.last.isNullOrEmpty())
            val hasOriginal = w.screen.text != null && VoiceInputText.inserted(w.screen.text, w.last.orEmpty()).isNotBlank()
            if (now >= stopped + 500 && unreadable && !hasOriginal && !w.saved && !w.cleared) {
                window = null; releaseAt = null // 请求前消费窗口；失败也不重试。
                if (settings.screenOcr && w.settings.screenOcr) {
                    val j = OcrJob(UUID.randomUUID().toString(), w.at, screen, w.settings.context && settings.context, stopped)
                    job = j
                    issue(j, 0)
                }
            } else if (now >= stopped + 3000) finishWindow()
        }
    }
    private fun issue(j: OcrJob, shot: Int) {
        if (j.closed || j.issued[shot]) return
        j.issued[shot] = true
        dbg("截屏识字：第 ${shot + 1} 张")
        requestOcr(OcrRequest(j.token, j.at, j.screen, j.context, shot))
    }
    fun accepts(request: OcrRequest): Boolean = job?.let { it.token == request.token && !it.done[request.shot] } == true
    fun recognized(request: OcrRequest, result: OcrInput?) {
        val j = job?.takeIf { accepts(request) } ?: return
        j.done[request.shot] = true; j.results[request.shot] = result
        maybeFinish(j)
    }
    /** 三张都回来了，或已离开、剩下的不会再截且已发出的都回来了，就合成 */
    private fun maybeFinish(j: OcrJob) {
        val waiting = (0..2).any { j.issued[it] && !j.done[it] }
        val more = !j.closed && (0..2).any { !j.issued[it] }
        if (!waiting && !more) finishJob(j)
    }
    private fun finishJob(j: OcrJob) {
        if (job === j) job = null
        val early = j.results[0]; val mid = j.results[1]; val late = j.results[2]
        // 输入框：以 1 秒为准，没有用 0.5 秒
        val box = mid?.text?.takeIf { it.isNotBlank() } ?: early?.text?.takeIf { it.isNotBlank() }
        val base = mid?.takeIf { it.text.isNotBlank() } ?: early?.takeIf { it.text.isNotBlank() } ?: mid ?: early ?: late
        // 3 秒：出现与输入框相近的新靠右气泡 = 真正发出去的那句，以它为准
        val sent = late?.bubble?.takeIf { b -> base?.context?.none { VoiceInputText.normalized(it) == VoiceInputText.normalized(b) } != false }
        val text = when {
            box != null && sent != null && VoiceInputText.similar(sent, box) -> sent
            box != null -> box
            // 输入框没读到：只有前面截到过界面、且这条气泡是新冒出来的才算
            sent != null && (early != null || mid != null) -> sent
            else -> null
        }
        dbg("截屏识字合成：输入框字数=${box?.length ?: 0} 发出气泡字数=${sent?.length ?: 0} 采用=${if (text == null) "无" else if (text == sent) "气泡" else "输入框"}")
        if (text.isNullOrBlank() || base == null) return
        output(InputChange.Save(InputRecord(j.token, j.at, text,
            VoiceInputMeta(j.screen.app, j.screen.packageName, if (j.context) base.title else null,
                if (j.context) VoiceInputText.context(base.context) else null, null), null)))
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
        output(InputChange.Save(InputRecord(UUID.randomUUID().toString(), w.at, text,
            VoiceInputMeta(w.screen.app, w.screen.packageName, w.screen.title,
                if (w.settings.context) VoiceInputText.context(w.screen.lines) else null, null), null)))
    }
}

/** 诊断日志：只写步骤和数字，绝不写文字内容。 */
internal fun dbg(msg: String) { runCatching { android.util.Log.d("VoiceInputDbg", msg) } }
