package app.resound.voiceprint

import org.junit.Assert.*
import org.junit.Test
import org.junit.Assume.assumeNotNull
import org.json.JSONObject
import java.io.File

class SpeakerPrecheck073Test {
    private val pcm = ShortArray(12 * 16000)
    private val speech = listOf(pcm.indices)
    private fun vector(i: Int) = FloatArray(192) { if (it == i) 1f else 0f }

    @Test fun shortFullyVoicedSingleSpeakerCanSkip() {
        assertTrue(SpeakerPrecheck.single(pcm, speech, SpeakerEmbedding { vector(0) }))
        val windows = SpeakerPrecheck.windows(pcm.size)
        assertEquals(0, windows.first().first)
        assertEquals(pcm.lastIndex, windows.last().last)
        assertTrue(windows.zipWithNext().all { (a, b) -> a.last + 1 == b.first })
    }

    @Test fun interveningSecondSpeakerMustUseFullDiarization() {
        var calls = 0
        assertFalse(SpeakerPrecheck.single(pcm, speech, SpeakerEmbedding {
            vector(if (++calls == 3) 1 else 0)
        }))
        assertEquals(3, calls)
    }

    @Test fun longOrNotClearlyVoicedNeverClaimsSingle() {
        val forbidden = SpeakerEmbedding { error("保守门禁必须先于模型计算") }
        assertFalse(SpeakerPrecheck.single(ShortArray(301 * 16000), emptyList(), forbidden))
        assertFalse(SpeakerPrecheck.single(pcm, listOf(0 until 3 * 16000), forbidden))
    }

    @Test fun atMostEightSampledWindowsKeepS2M2M3FullAndFindM1() {
        val root = System.getenv("ASR073_PCM_DIR")
        assumeNotNull(root)
        val rows = JSONObject(File(root, "sampled-precheck.json").readText()).getJSONArray("rows")
        assertEquals(8, rows.length())
        for (index in 0 until rows.length()) {
            val row = rows.getJSONObject(index)
            val pcm = app.resound.transcribe.readPcm16(File(root, "${row.getString("id")}.pcm"))
            val ranges = row.getJSONArray("speech")
            val speech = (0 until ranges.length()).map { i ->
                val span = ranges.getJSONArray(i); span.getInt(0) until span.getInt(1)
            }
            val vectors = row.getJSONArray("vectors")
            var index = 0
            val skip = SpeakerPrecheck.single(pcm, speech, SpeakerEmbedding {
                val v = vectors.getJSONArray(index++)
                FloatArray(v.length()) { v.getDouble(it).toFloat() }
            })
            assertTrue(index <= 8)
            assertEquals(row.getString("id"), row.getString("id") == "M1", skip)
        }
    }
}
