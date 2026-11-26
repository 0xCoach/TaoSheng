package app.resound.transcribe

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackDigestSettingsTest {
    @Test fun recoveryClampsDurationAndRoundsHalfUp() {
        assertEquals(PlaybackDecision(emptyList(), 4, false, 0), LegacyPlaybackFile.recover("4:true:true", 9))
        assertEquals(PlaybackDecision(emptyList(), 5, true, 1), LegacyPlaybackFile.recover("5:true:false", 9))
        assertEquals(PlaybackDecision(emptyList(), 10, false, 2), LegacyPlaybackFile.recover("20:false:true", 10))
        assertEquals(PlaybackDecision(emptyList(), 0, false, 0), LegacyPlaybackFile.recover("8:true:true", 0))
    }

    @Test fun recoveryKeepsLegacyDefaultsAndLiteralFlagValues() {
        assertEquals(PlaybackDecision(emptyList(), 5, true, 1), LegacyPlaybackFile.recover("5", 10))
        assertEquals(PlaybackDecision(emptyList(), 5, false, 1), LegacyPlaybackFile.recover("5:false", 10))
        assertEquals(PlaybackDecision(emptyList(), 5, true, 1), LegacyPlaybackFile.recover("5:False:TRUE:extension", 10))
        assertEquals(PlaybackDecision(emptyList(), 5, true, 2), LegacyPlaybackFile.recover("5::true:extension", 10))
        assertEquals(PlaybackDecision(emptyList(), 0, false, 0), LegacyPlaybackFile.recover("bad:false:true", 10))
    }
}
