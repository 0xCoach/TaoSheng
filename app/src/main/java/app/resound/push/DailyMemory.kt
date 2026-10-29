package app.resound.push

import android.content.Context
import app.resound.data.*
import app.resound.diary.LocalDiaryRepository
import app.resound.digest.*
import app.resound.review.*
import app.resound.skills.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** 对外 JSON 与内部数据读取分离，字段名和空值在此集中定义。时间均为 Unix 毫秒。 */
internal fun dailyMemoryJson(date: LocalDate, generatedAt: Long, markdown: String, review: DailyReview?, input: ReviewInput,
    cards: List<SkillCard>, lines: List<TranscriptLineEntity>?, lineContext: Boolean = true): JSONObject = JSONObject()
    .put("type", "daily_memory").put("version", 1).put("app", "resound").put("date", date.toString())
    .put("generatedAt", generatedAt).put("markdown", markdown).put("data", JSONObject()
        .put("review", review?.let { JSONObject().put("brief", it.brief).put("sections", it.pushJson().getJSONArray("sections")) } ?: JSONObject.NULL)
        .put("events", JSONArray(input.events.mapNotNull { e -> localRecord("event", e.id) { JSONObject().put("id", e.id).put("start", e.startAt).put("end", e.endAt)
            .put("title", e.title).put("summary", e.summary).put("scene", e.scene).put("people", JSONArray(e.peopleJson)).put("important", e.important).put("starred", e.starred) } }))
        .put("items", JSONArray(input.items.filter { it.status != "Ignored" }.map { i -> JSONObject().put("id", i.id).put("category", i.category)
            .put("kind", i.kind ?: JSONObject.NULL).put("content", i.content).put("due", i.dueAt ?: i.dueText ?: JSONObject.NULL)
            .put("who", i.who ?: JSONObject.NULL).put("status", i.status) }))
        .put("cards", JSONArray(cards.map { c -> JSONObject().put("skill", c.skillName).put("title", c.title).put("summary", c.summary)
            .put("at", c.at).put("values", SkillJson.values(c.values)) }))
        .apply { if (lines != null) put("lines", JSONArray(lines.map { l -> JSONObject().put("id", l.id).put("at", l.startAt)
            .put("speaker", l.speakerName ?: l.speakerTag ?: "某人").put("text", l.text).apply { app.resound.transcribe.VoiceInputMetadata.read(l.voiceInputJson)?.let { put("voice_input", app.resound.transcribe.VoiceInputMetadata.json(it, lineContext)) } } })) })

internal class DailyMemorySource(context: Context, memory: MemoryRepository, private val reviews: ReviewRepository,
    private val dao: MemoryDao, private val reviewDao: ReviewDao, private val skills: SkillDao) {
    private fun window(date: LocalDate): Pair<Long, Long> {
        val zone = ZoneId.systemDefault()
        val minutes = reviews.state.value.dailyAtMinutes
        return ReviewTime.cutoff(date.minusDays(1), minutes, zone).toInstant().toEpochMilli() to ReviewTime.cutoff(date, minutes, zone).toInstant().toEpochMilli()
    }
    private suspend fun input(date: LocalDate): ReviewInput = window(date).let { (start, end) -> reviewDao.input(start, end) }.let { source ->
        source.copy(events = source.events.filter { e -> localRecord("event", e.id) { strings(e.pointsJson); strings(e.peopleJson); true } == true })
    }
    private suspend fun cards(date: LocalDate) = skills.cards(date.toEpochDay()).first().mapNotNull { row -> localRecord("card", row.id) { row.card() } }
    // 复用 DiaryRepository.markdown，而取数窗口与回顾严格一致：(前一日截止，当日截止]。
    private val windowMemory = object : MemoryRepository by memory {
        override fun events(date: LocalDate) = flow {
            val source = input(date)
            emit(source.events.map { e -> MemoryEvent(e.id, e.startAt, e.endAt, e.scene, e.title, e.summary,
                strings(e.pointsJson), strings(e.peopleJson), e.place, e.emotion, e.intensity, e.important, emptyList(), e.starred, e.starredAt) })
        }
        override fun dayItems(date: LocalDate) = flow {
            val rows = input(date).items.filter { it.status != "Ignored" }.map { i -> MemoryItem(i.id, i.eventId, i.category, i.kind, i.content,
                i.dueText, i.dueAt, i.who, ItemStatus.valueOf(i.status), i.lineId, i.createdAt, i.doneAt, origin = app.resound.digest.ItemOrigin.entries.firstOrNull { it.name.equals(i.origin, true) } ?: app.resound.digest.ItemOrigin.Digest, sourceText = i.sourceText) }
            emit(DayItems(rows.filter { it.category in setOf("待办", "约定", "备忘") }, emptyList(), emptyList(),
                rows.filter { it.category !in setOf("待办", "约定", "备忘") }))
        }
    }
    private val safeReviews = object : ReviewRepository by reviews {
        override fun daily(date: LocalDate) = flow { emit(localRecord("review", date.toEpochDay()) { reviews.daily(date).first() }) }
    }
    private val diary = LocalDiaryRepository(context, windowMemory, safeReviews, dao, ::cards) { date ->
        window(date).let { (start, end) -> (start + 1) to (end + 1) }
    }
    suspend fun load(date: LocalDate?, withLines: Boolean, lineContext: Boolean = true): PushMessage? {
        val target = date ?: latestDate(withLines) ?: return null
        val source = input(target)
        val review = localRecord("review", target.toEpochDay()) { reviews.daily(target).first() }
        val cards = cards(target)
        val (start, end) = window(target)
        val lines = if (withLines) dao.diaryLines(start + 1, end + 1).filter { it.text.isNotBlank() } else null
        PushTrace.current()?.requireContent(source.events.size + source.items.size + cards.size + (if (review == null) 0 else 1) + lines.orEmpty().size)
        if (source.events.isEmpty() && source.items.isEmpty() && cards.isEmpty() && review == null && lines.isNullOrEmpty()) return null
        val markdown = diary.markdownWithContext(target, target, withLines, lineContext)
        if (markdown.isBlank()) return null
        val at = System.currentTimeMillis()
        val json = dailyMemoryJson(target, at, markdown, review, source, cards, lines, lineContext)
        return PushMessage("每日记忆文档 · $target", review?.pushBody(PushFormat.Text) ?: "$target 的记忆文档", PushTopic.DailyMemory,
            "resound://review?open=review&date=$target", at, memory = json)
    }
    private suspend fun latestDate(withLines: Boolean): LocalDate? {
        val zone = ZoneId.systemDefault()
        val latest = dao.latestDiaryAt(withLines)?.let { at ->
            val time = Instant.ofEpochMilli(at).atZone(zone)
            val date = time.toLocalDate()
            if (time.isAfter(ReviewTime.cutoff(date, reviews.state.value.dailyAtMinutes, zone))) date.plusDays(1) else date
        }
        return listOfNotNull(latest, skills.latestCardDate()?.let(LocalDate::ofEpochDay), reviewDao.recent().first().mapNotNull { row -> localRecord("review", row.id) { LocalDate.parse(row.startDate) } }.maxOrNull()).maxOrNull()
    }
    private fun strings(text: String): List<String> = JSONArray(text).let { a -> List(a.length()) { a.getString(it) } }
}
