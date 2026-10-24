package app.resound.voiceprint

import org.junit.Assert.*
import org.junit.Test

class SpeakerIdentityTest {
    private fun vector(a: Float, b: Float) = FloatArray(192).apply { this[0]=a;this[1]=b }
    @Test fun competingIdentityMustHaveClearMargin() {
        val people = listOf(SpeakerIdentity.Candidate(0,"我", vector(1f,0f)), SpeakerIdentity.Candidate(1,"同事",vector(0f,1f)))
        assertEquals("同事",SpeakerIdentity.match(vector(0f,1f),people).name)
        assertNull(SpeakerIdentity.match(vector(1f,1f),people).name)
        assertNull(SpeakerIdentity.match(null,people).personId)
    }
    @Test fun overlappingTurnsDoNotDuplicateAudioOrAssignMixedSpeech() {
        val turns = SpeakerTurns.partition(listOf(SpeakerTurns.Turn(0,48000,0),SpeakerTurns.Turn(32000,80000,1)),80000)
        assertEquals(listOf(0,null,1),turns.map { it.speaker })
        assertEquals(80000,turns.sumOf { it.end-it.start })
        assertTrue(turns.zipWithNext().all { (a,b)-> a.end<=b.start })
        val gaps = SpeakerTurns.partition(listOf(SpeakerTurns.Turn(16000,32000,0),SpeakerTurns.Turn(64000,80000,0)),96000)
        assertEquals(listOf(SpeakerTurns.Turn(0,96000,0)),gaps)
        assertEquals(3,SpeakerTurns.partition(emptyList(),65*16000).size)
    }
}
