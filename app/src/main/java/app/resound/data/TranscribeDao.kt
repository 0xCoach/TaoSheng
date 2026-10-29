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
    @ColumnInfo(defaultValue = "0") val speakerManual: Boolean = false,
    val voiceInputJson: String? = null, val voiceInputPendingUntil: Long? = null,
    /** 本地同音纠正前的整句；未纠正为 null。 */
    val originalText: String? = null)
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
    @Transaction
    @Query("SELECT * FROM segments WHERE id IN (:ids) ORDER BY startAt, id")
    abstract suspend fun exportSegments(ids: List<Long>): List<SegmentWithLines>
    @Query("""SELECT * FROM memory_events WHERE deleted = 0 AND
        ((startAt < :until AND endAt > :from) OR id IN
        (SELECT eventId FROM transcript_lines WHERE segmentId = :segmentId))""")
    abstract suspend fun exportEvents(segmentId: Long, from: Long, until: Long): List<MemoryEventEntity>

    @Query("SELECT * FROM segments") abstract suspend fun allAudio(): List<SegmentEntity>
    @Query("""SELECT s.id FROM segments s WHERE EXISTS (SELECT 1 FROM memory_events e
        WHERE e.starred = 1 AND e.deleted = 0 AND s.startAt < e.endAt AND s.endAt > e.startAt)""")
    abstract suspend fun starredAudio(): List<Long>

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
    protected abstract suspend fun candidateRows(now: Long): List<SegmentEntity>
    @Transaction
    open suspend fun candidates(now: Long): List<SegmentEntity> {
        refreshFailures(now)
        return candidateRows(now)
    }
    /** 还没转写的录音总时长（毫秒），决定是否提前转写 */
    @Query("SELECT COALESCE(SUM(endAt - startAt), 0) FROM segments WHERE status = 'Pending'")
    abstract suspend fun pendingMillis(): Long
    @Query("SELECT MIN(nextRetryAt) FROM segments WHERE status = 'Failed' AND attempts < 5 AND nextRetryAt IS NOT NULL")
    abstract suspend fun earliestRetry(): Long?
    // Running 的尝试未完成，不占失败次数；已发送的用量仍保留，第五次中断也可恢复。
    @Query("UPDATE segments SET status = 'Failed', error = '任务中断，等待重试', nextRetryAt = :now, firstFailedAt = COALESCE(firstFailedAt, :now), attempts = MAX(attempts - 1, 0) WHERE status = 'Running'")
    abstract suspend fun recoverInterrupted(now: Long)
    @Query("UPDATE segments SET status = 'Pending', attempts = 0, nextRetryAt = NULL, error = NULL, completedAt = NULL WHERE status = 'Failed'")
    protected abstract suspend fun resetFailed()
    @Transaction
    open suspend fun retryFailed(now: Long = System.currentTimeMillis()) {
        refreshFailures(now)
        resetFailed()
    }
    @Query("UPDATE segments SET status = 'Abandoned', completedAt = :now, nextRetryAt = NULL, error = CASE WHEN error = '录音文件已不存在' THEN error ELSE '录音损坏，无法转写' END WHERE status = 'Failed' AND nextRetryAt IS NULL AND (error IN ('录音文件不可读取或解码失败', '录音文件已不存在', '录音损坏，无法转写', '导入音频已满 3 天并删除，请重新分享') OR EXISTS (SELECT 1 FROM asr_attempts a WHERE a.id = (SELECT MAX(id) FROM asr_attempts WHERE segmentId = segments.id) AND a.errorType = 'Audio'))")
    protected abstract suspend fun abandonOldAudio(now: Long)
    @Query("UPDATE segments SET firstFailedAt = :now WHERE status = 'Failed' AND firstFailedAt IS NULL")
    protected abstract suspend fun initializeFailureTime(now: Long)
    @Query("UPDATE segments SET status = 'Quiet', completedAt = :now, nextRetryAt = NULL, error = '多次转写失败' WHERE status = 'Failed' AND firstFailedAt < :now - 259200000")
    protected abstract suspend fun quietExpired(now: Long)
    @Transaction
    open suspend fun refreshFailures(now: Long) {
        abandonOldAudio(now)
        initializeFailureTime(now)
        quietExpired(now)
    }
    @Query("UPDATE segments SET status = 'Quiet', completedAt = :now, nextRetryAt = NULL WHERE status = 'Failed'")
    protected abstract suspend fun quietFailed(now: Long)
    @Transaction
    open suspend fun ignoreFailed(now: Long = System.currentTimeMillis()) {
        refreshFailures(now)
        quietFailed(now)
    }
    @Query("UPDATE segments SET status = 'Pending', attempts = 0, nextRetryAt = NULL, error = NULL, completedAt = NULL, firstFailedAt = NULL WHERE id = :id AND status IN ('Failed', 'Quiet', 'Abandoned')")
    abstract suspend fun retrySegment(id: Long): Int

    @Query("DELETE FROM asr_attempts WHERE segmentId = :id")
    protected abstract suspend fun deleteAttempts(id: Long)
    @Query("DELETE FROM voiceprint_samples WHERE segmentId = :id OR sourceLineId IN (SELECT id FROM transcript_lines WHERE segmentId = :id)")
    protected abstract suspend fun deleteSamples(id: Long)
    @Query("DELETE FROM voiceprint_exclusions WHERE segmentId = :id")
    protected abstract suspend fun deleteExclusions(id: Long)
    @Query("DELETE FROM memory_items WHERE lineId IN (SELECT id FROM transcript_lines WHERE segmentId = :id)")
    protected abstract suspend fun deleteLineItems(id: Long)
    @Query("DELETE FROM memory_events WHERE id IN (SELECT eventId FROM transcript_lines WHERE segmentId = :id) AND NOT EXISTS (SELECT 1 FROM transcript_lines l WHERE l.eventId = memory_events.id AND l.segmentId != :id)")
    protected abstract suspend fun deleteExclusiveEvents(id: Long)
    @Query("DELETE FROM segments WHERE id = :id")
    protected abstract suspend fun deleteSegmentRow(id: Long)
    @Transaction
    open suspend fun deleteFailed(id: Long): Boolean {
        val row = findSegment(id) ?: return false
        if (row.status !in setOf("Failed", "Quiet", "Abandoned")) return false
        val file = java.io.File(row.path)
        if (file.exists() && !file.delete()) return false
        deleteSamples(id)
        deleteExclusions(id)
        deleteAttempts(id)
        deleteLineItems(id)
        deleteExclusiveEvents(id)
        deleteSegmentRow(id) // 原话由外键级联删除；累计用量不回退。
        return true
    }
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
    @Query("SELECT * FROM transcript_lines WHERE voiceInputJson IS NOT NULL AND startAt BETWEEN :from AND :to")
    abstract suspend fun voiceInputs(from: Long, to: Long): List<TranscriptLineEntity>
    @Transaction
    open suspend fun complete(row: SegmentEntity, lines: List<TranscriptLineEntity>) {
        if (findSegment(row.id) == null) return
        deleteLines(row.id)
        // 语音输入那条已经记下的话（带上下文），录音转写里不再重复记；重转时同样过一遍
        val voice = if (lines.isEmpty()) emptyList() else voiceInputs(lines.minOf { it.startAt } - app.resound.capture.VoiceInputDedup.MIC_AFTER_MS,
            lines.maxOf { it.startAt } + app.resound.capture.VoiceInputDedup.MIC_BEFORE_MS)
        insertLines(lines.filter { line -> voice.none { app.resound.capture.VoiceInputDedup.heardByMic(it.startAt, it.text, line.startAt, line.text, line.speakerName) } })
        update(row)
    }
    @Query("SELECT * FROM segments WHERE status IN ('Done', 'Empty', 'SkippedPlayback', 'Abandoned', 'Quiet') AND completedAt <= :before")
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
