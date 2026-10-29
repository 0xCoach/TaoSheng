package app.resound.digest

import app.resound.data.TranscriptLineEntity
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class DigestPromptTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val base = Instant.parse("2026-10-23T01:00:00Z").toEpochMilli()
    private val lines = (1..8).map { n ->
        TranscriptLineEntity(
            id = 100L + n * 7, segmentId = 1, startAt = base + (n - 1) * 1000,
            endAt = base + n * 1000, text = "第${n}句", speakerTag = null,
        )
    }

    private fun parse(json: String) = DigestPrompt.parse(json, lines, zone)

    @Test fun calendarLabelsAndInclusiveWindow() {
        val rows = DigestPrompt.calendar(LocalDate.of(2026, 10, 23)).lines()
        assertEquals(22, rows.size)
        assertEquals("2026-10-28 周三（下周周三）", rows.single { it.startsWith("2026-10-28") })
        assertEquals("2026-10-24 周六（本周周六，明天）", rows.single { it.startsWith("2026-10-24") })
        assertEquals("2026-10-21 周三（本周周三，前天）", rows.single { it.startsWith("2026-10-21") })
        assertTrue(rows.first().startsWith("2026-10-16 "))
        assertTrue(rows.last().startsWith("2026-11-06 "))
    }

    @Test fun transcriptNumbersOnlySentencesAndPreservesIndexMapping() {
        val input = listOf(
            lines[0].copy(speakerTag = "0", text = "第一行\n第二行"),
            lines[1].copy(speakerTag = "0", speakerName = "小王"),
            lines[2].copy(segmentId = 2),
            lines[3].copy(segmentId = 1),
        )
        val (text, indexed) = DigestPrompt.transcript(input, zone)
        assertEquals(
            "[1] 09:00:00 说话人1：第一行 第二行\n" +
                "[2] 09:00:01 小王：第2句\n" +
                "—— 新录音 ——\n[3] 09:00:02 某人：第3句\n" +
                "—— 新录音 ——\n[4] 09:00:03 某人：第4句\n",
            text,
        )
        assertEquals(input, indexed)
    }

    @Test fun speakerUsesNameThenTagThenFallback() {
        assertEquals("说话人1", DigestPrompt.speaker(lines[0].copy(speakerTag = "0")))
        assertEquals("小王", DigestPrompt.speaker(lines[0].copy(speakerTag = "0", speakerName = "小王")))
        assertEquals("某人", DigestPrompt.speaker(lines[0]))
    }

    @Test fun normalJsonMapsIdsAndUsesFirstStartAndMaximumEnd() {
        val input = lines.take(3).mapIndexed { index, line ->
            if (index == 1) line.copy(endAt = base + 20_000) else line
        }
        val (events, items) = DigestPrompt.parse("""
            {"events":[{"from":1,"to":3,"scene":"工作","title":"项目讨论","summary":"确定交付",
              "points":["周三交付"],"people":["说话人1","小王"],"place":"办公室",
              "emotion":"高兴","intensity":5,"important":true}],
             "items":[{"line":2,"category":"待办","kind":"行动","content":"提交方案",
               "due":"2026-10-28 14:00","who":"小王"}]}
        """.trimIndent(), input, zone)
        assertEquals(listOf(DigestPrompt.ParsedEvent(
            lineIds = listOf(107L, 114L, 121L), startAt = base, endAt = base + 20_000,
            scene = "工作", title = "项目讨论", summary = "确定交付", points = listOf("周三交付"),
            people = listOf("小王"), place = "办公室", emotion = "高兴", intensity = 5, important = true,
        )), events)
        assertEquals(listOf(DigestPrompt.ParsedItem(
            eventIndex = 0, category = "待办", kind = "行动", content = "提交方案",
            dueText = "2026-10-28 14:00", dueAt = Instant.parse("2026-10-28T06:00:00Z").toEpochMilli(),
            who = "小王", lineId = 114L,
        )), items)
    }

    @Test fun rejectsWhollyOutOfBoundsAndReversedRanges() {
        val (events, items) = parse("""{"events":[
            {"from":9,"to":10},{"from":-2,"to":0},{"from":4,"to":3}],
            "items":[{"line":1,"category":"待办","content":"无事件"}]}""")
        assertTrue(events.isEmpty())
        assertTrue(items.isEmpty())
    }

    // 按任务卡保留期望行为：当前实现会裁剪并保留，详见 005-测试发现.md。
    /** 部分越界：截到有效范围内保留（宁可留下这件事，也不让这几句没有归属） */
    @Test fun clampsEventWhoseFromIsBelowOne() {
        assertEquals(listOf(107L, 114L), parse("""{"events":[{"from":0,"to":2}]}""").first.single().lineIds)
    }

    @Test fun clampsEventWhoseToExceedsLineCount() {
        assertEquals(listOf(149L, 156L), parse("""{"events":[{"from":7,"to":9}]}""").first.single().lineIds)
    }

    @Test fun sortsRangesTrimsOverlapAndDropsFullyCoveredEvent() {
        val (events, _) = parse("""{"events":[
            {"from":3,"to":5,"title":"后事件"},
            {"from":1,"to":3,"title":"前事件"},
            {"from":2,"to":2,"title":"完全重叠"}]}""")
        assertEquals(listOf("前事件", "后事件"), events.map { it.title })
        assertEquals(listOf(listOf(107L, 114L, 121L), listOf(128L, 135L)), events.map { it.lineIds })
        assertEquals(lines[3].startAt, events[1].startAt)
    }

    @Test fun intensityAcceptsOnlyOneThroughFive() {
        for (intensity in listOf(-1, 0, 1, 3, 5, 6)) {
            val event = parse("""{"events":[{"from":1,"to":1,"intensity":$intensity}]}""").first.single()
            assertEquals("intensity=$intensity", if (intensity in 1..5) intensity else null, event.intensity)
        }
        assertNull(parse("""{"events":[{"from":1,"to":1}]}""").first.single().intensity)
    }

    @Test fun itemsUseContainingOrNearestEventAndNonTodoKindIsNull() {
        val (_, items) = parse("""{"events":[{"from":2,"to":3},{"from":7,"to":7}],"items":[
            {"line":3,"category":"待办","kind":"行动","content":"包含于前事件"},
            {"line":7,"category":"约定","kind":"忽略","content":"包含于后事件"},
            {"line":4,"category":"备忘","content":"靠近前事件"},
            {"line":6,"category":"灵感","content":"靠近后事件"},
            {"line":1,"category":"决定","content":"在所有事件之前"},
            {"line":8,"category":"知识","content":"在所有事件之后"},
            {"line":2,"category":"关键信息","kind":"忽略","content":"  去空格  "}
        ]}""")
        assertEquals(listOf(0, 1, 0, 1, 0, 1, 0), items.map { it.eventIndex })
        assertEquals(listOf(121L, 149L, 128L, 142L, 107L, 156L, 114L), items.map { it.lineId })
        assertEquals("行动", items[0].kind)
        assertTrue(items.drop(1).all { it.kind == null })
        assertEquals("去空格", items.last().content)
    }

    @Test fun dropsUnknownCategoriesAndBlankContent() {
        val (_, items) = parse("""{"events":[{"from":1,"to":8}],"items":[
            {"line":1,"category":"未知","content":"不能保留"},
            {"line":1,"category":"待办","content":""},
            {"line":1,"category":"待办","content":"  \n  "},
            {"line":1,"category":"备忘","content":"有效"}]}""")
        assertEquals(listOf("有效"), items.map { it.content })
    }

    @Test fun keepsBystanderItemsAndDropsMediaItems() {
        val (_, items) = parse("""{"events":[
            {"from":1,"to":2,"scene":"媒体"},{"from":4,"to":5,"scene":"旁人"},
            {"from":8,"to":8,"scene":"工作"}],"items":[
            {"line":1,"category":"待办","content":"媒体内"},
            {"line":4,"category":"待办","content":"旁人内"},
            {"line":6,"category":"待办","content":"最近是旁人"},
            {"line":8,"category":"待办","content":"自己的事"}]}""")
        assertEquals(listOf("旁人内", "最近是旁人", "自己的事"), items.map { it.content })
        assertEquals(listOf(1, 1, 2), items.map { it.eventIndex })
    }

    @Test fun acceptsJsonCodeFence() {
        val json = """{"events":[{"from":1,"to":2}],"items":[]}"""
        assertEquals(parse(json), parse("```json\n$json\n```"))
    }

    @Test fun invalidJsonAndMissingEventsThrowFormat() {
        for (json in listOf("这不是 JSON", "{", """{"items":[]}""")) {
            val error = assertThrows(LlmException::class.java) { parse(json) }
            assertEquals(LlmError.Format, error.type)
        }
    }

    @Test fun parseDueUsesShanghaiTimeAndDateStartsAtMidnight() {
        assertEquals(Instant.parse("2026-10-28T06:00:00Z").toEpochMilli(), DigestPrompt.parseDue("2026-10-28 14:00", zone))
        assertEquals(Instant.parse("2026-10-27T16:00:00Z").toEpochMilli(), DigestPrompt.parseDue("2026-10-28", zone))
        assertNull(DigestPrompt.parseDue("随便乱写", zone))
    }
}
