package app.resound.scheduling

import kotlinx.coroutines.sync.Mutex

data class PipelineRequest(val mode: String = PipelineModes.AUTO, val periodic: Boolean = false,
                           val manual: Boolean = false) {
    val automatic: Boolean get() = periodic || mode in listOf(PipelineModes.AUTO, PipelineModes.TRANSCRIBE,
        PipelineModes.DIGEST, PipelineModes.TIMED)
    val periodicFallback: Boolean get() = PipelineModes.periodic(mode, periodic)
}

sealed interface PipelineOffer {
    val retry: Boolean
    data object Skipped : PipelineOffer { override val retry = false }
    data class Busy(override val retry: Boolean) : PipelineOffer
    data class Completed(val result: PipelineResult, override val retry: Boolean) : PipelineOffer
}

/** 所有执行机会共用此门；周期撞锁成功，回顾可取消地等锁，拿锁后重判。 */
class PipelineGate {
    val mutex = Mutex()
    suspend fun offer(request: PipelineRequest, decide: () -> PipelineDue.Decision,
                      execute: suspend (PipelineDue.Decision.Run?) -> PipelineResult): PipelineOffer {
        if (request.mode == PipelineModes.REVIEW) mutex.lock()
        else if (!mutex.tryLock()) return PipelineOffer.Busy(!request.periodicFallback)
        try {
            val due = if (request.automatic) decide() as? PipelineDue.Decision.Run ?: return PipelineOffer.Skipped else null
            val result = execute(due)
            val retry = when {
                request.periodicFallback -> false
                request.automatic -> result.retry && !result.blocked
                else -> result.retry
            }
            return PipelineOffer.Completed(result, retry)
        } finally { mutex.unlock() }
    }

    suspend fun runIfDue(decide: () -> PipelineDue.Decision, execute: suspend (PipelineDue.Decision.Run) -> Unit): Boolean =
        offer(PipelineRequest(), decide) { due -> execute(requireNotNull(due)); PipelineResult() } is PipelineOffer.Completed
}
