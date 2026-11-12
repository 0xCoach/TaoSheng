@file:OptIn(kotlin.time.ExperimentalTime::class)
package app.resound.ask.core
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import app.resound.net.Arr as JSONArray
import kotlin.time.Instant
import kotlinx.datetime.*

/** reserve 必须一次持久化三项，失败返回 false，不能继续请求模型。 */
interface AskSuggestionStore {
    fun day(): String?
    fun count(): Int
    fun at(): Long
    fun questions(): String
    fun reserve(day: String, count: Int, at: Long): Boolean
    fun save(questions: String): Boolean
}
/** 次数先落盘，失败也算一次；跨进程重启仍每天最多两次。 */
class AskSuggestionsCore(
    private val store: AskSuggestionStore,
    private val hasKey: () -> Boolean,
    private val recent: suspend () -> String,
    private val generate: suspend (String) -> List<String>,
    private val now: app.resound.platform.NowMillis,
    private val zone: app.resound.platform.ZoneProvider,
) {
    private val gate = Mutex()
    private val state = MutableStateFlow(if (hasKey()) cached().ifEmpty { EXAMPLES } else EXAMPLES)
    val questions = state.asStateFlow()

    suspend fun refresh() {
        if (!hasKey()) { state.value = EXAMPLES; return }
        if (!gate.tryLock()) return
        try {
            val at = now.now()
            val day = Instant.fromEpochMilliseconds(at).toLocalDateTime(TimeZone.of(zone.zoneId())).date.toString()
            val sameDay = store.day() == day
            val count = if (sameDay) store.count() else 0
            val last = store.at()
            if (count >= 2 || (sameDay && count > 0 && at - last in 0 until SIX_HOURS)) {
                state.value = cached().ifEmpty { EXAMPLES }; return
            }
            val context = recent()
            if (context.isBlank()) { state.value = cached().ifEmpty { EXAMPLES }; return }
            check(store.reserve(day, count + 1, at))
            val result = clean(generate(context))
            if (result.isNotEmpty()) {
                check(store.save(JSONArray(result).toString()))
                state.value = if (hasKey()) result else EXAMPLES
            }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { /* 推荐失败不打断问答，不展示原始服务异常。 */ }
        finally { gate.unlock() }
    }

    private fun cached(): List<String> = runCatching {
        val a = JSONArray(store.questions())
        clean((0 until a.length()).map { a.getString(it) })
    }.getOrDefault(emptyList())

    private fun clean(values: List<String>): List<String> = values.map { it.trim() }
        .filter { it.isNotEmpty() && app.resound.digest.core.takeCodePoints(it, 12).length == it.length && '\n' !in it && '\r' !in it }
        .distinct().take(4)

    companion object {
        val EXAMPLES = listOf("今天做了什么？", "最近有哪些待办？")
        private const val SIX_HOURS = 6 * 60 * 60_000L
    }
}
