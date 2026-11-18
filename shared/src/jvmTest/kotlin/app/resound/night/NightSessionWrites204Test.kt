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
        val saved = NightSession(0,0,90_000,snapshots.last())
        val resumed = saved.interruptedUntil(170_000).interruptedUntil(190_000)
        assertEquals(listOf(NightSignal(150_000,190_000,"gap",text="没有记录")),resumed.signals)
        assertEquals(190_000L,resumed.lastAt)
    }
}
