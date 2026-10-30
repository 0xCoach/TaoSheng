package app.resound.transcribe

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** 所有豆包入口共用；限流后本进程保守降为一路，下一轮也不立刻升回去。 */
internal class DoubaoConcurrency {
    private val lock = Mutex()
    private val changed = MutableStateFlow(0L)
    private var active = 0
    @Volatile var limit = 2; private set

    suspend fun reduce() = lock.withLock { limit = 1; changed.value++ }

    suspend fun <T> withPermit(block: suspend () -> T): T {
        while (true) {
            val version = lock.withLock {
                if (active < limit) { active++; null } else changed.value
            }
            if (version == null) break
            changed.first { it != version }
        }
        try { return block() }
        finally { withContext(NonCancellable) { lock.withLock { active--; changed.value++ } } }
    }

    companion object { val global = DoubaoConcurrency() }
}

/** 只提取限流类别，不保留远端原文（可能含凭据）。 */
internal fun doubaoLimited(code: Long?, http: Int? = null, message: String? = null): Boolean =
    http == 429 || code == 55000031L || message?.lowercase()?.let { text ->
        listOf("concurrent", "concurrency", "qps", "rate limit", "并发", "限流").any { it in text }
    } == true
