package app.resound.scheduling

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class PipelineGate188Test {
    private val due = PipelineDue.Decision.Run(PipelineDue.Mode.IntervalDigest, 0)
    @Test fun rechecksAfterPreviousWorkCompleted() = runBlocking {
        val gate = PipelineGate()
        var expired = true
        gate.mutex.lock()
        expired = false
        gate.mutex.unlock()
        assertFalse(gate.runIfDue({ if (expired) due else PipelineDue.Decision.NotDue }) { fail("不应执行") })
    }
    @Test fun heartbeatAndPeriodicExecuteOnlyOnce() = runBlocking {
        val gate = PipelineGate()
        var count = 0
        var expired = true
        val tasks = List(2) { async { gate.runIfDue({ if (expired) due else PipelineDue.Decision.NotDue }) {
            count++; yield(); expired = false
        } } }
        tasks.awaitAll()
        assertEquals(1, count)
        assertFalse(gate.mutex.isLocked)
    }
    @Test fun busyReturnsImmediatelyWithoutJudgingOrRetrying() = runBlocking {
        val gate = PipelineGate()
        gate.mutex.lock()
        try { assertFalse(gate.runIfDue({ error("锁忙不能判定或记尝试") }) { fail() }) }
        finally { gate.mutex.unlock() }
        gate.mutex.lock()
        val review = async { gate.offer(PipelineRequest(PipelineModes.REVIEW), { error("回顾不受周期到期限制") }) {
            error("超时前不应拿到锁")
        } }
        yield()
        assertFalse(review.isCompleted)
        review.cancelAndJoin()
        assertTrue(gate.mutex.isLocked) // 等锁者取消不能释放别人的锁。
        gate.mutex.unlock()
    }
}
