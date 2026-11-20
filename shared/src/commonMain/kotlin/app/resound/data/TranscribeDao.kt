package app.resound.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "transcript_lines", indices = [Index("segmentId"), Index("eventId"), Index("startAt")], foreignKeys = [ForeignKey(
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
    val originalText: String? = null,
    /** 卡 211：人工修改文字的时间；没改过为 null。重新转写会生成新行，自然回到机器结果 */
    val textEditedAt: Long? = null)
@Entity(tableName = "asr_usage")
data class AsrUsageEntity(@PrimaryKey val day: String, val sentMillis: Long,
    @ColumnInfo(defaultValue = "0") val inputTokens: Long = 0,
    @ColumnInfo(defaultValue = "0") val outputTokens: Long = 0)
/** 严格只存元数据；禁止异常原文、识别原文或请求头。 */
@Entity(tableName = "asr_attempts")
data class AsrAttemptEntity(@PrimaryKey(autoGenerate = true) val id: Long = 0, val segmentId: Long?,
    val at: Long, val result: String, val errorType: String?, val elapsedMillis: Long, val sentMillis: Long, val logId: String?)
data class SegmentWithLines(@Embedded val segment: SegmentEntity,
    @Relation(parentColumn = "id", entityColumn = "segmentId") val lines: List<TranscriptLineEntity>)

data class QueueCounts(val pending: Int, val failed: Int, val running: Boolean)
data class VoiceprintCounts(val processed: Int, val pending: Int)
data class AsrUsageTotals(val todayMillis: Long, val totalMillis: Long)
/** 手机麦克风片段的外放毫秒数快照，供整理提示标注外放。 */
data class SegmentPlayback(val id: Long, val playbackMillis: Long, val speakerIntervals: String? = null, val duration: Long = 0)

@Dao
abstract class TranscribeDao {
    @Transaction open suspend fun <T> transaction(block: suspend () -> T): T = block()
    @Query("""SELECT EXISTS(SELECT 1 FROM segments WHERE (sourceId='mic' OR speakerIntervals IS NOT NULL) AND status='Done' AND playbackMillis>0
        GROUP BY date(startAt/1000, 'unixepoch', 'localtime') HAVING SUM(playbackMillis)>1800000)""")
    abstract fun hasHeavyPlaybackDay(): Flow<Boolean>

    /** 只读时间元数据；空白句子不算首次转写。 */
    @Query("SELECT MIN(startAt) FROM transcript_lines WHERE LENGTH(TRIM(text, CHAR(9) || CHAR(10) || CHAR(13) || ' ' || CHAR(12288))) > 0")
    abstract fun onboardingFirstTranscript(): Flow<Long?>

    /** 标签只在同一录音段内比较；uncertain:* 是分块占位符，不是可靠的说话人。 */
    @Query("""SELECT s.startAt FROM segments s WHERE s.startAt >= :since AND s.sourceId != 'voice-input'
        AND (SELECT COUNT(DISTINCT CASE WHEN l.speakerTag NOT LIKE 'uncertain:%'
                AND TRIM(l.speakerTag) != '' THEN l.speakerTag END) >= 2
            OR MAX(CASE WHEN l.speakerPersonId > 0 THEN 1 ELSE 0 END) = 1
            FROM transcript_lines l WHERE l.segmentId = s.id
                AND LENGTH(TRIM(l.text, CHAR(9) || CHAR(10) || CHAR(13) || ' ' || CHAR(12288))) > 0)
        ORDER BY s.startAt DESC LIMIT 1""")
    abstract fun onboardingMultipleSpeakers(since: Long): Flow<Long?>

    @Transaction
    @Query("SELECT * FROM segments WHERE id IN (:ids) ORDER BY startAt, id")
    abstract suspend fun exportSegments(ids: List<Long>): List<SegmentWithLines>
    @Query("""SELECT * FROM memory_events WHERE deleted = 0 AND
        ((startAt < :until AND endAt > :from) OR id IN
        (SELECT eventId FROM transcript_lines WHERE segmentId = :segmentId))""")
    abstract suspend fun exportEvents(segmentId: Long, from: Long, until: Long): List<MemoryEventEntity>

    /** 只导出仍有效的待办与约定，已合并或忽略的旧项不再展示。 */
    @Query("""SELECT * FROM memory_items WHERE eventId IN (:eventIds)
        AND deleted = 0 AND mergedInto IS NULL AND status != 'Ignored'
        AND category IN ('待办', '约定') ORDER BY createdAt, id""")
    abstract suspend fun exportItems(eventIds: List<Long>): List<MemoryItemEntity>

    @Transaction
    @Query("SELECT * FROM segments") abstract suspend fun allAudio(): List<SegmentEntity>
    // 先取少量收藏事件/标记，再逐个对录音段做区间比对（CROSS JOIN 固定外层顺序）。
    // 旧写法让每段录音去扫全部事件，几万段时一次要 8 秒，且推录音每次刷新都会调用。
    @Query("""SELECT DISTINCT s.id FROM (
        SELECT startAt AS a, endAt AS b FROM memory_events WHERE starred = 1 AND deleted = 0
        UNION ALL SELECT at - beforeMillis AS a, at + afterMillis AS b FROM marks
    ) k CROSS JOIN segments s WHERE s.startAt < k.b AND s.endAt > k.a""")
    abstract suspend fun starredAudio(): List<Long>

    @Query("SELECT EXISTS(SELECT 1 FROM segments WHERE path IN (:ogg, :m4a))")
    abstract suspend fun hasImportPath(ogg: String, m4a: String): Boolean
    @Query("SELECT * FROM segments WHERE sourceId = 'import' AND status != 'Running'")
    abstract suspend fun importedAudio(): List<SegmentEntity>

    @Transaction
    @Query("SELECT * FROM segments WHERE startAt >= :start AND startAt < :end ORDER BY startAt DESC, id DESC")
    abstract fun day(start: Long, end: Long): Flow<List<SegmentWithLines>>
    @Transaction
    @Query("SELECT * FROM segments") abstract fun observeSegments(): Flow<List<SegmentEntity>>
    // 只扫描队列状态的覆盖索引；无匹配行时三个聚合仍返回 0/false。
    @Query("""SELECT COUNT(CASE WHEN status = 'Pending' THEN 1 END) AS pending,
        COUNT(CASE WHEN status = 'Failed' THEN 1 END) AS failed,
        COUNT(CASE WHEN status = 'Running' THEN 1 END) > 0 AS running FROM segments WHERE status IN ('Pending', 'Failed', 'Running')""")
    abstract fun observeQueueCounts(): Flow<QueueCounts>
    @Query("""SELECT COUNT(voiceprintProcessedAt) AS processed,
        COUNT(*) - COUNT(voiceprintProcessedAt) AS pending FROM segments WHERE status = 'Done'""")
    abstract fun observeVoiceprintCounts(): Flow<VoiceprintCounts>
    @Query("SELECT * FROM segments WHERE status = 'Failed' ORDER BY id")
    abstract fun observeFailedSegments(): Flow<List<SegmentEntity>>
    // Done占绝大多数，近期查询仍按时间范围查，不能改走状态索引。
    @Query("SELECT * FROM segments INDEXED BY index_segments_startAt WHERE status = 'Done' AND startAt >= :since")
    abstract suspend fun recentDone(since: Long): List<SegmentEntity>
    @Query("SELECT * FROM segments WHERE startAt < :end AND endAt > :start ORDER BY startAt, id")
    abstract fun observeOverlapping(start: Long, end: Long): Flow<List<SegmentEntity>>
    @Query("""SELECT COALESCE(SUM(CASE WHEN day = :today THEN sentMillis ELSE 0 END), 0) AS todayMillis,
        COALESCE(SUM(sentMillis), 0) AS totalMillis FROM asr_usage""")
    abstract fun observeUsageTotals(today: String): Flow<AsrUsageTotals>
    @Query("SELECT * FROM asr_usage WHERE day >= :from AND day <= :to ORDER BY day")
    abstract fun usageBetween(from: String, to: String): Flow<List<AsrUsageEntity>>
    @Query("SELECT * FROM asr_usage") abstract fun observeUsage(): Flow<List<AsrUsageEntity>>
    // 超时只影响本段：到期后排在其他候选后面，避免长段反复占住队首。
    @Query("SELECT * FROM segments WHERE status = 'Pending' OR (status = 'Failed' AND attempts < 5 AND nextRetryAt <= :now) ORDER BY CASE WHEN EXISTS (SELECT 1 FROM topic_spans ts JOIN topics t ON t.id = ts.topicId WHERE t.status IN ('Running', 'Paused', 'Ended', 'Summarizing', 'Failed') AND segments.startAt < COALESCE(ts.endAt, :now) AND segments.endAt > ts.startAt) THEN 0 ELSE 1 END, CASE WHEN error = '转写超时，等待重试' THEN 1 ELSE 0 END, startAt, id")
    protected abstract suspend fun candidateRows(now: Long): List<SegmentEntity>
    @Transaction
    open suspend fun candidates(now: Long): List<SegmentEntity> {
        refreshFailures(now)
        return candidateRows(now)
    }
    /** 还没转写的录音总时长（毫秒），决定是否提前转写 */
    @Query("SELECT COALESCE(SUM(endAt - startAt), 0) FROM segments WHERE status = 'Pending'")
    abstract suspend fun pendingMillis(): Long
    @Query("SELECT COALESCE(SUM(endAt - startAt), 0) FROM segments WHERE status = 'Pending' OR (status = 'Failed' AND attempts < 5)")
    abstract fun observePendingMillis(): Flow<Long>
    @Query("SELECT MIN(nextRetryAt) FROM segments WHERE status = 'Failed' AND attempts < 5 AND nextRetryAt IS NOT NULL")
    abstract suspend fun earliestRetry(): Long?
    // Running 的尝试未完成，不占失败次数；已发送的用量仍保留，第五次中断也可恢复。
    @Query("UPDATE segments SET status = 'Failed', error = '任务中断，等待重试', nextRetryAt = :now, firstFailedAt = COALESCE(firstFailedAt, :now), attempts = MAX(attempts - 1, 0) WHERE status = 'Running'")
    abstract suspend fun recoverInterrupted(now: Long)
    @Query("UPDATE segments SET status = 'Pending', attempts = 0, nextRetryAt = NULL, error = NULL, completedAt = NULL WHERE status = 'Failed'")
    protected abstract suspend fun resetFailed()
    @Transaction
    open suspend fun retryFailed(now: Long) {
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
    open suspend fun ignoreFailed(now: Long) {
        refreshFailures(now)
        quietFailed(now)
    }
    @Query("UPDATE segments SET status = 'Pending', attempts = 0, nextRetryAt = NULL, error = NULL, completedAt = NULL, firstFailedAt = NULL WHERE id = :id AND status IN ('Failed', 'Quiet', 'Abandoned')")
    abstract suspend fun retrySegment(id: Long): Int

    @Query("DELETE FROM asr_attempts WHERE segmentId = :id")
    abstract suspend fun deleteAttempts(id: Long)
    @Query("DELETE FROM voiceprint_samples WHERE segmentId = :id OR sourceLineId IN (SELECT id FROM transcript_lines WHERE segmentId = :id)")
    abstract suspend fun deleteSamples(id: Long)
    @Query("DELETE FROM voiceprint_exclusions WHERE segmentId = :id")
    abstract suspend fun deleteExclusions(id: Long)
    @Query("DELETE FROM memory_items WHERE lineId IN (SELECT id FROM transcript_lines WHERE segmentId = :id)")
    abstract suspend fun deleteLineItems(id: Long)
    @Query("DELETE FROM memory_events WHERE id IN (SELECT eventId FROM transcript_lines WHERE segmentId = :id) AND NOT EXISTS (SELECT 1 FROM transcript_lines l WHERE l.eventId = memory_events.id AND l.segmentId != :id)")
    abstract suspend fun deleteExclusiveEvents(id: Long)
    @Query("DELETE FROM segments WHERE id = :id")
    abstract suspend fun deleteSegmentRow(id: Long)

    @Query("UPDATE segments SET status = 'Pending', attempts = 0, nextRetryAt = NULL, error = NULL, completedAt = NULL, playbackDigest = :playbackDigest WHERE id = :id AND status = 'SkippedPlayback'")
    abstract suspend fun transcribeAnyway(id: Long, playbackDigest: Int): Int
    suspend fun transcribeAnyway(id: Long): Int = transcribeAnyway(id, 1)

    /** 放行暂不整理的外放片段；只从 1 变 2，不触发整理。 */
    @Query("UPDATE segments SET playbackDigest = 2 WHERE id = :id AND playbackDigest = 1")
    abstract suspend fun allowPlaybackDigest(id: Long): Int
    @Query("SELECT COUNT(*) FROM segments WHERE status = 'SkippedPlayback'")
    abstract suspend fun skippedPlaybackCount(): Int
    @Query("UPDATE segments SET status = 'SkippedEnv', completedAt = :at, nextRetryAt = NULL, error = NULL, voiceprintProcessedAt = :at WHERE id = :id AND status = 'Running' AND NOT EXISTS (SELECT 1 FROM env_sounds WHERE segmentId = :id AND (restored = 1 OR reportedWrong = 1))")
    abstract suspend fun finishEnvSkip(id: Long, at: Long): Int
    @Query("UPDATE segments SET separateSpeakers = :on WHERE id = :id AND separateSpeakers IS NULL")
    abstract suspend fun rememberSpeakers(id: Long, on: Boolean)
    @Query("UPDATE segments SET separateSpeakers = 0 WHERE id = :id")
    abstract suspend fun topicTraining(id: Long)
    @Query("SELECT id FROM segments WHERE id IN (:ids) AND separateSpeakers = 0 AND (importChannel IS NULL OR speakerCount = 0)")
    abstract suspend fun unseparated(ids: List<Long>): List<Long>
    /** 外放提示只认手机麦克风片段；阈值判定在 SituationPrompt，这里不按毫秒数过滤。 */
    @Query("SELECT id, playbackMillis, speakerIntervals, endAt-startAt AS duration FROM segments WHERE id IN (:ids) AND (sourceId = 'mic' OR speakerIntervals IS NOT NULL)")
    abstract suspend fun playbackMillis(ids: List<Long>): List<SegmentPlayback>
    @Update abstract suspend fun update(row: SegmentEntity)
    @Query("SELECT * FROM segments WHERE id = :id") abstract suspend fun findSegment(id: Long): SegmentEntity?
    @Query("UPDATE segments SET status = 'Running', attempts = attempts + 1, nextRetryAt = NULL, error = NULL WHERE id = :id AND status IN ('Pending', 'Failed')")
    abstract suspend fun claim(id: Long): Int
    @Query("SELECT * FROM segments WHERE id = :id") abstract suspend fun segment(id: Long): SegmentEntity
    @Query("DELETE FROM transcript_lines WHERE segmentId = :id") abstract suspend fun deleteLines(id: Long)
    @Insert abstract suspend fun insertLines(lines: List<TranscriptLineEntity>)
    /** 卡 211：人工修改一句原话：直接替换文字，清掉同音纠正前的旧句，记下修改时间；全文索引由触发器跟上 */
    @Query("UPDATE transcript_lines SET text = :text, originalText = NULL, textEditedAt = :at WHERE id = :id")
    abstract suspend fun editLineText(id: Long, text: String, at: Long): Int
    /** 卡 211：这些录音段里人工改过几句（重新转写会覆盖它们） */
    @Query("SELECT COUNT(*) FROM transcript_lines WHERE segmentId IN (:segmentIds) AND textEditedAt IS NOT NULL")
    abstract suspend fun editedLineCount(segmentIds: List<Long>): Int
    @Query("SELECT * FROM transcript_lines WHERE segmentId = :id ORDER BY startAt, id")
    abstract suspend fun linesForRedo(id: Long): List<TranscriptLineEntity>
    @Query("UPDATE memory_items SET lineId = :replacement WHERE lineId = :old")
    abstract suspend fun remapItemLine(old: Long, replacement: Long?)
    @Query("UPDATE memory_items SET doneHintLineId = :replacement WHERE doneHintLineId = :old")
    abstract suspend fun remapHintLine(old: Long, replacement: Long?)
    /** 保持原话引用有效；只重转写/失败重试均不能偷偷触发事件重整理。 */

    @Query("SELECT * FROM transcript_lines WHERE voiceInputJson IS NOT NULL AND startAt BETWEEN :from AND :to")
    abstract suspend fun voiceInputs(from: Long, to: Long): List<TranscriptLineEntity>

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
    @Query("UPDATE asr_usage SET inputTokens = inputTokens + :input, outputTokens = outputTokens + :output WHERE day = :day")
    abstract suspend fun addTokens(day: String, input: Long, output: Long)
    @Transaction
    open suspend fun recordTokens(day: String, input: Long?, output: Long?) {
        if (input == null && output == null) return
        insertUsage(AsrUsageEntity(day, 0))
        addTokens(day, (input ?: 0).coerceAtLeast(0), (output ?: 0).coerceAtLeast(0))
    }
    @Insert abstract suspend fun attempt(row: AsrAttemptEntity)
    @Query("SELECT * FROM asr_attempts ORDER BY id DESC LIMIT 20") abstract suspend fun recentAttempts(): List<AsrAttemptEntity>
}
