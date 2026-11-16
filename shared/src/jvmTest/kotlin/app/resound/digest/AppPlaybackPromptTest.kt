package app.resound.digest

import org.junit.Assert.assertEquals
import org.junit.Test

class AppPlaybackPromptTest {
    @Test fun allowedPlaybackIsListeningEventNotPersonalExperienceOrTodo() {
        val rule = app.resound.digest.core.SituationPrompt(false, null, mapOf(1L to 3_000L)).system("base")
        org.junit.Assert.assertTrue(rule.contains("用户允许整理的外放片段"))
        org.junit.Assert.assertTrue(rule.contains("收听/观看的节目内容"))
        org.junit.Assert.assertTrue(rule.contains("不得写成用户本人经历"))
        org.junit.Assert.assertTrue(rule.contains("不得据节目内容生成待办或约定"))
        org.junit.Assert.assertTrue(rule.contains("未允许整理的片段仍不进整理"))
        org.junit.Assert.assertFalse(rule.contains("不要据此生成事件"))
    }

    @Test fun closedGatesSkipQuery() {
        var n = 0
        assertEquals(emptyList<String>(), playbackRecordsForPrompt(false, true) { n++; listOf("x") })
        assertEquals(emptyList<String>(), playbackRecordsForPrompt(true, false) { n++; listOf("x") })
        assertEquals(emptyList<String>(), playbackRecordsForPrompt(false, false) { n++; listOf("x") })
        assertEquals(0, n)
        assertEquals(listOf("x"), playbackRecordsForPrompt(true, true) { n++; listOf("x") })
        assertEquals(1, n)
    }

    @Test fun foregroundGateSkipsQueryUnlessScreenAndPrivacyOn() {
        var n = 0
        assertEquals(emptyList<String>(), foregroundSessionsForPrompt(false, true) { n++; listOf("微信") })
        assertEquals(emptyList<String>(), foregroundSessionsForPrompt(true, false) { n++; listOf("微信") })
        assertEquals(emptyList<String>(), foregroundSessionsForPrompt(false, false) { n++; listOf("微信") })
        assertEquals(0, n)
        assertEquals(listOf("微信"), foregroundSessionsForPrompt(true, true) { n++; listOf("微信") })
        assertEquals(1, n)
    }
}
