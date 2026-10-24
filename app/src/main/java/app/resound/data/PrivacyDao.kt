package app.resound.data

import androidx.room.Dao
import androidx.room.Query

@Dao
interface PrivacyDao {
    @Query("SELECT * FROM segments WHERE startAt >= :from AND startAt <= :until")
    suspend fun recent(from: Long, until: Long): List<SegmentEntity>
    @Query("SELECT * FROM segments WHERE id = :id")
    suspend fun segment(id: Long): SegmentEntity?
    @Query("SELECT DISTINCT s.* FROM segments s JOIN transcript_lines l ON l.segmentId = s.id WHERE l.eventId = :id")
    suspend fun eventSegments(id: Long): List<SegmentEntity>
    @Query("DELETE FROM memory_items WHERE lineId IN (SELECT id FROM transcript_lines WHERE segmentId = :id)")
    suspend fun deleteLineItems(id: Long): Int
    @Query("DELETE FROM memory_items WHERE eventId = :id")
    suspend fun deleteEventItems(id: Long): Int
    @Query("DELETE FROM memory_items WHERE lineId = :id")
    suspend fun deleteLineItemsById(id: Long): Int
    @Query("DELETE FROM transcript_lines WHERE id = :id")
    suspend fun deleteLine(id: Long): Int
    @Query("DELETE FROM transcript_lines WHERE segmentId = :id")
    suspend fun deleteLines(id: Long): Int
    @Query("DELETE FROM voiceprint_samples WHERE segmentId = :id OR sourceLineId IN (SELECT id FROM transcript_lines WHERE segmentId = :id)")
    suspend fun deleteSamples(id: Long)
    @Query("DELETE FROM voiceprint_exclusions WHERE segmentId = :id")
    suspend fun deleteExclusions(id: Long)
    @Query("DELETE FROM segments WHERE id = :id")
    suspend fun deleteSegment(id: Long): Int
    @Query("SELECT COUNT(*) FROM transcript_lines WHERE eventId = :id")
    suspend fun eventLineCount(id: Long): Int
    @Query("DELETE FROM memory_events WHERE id = :id")
    suspend fun deleteEvent(id: Long): Int
}
