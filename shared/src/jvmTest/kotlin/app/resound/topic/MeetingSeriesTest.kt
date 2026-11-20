package app.resound.topic

import app.resound.topic.core.MeetingOpenItem
import app.resound.topic.core.MeetingSeriesRules
import app.resound.topic.core.TopicPolicy
import org.junit.Assert.*
import org.junit.Test

class MeetingSeriesTest {

    @Test fun seriesKeyNormalizesName() {
        assertEquals("产品周会", TopicPolicy.seriesKey(" 产品 周会 "))
        assertEquals("产品周会", TopicPolicy.seriesKey("产品　周会"))
        assertEquals("sprintplanning", TopicPolicy.seriesKey("Sprint Planning"))
        assertNull(TopicPolicy.seriesKey(null))
        assertNull(TopicPolicy.seriesKey("   "))
    }

    @Test fun previousContextIncludesCardAndOpenItems() {
        val card = """{"conclusion":{"text":"定了先上安卓"},"decisions":{"items":["报价走 B 方案","UI 不改"]},
            "actions":{"items":["小李周五前确认库存","我明早发报价"]}}"""
        val open = listOf(
            MeetingOpenItem("待办", "小李", "周五前确认库存", "周五前"),
            MeetingOpenItem("约定", "我", "明天下午发报价方案", "明天下午"))
        val text = MeetingSeriesRules.previousContext("产品周会", "2026-11-13", card, open)
        assertTrue(text.contains("《产品周会》"))
        assertTrue(text.contains("上次结论：定了先上安卓"))
        assertTrue(text.contains("报价走 B 方案"))
        assertTrue(text.contains("小李周五前确认库存"))
        assertTrue(text.contains("周五前确认库存"))
        assertTrue(text.contains("明天下午发报价方案"))
    }

    @Test fun previousContextEmptyWithoutMaterial() {
        // 无卡片、无待跟进：不注入空标题行。
        assertEquals("", MeetingSeriesRules.previousContext("周会", "2026-11-13", null, emptyList()))
        // 卡片 JSON 损坏也不能把生成拖垮。
        assertEquals("", MeetingSeriesRules.previousContext("周会", "2026-11-13", "not-json", emptyList()))
    }

    @Test fun previousContextWorksWithoutCard() {
        val open = listOf(MeetingOpenItem("待办", null, "确认库存", null))
        val text = MeetingSeriesRules.previousContext(null, "2026-11-13", null, open)
        assertTrue(text.contains("待办：确认库存"))
        assertFalse(text.contains("上次结论"))
    }
}
