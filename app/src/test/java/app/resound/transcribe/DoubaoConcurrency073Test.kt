package app.resound.transcribe

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class DoubaoConcurrency073Test {
    @Test fun twoSlotsDowngradeDrainsExistingRequestsAndCancellationReleasesSlot() = runBlocking {
        val gate = DoubaoConcurrency()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var active = 0
        val running = (1..2).map { launch { gate.withPermit { if (++active == 2) entered.complete(Unit); release.await(); active-- } } }
        withTimeout(1000) { entered.await() }
        gate.reduce()
        var thirdEntered = false
        val waiting = launch { gate.withPermit { thirdEntered = true } }
        yield(); assertFalse(thirdEntered)
        waiting.cancelAndJoin()
        release.complete(Unit); running.joinAll()
        assertEquals(1, gate.limit)
        withTimeout(1000) { gate.withPermit { assertEquals(0, active) } }
        assertTrue(doubaoLimited(null, 429))
        assertTrue(doubaoLimited(45000030, message = "concurrent quota exceeded"))
        assertFalse(doubaoLimited(45000030, 403, "access denied"))
    }
}
