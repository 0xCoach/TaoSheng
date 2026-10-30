package app.resound.transcribe

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AsrSilence073Test {
    @get:Rule val temp = TemporaryFolder()
    private fun speech(seconds: Int) = ShortArray(seconds * 16000) { 1000 }
    private fun audio() = speech(1) + ShortArray(3 * 16000) + speech(2)
    private fun trimmer() = AsrSilenceTrimmer(temp.root) { _, target ->
        target.outputStream().buffered().use { out -> audio().forEach {
            out.write(it.toInt() and 255); out.write((it.toInt() shr 8) and 255)
        } }
    }

    @Test fun cutInteriorPauseRetainsPaddingAndMapsBothSidesOfJoin() {
        val plan = AsrSilencePlan.analyze(audio())
        assertEquals(56000, plan.trimmedSamples)
        assertEquals(1250L, plan.originalMillis(1250, end = true))
        assertEquals(3750L, plan.originalMillis(1250))
        val result = plan.restore(AsrResult(listOf(AsrSentence(1500, 2500, "原话", "0")), 3500, null), 6000)
        assertEquals(AsrSentence(4000, 5000, "原话", "0"), result.sentences.single())
        assertEquals(6000L, result.durationMillis)
    }

    @Test fun preserveTwoSecondPauseWeakSpeechAndLeadingTrailingSilence() {
        listOf(speech(1) + ShortArray(32000) + speech(1),
            speech(1) + ShortArray(48000) { 100 } + speech(1),
            ShortArray(48000) + speech(1) + ShortArray(48000)).forEach {
            assertFalse(AsrSilencePlan.analyze(it).changed)
        }
    }

    @Test fun providerGetsTrimmedCopyButPlaybackSourceAndMapStayOriginal() = runBlocking {
        val source = temp.newFile("original.ogg").apply { writeText("原录音占位") }
        val original = source.readBytes()
        val trimmer = trimmer()
        var sent: File? = null
        val result = trimmer.recognize(AsrAudio(source, "ogg", 6000)) { input ->
            sent = input.file
            assertEquals(3500L, input.durationMillis)
            assertEquals(56000, readPcm16(input.file).size)
            AsrResult(listOf(AsrSentence(1500, 2500, "试听句", "0")), 3500, null)
        }
        assertEquals(4000L, result.sentences.single().startMillis)
        assertArrayEquals(original, source.readBytes())
        assertFalse(sent!!.exists())
        assertEquals(1, File(temp.root, "asr-time-maps").listFiles()!!.size)
    }

    @Test fun cancellationDeletesTrimmedCopyAndDoesNotRetryOriginal() = runBlocking {
        val source = temp.newFile("source.ogg")
        val trimmer = trimmer()
        var sent: File? = null
        var calls = 0
        try {
            trimmer.recognize(AsrAudio(source, "ogg", 6000)) { input ->
                calls++; sent = input.file; throw CancellationException()
            }
            fail("必须传播取消")
        } catch (_: CancellationException) { }
        assertEquals(1, calls); assertFalse(sent!!.exists()); assertTrue(source.exists())
    }

    /** 可选的同批真实素材门禁：只读 PCM；不上传、不把私人素材打进测试包。 */
    @Test fun unchangedRealSamplesKeepExactProviderInput() = runBlocking {
        val root = System.getenv("ASR073_PCM_DIR")
        assumeNotNull(root)
        val trimmer = AsrSilenceTrimmer(temp.root) { _, _ -> error("已有 PCM，不得重复解码") }
        for (id in listOf("S1", "S2", "S3", "M1", "M2", "M3", "L1", "L2")) {
            val pcm = File(root, "$id.pcm")
            val input = AsrAudio(pcm, "pcm", pcm.length() / 32, pcmFile = pcm)
            val hash = java.security.MessageDigest.getInstance("SHA-256")
            fun digest(): ByteArray = pcm.inputStream().use { stream ->
                hash.reset(); val bytes = ByteArray(8192)
                while (true) { val n = stream.read(bytes); if (n < 0) break; hash.update(bytes, 0, n) }
                hash.digest()
            }
            val before = digest()
            trimmer.recognize(input) { sent ->
                assertSame("$id 有删音，不应按无变化通过", input, sent)
                AsrResult(emptyList(), input.durationMillis, null)
            }
            assertArrayEquals(before, digest())
        }
    }
}
