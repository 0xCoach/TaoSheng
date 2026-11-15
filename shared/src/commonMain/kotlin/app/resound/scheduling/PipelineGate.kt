package app.resound.scheduling

import kotlinx.coroutines.sync.Mutex

/** 多个入口共用同一把锁；判断必须发生在拿锁之后。 */
class PipelineGate {
    val mutex = Mutex()
    suspend fun runIfDue(decide: () -> PipelineDue.Decision, execute: suspend (PipelineDue.Decision.Run) -> Unit): Boolean {
        if (!mutex.tryLock()) return false
        try {
            val due = decide() as? PipelineDue.Decision.Run ?: return false
            execute(due)
            return true
        } finally { mutex.unlock() }
    }
}
