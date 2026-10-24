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
/** 停止录音后几毫秒截图：0.5 秒、1.2 秒取输入框；3 秒看是否已发出；3 秒还没发再看 6 秒 */
internal val SHOTS = longArrayOf(500, 1200, 3000, 6000)
/** 读得到原文时，停下来这么久没再说、也没发送，就算这条说完了 */
internal const val IDLE_MS = 60_000L

internal data class OcrRequest(val token: String, val at: Long, val screen: InputScreen, val context: Boolean, val shot: Int = 0)

internal class VoiceInputEngine(
    private val requestOcr: (OcrRequest) -> Unit,
    private val output: (InputChange) -> Unit,
) {
    constructor(output: (InputChange) -> Unit) : this({}, output)

    private data class Window(val at: Long, val screen: InputScreen, val settings: VoiceInputSettings,
        var last: String?, var cleared: Boolean = false, var sendHint: Boolean = false, var saved: Boolean = false,
        /** 这条消息里见过的最长文字：一下子清空 = 发送；一点点删到空 = 删掉了 */
        var peak: Int = 0)
    private var recording = false
    private var releaseAt: Long? = null
    private var window: Window? = null
    /** 一次语音输入的截图识字：三张的结果凑齐（或不能再截）后合成一条 */
    private class OcrJob(val token: String, val at: Long, val screen: InputScreen, val context: Boolean, val stopped: Long) {
        val issued = BooleanArray(SHOTS.size); val done = BooleanArray(SHOTS.size); val results = arrayOfNulls<OcrInput>(SHOTS.size)
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
        if (!active && recording) { releaseAt = now; dbg("停止录音") }
        recording = active
    }
    fun tick(now: Long, screen: InputScreen?, settings: VoiceInputSettings) {
        if (!settings.enabled) { reset(); return }
        job?.let { j ->
            if (!same(j.screen, screen) || !settings.screenOcr || screen!!.packageName in settings.excludedPackages) {
                // 离开了：已识出的照用，没截的不再截
                j.closed = true; maybeFinish(j)
            } else {
                for (k in 1 until SHOTS.size) if (now >= j.stopped + SHOTS[k] && needShot(j, k)) issue(j, k)
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
                    job = j
                    issue(j, 0)
                }
            } else if (unreadable && now >= stopped + 3000 || now >= stopped + IDLE_MS) finishWindow()
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
        val waiting = j.issued.indices.any { j.issued[it] && !j.done[it] }
        // 还有没截的、而且确实需要截（6 秒那张只在 3 秒时还没发出时才要）
        val more = !j.closed && j.issued.indices.any { !j.issued[it] && (it == 0 || j.done[it - 1] || j.issued[it - 1]) && needShot(j, it) }
        if (!waiting && !more) finishJob(j)
    }
    /** 3 秒时输入框还有字、也没看到发出的气泡：再等一张（6 秒）；其他张都截 */
    private fun needShot(j: OcrJob, k: Int): Boolean {
        if (k < SHOTS.lastIndex) return true
        val r = j.results[k - 1] ?: return j.done[k - 1]
        return !VoiceInputOcr.clean(r.text).isNullOrEmpty() && freshBubble(j, r) == null
    }
    /** 这张图里最下面靠右的气泡，且第一张里没有（是新发出去的） */
    private fun freshBubble(j: OcrJob, r: OcrInput?): String? {
        val b = VoiceInputOcr.clean(r?.bubble) ?: return null
        val first = j.results.firstOrNull { it != null }
        if (first === r) return null
        val key = VoiceInputText.normalized(b)
        val seen = (first?.context.orEmpty() + listOfNotNull(first?.bubble)).any { VoiceInputText.normalized(it).let { n -> n == key || n.endsWith(key) } }
        return b.takeIf { !seen }
    }
    private fun finishJob(j: OcrJob) {
        if (job === j) job = null
        val r = j.results
        // 输入框：取字最全的一张；后一张明显变短说明已经发出去了，用前一张
        var box: String? = null
        for (x in r) {
            val t = VoiceInputOcr.clean(x?.text) ?: continue
            val prev = box
            box = if (prev == null || t.length >= prev.length * 0.6 || VoiceInputText.similar(t, prev.take(t.length))) (if (prev != null && t.length < prev.length && prev.startsWith(t)) prev else t) else prev
        }
        val sent = r.indices.reversed().firstNotNullOfOrNull { freshBubble(j, r[it]) }
        val base = r.firstOrNull { !VoiceInputOcr.clean(it?.text).isNullOrEmpty() } ?: r.firstOrNull { it != null }
        val text = when {
            sent != null && (box == null || VoiceInputText.similar(sent, box) || VoiceInputText.normalized(sent).contains(VoiceInputText.normalized(box))) -> sent
            box != null -> box
            else -> null
        }
        dbg("截屏识字合成：张数=${r.count { it != null }} 输入框字数=${box?.length ?: 0} 发出气泡字数=${sent?.length ?: 0} 采用=${if (text == null) "无" else if (text == sent) "气泡" else "输入框"}")
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
            VoiceInputMeta(w.screen.app, w.screen.packageName, w.screen.title?.takeIf { w.screen.packageName in VoiceInputOcr.TITLED },
                if (w.settings.context) VoiceInputText.context(w.screen.lines) else null, null), null)))
    }
}

/** 诊断日志：只写步骤和数字，绝不写文字内容。 */
internal fun dbg(msg: String) { runCatching { android.util.Log.d("VoiceInputDbg", msg) } }
