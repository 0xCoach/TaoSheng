package app.resound.transcribe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 低置信转写复核规则：各标记的触发边界。 */
class TranscriptReviewRulesTest {

    @Test fun normalLineProducesNoFlags() {
        assertTrue(TranscriptReviewRules.flagsOf("今天把方案定了", 2500, 1, null, null, false).isEmpty())
    }

    @Test fun denseAndSparseLinesFlagSuspiciousDensity() {
        // 1 秒 12 字 → 异常粘连
        assertTrue(ReviewFlag.SuspiciousDensity in TranscriptReviewRules.flagsOf("一二三四五六七八九十甲乙", 1000, 1, null, null, false))
        // 6 秒只出 1 字 → 大概率漏听
        assertTrue(ReviewFlag.SuspiciousDensity in TranscriptReviewRules.flagsOf("嗯", 6000, 1, null, null, false))
        // 6 秒正常说话不标
        assertTrue(ReviewFlag.SuspiciousDensity !in TranscriptReviewRules.flagsOf("今天把方案定下来再发出去", 6000, 1, null, null, false))
    }

    @Test fun retriedAndLowSpeakerScoreFlag() {
        val flags = TranscriptReviewRules.flagsOf("正常一句话", 2000, 3, 0.2f, "甲", false)
        assertEquals(setOf(ReviewFlag.RetriedSegment, ReviewFlag.LowSpeakerScore), flags)
        // 没分人的行不因低分被标
        assertTrue(TranscriptReviewRules.flagsOf("正常一句话", 2000, 1, 0.1f, null, false).isEmpty())
    }

    @Test fun correctedTextIsWeakSignalAndWeightOrders() {
        val corrected = TranscriptReviewRules.flagsOf("正常一句话", 2000, 1, null, null, true)
        assertEquals(setOf(ReviewFlag.CorrectedText), corrected)
        val dense = setOf(ReviewFlag.SuspiciousDensity)
        assertTrue(TranscriptReviewRules.weight(dense) > TranscriptReviewRules.weight(corrected))
    }
}
