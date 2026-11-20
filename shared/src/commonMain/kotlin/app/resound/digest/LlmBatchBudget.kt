package app.resound.digest

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** 存取只保存模型能力和计数，不含密钥、原话、响应；平台负责本机持久化。 */
interface LlmAdaptiveStore {
    fun read(key: String): String?
    fun write(key: String, value: String)
}
fun llmModelKey(preset: LlmPreset): String = "${preset.id.length}:${preset.id}${preset.model}"

class LlmBatchBudget(private val store: LlmAdaptiveStore) {
    @Serializable private data class State(val maxChars: Int = DEFAULT, val thinking: Boolean = false, val successes: Int = 0,
        val thinkingBudgetApplied: Boolean = false)
    private val mutex = Mutex()
    private fun read(p: LlmPreset): State = runCatching { Json.decodeFromString<State>(store.read(llmModelKey(p)) ?: "{}") }
        .getOrDefault(State()).let { it.copy(maxChars = it.maxChars.coerceIn(MINIMUM, DEFAULT), successes = it.successes.coerceIn(0, 4)) }
    private fun write(p: LlmPreset, s: State) = store.write(llmModelKey(p), Json.encodeToString(State.serializer(), s))
    private fun thinkingBudget(s: State, userThinking: Boolean): State =
        if (s.thinking && !userThinking && !s.thinkingBudgetApplied)
            s.copy(maxChars = minOf(s.maxChars, 20_000), thinkingBudgetApplied = true) else s
    suspend fun current(p: LlmPreset, userThinking: Boolean = false): Int = mutex.withLock {
        val s = read(p); val next = thinkingBudget(s, userThinking)
        if (next != s) write(p, next)
        next.maxChars
    }
    suspend fun observedThinking(p: LlmPreset, userThinking: Boolean) = mutex.withLock {
        val s = read(p)
        val next = thinkingBudget(s.copy(thinking = true), userThinking)
        if (next != s) write(p, next)
    }
    suspend fun failed(p: LlmPreset, shrink: Boolean) = mutex.withLock {
        val s = read(p)
        write(p, s.copy(maxChars = if (shrink) maxOf(MINIMUM, s.maxChars / 2) else s.maxChars, successes = 0))
    }
    suspend fun succeeded(p: LlmPreset) = mutex.withLock {
        val s = read(p)
        write(p, if (s.successes == 4) s.copy(maxChars = minOf(DEFAULT, s.maxChars * 5 / 4), successes = 0)
            else s.copy(successes = s.successes + 1))
    }
    companion object { const val DEFAULT = 40_000; const val MINIMUM = 4_000 }
}

/** 连续超时按服务商统计，不把正常成功、其他错误夹在中间也算连续。 */
class LlmSlowStreak(private val store: LlmAdaptiveStore) {
    private val mutex = Mutex()
    suspend fun result(p: LlmPreset, tooSlow: Boolean): String? = mutex.withLock {
        val count = if (tooSlow) ((store.read(p.id)?.toIntOrNull() ?: 0) + 1).coerceAtMost(2) else 0
        store.write(p.id, count.toString())
        if (count >= 2) SLOW_MODEL_TEXT else null
    }
}
const val SLOW_MODEL_TEXT = "这个模型较慢，已自动把每批改小，整理会久一点"
