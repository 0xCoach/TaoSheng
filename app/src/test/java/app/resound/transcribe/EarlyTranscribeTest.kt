package app.resound.transcribe

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EarlyTranscribeTest {
    @Test fun realtimeRunsRightAwayOtherwiseLeavesItToTheTimer() {
        assertTrue(EarlyTranscribe.shouldRun(realtime = true, pendingMillis = 5_000, battery = 80, charging = false, hot = false))
        assertFalse(EarlyTranscribe.shouldRun(realtime = false, pendingMillis = 60 * 60_000L, battery = 80, charging = false, hot = false))
    }

    @Test fun lowBatteryOrHotPhoneLeavesItToTheTimer() {
        assertFalse(EarlyTranscribe.shouldRun(realtime = true, pendingMillis = 5_000, battery = 15, charging = false, hot = false))
        assertTrue(EarlyTranscribe.shouldRun(realtime = true, pendingMillis = 5_000, battery = 15, charging = true, hot = false))
        assertFalse(EarlyTranscribe.shouldRun(realtime = true, pendingMillis = 5_000, battery = 80, charging = true, hot = true))
    }
}
