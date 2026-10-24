package app.resound.ask

import android.content.SharedPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import org.json.JSONArray
import java.time.Instant
import java.time.ZoneId

/** 次数先落盘，失败也算一次；跨进程重启仍每天最多两次。 */
class AskSuggestions(
    private val prefs: SharedPreferences,
    private val hasKey: () -> Boolean,
    private val recent: suspend () -> String,
    private val generate: suspend (String) -> List<String>,
    private val now: () -> Long = System::currentTimeMillis,
    private val zone: ZoneId = ZoneId.systemDefault(),
) {
    private val gate = Mutex()
    private val state = MutableStateFlow(if (hasKey()) cached().ifEmpty { EXAMPLES } else EXAMPLES)
    val questions = state.asStateFlow()

    suspend fun refresh() {
        if (!hasKey()) { state.value = EXAMPLES; return }
        if (!gate.tryLock()) return
        try {
            val at = now()
            val day = Instant.ofEpochMilli(at).atZone(zone).toLocalDate().toString()
            val sameDay = prefs.getString("day", null) == day
            val count = if (sameDay) prefs.getInt("count", 0) else 0
            val last = prefs.getLong("at", 0)
            if (count >= 2 || (sameDay && count > 0 && at - last in 0 until SIX_HOURS)) {
                state.value = cached().ifEmpty { EXAMPLES }; return
            }
            val context = recent()
            if (context.isBlank()) { state.value = cached().ifEmpty { EXAMPLES }; return }
            check(prefs.edit().putString("day", day).putInt("count", count + 1).putLong("at", at).commit())
            val result = clean(generate(context))
            if (result.isNotEmpty()) {
                check(prefs.edit().putString("questions", JSONArray(result).toString()).commit())
                state.value = if (hasKey()) result else EXAMPLES
            }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { /* 推荐失败不打断问答，不展示原始服务异常。 */ }
        finally { gate.unlock() }
    }

    private fun cached(): List<String> = runCatching {
        val a = JSONArray(prefs.getString("questions", "[]"))
        clean((0 until a.length()).map { a.getString(it) })
    }.getOrDefault(emptyList())

    private fun clean(values: List<String>): List<String> = values.map { it.trim() }
        .filter { it.isNotEmpty() && it.codePointCount(0, it.length) <= 12 && '\n' !in it && '\r' !in it }
        .distinct().take(4)

    companion object {
        val EXAMPLES = listOf("今天做了什么？", "最近有哪些待办？")
        private const val SIX_HOURS = 6 * 60 * 60_000L
    }
}
