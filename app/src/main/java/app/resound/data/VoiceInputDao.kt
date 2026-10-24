package app.resound.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
abstract class VoiceInputDao {
    @Insert abstract suspend fun segment(row: SegmentEntity): Long
    @Insert abstract suspend fun line(row: TranscriptLineEntity): Long
    @Query("SELECT l.* FROM transcript_lines l JOIN segments s ON s.id = l.segmentId WHERE s.sourceId != 'voice-input' AND l.startAt BETWEEN :at - 30000 AND :at + 30000")
    abstract suspend fun nearby(at: Long): List<TranscriptLineEntity>
    @Query("SELECT * FROM transcript_lines WHERE voiceInputJson IS NOT NULL AND startAt BETWEEN :at - 1800000 AND :at ORDER BY startAt DESC, id DESC")
    abstract suspend fun recent(at: Long): List<TranscriptLineEntity>
    @Query("SELECT * FROM transcript_lines WHERE voiceInputJson IS NOT NULL ORDER BY startAt DESC, id DESC")
    abstract fun observed(): Flow<List<TranscriptLineEntity>>
    @Query("SELECT l.* FROM transcript_lines l JOIN segments s ON s.id=l.segmentId WHERE s.path=:token")
    abstract suspend fun byToken(token: String): TranscriptLineEntity?
    @Query("UPDATE transcript_lines SET voiceInputJson=:json, voiceInputPendingUntil=NULL WHERE id=:id AND voiceInputPendingUntil IS NOT NULL")
    abstract suspend fun finish(id: Long, json: String): Int
    @Query("UPDATE transcript_lines SET voiceInputPendingUntil=:until WHERE segmentId IN (SELECT id FROM segments WHERE path=:token) AND voiceInputPendingUntil IS NOT NULL")
    abstract suspend fun sent(token: String, until: Long)
    @Query("UPDATE transcript_lines SET voiceInputPendingUntil=NULL WHERE voiceInputPendingUntil IS NOT NULL")
    abstract suspend fun recover()
}
