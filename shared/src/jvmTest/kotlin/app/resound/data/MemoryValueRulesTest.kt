package app.resound.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryValueRulesTest {
    @Test fun commitmentToPersonWithDueIsHighValue() {
        val score = MemoryValueRules.itemScore("约定", "我答应别人", dueAt = 1, who = "小王", origin = "digest")
        assertEquals(85, score)
        assertEquals(3, MemoryValueRules.tier(score))
    }

    @Test fun plainDigestMemoStaysLowAndManualEntryGetsBonus() {
        assertEquals(25, MemoryValueRules.itemScore("备忘", null, null, null, "digest"))
        assertEquals(35, MemoryValueRules.itemScore("备忘", null, null, null, "typed"))
        assertEquals(15, MemoryValueRules.itemScore("灵感", null, null, null, "digest"))
        // 用户主动来源（typed/voice/idea/rule/manual）都有加成；AI 提取没有。
        for (origin in listOf("typed", "voice", "idea", "rule", "manual"))
            assertEquals(50, MemoryValueRules.itemScore("待办", null, null, null, origin))
    }

    @Test fun parentBonusAppliesLiveAndCapsAtHundred() {
        assertEquals(40, MemoryValueRules.effectiveItemScore(40, eventStarred = false, eventImportant = false))
        assertEquals(60, MemoryValueRules.effectiveItemScore(40, eventStarred = true, eventImportant = false))
        assertEquals(100, MemoryValueRules.effectiveItemScore(85, eventStarred = true, eventImportant = true))
        assertEquals(40, MemoryValueRules.eventBonus(starred = true, important = false))
        assertEquals(65, MemoryValueRules.eventBonus(starred = true, important = true))
        assertTrue(MemoryValueRules.tier(MemoryValueRules.effectiveItemScore(40, true, false)) >= 2)
    }
}
