package app.resound.transcribe

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackDigestSettingsTest {
    @Test fun initialDigestMatchesMajorityAndSwitch() {
        assertEquals(0, PlaybackDigestSettings.initial(10_000, 4_999, false))
        assertEquals(0, PlaybackDigestSettings.initial(10_000, 4_999, true))
        assertEquals(1, PlaybackDigestSettings.initial(10_000, 5_000, false))
        assertEquals(2, PlaybackDigestSettings.initial(10_000, 5_000, true))
        assertEquals(0, PlaybackDigestSettings.initial(0, 0, true))
    }

    @Test fun playbackFileRoundTripAndOldTwoFieldDefaultsOff() {
        assertEquals("1000:true:false", PlaybackDigestSettings.formatFile(1000, true, false))
        assertEquals(Triple(1000L, true, false), PlaybackDigestSettings.parseFile("1000:true:false"))
        assertEquals(Triple(500L, false, true), PlaybackDigestSettings.parseFile("500:false:true"))
        assertEquals(Triple(0L, true, false), PlaybackDigestSettings.parseFile("0:true"))
    }
}
