package app.resound.digest

import app.resound.data.TranscriptLineEntity
import org.junit.Assert.*
import org.junit.Test
import java.time.ZoneId

/** 合并提示词的解析（卡 105）：一条 merged + items，merged 覆盖全部原话行。 */
class MergePrompt105Test {
    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")

    private fun line(id: Long, start: Long = 1000, end: Long = 2000) =
        TranscriptLineEntity(id, id, start, end, "原话$id", null, eventId = id, digestedAt = 1)

    @Test fun parsesOneMergedEventCoveringEveryLine() {
        val lines = listOf(line(1, 1000, 2000), line(2, 3000, 4000))
        val json = """
            {"merged":{"scene":"工作","title":"合并后的标题","summary":"一段摘要","points":["要点一","要点二"],
            "people":["小王"],"place":"公司","emotion":"平静","intensity":3,"important":true},
            "items":[{"category":"待办","kind":"截止","content":"交报告","due":"2026-10-28 14:00","who":"我","line":2}]}
        """.trimIndent()
        val (event, items) = MergePrompt.parse(json, lines, zone)
        assertEquals(listOf(1L, 2L), event.lineIds)
        assertEquals(1000, event.startAt)
        assertEquals(4000, event.endAt)
        assertEquals("工作", event.scene)
        assertEquals("合并后的标题", event.title)
        assertEquals("一段摘要", event.summary)
        assertEquals(listOf("要点一", "要点二"), event.points)
        assertEquals(listOf("小王"), event.people)
        assertEquals("公司", event.place)
        assertEquals("平静", event.emotion)
        assertEquals(3, event.intensity)
        assertTrue(event.important)
        assertEquals(1, items.size)
        assertEquals("交报告", items.single().content)
        assertEquals("截止", items.single().kind)
        assertEquals(2L, items.single().lineId)
        assertEquals(0, items.single().eventIndex)
    }

    @Test fun failsWhenMergedIsMissing() {
        try {
            MergePrompt.parse("""{"items":[]}""", listOf(line(1)), zone)
            fail("缺少 merged 时应当报错")
        } catch (e: LlmException) {
            assertEquals(LlmError.Format, e.type)
        }
    }

    @Test fun failsWhenContentIsNotJson() {
        try {
            MergePrompt.parse("抱歉，我做不到", listOf(line(1)), zone)
            fail("不是 JSON 时应当报错")
        } catch (e: LlmException) {
            assertEquals(LlmError.Format, e.type)
        }
    }

    @Test fun keepsItemsOutOfMediaSceneAndDropsUnknownCategories() {
        val lines = listOf(line(1))
        val json = """
            {"merged":{"scene":"媒体","title":"一段视频","summary":"摘要","points":[],"people":[]},
            "items":[{"category":"待办","content":"不该留下","line":1},{"category":"胡说","content":"也不该留下","line":1}]}
        """.trimIndent()
        val (_, items) = MergePrompt.parse(json, lines, zone)
        assertTrue(items.isEmpty())
    }

    @Test fun itemLineOutOfRangeKeepsItemWithoutSource() {
        val lines = listOf(line(1))
        val json = """
            {"merged":{"scene":"工作","title":"标题","summary":"摘要","points":[],"people":[]},
            "items":[{"category":"待办","content":"交报告","line":9}]}
        """.trimIndent()
        val (_, items) = MergePrompt.parse(json, lines, zone)
        assertEquals(1, items.size)
        assertNull(items.single().lineId)
    }
}
