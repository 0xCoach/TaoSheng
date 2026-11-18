package app.resound.night

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class NightSessionWrites204Test {
    @Test fun transitionsAndRecoverableMinuteAreWritten() = runTest {
        val writes = NightSessionWrites()
        val snapshots = mutableListOf<Long>()
        for (at in 0L..170_000 step 10_000) {
            val state = if (at < 90_000) NightState.Provisional else NightState.Active
            writes.checkpoint(state, at) { snapshots += at }
        }
        assertEquals(listOf(0L,60_000L,90_000L,150_000L),snapshots)
        assertEquals(20_000L,170_000 - snapshots.last())
    }
}
