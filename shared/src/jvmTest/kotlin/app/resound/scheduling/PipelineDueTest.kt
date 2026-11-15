package app.resound.scheduling

import app.resound.scheduling.PipelineDue.Mode
import app.resound.scheduling.PipelineDue.Failure
import app.resound.scheduling.PipelineDue.Waiting
import app.resound.scheduling.PipelineDue.History
import app.resound.scheduling.PipelineDue.Input
import app.resound.scheduling.PipelineDue.Decision
import org.junit.Assert.*
import org.junit.Test

class PipelineDueTest {
    private val minute = 60_000L
    private fun input(now: Long = 30 * minute) = Input(Mode.IntervalDigest, now, 30,
        History(0), 1_000, false, true, false, true)
    @Test fun intervalUsesAttemptRatherThanSuccess() {
        assertEquals(Decision.NotDue, PipelineDue.decide(input(29 * minute)))
        assertTrue(PipelineDue.decide(input()) is Decision.Run)
        assertEquals(Decision.NotDue, PipelineDue.decide(input().copy(history = History(20 * minute, 0))))
    }
    @Test fun retryDelayIncreasesAndCapsAtInterval() {
        for ((count, delay) in listOf(1 to 5, 2 to 10, 3 to 20, 8 to 30)) {
            val request = input().copy(history = History(0, failure = Failure.Retryable, failures = count))
            assertEquals(Decision.NotDue, PipelineDue.decide(request.copy(now = delay * minute - 1)))
            assertTrue(PipelineDue.decide(request.copy(now = delay * minute)) is Decision.Run)
        }
        assertTrue(PipelineDue.decide(input(15 * minute).copy(intervalMinutes = 15,
            history = History(0, failure = Failure.Retryable, failures = 3))) is Decision.Run)
    }
    @Test fun permanentFailureRequiresUserAction() {
        assertEquals(Waiting.NeedsUser, (PipelineDue.decide(input(Long.MAX_VALUE).copy(
            history = History(0, failure = Failure.Blocked))) as Decision.Wait).reason)
    }
    @Test fun busyWaitDoesNotConsumeAttempt() {
        val request = input().copy(busy = true)
        assertEquals(Waiting.Busy, (PipelineDue.decide(request) as Decision.Wait).reason)
        assertEquals(0L, request.history.lastAttempt)
        assertTrue(PipelineDue.decide(request.copy(busy = false)) is Decision.Run)
    }
    @Test fun offlineWaitDoesNotConsumeAttempt() {
        val request = input().copy(online = false)
        assertEquals(Waiting.Offline, (PipelineDue.decide(request) as Decision.Wait).reason)
        assertEquals(0L, request.history.lastAttempt)
        assertTrue(PipelineDue.decide(request.copy(online = true)) is Decision.Run)
    }
    @Test fun timedNeedsTicketAndPreservesIt() {
        val request = input().copy(mode = Mode.TimedDigest)
        assertEquals(Decision.NotDue, PipelineDue.decide(request))
        val ticket = TimedDigestRecovery.Ticket("generation", 20 * minute)
        assertEquals(Decision.Run(Mode.TimedDigest, ticket.target, ticket), PipelineDue.decide(request.copy(ticket = ticket)))
    }
    @Test fun transcriptionRequiresPendingAndUnmeteredWhenSelected() {
        val request = input().copy(mode = Mode.Transcribe, wifiOnly = true, unmetered = false)
        assertEquals(Waiting.Metered, (PipelineDue.decide(request) as Decision.Wait).reason)
        assertEquals(Decision.NotDue, PipelineDue.decide(request.copy(pendingMillis = 0)))
        assertTrue(PipelineDue.decide(request.copy(unmetered = true)) is Decision.Run)
    }
    @Test fun realtimeFallbackAlwaysUsesThirtyMinutes() {
        val request = input(29 * minute).copy(mode = Mode.RealtimeFallback, intervalMinutes = 15)
        assertEquals(Decision.NotDue, PipelineDue.decide(request))
        assertTrue(PipelineDue.decide(request.copy(now = 30 * minute)) is Decision.Run)
    }
}
