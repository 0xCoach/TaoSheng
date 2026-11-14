package app.resound.scheduling

import org.junit.Assert.*
import org.junit.Test

class TimedDigestRecoveryTest {
    @Test fun lateDeliveryAndMultipleMissesCollapseIntoLatestPoint() {
        val ticket = TimedDigestRecovery.pending("a", true, 100, 0, 100_000, listOf(200, 300, 100_001))
        assertEquals(TimedDigestRecovery.Ticket("a", 300), ticket)
    }
    @Test fun completedAndDisabledSchedulesNeverRunAgain() {
        assertNull(TimedDigestRecovery.pending("a", true, 100, 300, 100_000, listOf(200, 300)))
        assertNull(TimedDigestRecovery.pending("a", false, 100, 0, 100_000, listOf(200, 300)))
        assertNull(TimedDigestRecovery.pending("a", true, 400, 0, 100_000, listOf(200, 300)))
    }
    @Test fun failedAttemptRemainsDueAndNextPointDuringExecutionIsPreserved() {
        val points = listOf(200L, 300L)
        val first = TimedDigestRecovery.pending("a", true, 100, 0, 250, points)!!
        assertEquals(first, TimedDigestRecovery.pending("a", true, 100, 0, 280, points))
        assertEquals(300L, TimedDigestRecovery.pending("a", true, 100, first.target, 350, points)!!.target)
    }
}
