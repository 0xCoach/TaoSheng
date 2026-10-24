package app.resound.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "transcript_lines", indices = [Index("segmentId"), Index("eventId")], foreignKeys = [ForeignKey(
    entity = SegmentEntity::class, parentColumns = ["id"], childColumns = ["segmentId"], onDelete = ForeignKey.CASCADE)])
data class TranscriptLineEntity(@PrimaryKey(autoGenerate = true) val id: Long = 0, val segmentId: Long,
    val startAt: Long, val endAt: Long, val text: String, val speakerTag: String?, val speakerName: String? = null,
    /** 整理进了哪件事；没整理或没归进事件为 null */
    val eventId: Long? = null,
    /** 整理过的时间；null 表示还没整理 */
    val digestedAt: Long? = null, val speakerScore: Float? = null,
    val speakerPersonId: Long? = null,
    @ColumnInfo(defaultValue = "0") val speakerManual: Boolean = false)
@Entity(tableName = "asr_usage")
data class AsrUsageEntity(@PrimaryKey val day: String, val sentMillis: Long)
/** 严格只存元数据；禁止异常原文、识别原文或请求头。 */
@Entity(tableName = "asr_attempts")
data class AsrAttemptEntity(@PrimaryKey(autoGenerate = true) val id: Long = 0, val segmentId: Long?,
    val at: Long, val result: String, val errorType: String?, val elapsedMillis: Long, val sentMillis: Long, val logId: String?)
data class SegmentWithLines(@Embedded val segment: SegmentEntity,
    @Relation(parentColumn = "id", entityColumn = "segmentId") val lines: List<TranscriptLineEntity>)

@Dao
abstract class TranscribeDao {
    @Query("SELECT EXISTS(SELECT 1 FROM segments WHERE path IN (:ogg, :m4a))")
    abstract suspend fun hasImportPath(ogg: String, m4a: String): Boolean
    @Query("SELECT * FROM segments WHERE sourceId = 'import' AND status != 'Running'")
    abstract suspend fun importedAudio(): List<SegmentEntity>

    @Transaction
    @Query("SELECT * FROM segments WHERE startAt >= :start AND startAt < :end ORDER BY startAt DESC, id DESC")
    abstract fun day(start: Long, end: Long): Flow<List<SegmentWithLines>>
    @Query("SELECT * FROM segments") abstract fun observeSegments(): Flow<List<SegmentEntity>>
    @Query("SELECT * FROM asr_usage WHERE day >= :from AND day <= :to ORDER BY day")
    abstract fun usageBetween(from: String, to: String): Flow<List<AsrUsageEntity>>
    @Query("SELECT * FROM asr_usage") abstract fun observeUsage(): Flow<List<AsrUsageEntity>>
    // 超时只影响本段：到期后排在其他候选后面，避免长段反复占住队首。
    @Query("SELECT * FROM segments WHERE status = 'Pending' OR (status = 'Failed' AND attempts < 5 AND nextRetryAt <= :now) ORDER BY CASE WHEN error = '转写超时，等待重试' THEN 1 ELSE 0 END, startAt, id")
    abstract suspend fun candidates(now: Long): List<SegmentEntity>
    @Query("SELECT MIN(nextRetryAt) FROM segments WHERE status = 'Failed' AND attempts < 5 AND nextRetryAt IS NOT NULL")
    abstract suspend fun earliestRetry(): Long?
    // Running 的尝试未完成，不占失败次数；已发送的用量仍保留，第五次中断也可恢复。
    @Query("UPDATE segments SET status = 'Failed', error = '任务中断，等待重试', nextRetryAt = :now, attempts = MAX(attempts - 1, 0) WHERE status = 'Running'")
    abstract suspend fun recoverInterrupted(now: Long)
    @Query("UPDATE segments SET status = 'Pending', attempts = 0, nextRetryAt = NULL, error = NULL, completedAt = NULL WHERE status = 'Failed'")
    abstract suspend fun retryFailed()
    @Query("UPDATE segments SET status = 'Pending', attempts = 0, nextRetryAt = NULL, error = NULL, completedAt = NULL WHERE id = :id AND status = 'SkippedPlayback'")
    abstract suspend fun transcribeAnyway(id: Long): Int
    @Query("SELECT COUNT(*) FROM segments WHERE status = 'SkippedPlayback'")
    abstract suspend fun skippedPlaybackCount(): Int
    @Update abstract suspend fun update(row: SegmentEntity)
    @Query("SELECT * FROM segments WHERE id = :id") abstract suspend fun findSegment(id: Long): SegmentEntity?
    @Query("UPDATE segments SET status = 'Running', attempts = attempts + 1, nextRetryAt = NULL, error = NULL WHERE id = :id AND status IN ('Pending', 'Failed')")
    abstract suspend fun claim(id: Long): Int
    @Query("SELECT * FROM segments WHERE id = :id") abstract suspend fun segment(id: Long): SegmentEntity
    @Query("DELETE FROM transcript_lines WHERE segmentId = :id") abstract suspend fun deleteLines(id: Long)
    @Insert abstract suspend fun insertLines(lines: List<TranscriptLineEntity>)
    @Transaction
    open suspend fun complete(row: SegmentEntity, lines: List<TranscriptLineEntity>) {
        if (findSegment(row.id) == null) return
        deleteLines(row.id)
        insertLines(lines)
        update(row)
    }
    @Query("SELECT * FROM segments WHERE status IN ('Done', 'Empty', 'SkippedPlayback') AND completedAt <= :before")
    abstract suspend fun expired(before: Long): List<SegmentEntity>
    @Insert(onConflict = OnConflictStrategy.IGNORE) abstract suspend fun insertUsage(row: AsrUsageEntity)
    @Query("UPDATE asr_usage SET sentMillis = sentMillis + :millis WHERE day = :day")
    abstract suspend fun addUsage(day: String, millis: Long)
    @Query("UPDATE segments SET sentMillis = sentMillis + :millis WHERE id = :id")
    abstract suspend fun addSegmentUsage(id: Long, millis: Long)
    /** 首次音频入发送队列前先落账；中途失败仍保守计入整段，不扣回。 */
    @Transaction
    open suspend fun reserveAudio(day: String, millis: Long, segmentId: Long?) {
        insertUsage(AsrUsageEntity(day, 0))
        addUsage(day, millis)
        if (segmentId != null) addSegmentUsage(segmentId, millis)
    }
    @Insert abstract suspend fun attempt(row: AsrAttemptEntity)
    @Query("SELECT * FROM asr_attempts ORDER BY id DESC LIMIT 20") abstract suspend fun recentAttempts(): List<AsrAttemptEntity>
}
