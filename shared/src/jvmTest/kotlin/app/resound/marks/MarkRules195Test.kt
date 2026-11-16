package app.resound.marks

import app.resound.digest.core.TranscriptLineData
import app.resound.quick.*
import app.resound.topic.TopicKind
import org.junit.Test
import org.junit.Assert.*

class MarkRules195Test {
    private fun assertContains(text: String, expected: String) = assertTrue(text, text.contains(expected))
    @Test fun playbackUsesSourceClockAndRejectsInvalidPosition() {
        assertEquals(1_005_000L, MarkRules.playbackAt(1_000_000, 5_000, 10_000))
        assertThrows(IllegalArgumentException::class.java) { MarkRules.playbackAt(1_000_000, 11_000, 10_000) }
    }
    @Test fun halfOpenOverlapAndRangeValidation() {
        val mark = MarkRules.create(200_000, MarkRange(), null, "Recording", 300_000)
        assertEquals(80_000L, mark.startAt)
        assertEquals(260_000L, mark.endAt)
        assertTrue(MarkRules.overlaps(mark.startAt, mark.endAt, 100_000, 110_000))
        assertFalse(MarkRules.overlaps(mark.startAt, mark.endAt, 260_000, 270_000))
        assertThrows(IllegalArgumentException::class.java) { MarkRange(29_999, 60_000) }
        assertThrows(IllegalArgumentException::class.java) { MarkRange(30_000, 600_001) }
    }
    @Test fun promptClipsToBatchAndUsesItsActualLineNumbers() {
        val lines = listOf(TranscriptLineData(99, 1, 200_000, 210_000, "确认交付", "1", "甲"),
            TranscriptLineData(110, 1, 220_000, 230_000, "完成", "2", "乙"))
        val mark = MarkRules.create(210_000, MarkRange(), "进度", "Recording", 210_000)
        val outside = MarkRules.create(9_000_000, MarkRange(), "不应出现", "Recording", 9_000_000)
        val text = MarkPrompt.context(listOf(mark, outside), lines)
        assertContains(text, "\"from\":200000")
        assertContains(text, "\"until\":230000")
        assertContains(text, "\"lineNumbers\":[\"1\",\"2\"]")
        assertFalse(text.contains("不应出现"))
        assertContains(text, "不得编造")
        assertContains(MarkPrompt.context(listOf(mark), lines, true), "L99")
        assertEquals("", MarkPrompt.context(listOf(mark), emptyList()))
    }
    @Test fun listeningMenuKeepsFourEntriesAndStaleTopicGuard() {
        val entries = DefaultQuickActionsPolicy().entries(ListenState.Listening, TopicState.None, 30, TopicKind.Meeting)
        assertEquals(4, entries.size)
        assertEquals(QuickAction.MarkTopic, entries[1].action)
        assertFalse(QuickExecutionRules.allowed(QuickAction.MarkTopic, 0, 7, ListenState.Listening, TopicState.Running, 30, TopicKind.Meeting))
    }
}
