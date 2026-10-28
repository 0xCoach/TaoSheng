package app.resound.push

import androidx.room.*
import app.resound.data.*
import app.resound.skills.SkillCardRow
import java.time.*

internal data class PushSnapshot(
    val events: List<MemoryEventEntity> = emptyList(), val items: List<MemoryItemEntity> = emptyList(),
    val cards: List<SkillCardRow> = emptyList(), val reviews: List<ReviewEntity> = emptyList(),
    val lines: List<TranscriptLineEntity> = emptyList(), val repeats: Map<Long, Int> = emptyMap(),
    val startDate: LocalDate? = null,
    val lineOrigins: Map<Long, Long?> = emptyMap(),
) {
    fun select(s: PushSubscription, since: LocalDate?, today: LocalDate, zone: ZoneId): PushSnapshot {
        val lower = listOfNotNull(since, s.rangeDays?.let { today.minusDays(it.toLong() - 1) }).maxOrNull()
        fun included(at: Long) = lower == null || day(at, zone) >= lower
        val allowed = events.filter { !it.deleted && (!s.excludeMedia || it.scene !in setOf("媒体", "旁人")) }
        val origins = if (lineOrigins.isNotEmpty()) lineOrigins else lines.associate { it.id to it.eventId }
        val ids = allowed.map { it.id }.toSet()
        val visible = allowed.filter { included(it.startAt) }
        val starredIds = visible.filter { it.starred && RecordKind.Starred in s.records }.map { it.id }.toSet()
        val selectedItems = items.filter { !it.deleted && (it.eventId == null || it.eventId in ids) && it.mergedInto == null && it.status != "Ignored" && included(it.createdAt) }.filter {
            it.eventId in starredIds || when (it.category) {
                "待办", "约定", "备忘" -> RecordKind.Todos in s.records && (it.status != "Done" || it.doneAt?.let { at -> day(at, zone) >= today.minusDays(29) } == true)
                "灵感" -> RecordKind.Ideas in s.records
                else -> RecordKind.Decisions in s.records
            }
        }
        // 保留可见事件作来源/场景元数据，渲染与 delta 时再按 Timeline/Starred 选择。
        return PushSnapshot(visible, selectedItems,
            if (s.summaries) cards.filter { c ->
                c.deletedAt == null && included(c.at) && org.json.JSONArray(c.evidenceJson).strings().mapNotNull(String::toLongOrNull).all { id -> origins.containsKey(id) && (origins[id] == null || origins[id] in ids) }
            } else emptyList(),
            if (s.summaries) reviews.filter { lower == null || LocalDate.parse(it.endDate) >= lower } else emptyList(),
            if (s.lines == LinesMode.Off) emptyList() else lines.filter {
                included(it.startAt) && (it.eventId == null || it.eventId in ids) &&
                    (s.lines != LinesMode.MeOnly || it.speakerName == "我") && it.text.isNotBlank()
            }, repeats, lower, origins)
    }
}
internal fun day(at: Long, zone: ZoneId) = Instant.ofEpochMilli(at).atZone(zone).toLocalDate()

internal data class PushLineOrigin(val id: Long, val eventId: Long?)

@Dao
internal abstract class PushDao {
    @Query("SELECT id, eventId FROM transcript_lines") abstract suspend fun lineOrigins(): List<PushLineOrigin>
    @Query("SELECT * FROM memory_events ORDER BY startAt, id") abstract suspend fun events(): List<MemoryEventEntity>
    @Query("SELECT i.* FROM memory_items i LEFT JOIN memory_events e ON e.id = i.eventId WHERE (e.id IS NULL OR e.deleted = 0) AND i.deleted = 0 ORDER BY i.createdAt, i.id") abstract suspend fun items(): List<MemoryItemEntity>
    @Query("SELECT * FROM skill_cards WHERE deletedAt IS NULL ORDER BY at, id") abstract suspend fun cards(): List<SkillCardRow>
    @Query("SELECT * FROM reviews ORDER BY startDate, id") abstract suspend fun reviews(): List<ReviewEntity>
    @Query("SELECT l.* FROM transcript_lines l JOIN segments s ON s.id = l.segmentId WHERE s.status = 'Done' AND l.startAt >= :start ORDER BY l.startAt, l.id") abstract suspend fun lines(start: Long): List<TranscriptLineEntity>
    @Transaction open suspend fun snapshot(start: Long, withLines: Boolean): PushSnapshot {
        val rows = items()
        val repeats = rows.groupingBy { it.mergedInto ?: it.id }.eachCount()
        return PushSnapshot(events(), rows, cards(), reviews(), if (withLines) lines(start) else emptyList(), repeats, lineOrigins = lineOrigins().associate { it.id to it.eventId })
    }
}
