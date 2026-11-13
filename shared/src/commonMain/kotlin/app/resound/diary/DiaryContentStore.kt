@file:OptIn(kotlin.time.ExperimentalTime::class)
package app.resound.diary

import app.resound.data.MemoryDao
import app.resound.digest.*
import app.resound.review.ReviewRepository
import app.resound.platform.ZoneProvider
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.datetime.*

/** 日记查询和内容组装；Uri/实际文件输出仍由安卓原入口负责。 */
class DiaryContentStore(
    private val memory: MemoryRepository, private val reviews: ReviewRepository,
    private val dao: MemoryDao, private val cards: suspend (LocalDate) -> List<app.resound.skills.SkillCard>,
    private val lineWindow: ((LocalDate) -> Pair<Long, Long>)?,
    private val zoneProvider: ZoneProvider, private val io: CoroutineDispatcher,
    initialCloudSync: Boolean, private val saveCloudSync: (Boolean) -> Boolean,
    private val formatting: (String) -> DiaryTextTime,
    private val environmentLine: suspend (Long) -> String,
    private val voiceMarkdown: (String?, Boolean) -> String,
) {
    private val mutableCloudSync = MutableStateFlow(initialCloudSync)
    val cloudSync = mutableCloudSync.asStateFlow()

    fun setCloudSync(enabled: Boolean) {
        check(saveCloudSync(enabled))
        mutableCloudSync.value = enabled
    }

    suspend fun markdown(from: LocalDate, to: LocalDate, includeLines: Boolean): String =
        markdownWithContext(from, to, includeLines, true)

    suspend fun markdownWithContext(from: LocalDate, to: LocalDate, includeLines: Boolean, lineContext: Boolean): String = withContext(io) {
        require(to >= from) { "结束日期不能早于开始日期" }
        val zone = TimeZone.of(zoneProvider.zoneId())
        val format = formatting(zone.id)
        val days = mutableListOf<String>()
        var date = from
        while (date <= to) {
            val sections = mutableListOf<String>()
            reviews.daily(date).first()?.let { review ->
                val content = review.sections.mapNotNull { section ->
                    val lines = section.lines.filter { it.isNotBlank() }
                    if (lines.isEmpty()) null
                    else if (section.title == "一句话") lines.joinToString("\n")
                    else "**${section.title}**\n" + lines.joinToString("\n") { "- $it" }
                }.joinToString("\n").ifBlank { review.brief }
                if (content.isNotBlank()) sections += "## 回顾\n$content"
            }
            diaryCardsMarkdown(cards(date), format).takeIf { it.isNotBlank() }?.let { sections += it }
            val events = memory.events(date).first().filter { it.scene != "媒体" || it.starred }.sortedBy { it.startAt }
            if (events.isNotEmpty()) sections += "## 时间线\n" + events.map { event ->
                buildList {
                    add("### ${format.event(event.startAt, event.endAt)} ${if (event.starred) "★ " else ""}${event.title}（${event.scene}）")
                    if (event.summary.isNotBlank()) add(event.summary)
                    environmentLine(event.id).takeIf { it.isNotBlank() }?.let { add(it) }
                    addAll(event.points.filter { it.isNotBlank() }.map { "- $it" })
                    val detail = buildList {
                        if (event.people.isNotEmpty()) add("人物：${event.people.joinToString("、")}")
                        if (!event.place.isNullOrBlank()) add("地点：${event.place}")
                    }.joinToString(" · ")
                    if (detail.isNotBlank()) add(detail)
                }.joinToString("\n")
            }.joinToString("\n\n")
            val items = memory.dayItems(date).first()
            val tasks = listOf("当天提到" to items.mentioned, "当天到期" to items.due, "当天完成" to items.done)
                .filter { it.second.isNotEmpty() }.joinToString("\n") { (title, rows) ->
                    "**$title**\n" + rows.joinToString("\n") { task(it, format) }
                }
            if (tasks.isNotEmpty()) sections += "## 待办\n$tasks"
            for (category in listOf("灵感", "关键信息", "决定", "知识")) {
                val notes = items.notes.filter { it.category == category }
                if (notes.isNotEmpty()) sections += "## $category\n" + notes.joinToString("\n") { "- ${it.content}" }
            }
            if (includeLines) {
                val (start, end) = lineWindow?.invoke(date) ?: (date.atStartOfDayIn(zone).toEpochMilliseconds() to
                    date.plus(1, DateTimeUnit.DAY).atStartOfDayIn(zone).toEpochMilliseconds())
                val lines = dao.diaryLines(start, end).filter { it.text.isNotBlank() }
                if (lines.isNotEmpty()) sections += "## 原话\n" + lines.joinToString("\n") {
                    "**${format.moment(it.startAt)} ${it.speakerName?.takeIf(String::isNotBlank) ?: "某人"}**：${it.text}${voiceMarkdown(it.voiceInputJson, lineContext)}"
                }
            }
            if (sections.isNotEmpty()) days += "# ${format.day(date)}\n\n${sections.joinToString("\n\n")}"
            date = date.plus(1, DateTimeUnit.DAY)
        }
        days.joinToString("\n\n")
    }

    private fun task(item: MemoryItem, format: DiaryTextTime): String {
        val details = buildList {
            val due = item.dueAt?.let { format.moment(it) }
                ?: item.dueText?.takeIf { it.isNotBlank() }
            if (due != null) add("截止 $due")
            if (!item.who.isNullOrBlank()) add("对方：${item.who}")
        }
        return "- [${if (item.status == ItemStatus.Done) "x" else " "}] ${item.content}" +
            if (details.isEmpty()) "" else "（${details.joinToString(" · ")}）"
    }

}

interface DiaryTextTime {
    fun day(date: LocalDate): String
    fun moment(at: Long): String
    fun event(start: Long, end: Long): String
}

/** 按生成时保存的字段类型渲染，技能改名或删字段不影响旧卡导出。 */
fun diaryCardsMarkdown(cards: List<app.resound.skills.SkillCard>, format: DiaryTextTime): String {
    if (cards.isEmpty()) return ""
    fun time(at: Long) = format.moment(at)
    return "## 卡片\n" + cards.sortedBy { it.at }.joinToString("\n\n") { card ->
        buildList {
            add("### ${card.skillName} · ${card.endAt?.let { format.event(card.at, it) } ?: time(card.at)} · ${card.title}")
            if (card.summary.isNotBlank()) add(card.summary)
            card.fields.forEach { field ->
                val value = card.values[field.key] ?: return@forEach
                val text = when (field.type) {
                    app.resound.skills.FieldType.Text, app.resound.skills.FieldType.Paragraph -> value.text.orEmpty()
                    app.resound.skills.FieldType.Bullets -> value.items.joinToString("\n") { "- $it" }
                    app.resound.skills.FieldType.Checklist -> value.items.mapIndexed { i, item -> "- [${if (value.checked.getOrNull(i) == true) "x" else " "}] $item" }.joinToString("\n")
                    app.resound.skills.FieldType.Quotes -> value.quotes.joinToString("\n") { q ->
                        ("${q.speaker} · ${time(q.at)}：${q.text}").lines().joinToString("\n") { "> $it" }
                    }
                    app.resound.skills.FieldType.Number -> value.number?.let { "$it ${value.unit.orEmpty()}" }.orEmpty()
                    app.resound.skills.FieldType.Tags -> value.items.joinToString("、")
                    app.resound.skills.FieldType.Rating -> value.rating?.let { "$it / 5" }.orEmpty()
                }
                if (text.isNotBlank()) add("**${field.label}**\n$text")
            }
        }.joinToString("\n\n")
    }
}
