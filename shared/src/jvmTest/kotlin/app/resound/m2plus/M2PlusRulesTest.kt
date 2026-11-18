package app.resound.m2plus

import app.resound.collect.CaptureKind
import app.resound.collect.CollectRules
import app.resound.decision.DecisionRow
import app.resound.decision.DecisionConfidence
import app.resound.decision.DecisionRules
import app.resound.decision.DecisionStatus
import app.resound.followup.FollowupKind
import app.resound.followup.FollowupRow
import app.resound.followup.FollowupRules
import app.resound.followup.FollowupStatus
import app.resound.mcp.McpTools
import app.resound.rules.WorkflowRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** M2+ 共用规则：决策冲突/置信度、跟进限频、规则触发判定、收集分类、MCP 工具白名单。 */
class M2PlusRulesTest {

    private fun decision(id: Long, topic: String, status: String = DecisionStatus.Active.name) =
        DecisionRow(id = id, title = "t$id", detail = "d", status = status, topicKey = topic, decidedAt = 1, createdAt = 1, updatedAt = 1)

    @Test fun topicKeyNormalizesPunctuationAndCase() {
        assertEquals(DecisionRules.topicKey("做 声 声 不 息！"), DecisionRules.topicKey("做声声不息"))
        assertEquals(DecisionRules.topicKey("AB-12"), DecisionRules.topicKey("ab12"))
    }

    @Test fun conflictsOnlyFlagsActiveSameTopic() {
        val rows = listOf(decision(1, "k"), decision(2, "k", DecisionStatus.Superseded.name), decision(3, "other"))
        assertEquals(listOf(1L), DecisionRules.conflicts(rows, "k").map { it.id })
        assertTrue(DecisionRules.conflicts(rows, "").isEmpty())
    }

    @Test fun guessConfidenceSeparatesCasualFromCommitment() {
        assertEquals(DecisionConfidence.Tentative, DecisionRules.guessConfidence("也许可以研究一下"))
        assertEquals(DecisionConfidence.Sure, DecisionRules.guessConfidence("就这么定，放弃这个方向"))
        assertEquals(DecisionConfidence.Likely, DecisionRules.guessConfidence("看看这个产品的需求"))
    }

    private fun followup(kind: FollowupKind, lastRemindedAt: Long = 0, snoozedUntil: Long = 0, status: String = FollowupStatus.Open.name) =
        FollowupRow(kind = kind.name, title = "t", status = status, createdAt = 1,
            lastRemindedAt = lastRemindedAt, snoozedUntil = snoozedUntil, updatedAt = 1)

    @Test fun canRemindRespectsLimits() {
        val now = 100_000_000L
        assertTrue(FollowupRules.canRemind(followup(FollowupKind.Todo), now, 0))
        assertFalse("每日上限", FollowupRules.canRemind(followup(FollowupKind.Todo), now, FollowupRules.DAILY_LIMIT))
        assertFalse("同项最小间隔", FollowupRules.canRemind(followup(FollowupKind.Todo, lastRemindedAt = now - 1000), now, 0))
        assertFalse("稍后期间不提", FollowupRules.canRemind(followup(FollowupKind.Todo, snoozedUntil = now + 1000), now, 0))
        assertFalse("已了结不提", FollowupRules.canRemind(followup(FollowupKind.Todo, status = FollowupStatus.Done.name), now, 0))
    }

    @Test fun nextCheckAtDecaysAfterRepeatReminders() {
        val now = 1_000_000L
        assertEquals(now + FollowupRules.CHECK_INTERVAL_MS, FollowupRules.nextCheckAt(0, now))
        assertEquals(now + 7 * FollowupRules.CHECK_INTERVAL_MS, FollowupRules.nextCheckAt(FollowupRules.MAX_REMIND_COUNT, now))
    }

    @Test fun suggestionReminderIsMarkedAsNotACommitment() {
        val text = FollowupRules.reminderText(followup(FollowupKind.Suggestion))
        assertTrue(text.contains("不是你的承诺"))
        assertFalse(FollowupRules.reminderText(followup(FollowupKind.Commitment)).contains("不是你的承诺"))
    }

    @Test fun timeDueFiresOncePerDayAfterThreshold() {
        val trig = """{"hour":9,"minute":30}"""
        assertTrue(WorkflowRules.timeDue(trig, "", "2026-11-19", 9 * 60 + 30))
        assertFalse("当天已触发不再发", WorkflowRules.timeDue(trig, "2026-11-19", "2026-11-19", 600))
        assertFalse("到点前不发", WorkflowRules.timeDue(trig, "", "2026-11-19", 9 * 60 + 29))
    }

    @Test fun eventMatchesOnlyNamedEvent() {
        val trig = """{"event":"idea_created"}"""
        assertTrue(WorkflowRules.eventMatches(trig, "idea_created"))
        assertFalse(WorkflowRules.eventMatches(trig, "capture_arrived"))
    }

    @Test fun classifySplitsLinksFromText() {
        assertEquals(CaptureKind.Url, CollectRules.classify("https://x.com/a 看看"))
        assertEquals(CaptureKind.Text, CollectRules.classify("没有链接的一段话"))
    }

    @Test fun extractUrlTrimsTrailingCjkPunctuation() {
        assertEquals("https://x.com/a", CollectRules.extractUrl("看这个 https://x.com/a。"))
        assertEquals("x.com", CollectRules.urlTitle("https://x.com/a"))
    }

    @Test fun mcpWhitelistAllowsOnlyReadTools() {
        assertTrue(McpTools.exists("memory_search"))
        assertFalse("写工具不在只读清单", McpTools.exists("memory_write"))
        assertEquals(McpTools.readOnly.map { it.name }.toSet(), McpTools.allowed(emptyList()).toSet())
        assertEquals(listOf("decision_list"), McpTools.allowed(listOf("decision_list", "drop_table")))
    }
}
