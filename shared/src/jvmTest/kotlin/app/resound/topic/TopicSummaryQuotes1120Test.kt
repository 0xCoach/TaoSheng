package app.resound.topic

import app.resound.digest.core.TranscriptLineData
import app.resound.skills.*
import app.resound.skills.core.*
import app.resound.topic.core.TopicGroup
import app.resound.topic.core.TopicRow
import app.resound.topic.core.TopicSpanRow
import app.resound.topic.core.*
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.TimeZone
import org.junit.Assert.*
import org.junit.Test

/** 11-20 真机：培训专题 13 块都整理完，汇总时模板里必填的“原话”引用拿不到编号而丢卡，single() 抛 NoSuchElementException，显示“本地处理未完成”。 */
class TopicSummaryQuotes1120Test {
    private val minute = 60_000L
    private val skill = Skill("training", "培训笔记", "", "bulb", SkillOrigin.Builtin, 1, true, SkillTrigger.Manual, SkillScope(),
        listOf(SkillField("summary", "概括", FieldType.Text, "", required = true), SkillField("quotes", "原话", FieldType.Quotes, "", required = true)),
        1, false, null, false)
    private fun card(quotes: String) = """{"cards":[{"title":"培训笔记","summary":"要点","values":{"summary":"讲了汇报要求","quotes":$quotes}}]}"""

    @Test fun summaryKeepsCardWhenQuotesCannotBeResolved() = runBlocking {
        val g = TopicGroup(TopicRow(id = 1, kind = "Training", name = "培训", status = "Ended", createdAt = 0),
            listOf(TopicSpanRow(topicId = 1, startAt = 0, endAt = 20 * minute)), emptyList())
        val lines = (0..1).map { b -> TranscriptLineData(id = b + 1L, segmentId = b + 1L, startAt = b * 10 * minute, endAt = b * 10 * minute + 1000, text = "第${b}块内容", speakerTag = "0") }
        val generator = TopicBlockGenerator({ "提取 events items" }, { skill to "整理培训笔记" }, { _, u ->
            when {
                u.contains("按时间合成") -> card("[]")
                u.contains("重点资料 L 编号") -> """{"events":[],"items":[]}"""
                else -> card("[\"L1\",\"L2\"]")
            }
        }, { emptyList() }, { 30 * minute }, { TimeZone.UTC }, "涛声")
        val processor = TopicBlockProcessor(1, MemoryTopicCheckpoints(), generator, { it },
            { TopicFailure(TopicFailureKind.Temporary, it.javaClass.simpleName) }, {}, { 30 * minute }, { (it / minute).toString() })
        val run = processor.run(g, lines, emptyList(), { false }, true, { "" })
        val summary = requireNotNull(run.summary)
        assertEquals("讲了汇报要求", SkillJson.values(app.resound.net.Obj(summary.card!!.valuesJson)).getValue("summary").text)
    }

    @Test fun droppedCardIsFormatErrorNotLocalFailure() {
        val e = runCatching { app.resound.skills.core.SkillOutput.parse("""{"cards":[{"title":"","values":{}}]}""", skill,
            kotlinx.datetime.LocalDate(2026, 11, 20), SkillInput("", emptyMap()), 0, true).rows.singleOrNull() ?: throw app.resound.skills.core.SkillFormatException() }
            .exceptionOrNull()
        assertTrue(e is IllegalArgumentException)
    }
}
