package app.resound.skills

import androidx.room.*
import app.resound.data.MemoryEventEntity
import app.resound.data.MemoryItemEntity
import app.resound.data.ReviewEntity
import app.resound.data.TranscriptLineEntity
import kotlinx.coroutines.flow.Flow
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

@Entity(tableName = "skill_cards", indices = [Index("date"), Index(value = ["skillId", "date"])])
data class SkillCardRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val skillId: String, val skillVersion: Int, val skillName: String, val icon: String,
    val date: Long, val entity: String, val at: Long, val endAt: Long?, val title: String, val summary: String,
    val fieldsJson: String, val valuesJson: String, val evidenceJson: String,
    val generatedAt: Long, val manual: Boolean, val deletedAt: Long? = null,
    /** 合并后的旧 entity 也要保留，避免换 entity 后软删除失效。 */
    val aliasesJson: String = "[]",
) {
    fun card() = SkillCard(id, skillId, skillName, icon, LocalDate.ofEpochDay(date), at, endAt, title, summary,
        SkillJson.fields(JSONArray(fieldsJson)), SkillJson.values(JSONObject(valuesJson)),
        JSONArray(evidenceJson).elements().map { (it as Number).toLong() }, generatedAt, manual)
    fun entities() = JSONArray(aliasesJson).elements().map { it.toString() }.toSet() + entity
}

@Entity(tableName = "skill_runs", indices = [Index(value = ["skillId", "date"])])
data class SkillRunRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val skillId: String, val date: Long, val at: Long, val ok: Boolean, val result: String, val tokens: Long,
    val kind: String, val retryable: Boolean = false,
    val purpose: String = "skill:$skillId",
)

sealed interface SkillMergeResult {
    data class Saved(val row: SkillCardRow, val isNew: Boolean) : SkillMergeResult
    data object Deleted : SkillMergeResult
    data class Full(val count: Int) : SkillMergeResult
}

@Dao
abstract class SkillDao {
    @Query("SELECT MAX(date) FROM skill_cards WHERE deletedAt IS NULL")
    abstract suspend fun latestCardDate(): Long?

    @Query("SELECT * FROM skill_cards WHERE date = :date AND deletedAt IS NULL ORDER BY at, id")
    abstract fun cards(date: Long): Flow<List<SkillCardRow>>
    @Query("SELECT * FROM skill_cards WHERE skillId = :id AND deletedAt IS NULL ORDER BY date DESC, at DESC LIMIT 200")
    abstract fun cardsOf(id: String): Flow<List<SkillCardRow>>
    @Query("SELECT * FROM skill_cards WHERE id = :id AND deletedAt IS NULL") abstract fun card(id: Long): Flow<SkillCardRow?>
    @Query("SELECT skillId FROM skill_cards WHERE deletedAt IS NULL GROUP BY skillId ORDER BY MAX(generatedAt) DESC")
    abstract fun skillsWithCards(): Flow<List<String>>
    @Query("SELECT * FROM skill_cards WHERE skillId = :id AND date = :date ORDER BY at, id")
    abstract suspend fun dayRows(id: String, date: Long): List<SkillCardRow>
    @Insert(onConflict = OnConflictStrategy.REPLACE) abstract suspend fun save(row: SkillCardRow): Long
    @Query("UPDATE skill_cards SET deletedAt = :at WHERE id = :id") abstract suspend fun delete(id: Long, at: Long)
    @Query("DELETE FROM skill_cards WHERE id IN (:ids)") protected abstract suspend fun removeMerged(ids: List<Long>)
    @Insert abstract suspend fun run(row: SkillRunRow): Long
    @Query("SELECT * FROM skill_runs ORDER BY at, id") abstract fun runs(): Flow<List<SkillRunRow>>
    @Query("SELECT * FROM skill_runs WHERE skillId = :id ORDER BY at, id") abstract suspend fun runsOf(id: String): List<SkillRunRow>
    // 保留完整 30 天用量，同时至少保留最近 500 行。
    @Query("DELETE FROM skill_runs WHERE at < :before AND id NOT IN (SELECT id FROM skill_runs ORDER BY id DESC LIMIT 500)")
    abstract suspend fun trimRuns(before: Long)

    @Query("SELECT * FROM memory_events WHERE deleted = 0 AND startAt >= :start AND startAt < :end AND createdAt > :since AND createdAt <= :until ORDER BY startAt, id")
    abstract suspend fun events(start: Long, end: Long, since: Long, until: Long): List<MemoryEventEntity>
    @Query("SELECT DISTINCT startAt FROM memory_events WHERE deleted = 0 AND createdAt > :since AND createdAt <= :until")
    abstract suspend fun newEventStarts(since: Long, until: Long): List<Long>
    @Query("""SELECT * FROM memory_events WHERE deleted = 0 AND starred = 1
        AND starredAt >= :start AND starredAt < :end AND starredAt <= :until ORDER BY starredAt, id""")
    abstract suspend fun starredEvents(start: Long, end: Long, until: Long): List<MemoryEventEntity>
    @Query("SELECT * FROM memory_items WHERE deleted = 0 AND mergedInto IS NULL AND eventId IN (:events) AND status != 'Ignored' ORDER BY createdAt, id")
    abstract suspend fun items(events: List<Long>): List<MemoryItemEntity>
    @Query("SELECT l.* FROM transcript_lines l JOIN segments s ON s.id = l.segmentId LEFT JOIN memory_events e ON e.id = l.eventId WHERE s.status = 'Done' AND l.startAt >= :start AND l.startAt < :end AND (e.id IS NULL OR e.deleted = 0) ORDER BY l.startAt, l.id")
    abstract suspend fun lines(start: Long, end: Long): List<TranscriptLineEntity>
    @Query("SELECT * FROM transcript_lines WHERE eventId IN (:events) ORDER BY startAt, id")
    abstract suspend fun eventLines(events: List<Long>): List<TranscriptLineEntity>
    @Query("SELECT * FROM reviews WHERE type = 'daily' AND startDate >= :start AND endDate <= :end ORDER BY startDate")
    abstract suspend fun reviews(start: String, end: String): List<ReviewEntity>

    open suspend fun merge(incoming: SkillCardRow, max: Int, manual: Boolean = false): Pair<SkillCardRow, Boolean>? =
        (mergeWithReason(incoming, max, manual) as? SkillMergeResult.Saved)?.let { it.row to it.isNew }

    /** 删除判断、手动恢复、合并和日上限在同一事务里完成。 */
    @Transaction
    open suspend fun mergeWithReason(incoming: SkillCardRow, max: Int, manual: Boolean = false): SkillMergeResult {
        val existing = dayRows(incoming.skillId, incoming.date)
        if (!manual && existing.any { it.deletedAt != null && incoming.entity in it.entities() }) return SkillMergeResult.Deleted
        var matches = existing.filter { (manual || it.deletedAt == null) && incoming.entity in it.entities() }
        var start = minOf(incoming.at, matches.minOfOrNull { it.at } ?: incoming.at)
        var end = (matches.mapNotNull { it.endAt } + listOfNotNull(incoming.endAt)).maxOrNull()
        // 闭包处理：新时段可能连上两张此前分开的会议卡。
        if (incoming.endAt != null) do {
            val previous = matches.size
            if (!manual && existing.any { it.deletedAt != null && it.endAt != null && it.at <= end!! && it.endAt >= start }) return SkillMergeResult.Deleted
            matches = existing.filter { (manual || it.deletedAt == null) && (it in matches || (it.endAt != null && it.at <= end!! && it.endAt >= start)) }
            start = minOf(start, matches.minOfOrNull { it.at } ?: start)
            end = (matches.mapNotNull { it.endAt } + listOfNotNull(end)).maxOrNull()
        } while (matches.size != previous)
        val activeCount = existing.count { it.deletedAt == null }
        if (matches.none { it.deletedAt == null } && activeCount >= max) return SkillMergeResult.Full(activeCount)
        val keep = matches.minByOrNull { it.id }
        val allEvidence = (matches.flatMap { JSONArray(it.evidenceJson).elements() } + JSONArray(incoming.evidenceJson).elements()).distinct()
        val merged = incoming.copy(id = keep?.id ?: 0, entity = keep?.entity ?: incoming.entity,
            at = if (incoming.endAt != null) start else incoming.at, endAt = end,
            aliasesJson = JSONArray((matches.flatMap { it.entities() } + incoming.entity).distinct()).toString(),
            evidenceJson = JSONArray(allEvidence).toString(), deletedAt = null)
        if (matches.size > 1) removeMerged(matches.filter { it.id != keep!!.id }.map { it.id })
        val id = save(merged)
        return SkillMergeResult.Saved(merged.copy(id = if (merged.id == 0L) id else merged.id), matches.none { it.deletedAt == null })
    }
}
