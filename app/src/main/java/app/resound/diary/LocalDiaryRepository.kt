package app.resound.diary

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import app.resound.data.MemoryDao
import app.resound.digest.ItemStatus
import app.resound.digest.MemoryItem
import app.resound.digest.MemoryRepository
import app.resound.review.ReviewRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** 只读已有回顾、事件与句子，不触发整理或大模型调用。 */
class LocalDiaryRepository(
    private val context: Context,
    private val memory: MemoryRepository,
    private val reviews: ReviewRepository,
    private val dao: MemoryDao,
    private val cards: suspend (LocalDate) -> List<app.resound.skills.SkillCard> = { emptyList() },
    private val lineWindow: ((LocalDate) -> Pair<Long, Long>)? = null,
) : DiaryRepository {
    private val prefs = context.getSharedPreferences("diary", Context.MODE_PRIVATE)
    private val mutableCloudSync = MutableStateFlow(prefs.getBoolean("cloudSync", false))
    override val cloudSync = mutableCloudSync.asStateFlow()

    @Synchronized override fun setCloudSync(enabled: Boolean) {
        check(prefs.edit().putBoolean("cloudSync", enabled).commit())
        mutableCloudSync.value = enabled
    }

    override suspend fun markdown(from: LocalDate, to: LocalDate, includeLines: Boolean): String =
        markdownWithContext(from, to, includeLines, true)

    internal suspend fun markdownWithContext(from: LocalDate, to: LocalDate, includeLines: Boolean, lineContext: Boolean): String = withContext(Dispatchers.IO) {
        require(!to.isBefore(from)) { "结束日期不能早于开始日期" }
        val zone = ZoneId.systemDefault()
        val days = mutableListOf<String>()
        var date = from
        while (!date.isAfter(to)) {
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
            cardsMarkdown(cards(date), zone).takeIf { it.isNotBlank() }?.let { sections += it }
            val events = memory.events(date).first().filter { it.scene != "媒体" || it.starred }.sortedBy { it.startAt }
            if (events.isNotEmpty()) sections += "## 时间线\n" + events.joinToString("\n\n") { event ->
                buildList {
                    add("### ${app.resound.OutputTime(zone).event(event.startAt, event.endAt)} ${if (event.starred) "★ " else ""}${event.title}（${event.scene}）")
                    if (event.summary.isNotBlank()) add(event.summary)
                    app.resound.envsound.EnvSoundPresentation.line(event.id).takeIf { it.isNotBlank() }?.let { add(it) }
                    addAll(event.points.filter { it.isNotBlank() }.map { "- $it" })
                    val detail = buildList {
                        if (event.people.isNotEmpty()) add("人物：${event.people.joinToString("、")}")
                        if (!event.place.isNullOrBlank()) add("地点：${event.place}")
                    }.joinToString(" · ")
                    if (detail.isNotBlank()) add(detail)
                }.joinToString("\n")
            }
            val items = memory.dayItems(date).first()
            val tasks = listOf("当天提到" to items.mentioned, "当天到期" to items.due, "当天完成" to items.done)
                .filter { it.second.isNotEmpty() }.joinToString("\n") { (title, rows) ->
                    "**$title**\n" + rows.joinToString("\n") { task(it, zone) }
                }
            if (tasks.isNotEmpty()) sections += "## 待办\n$tasks"
            for (category in listOf("灵感", "关键信息", "决定", "知识")) {
                val notes = items.notes.filter { it.category == category }
                if (notes.isNotEmpty()) sections += "## $category\n" + notes.joinToString("\n") { "- ${it.content}" }
            }
            if (includeLines) {
                val (start, end) = lineWindow?.invoke(date) ?: (date.atStartOfDay(zone).toInstant().toEpochMilli() to
                    date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli())
                val lines = dao.diaryLines(start, end).filter { it.text.isNotBlank() }
                if (lines.isNotEmpty()) sections += "## 原话\n" + lines.joinToString("\n") {
                    "**${time(it.startAt, zone)} ${it.speakerName?.takeIf(String::isNotBlank) ?: "某人"}**：${it.text}${app.resound.transcribe.VoiceInputMetadata.markdown(it.voiceInputJson, lineContext)}"
                }
            }
            if (sections.isNotEmpty()) days += "# ${date.format(DAY)}\n\n${sections.joinToString("\n\n")}"
            date = date.plusDays(1)
        }
        days.joinToString("\n\n")
    }

    override suspend fun exportFile(from: LocalDate, to: LocalDate, includeLines: Boolean): Uri = withContext(Dispatchers.IO) {
        val content = markdown(from, to, includeLines)
        val directory = File(context.cacheDir, "diary").apply { check(isDirectory || mkdirs()) }
        val range = if (from == to) "$from" else "${from}至${to}"
        val file = File(directory, "resound-diary_$range.md")
        file.writeText(content, Charsets.UTF_8)
        FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    }

    private fun task(item: MemoryItem, zone: ZoneId): String {
        val details = buildList {
            val due = item.dueAt?.let { time(it, zone) }
                ?: item.dueText?.takeIf { it.isNotBlank() }
            if (due != null) add("截止 $due")
            if (!item.who.isNullOrBlank()) add("对方：${item.who}")
        }
        return "- [${if (item.status == ItemStatus.Done) "x" else " "}] ${item.content}" +
            if (details.isEmpty()) "" else "（${details.joinToString(" · ")}）"
    }

    private fun time(at: Long, zone: ZoneId) = app.resound.OutputTime(zone).moment(at)

    companion object {
        private val DAY = DateTimeFormatter.ofPattern("M月d日 EEEE", Locale.CHINA)
    }
}

/** 按生成时保存的字段类型渲染，技能改名或删字段不影响旧卡导出。 */
internal fun cardsMarkdown(cards: List<app.resound.skills.SkillCard>, zone: ZoneId = ZoneId.systemDefault()): String {
    if (cards.isEmpty()) return ""
    fun time(at: Long) = app.resound.OutputTime(zone).moment(at)
    return "## 卡片\n" + cards.sortedBy { it.at }.joinToString("\n\n") { card ->
        buildList {
            add("### ${card.skillName} · ${card.endAt?.let { app.resound.OutputTime(zone).event(card.at, it) } ?: time(card.at)} · ${card.title}")
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
