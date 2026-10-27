package app.resound.transcribe

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EarlyTranscribeTest {
    @Test fun localRunsRightAwayCloudWaitsForTenMinutes() {
        assertTrue(EarlyTranscribe.shouldRun(local = true, pendingMillis = 5_000, battery = 80, charging = false, hot = false))
        assertFalse(EarlyTranscribe.shouldRun(local = false, pendingMillis = 9 * 60_000L, battery = 80, charging = false, hot = false))
        assertTrue(EarlyTranscribe.shouldRun(local = false, pendingMillis = 10 * 60_000L, battery = 80, charging = false, hot = false))
    }

    @Test fun lowBatteryOrHotPhoneLeavesItToTheTimer() {
        assertFalse(EarlyTranscribe.shouldRun(local = true, pendingMillis = 5_000, battery = 15, charging = false, hot = false))
        assertTrue(EarlyTranscribe.shouldRun(local = true, pendingMillis = 5_000, battery = 15, charging = true, hot = false))
        assertFalse(EarlyTranscribe.shouldRun(local = true, pendingMillis = 5_000, battery = 80, charging = true, hot = true))
    }
}
