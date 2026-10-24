package app.resound.capture

import app.resound.transcribe.VoiceInputMeta
import java.util.UUID

/** 不持有无障碍节点；全部时钟由调用者传入，便于验证释放宽限和回复截止时间。 */
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

internal class VoiceInputEngine(private val output: (InputChange) -> Unit) {
    private data class Window(val at: Long, val screen: InputScreen, val settings: VoiceInputSettings,
        var last: String, var cleared: Boolean = false, var sendHint: Boolean = false, var saved: Boolean = false)
    private data class Reply(val record: InputRecord, val page: String, val field: String?, val before: Set<String>, val baseline: MutableSet<String>,
        var until: Long?, var sendHint: Boolean = false, val lines: LinkedHashSet<String> = linkedSetOf())
    private var recording = false
    private var releaseAt: Long? = null
    private var window: Window? = null
    private val replies = mutableListOf<Reply>()
    val needsScreen: Boolean get() = recording || window != null || replies.isNotEmpty()

    fun reset() {
        // 用户暂停/关闭开关后不再读取；已有记录立即定稿，不追加内存里的上下文。
        replies.forEach { output(InputChange.Finish(it.record.token, null)) }
        replies.clear(); window = null; recording = false; releaseAt = null
    }
    fun recording(active: Boolean, now: Long, screen: InputScreen?, settings: VoiceInputSettings) {
        tick(now, screen, settings)
        if (active && !recording) {
            dbg("语音窗口开始：有界面=${screen != null} 有输入框=${screen?.field != null} 原有字数=${screen?.text?.length}")
            finishWindow(now, screen)
            window = screen?.takeIf { !it.password && it.field != null && it.text != null }?.let {
                Window(now, it, settings, it.text!!)
            }
            releaseAt = null
        } else if (!active && recording) { releaseAt = now; dbg("别的应用停止录音，3 秒后收尾") }
        recording = active
    }
    fun tick(now: Long, screen: InputScreen?, settings: VoiceInputSettings) {
        val iterator = replies.iterator()
        while (iterator.hasNext()) {
            val reply = iterator.next()
            // 补记回复时再用标题防止串到别的聊天（两边都读到标题才比较）
            val same = screen != null && screen.packageName == reply.record.meta.packageName && screen.page == reply.page &&
                (screen.title == null || reply.record.meta.title == null || screen.title == reply.record.meta.title)
            if (reply.until?.let { now >= it } == true || !same || !settings.context || !settings.replies) {
                output(InputChange.Finish(reply.record.token, if (settings.context && settings.replies) VoiceInputText.context(reply.lines.toList()) else null))
                iterator.remove()
            } else {
                val current = screen!!
                if (reply.until == null) {
                    // 3 秒只结束文字采集；用户稍后点发送时，回复窗口从实际发送时刻重新计时。
                    reply.sendHint = reply.sendHint || current.send
                    val echoes = current.lines.filter { it !in reply.before &&
                        VoiceInputText.normalized(it).contains(VoiceInputText.normalized(reply.record.text)) }
                    if (current.field == reply.field && current.text == "" && (reply.sendHint || echoes.isNotEmpty())) {
                        reply.until = now + 120000
                        reply.baseline.addAll(echoes)
                        output(InputChange.Sent(reply.record.token, reply.until!!))
                    } else if (current.field != null && current.field != reply.field ||
                        current.field == reply.field && !current.text.isNullOrEmpty() &&
                        !VoiceInputText.normalized(current.text).contains(VoiceInputText.normalized(reply.record.text))) {
                        output(InputChange.Finish(reply.record.token, null)); iterator.remove(); continue
                    }
                }
                if (reply.until != null) {
                    current.lines.filter { it !in reply.baseline && !VoiceInputText.similar(it, reply.record.text) }
                        .forEach { reply.lines.add(it) }
                    while (reply.lines.size > 20) reply.lines.remove(reply.lines.first())
                }
            }
        }
        window?.let { w ->
            if (screen == null || screen.password || screen.packageName != w.screen.packageName || screen.page != w.screen.page) {
                dbg("提前收尾：界面=${screen != null} 同应用=${screen?.packageName == w.screen.packageName} 同页面=${screen?.page == w.screen.page}")
                finishWindow(now, null)
            } else if (!w.saved) {
                if (screen.field != null && screen.field != w.screen.field) {
                    dbg("输入框变了，放弃")
                    window = null // 换输入框不能串读。
                } else {
                    w.sendHint = w.sendHint || screen.send
                    if (screen.field == w.screen.field && screen.text != null) {
                        if (screen.text.isEmpty() && w.last.isNotEmpty()) w.cleared = true
                        else { w.last = screen.text; w.cleared = false }
                    }
                    val inserted = VoiceInputText.inserted(w.screen.text!!, w.last)
                    val echoed = inserted.isNotBlank() && screen.lines.any {
                        it !in w.screen.lines && (VoiceInputText.similar(it, w.last) || VoiceInputText.similar(it, inserted))
                    }
                    if (w.cleared && (w.sendHint || echoed)) save(w, now, screen, sent = true)
                }
            }
        }
        if (releaseAt?.let { now >= it + 3000 } == true) finishWindow(now, screen)
    }
    private fun finishWindow(now: Long, screen: InputScreen?) {
        window?.let { if (!it.saved && !it.cleared) save(it, now, screen, sent = false) }
        window = null; releaseAt = null
    }
    private fun save(w: Window, now: Long, screen: InputScreen?, sent: Boolean) {
        w.saved = true
        val text = VoiceInputText.inserted(w.screen.text!!, w.last)
        dbg("收尾：新增字数=${text.length} 已发送=$sent 被清空=${w.cleared}")
        if (text.isBlank()) return
        val follow = w.settings.context && w.settings.replies && w.screen.title != null && screen != null
        val record = InputRecord(UUID.randomUUID().toString(), w.at, text,
            VoiceInputMeta(w.screen.app, w.screen.packageName, w.screen.title,
                if (w.settings.context) VoiceInputText.context(w.screen.lines) else null, null),
            if (follow) { if (sent) now + 120000 else Long.MAX_VALUE } else null)
        output(InputChange.Save(record))
        if (follow) {
            // 原始上下文作为基线，不能漏掉与清空同时到达的快速回复。
            replies += Reply(record, w.screen.page, w.screen.field, w.screen.lines.toSet(), (w.screen.lines + w.last + text).toMutableSet(),
                if (sent) now + 120000 else null,
                lines = if (sent) screen!!.lines.filter { it !in w.screen.lines && !VoiceInputText.similar(it, w.last) && !VoiceInputText.similar(it, text) }.toCollection(linkedSetOf()) else linkedSetOf())
        }
    }
}

/** 诊断日志：只写步骤和字数，绝不写文字内容 */
private fun dbg(msg: String) { runCatching { android.util.Log.d("VoiceInputDbg", msg) } }
