package app.resound.review
import kotlinx.coroutines.*

object ReviewPreparationRules {
    suspend fun prepare(timeoutMillis: Long = 10 * 60_000L, record: (String) -> Unit, action: suspend () -> String): Boolean {
        val result = try {
            withTimeoutOrNull(timeoutMillis) { action() } ?: "超时，使用已有内容"
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { "失败，使用已有内容" }
        record(result)
        return result == "完成"
    }
}
