package app.resound.voiceprint

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 标“这是我”、录入、改人物之后只重跑声纹，不连带转写和整理（原来会整条流水线立刻跑一遍）。
 * 连续标注在 [settleMillis] 内合并成一次；重跑时又有新标注会接着再跑；
 * 连续几次都没跑完（出错或一直有新标注）才交给完整的后台任务按退避重试。
 */
class VoiceprintRerun(
    scope: CoroutineScope,
    private val run: suspend () -> Boolean,
    private val fallback: () -> Unit,
    private val settleMillis: Long = 3_000,
) {
    private val requests = Channel<Unit>(Channel.CONFLATED)

    init {
        scope.launch(Dispatchers.IO) {
            for (request in requests) {
                delay(settleMillis)
                requests.tryReceive()
                var retry = true
                repeat(3) {
                    if (retry) retry = try { run() } catch (e: CancellationException) { throw e } catch (_: Exception) { true }
                }
                if (retry) fallback()
            }
        }
    }

    fun request() { requests.trySend(Unit) }
}
