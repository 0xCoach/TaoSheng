package app.resound.transcribe

import app.resound.scheduling.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

/** 先在未改生产代码的 b8db660 代码上录制；断言固定，不以新实现生成预期。 */
class PipelineSequence189Test {
    private val minute = 60_000L
    private suspend fun stages(mode: String, skip: String? = null): List<String> {
        val events = mutableListOf<String>()
        runPipelineStages(mode, skip, { events += "转写"; events += "声纹" }, { events += "整理" })
        return events
    }
    @Test fun oldModesHaveFixedStageSequences() = runBlocking {
        for (mode in listOf("full", "digest", "timed", "review"))
            assertEquals(mode, listOf("转写", "声纹", "整理"), stages(mode))
        for (mode in listOf("realtime", "transcribe", "unknown"))
            assertEquals(mode, listOf("转写", "声纹"), stages(mode))
    }
    @Test fun onlyIntervalDigestSkipsTranscriptionAndVoiceprint() = runBlocking {
        assertEquals(listOf("整理"), stages("digest", "等待 WiFi"))
        for (mode in listOf("full", "timed", "review"))
            assertEquals(listOf("转写", "声纹", "整理"), stages(mode, "等待 WiFi"))
    }
    @Test fun legacyPeriodicCollisionAndOtherRetries() = runBlocking {
        for (mode in listOf("transcribe", "digest", "timed", "full", "realtime")) {
            for (guard in listOf(false, true)) {
                val success = mode in listOf("transcribe", "digest") || mode == "timed" && guard
                assertEquals(if (success) androidx.work.ListenableWorker.Result.success() else androidx.work.ListenableWorker.Result.retry(),
                    TranscribeWork.busyResult(mode, guard))
                val request = TranscribeWork.request(androidx.work.workDataOf(
                    "mode" to mode, "periodic188" to guard), setOf(TranscribeWork.MANUAL_DIGEST))
                assertEquals(mode, request.mode)
                assertEquals(success, request.periodic)
                assertEquals(mode == "full", request.manual)
                val gate = PipelineGate()
                gate.mutex.lock()
                val offered = gate.offer(request,
                    { error("撞锁不得采样或写尝试") }) { error("撞锁不得执行") }
                gate.mutex.unlock()
                assertEquals(!success, offered.retry)
                var entered = false
                val run = gate.offer(request,
                    { PipelineDue.Decision.Run(PipelineDue.Mode.TimedDigest, 0) }) {
                    entered = true
                    PipelineResult(digest = app.resound.digest.core.DigestOutcome.Retryable("测试网络"))
                }
                assertTrue(entered)
                assertEquals(!success, run.retry)
            }
        }
    }
    @Test fun allAutomaticSourcesShareAttemptAndDoNotDuplicate() = runBlocking {
        val gate = PipelineGate()
        var now = 30 * minute
        var history = PipelineDue.History(0)
        val events = mutableListOf<String>()
        for (source in listOf("心跳", "系统周期", "亮屏", "接电", "打开App", "闹钟")) {
            gate.runIfDue({ PipelineDue.decide(PipelineDue.Input(PipelineDue.Mode.IntervalDigest, now, 30,
                history, 1000, false, true, false, true)) }) {
                events += source; events += stages("digest")
                history = PipelineDue.History(now, now)
            }
        }
        assertEquals(listOf("心跳", "转写", "声纹", "整理"), events)
        now += 29 * minute
        assertEquals(30 * minute, history.lastAttempt)
    }
    @Test fun offlineAndBusyDoNotConsumeTimedTicket() = runBlocking {
        val ticket = TimedDigestRecovery.Ticket("old", 20 * minute)
        val input = PipelineDue.Input(PipelineDue.Mode.TimedDigest, 30 * minute, 30,
            PipelineDue.History(0), 0, false, false, false, true, ticket)
        assertEquals(PipelineDue.Waiting.Offline, (PipelineDue.decide(input) as PipelineDue.Decision.Wait).reason)
        assertEquals(PipelineDue.Waiting.Busy, (PipelineDue.decide(input.copy(online = true, busy = true)) as PipelineDue.Decision.Wait).reason)
        assertEquals(ticket, (PipelineDue.decide(input.copy(online = true)) as PipelineDue.Decision.Run).ticket)
        assertEquals(0L, input.history.lastAttempt)
    }
    @Test fun retryHistoryAndGenerationPreserveSequence() {
        for ((count, delay) in listOf(1 to 5, 2 to 10, 3 to 20, 4 to 30)) {
            assertEquals(delay * minute, PipelineDue.nextAttemptAt(PipelineDue.Mode.IntervalDigest, 30,
                PipelineDue.History(0, failure = PipelineDue.Failure.Retryable, failures = count)))
        }
        assertEquals(TimedDigestRecovery.Ticket("new", 20 * minute),
            TimedDigestRecovery.pending("new", true, 0, 0, 30 * minute, listOf(20 * minute)))
        assertNull(TimedDigestRecovery.pending("old", true, 0, 20 * minute, 30 * minute, listOf(20 * minute)))
    }
    @Test fun stageCancellationNeverReachesDigestAndGateUnlocks() = runBlocking {
        val gate = PipelineGate()
        val events = mutableListOf<String>()
        try {
            gate.runIfDue({ PipelineDue.Decision.Run(PipelineDue.Mode.IntervalDigest, 0) }) {
                runPipelineStages("digest", null, { events += "转写"; throw CancellationException("测试") }, { events += "整理" })
            }
            fail("取消必须传播")
        } catch (_: CancellationException) { }
        assertEquals(listOf("转写"), events)
        assertFalse(gate.mutex.isLocked)
    }
    @Test fun existingDiagnosticRoundReplacementAndRetentionAreRecorded() {
        val now = 8 * 86_400_000L
        val row = BackgroundRun(now, "2026-11-15", "心跳", "digest", now - minute, "成功", 10)
        assertEquals(listOf(row), BackgroundRecords.retain(listOf(row.copy(at = 0), row), now))
        assertEquals(listOf(row), BackgroundRecords.decode(BackgroundRecords.encode(listOf(row))))
        assertTrue(BackgroundRecords.summary(listOf(row)).contains("心跳=1"))
        assertTrue(BackgroundRecords.summary(listOf(row)).contains("平均迟到=60000ms"))
    }
}
