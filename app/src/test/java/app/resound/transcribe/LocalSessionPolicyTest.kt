package app.resound.transcribe

import app.resound.transcribe.local.*
import app.resound.voiceprint.SpeakerTurns
import com.k2fsa.sherpa.onnx.OfflineRecognizerResult
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.io.File

class LocalSessionPolicyTest {
    @Test fun markerChecksMetadataAndUpgradesLegacyOnlyOnce() {
        val dir=Files.createTempDirectory("model-marker").toFile()
        try {
            val file=File(dir,"model").apply{writeText("abc")}
            val spec=ModelSpec("test","https://example.com",3,"","model",3,LocalModels.hash(file),false)
            File(dir,"verified").writeText(spec.modelHash)
            assertTrue(ModelVerification.ensure(dir,spec))
            assertTrue(ModelVerification.matches(dir,spec))
            val stamp=File(dir,"verified").lastModified()
            assertTrue(ModelVerification.ensure(dir,spec));assertEquals(stamp,File(dir,"verified").lastModified())
            file.setLastModified(file.lastModified()+5000)
            assertFalse(ModelVerification.matches(dir,spec));assertFalse(ModelVerification.ensure(dir,spec))
        } finally {dir.deleteRecursively()}
    }
    @Test fun pauseCutAndFallbackOverlapRespectSpeakerBoundaries() {
        val pcm=ShortArray(60*16000){1000}
        java.util.Arrays.fill(pcm,25*16000,26*16000,0.toShort())
        val chunks=PauseChunks.split(pcm,listOf(SpeakerTurns.Turn(0,pcm.size,0)))
        assertTrue(chunks.first().end in 25*16000..26*16000)
        assertEquals(0,chunks[1].overlapSamples)
        val continuous=PauseChunks.split(ShortArray(40*16000){1000},listOf(SpeakerTurns.Turn(0,40*16000,0)))
        assertEquals(16000,continuous[1].overlapSamples)
        assertEquals(continuous[0].end-16000,continuous[1].start)
        assertTrue(continuous.all{it.end-it.start<=PauseChunks.LIMIT})
        val fullTurns=SpeakerTurns.partition(listOf(SpeakerTurns.Turn(0,20*16000,0),SpeakerTurns.Turn(20*16000,pcm.size,1)),pcm.size,Int.MAX_VALUE)
        assertEquals(listOf(SpeakerTurns.Turn(0,20*16000,0),SpeakerTurns.Turn(20*16000,pcm.size,1)),fullTurns)
        val turns=PauseChunks.split(pcm,fullTurns)
        assertEquals(20*16000,turns.first().end);assertEquals(20*16000,turns[1].start)
    }
    private fun result(tokens:Array<String>,times:FloatArray)=OfflineRecognizerResult(tokens.joinToString(""),tokens,times,"zh","","",floatArrayOf())
    @Test fun realTokenTimesSplitSentencesAndOverlapDoesNotRepeatWords() {
        val aligned=TimedSentences()
        aligned.append(result(arrayOf("你","好","。","再","见","。"),floatArrayOf(.1f,.3f,.5f,2f,2.2f,2.4f)),PauseChunks.Chunk(16000,5*16000,0),"0")
        val lines=aligned.result();assertEquals(listOf("你好。","再见。"),lines.map{it.text})
        assertEquals(1100L,lines[0].startMillis);assertEquals(3000L,lines[1].startMillis)
        val english=TimedSentences()
        english.append(result(arrayOf("Hello","▁world","."),floatArrayOf(.1f,.3f,.5f)),PauseChunks.Chunk(0,16000,0),"0")
        assertEquals("Hello world.",english.result().single().text)
        val overlap=TimedSentences()
        overlap.append(result(arrayOf("你","好","。"),floatArrayOf(27.1f,27.4f,27.6f)),PauseChunks.Chunk(0,28*16000,0),"0")
        overlap.append(result(arrayOf("你","好","吗","？"),floatArrayOf(.1f,.4f,.7f,.9f)),PauseChunks.Chunk(27*16000,30*16000,0,16000),"0")
        assertEquals("你好吗？",overlap.result().joinToString(""){it.text})
    }
    @Test fun identityUsesStableIrreversibleFingerprint() {
        val key="synthetic-private-key"
        assertEquals(keyFingerprint(key),keyFingerprint(key))
        assertNotEquals(keyFingerprint(key),keyFingerprint("other"))
        assertFalse(keyFingerprint(key).contains(key));assertEquals(64,keyFingerprint(key).length)
    }
}
