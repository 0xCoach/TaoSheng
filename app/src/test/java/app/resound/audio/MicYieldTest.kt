package app.resound.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MicYieldTest {
    @Test fun waitsBeforeOpeningWhileOthersRecordOrInCall() {
        assertNull(MicYield.beforeOpen(inCall = false, othersRecording = 0))
        assertEquals(MicYield.OTHER_APP, MicYield.beforeOpen(inCall = false, othersRecording = 1))
        assertEquals(MicYield.IN_CALL, MicYield.beforeOpen(inCall = true, othersRecording = 0))
        assertEquals(MicYield.IN_CALL, MicYield.beforeOpen(inCall = true, othersRecording = 2))
    }

    @Test fun keepsOpenButIdleWhileSilencedOrInCall() {
        assertNull(MicYield.whileOpen(inCall = false, silenced = false))
        assertEquals(MicYield.OTHER_APP, MicYield.whileOpen(inCall = false, silenced = true))
        assertEquals(MicYield.IN_CALL, MicYield.whileOpen(inCall = true, silenced = false))
    }
}
