package app.resound.push

import app.resound.skills.card

import androidx.room.*
import app.resound.data.*
import app.resound.skills.SkillCardRow
import java.time.*
import kotlinx.datetime.toKotlinLocalDate
import kotlinx.datetime.toJavaLocalDate

internal data class PushSnapshot(
    val events: List<MemoryEventEntity> = emptyList(), val items: List<MemoryItemEntity> = emptyList(),
    val cards: List<SkillCardRow> = emptyList(), val reviews: List<ReviewEntity> = emptyList(),
    val lines: List<TranscriptLineEntity> = emptyList(), val repeats: Map<Long, Int> = emptyMap(),
    val startDate: LocalDate? = null,
    val lineOrigins: Map<Long, Long?> = emptyMap(),
) {
    fun select(s: PushSubscription, since: LocalDate?, today: LocalDate, zone: ZoneId): PushSnapshot {
        val lower = PushRecordRules.lower(s, since?.toKotlinLocalDate(), today.toKotlinLocalDate())?.toJavaLocalDate()
        fun included(at: Long) = lower == null || day(at, zone) >= lower
        val allowed = events.filter { PushRecordRules.event(it.deleted, it.scene, s.excludeMedia) }
        val origins = if (lineOrigins.isNotEmpty()) lineOrigins else lines.associate { it.id to it.eventId }
        val ids = allowed.map { it.id }.toSet()
        val visible = allowed.filter { included(it.startAt) }.filter { e -> localRecord("event", e.id) {
            org.json.JSONArray(e.pointsJson).strings(); org.json.JSONArray(e.peopleJson).strings(); true
        } == true }
        val starredIds = visible.filter { it.starred && RecordKind.Starred in s.records }.map { it.id }.toSet()
        val selectedItems = items.filter { !it.deleted && (it.eventId == null || it.eventId in ids) && it.mergedInto == null && it.status != "Ignored" && included(it.createdAt) }.filter {
            PushRecordRules.item(s, it.category, it.status, it.eventId in starredIds,
                it.doneAt?.let { at -> day(at, zone).toKotlinLocalDate() }, today.toKotlinLocalDate())
        }
        // 保留可见事件作来源/场景元数据，渲染与 delta 时再按 Timeline/Starred 选择。
        return PushSnapshot(visible, selectedItems,
            if (s.summaries) cards.filter { c ->
                c.deletedAt == null && included(c.at) && localRecord("card", c.id) {
                    org.json.JSONArray(c.evidenceJson).strings().mapNotNull(String::toLongOrNull).all { id -> origins.containsKey(id) && (origins[id] == null || origins[id] in ids) }.also { if (it) c.card() }
                } == true
            } else emptyList(),
            if (s.summaries) reviews.filter { r -> localRecord("review", r.id) {
                (lower == null || LocalDate.parse(r.endDate) >= lower).also { if (it) { LocalDate.parse(r.startDate); org.json.JSONArray(r.sectionsJson).objects() } }
            } == true } else emptyList(),
            if (s.lines == LinesMode.Off) emptyList() else lines.filter {
                included(it.startAt) && (it.eventId == null || it.eventId in ids) &&
                    PushRecordRules.line(s.lines, it.speakerName, it.text)
            }, repeats, lower, origins)
    }
}
internal fun day(at: Long, zone: ZoneId) = Instant.ofEpochMilli(at).atZone(zone).toLocalDate()

internal suspend fun PushDao.snapshot(start: Long, withLines: Boolean): PushSnapshot = transaction {
        val rows = items()
        val repeats = rows.groupingBy { it.mergedInto ?: it.id }.eachCount()
        PushSnapshot(events(), rows, cards(), reviews(), if (withLines) lines(start) else emptyList(), repeats, lineOrigins = lineOrigins().associate { it.id to it.eventId })
    }

/** 非订阅调用也逐条验证；重复生成文档时由 trace 的记录键去重计数。 */
internal fun PushSnapshot.validated(): PushSnapshot = copy(
    events = events.filter { e -> localRecord("event", e.id) { org.json.JSONArray(e.pointsJson).strings(); org.json.JSONArray(e.peopleJson).strings(); true } == true },
    cards = cards.filter { c -> localRecord("card", c.id) { c.card(); true } == true },
    reviews = reviews.filter { r -> localRecord("review", r.id) { LocalDate.parse(r.startDate); LocalDate.parse(r.endDate); org.json.JSONArray(r.sectionsJson).objects(); true } == true },
)
