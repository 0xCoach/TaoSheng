package app.resound.redo

import androidx.room.*
import app.resound.data.*

@Entity(tableName = "redo_snapshot")
data class RedoSnapshotRow(@PrimaryKey val slot: Int = 1, val jobId: String, val at: Long, val payload: String, val applied: Boolean = false)

@Dao
interface RedoDao {
    /** 不读快照正文：整天重做的快照可能超过 2MB，一次读出会让数据库游标装不下而闪退 */
    @Query("SELECT slot, jobId, at, '' AS payload, applied FROM redo_snapshot WHERE slot = 1") suspend fun snapshotMeta(): RedoSnapshotRow?
    @Query("SELECT length(payload) FROM redo_snapshot WHERE slot = 1") suspend fun payloadLength(): Int?
    @Query("SELECT substr(payload, :from, :count) FROM redo_snapshot WHERE slot = 1") suspend fun payloadPart(from: Int, count: Int): String?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun snapshot(row: RedoSnapshotRow)
    @Query("DELETE FROM redo_snapshot") suspend fun clear()
    @Query("SELECT * FROM memory_events WHERE deleted = 0 AND startAt < :end AND endAt > :start ORDER BY startAt, id")
    suspend fun events(start: Long, end: Long): List<MemoryEventEntity>
    @Query("SELECT * FROM segments WHERE startAt < :end AND endAt > :start ORDER BY startAt, id")
    suspend fun segments(start: Long, end: Long): List<SegmentEntity>
    @Query("SELECT * FROM memory_items WHERE eventId IN (:ids)") suspend fun items(ids: List<Long>): List<MemoryItemEntity>
    @Query("SELECT * FROM transcript_lines WHERE eventId IN (:ids) ORDER BY startAt, id") suspend fun eventLines(ids: List<Long>): List<TranscriptLineEntity>
    @Query("SELECT * FROM skill_cards WHERE deletedAt IS NULL") suspend fun cards(): List<app.resound.skills.SkillCardRow>
    @Update suspend fun item(row: MemoryItemEntity)
}
