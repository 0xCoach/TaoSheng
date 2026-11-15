package app.resound.scheduling

import app.resound.digest.core.DigestOutcome
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class PipelineCoordinator189Test {
    @Test fun modesKeepStageOrderForceManualAndRetryAggregation() = runBlocking {
        for (mode in listOf("full", "digest", "timed", "review", "transcribe", "realtime")) {
            val log = mutableListOf<String>()
            var clock = 0L
            val stages = object : PipelineStages {
                override suspend fun transcribe(retryFailed: Boolean): Boolean { log += "转写:$retryFailed"; clock += 3; return true }
                override suspend fun voiceprint(): Boolean { log += "声纹"; clock += 2; return false }
                override suspend fun organize(force: Boolean, manual: Boolean): DigestOutcome {
                    log += "整理:$force:$manual"; clock += 5; return DigestOutcome.Done
                }
            }
            val times = mutableMapOf<String, Long>()
            val result = PipelineCoordinator(stages) { clock }.run(mode, false, null) { key, value -> times[key] = value }
            val expected = mutableListOf("转写:${mode in listOf("timed", "review")}", "声纹")
            if (mode in listOf("full", "digest", "timed", "review")) expected += "整理:${mode in listOf("timed", "review")}:false"
            assertEquals(expected, log); assertTrue(result.retry); assertFalse(result.blocked)
            assertEquals(3L, times["转写"]); assertEquals(2L, times["声纹"])
        }
    }
    @Test fun manualUnlockAndTimedCompletionKeepBlockedPriority() {
        val before = PipelineDue.History(10, 5, PipelineDue.Failure.Blocked, 2)
        val done = PipelineResult(digest = DigestOutcome.Done)
        for (mode in PipelineDue.Mode.entries) {
            assertEquals(PipelineDue.History(20, 20), PipelineRunRules.manual(before, mode, 20, done))
        }
        val blocked = PipelineResult(transcribeRetry = true, digest = DigestOutcome.NeedsUser("密钥"))
        assertTrue(PipelineRunRules.consumeTicket(blocked))
        assertEquals(PipelineDue.Failure.Blocked, PipelineRunRules.completed(before, 20, 21, blocked).failure)
        assertFalse(PipelineRunRules.consumeTicket(PipelineResult(digest = DigestOutcome.Retryable("网络"))))
        assertEquals(20L, PipelineRunRules.nextAlarm(30, 20))
        assertNull(PipelineRunRules.nextAlarm(null, null))
    }
    @Test fun cancelledStageReportsElapsedAndCannotPublishSuccess() = runBlocking {
        val log = mutableListOf<String>()
        val stages = object : PipelineStages {
            override suspend fun transcribe(retryFailed: Boolean): Boolean { throw CancellationException("取消") }
            override suspend fun voiceprint(): Boolean { error("不能继续") }
            override suspend fun organize(force: Boolean, manual: Boolean): DigestOutcome { error("不能继续") }
        }
        try { PipelineCoordinator(stages) { 1L }.run("full", true, null) { name, _ -> log += name }; fail() }
        catch (_: CancellationException) { }
        assertEquals(listOf("转写"), log)
    }
}
