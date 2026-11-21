package app.resound.qa

import org.junit.Assert.*
import org.junit.Test

class QaRulesTest {
    private val DAY = 24L * 3600 * 1000
    // 固定注入：2026-11-18（周三）零点 = dayStart；nowMs = 当天中午
    private val dayStart = 1_800_000_000_000L - (1_800_000_000_000L % DAY)
    private val nowMs = dayStart + 12 * 3600 * 1000

    @Test fun today() {
        val h = QaRules.parse("今天我说过什么", nowMs, dayStart)
        assertEquals(dayStart, h.window!!.first)
        assertTrue(QaRules.inWindow(h, nowMs))
        assertFalse(QaRules.inWindow(h, dayStart - 1))
    }

    @Test fun lastWeekIsPreviousMondayToSunday() {
        val h = QaRules.parse("上周我提过哪些值得研究的产品", nowMs, dayStart)
        val dow = ((dayStart / DAY + 4) % 7).toInt()
        val weekStart = dayStart - dow * DAY
        assertEquals(weekStart - 7 * DAY, h.window!!.first)
        assertEquals(weekStart - 1, h.window.second)
        assertFalse(QaRules.inWindow(h, nowMs)) // 本周不在上周窗里
    }

    @Test fun rejectedStatus() {
        val h = QaRules.parse("哪些已经被否决", nowMs, dayStart)
        assertTrue("Rejected" in h.statuses!!)
        assertTrue("Withdrawn" in h.statuses!!)
    }

    @Test fun unverifiedStatus() {
        val h = QaRules.parse("还没验证的机会", nowMs, dayStart)
        assertTrue("Research" in h.statuses!!)
        assertNull(h.window)
    }

    @Test fun noHints() {
        val h = QaRules.parse("麦克风怎么配", nowMs, dayStart)
        assertFalse(h.active)
        assertEquals("未加过滤", QaRules.describe(h))
    }

    @Test fun nDaysAgo() {
        val h = QaRules.parse("3天前提到的方案", nowMs, dayStart)
        assertEquals(dayStart - 3 * DAY, h.window!!.first)
        assertEquals(dayStart - 2 * DAY - 1, h.window.second)
    }

    @Test fun topicWordsStripHintWords() {
        val h = QaRules.parse("上周否决过睡眠产品", nowMs, dayStart)
        assertTrue(h.topicWords.any { it.contains("睡眠") || it.contains("产品") })
        assertFalse(h.topicWords.any { it.contains("上周") || it.contains("否决") })
    }
}
