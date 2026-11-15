package app.resound.diagnostics

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/** 只记录固定代码位置和异常类别，禁止传入异常 message、用户文本或密钥。 */
object FailureNotes {
    /** 同一位置同一类错误，这段时间内只写一次持久记录，避免高频路径刷满日志、反复写盘。 */
    const val PERSIST_INTERVAL_MILLIS = 10 * 60_000L
    private class Seen(val persistedAt: Long, val suppressed: Int)
    private val notes = MutableStateFlow<List<String>>(emptyList())
    private val writer = MutableStateFlow<((String) -> Unit)?>(null)
    private val clock = MutableStateFlow<(() -> Long)?>(null)
    private val seen = MutableStateFlow<Map<String, Seen>>(emptyMap())

    fun record(site: String, error: Throwable) {
        val line = "$site ${error::class.simpleName ?: "Throwable"}"
        notes.update { (it + line).takeLast(200) }
        val write = writer.value ?: return
        val now = clock.value?.invoke()
        var persist: String? = null
        seen.update { map ->
            val last = map[line]
            if (now == null || last == null || now - last.persistedAt !in 0 until PERSIST_INTERVAL_MILLIS) {
                persist = if (last != null && last.suppressed > 0) "$line（期间另有 ${last.suppressed} 次）" else line
                if (now == null) map else (map + (line to Seen(now, 0))).let { m -> if (m.size > 500) m.entries.drop(m.size - 500).associate { it.key to it.value } else m }
            } else {
                persist = null
                map + (line to Seen(last.persistedAt, last.suppressed + 1))
            }
        }
        val text = persist ?: return
        // 诊断端失败不能改变业务结果；只在内存记第二次故障，不再递归调用持久化端。
        try { write(text) } catch (failure: Exception) {
            notes.update { (it + "diagnostics/write ${failure::class.simpleName}").takeLast(200) }
        }
    }

    /** 平台在现有崩溃诊断初始化处注入持久化与时钟；共用层不创建平台对象。 */
    fun install(now: (() -> Long)? = null, write: (String) -> Unit) { clock.value = now; writer.value = write }
    fun dump(): String = notes.value.joinToString("\n")
    internal fun resetForTest() { notes.value = emptyList(); writer.value = null; clock.value = null; seen.value = emptyMap() }
}
