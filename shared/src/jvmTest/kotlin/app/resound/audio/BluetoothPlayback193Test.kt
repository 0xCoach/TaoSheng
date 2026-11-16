package app.resound.audio

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BluetoothPlayback193Test {
    @Test fun onlyBluetoothWithEnabledSettingYields() {
        for (kind in InputKind.entries) {
            assertFalse(BluetoothPlaybackYield.eligible(kind, false))
            assertEquals(kind in listOf(InputKind.BluetoothCall, InputKind.BluetoothLe), BluetoothPlaybackYield.eligible(kind, true))
        }
    }
    @Test fun startsImmediatelyAndWaitsThreeSecondsAfterStop() = runTest {
        val playing = MutableStateFlow(false)
        val result = mutableListOf<Boolean>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { BluetoothPlaybackYield.held(playing).toList(result) }
        runCurrent(); assertEquals(listOf(false), result)
        playing.value = true; runCurrent(); assertEquals(listOf(false, true), result)
        playing.value = false; runCurrent(); advanceTimeBy(2999); runCurrent(); assertTrue(result.last())
        advanceTimeBy(1); runCurrent(); assertFalse(result.last())
    }
    @Test fun songGapCancelsPendingReturn() = runTest {
        val playing = MutableStateFlow(true)
        val result = mutableListOf<Boolean>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { BluetoothPlaybackYield.held(playing).toList(result) }
        runCurrent(); playing.value = false; runCurrent(); advanceTimeBy(2000)
        playing.value = true; runCurrent(); advanceTimeBy(4000); runCurrent()
        assertEquals(listOf(true), result)
        playing.value = false; runCurrent(); advanceTimeBy(3000); runCurrent()
        assertEquals(listOf(true, false), result)
    }
    @Test fun cancellationReleasesSubscriptionDuringConfirmation() = runTest {
        val playing = MutableStateFlow(true)
        val job = launch { BluetoothPlaybackYield.held(playing).collect {} }
        runCurrent(); assertEquals(1, playing.subscriptionCount.value)
        playing.value = false; runCurrent(); job.cancelAndJoin()
        assertEquals(0, playing.subscriptionCount.value)
    }
}
