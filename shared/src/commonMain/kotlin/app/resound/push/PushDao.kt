package app.resound.push

import androidx.room.*
import app.resound.data.*
import app.resound.skills.SkillCardRow

data class PushLineOrigin(val id: Long, val eventId: Long?)

@Dao
abstract class PushDao {
    @Transaction open suspend fun <T> transaction(block: suspend () -> T): T = block()
    @Query("SELECT id, eventId FROM transcript_lines") abstract suspend fun lineOrigins(): List<PushLineOrigin>
    @Query("SELECT * FROM memory_events ORDER BY startAt, id") abstract suspend fun events(): List<MemoryEventEntity>
    @Query("SELECT i.* FROM memory_items i LEFT JOIN memory_events e ON e.id = i.eventId WHERE (e.id IS NULL OR e.deleted = 0) AND i.deleted = 0 ORDER BY i.createdAt, i.id") abstract suspend fun items(): List<MemoryItemEntity>
    @Query("SELECT * FROM skill_cards WHERE deletedAt IS NULL ORDER BY at, id") abstract suspend fun cards(): List<SkillCardRow>
    @Query("SELECT * FROM reviews ORDER BY startDate, id") abstract suspend fun reviews(): List<ReviewEntity>
    @Query("SELECT l.* FROM transcript_lines l JOIN segments s ON s.id = l.segmentId WHERE s.status = 'Done' AND l.startAt >= :start ORDER BY l.startAt, l.id") abstract suspend fun lines(start: Long): List<TranscriptLineEntity>

}
