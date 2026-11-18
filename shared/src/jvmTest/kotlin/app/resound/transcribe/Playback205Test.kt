package app.resound.transcribe

import app.resound.audio.*
import app.resound.screentime.DevicePlayback
import org.junit.Assert.*
import org.junit.Test

class Playback205Test {
    @Test fun fresh() = assertEquals(PlaybackMode.Digest, migratePlayback(null, null))
    @Test fun bothOn() = assertEquals(PlaybackMode.Digest, migratePlayback(true, true))
    @Test fun digestOnly() = assertEquals(PlaybackMode.Digest, migratePlayback(false, true))
    @Test fun bothOff() = assertEquals(PlaybackMode.Transcribe, migratePlayback(false, false))
    @Test fun skipOnly() = assertEquals(PlaybackMode.Skip, migratePlayback(true, false))

    private val rows = listOf(DevicePlayback("a","A",null,null,0,30), DevicePlayback("b","B","节目",null,30,70),
        DevicePlayback("a","A",null,null,70,100))
    private val full = listOf(SpeakerInterval(0,100))
    private fun decide(rule: PlaybackOverride) = decidePlayback(0,100,full,rows,
        PlaybackPreferences(PlaybackMode.Digest, mapOf("a" to rule)))

    @Test fun skipLongestApp() = assertTrue(decide(PlaybackOverride.Skip).skip)
    @Test fun transcribeLongestApp() = assertEquals(1, decide(PlaybackOverride.Transcribe).digest)
    @Test fun digestLongestApp() = assertEquals(2, decide(PlaybackOverride.Digest).digest)
    @Test fun subtractIgnoredTimeBeforeMajorityAndLabelOnlyAudibleSentence() {
        val result = decide(PlaybackOverride.NotPlayback)
        assertEquals(0, result.digest)
        assertEquals(40L, result.millis)
        assertFalse(sentencePlayback(1,0,30,result.intervals,40,100,rows).playing)
        assertEquals("节目",sentencePlayback(2,30,70,result.intervals,40,100,rows).title)
        assertEquals(SentencePlayback(2,true),sentencePlayback(2,30,70,result.intervals,40,100,emptyList()))
    }
    @Test fun zeroVolumeKeepsPlaying() = assertEquals(PlaybackState(true,false),
        playbackState(listOf(PlaybackConfigSnapshot(1,2)),listOf(2),true,0))
    @Test fun noRowsUseDefaultAndSessionEndsAtStop() {
        val prefs = PlaybackPreferences(PlaybackMode.Skip)
        assertTrue(decidePlayback(0,100,full,emptyList(),prefs).skip)
        val session = PlaybackSession()
        session.update(true,true,null,prefs.rule(null),0)
        assertTrue(session.canRemember)
        session.remember(10)
        session.update(false,false,null,prefs.rule(null),80)
        assertTrue(session.remembers(70))
        assertFalse(session.remembers(80))
        assertEquals(2, decidePlayback(0,100,listOf(SpeakerInterval(10,80,true)),emptyList(),prefs).digest)
    }
}
