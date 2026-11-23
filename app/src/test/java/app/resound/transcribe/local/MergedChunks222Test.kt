package app.resound.transcribe.local

import com.k2fsa.sherpa.onnx.OfflineRecognizerResult
import org.junit.Assert.*
import org.junit.Test

/** 卡 222：不按说话人切，只按停顿切（目标 20 秒，最晚 28 秒），说话人按每个字的时间贴回去 */
class MergedChunks222Test {
    private val sr = 16000
    private fun speech(seconds: Int, vararg pausesAt: Int) = ShortArray(seconds * sr) { 1000 }.also { pcm ->
        for (p in pausesAt) java.util.Arrays.fill(pcm, p * sr, p * sr + sr / 2, 0.toShort())
    }

    @Test fun cutsAtLastPauseBefore20Seconds() {
        val chunks = PauseChunks.split(speech(40, 12, 18))
        assertTrue(chunks.first().end in 18 * sr..18 * sr + sr / 2)
        assertEquals(0, chunks[1].overlapSamples)
    }

    @Test fun noPauseBefore20LooksForwardUpTo28() {
        val chunks = PauseChunks.split(speech(40, 24))
        assertTrue(chunks.first().end in 24 * sr..24 * sr + sr / 2)
        assertEquals(0, chunks[1].overlapSamples)
    }

    @Test fun upTo28SecondsWithoutPauseStaysOneChunk() {
        assertEquals(1, PauseChunks.split(speech(26)).size)
    }

    @Test fun neverLongerThan28AndHardCutOverlapsOneSecond() {
        val chunks = PauseChunks.split(speech(70))
        assertTrue(chunks.all { it.end - it.start <= PauseChunks.LIMIT })
        assertEquals(16000, chunks[1].overlapSamples)
        assertEquals(chunks[0].end - 16000, chunks[1].start)
    }

    @Test fun speakerChangeNoLongerCutsAndTagsFollowWordTimes() {
        val turns = listOf(ResumeTurn(0, 3 * sr, 0), ResumeTurn(3 * sr, 6 * sr, 1))
        val chunks = PauseChunks.split(speech(6))
        assertEquals(1, chunks.size)
        val tags = TurnTags(turns, speakers = true)
        val sentences = TimedSentences()
        sentences.append(OfflineRecognizerResult("你好吗好的", arrayOf("你", "好", "吗", "好", "的"),
            floatArrayOf(.5f, .8f, 1.1f, 3.5f, 3.8f), "zh", "", "", floatArrayOf()), chunks.single(), tags::at, tags.dominant(0, 6 * sr))
        val lines = sentences.result()
        assertEquals(listOf("你好吗" to "0", "好的" to "1"), lines.map { it.text to it.speakerTag })
    }

    @Test fun unknownSpeakerAndSpeakersOff() {
        val turns = listOf(ResumeTurn(0, 2 * sr, null), ResumeTurn(2 * sr, 4 * sr, 1))
        assertEquals("uncertain:0", TurnTags(turns, true).at(500))
        assertEquals("1", TurnTags(turns, true).at(2500))
        assertNull(TurnTags(listOf(ResumeTurn(0, 4 * sr, null)), false).at(500))
        assertEquals("1", TurnTags(turns, true).dominant(sr, 4 * sr))
    }
}
