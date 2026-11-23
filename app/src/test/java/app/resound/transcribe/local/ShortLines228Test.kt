package app.resound.transcribe.local

import app.resound.transcribe.AsrSentence
import com.k2fsa.sherpa.onnx.OfflineRecognizerResult
import org.junit.Assert.*
import org.junit.Test

/** 卡 228：零散短行的通用兜底（同一个人没说完的短行并起来，不改说话人） */
class ShortLines228Test {
    private fun line(start: Long, end: Long, text: String, tag: String? = "0") = AsrSentence(start, end, text, tag)
    private fun texts(lines: List<AsrSentence>) = lines.map { it.text to it.speakerTag }

    @Test fun scatteredShortLinesOfOnePersonBecomeOneLine() {
        val merged = ShortLines.merge(listOf(line(0, 400, "情"), line(1800, 2600, "机会嘛"), line(4000, 4600, "负责"),
            line(6000, 6800, "是因")))
        assertEquals(listOf("情，机会嘛，负责，是因" to "0"), texts(merged))
        assertEquals(0L, merged.single().startMillis); assertEquals(6800L, merged.single().endMillis)
    }

    @Test fun sentenceEndStillSplits() {
        assertEquals(2, ShortLines.merge(listOf(line(0, 600, "你好。"), line(2000, 2600, "再见。"))).size)
    }

    @Test fun differentPeopleNeverJoin() {
        assertEquals(listOf("好" to "0", "嗯" to "1", "行" to "0"),
            texts(ShortLines.merge(listOf(line(0, 400, "好"), line(2000, 2400, "嗯", "1"), line(4000, 4400, "行")))))
    }

    @Test fun longSilenceKeepsLinesApart() {
        assertEquals(2, ShortLines.merge(listOf(line(0, 400, "情"), line(5401, 5800, "机会"))).size)
    }

    @Test fun twoFullLinesWithRealPauseStayApart() {
        assertEquals(2, ShortLines.merge(listOf(line(0, 2000, "今天下午开会"), line(3000, 5000, "大家都要到场"))).size)
    }

    @Test fun mergedLineIsCapped() {
        val long = "一".repeat(58)
        assertEquals(2, ShortLines.merge(listOf(line(0, 9000, long), line(9500, 10000, "二三四"))).size)
    }

    @Test fun shortInterjectionByAnotherPersonKeepsItsSpeaker() {
        assertEquals(listOf("我们今天" to "0", "对" to "1", "开会" to "0"),
            texts(ShortLines.merge(listOf(line(0, 1200, "我们今天"), line(1300, 1500, "对", "1"), line(1600, 2200, "开会")))))
    }

    @Test fun englishJoinsWithSpace() {
        assertEquals("ok so", ShortLines.merge(listOf(line(0, 400, "ok"), line(1500, 1900, "so"))).single().text)
    }

    @Test fun timedSentencesUsesIt() {
        val s = TimedSentences()
        s.append(OfflineRecognizerResult("情机会嘛负责", arrayOf("情", "机", "会", "嘛", "负", "责"),
            floatArrayOf(.1f, 1.8f, 2.0f, 2.2f, 4.0f, 4.2f), "zh", "", "", floatArrayOf()), PauseChunks.Chunk(0, 6 * 16000, 0), "0")
        assertEquals(listOf("情，机会嘛，负责"), s.result().map { it.text })
    }
}
