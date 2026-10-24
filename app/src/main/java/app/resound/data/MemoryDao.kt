package app.resound.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

/** 大模型切出的一件事。points / people 存 JSON 数组文本。 */
@Entity(tableName = "memory_events", indices = [Index("startAt")])
data class MemoryEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startAt: Long,
    val endAt: Long,
    val scene: String,
    val title: String,
    val summary: String,
    val pointsJson: String,
    val peopleJson: String,
    val place: String?,
    val emotion: String?,
    val intensity: Int?,
    val important: Boolean,
    @ColumnInfo(defaultValue = "0") val deleted: Boolean = false,
    val createdAt: Long,
    val model: String,
)

/** 提取项：待办、约定、备忘、灵感、关键信息、决定、知识。 */
@Entity(
    tableName = "memory_items",
    indices = [Index("eventId"), Index("status"), Index("dueAt")],
    foreignKeys = [ForeignKey(entity = MemoryEventEntity::class, parentColumns = ["id"], childColumns = ["eventId"], onDelete = ForeignKey.CASCADE)],
)
data class MemoryItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val eventId: Long,
    val category: String,
    val kind: String?,
    val content: String,
    val dueText: String?,
    val dueAt: Long?,
    val who: String?,
    val lineId: Long?,
    val status: String,
    val createdAt: Long,
    val doneAt: Long? = null,
    val statusChangedAt: Long? = null,
    val sortOrder: Long? = null,
)

/** 大模型用量，按天累计 */
@Entity(tableName = "llm_usage")
data class LlmUsageEntity(@PrimaryKey val day: String, val promptTokens: Long, val completionTokens: Long, val calls: Int)

@Dao
abstract class MemoryDao {
    // ---------- 整理 ----------
    /** 已转写、还没整理过的句子（按时间） */
    @Query(
        """SELECT l.* FROM transcript_lines l JOIN segments s ON s.id = l.segmentId
           WHERE l.digestedAt IS NULL AND s.status = 'Done' ORDER BY l.startAt, l.id""",
    )
    abstract suspend fun undigestedLines(): List<TranscriptLineEntity>

    @Query("SELECT COUNT(*) FROM segments WHERE id IN (:ids) AND voiceprintProcessedAt IS NULL")
    abstract suspend fun voiceprintPending(ids: List<Long>): Int

    @Query("SELECT * FROM transcript_lines WHERE id IN (:ids) ORDER BY startAt, id")
    abstract suspend fun refreshLines(ids: List<Long>): List<TranscriptLineEntity>

    /** 某时间范围内还没转写完的录音段数（会补进来的，整理要等它们） */
    @Query(
        """SELECT COUNT(*) FROM segments WHERE endAt > :start AND startAt < :end
           AND (status IN ('Pending', 'Running') OR (status = 'Failed' AND attempts < 5))""",
    )
    abstract suspend fun unfinishedSegments(start: Long, end: Long): Int

    @Query("SELECT COUNT(*) FROM transcript_lines l JOIN segments s ON s.id = l.segmentId WHERE l.digestedAt IS NULL AND s.status = 'Done'")
    abstract fun observeUndigestedCount(): Flow<Int>

    @Insert abstract suspend fun insertEvent(row: MemoryEventEntity): Long
    @Insert abstract suspend fun insertItems(rows: List<MemoryItemEntity>)

    @Query("UPDATE transcript_lines SET eventId = :eventId WHERE id IN (:ids)")
    abstract suspend fun assignLines(ids: List<Long>, eventId: Long)

    @Query("UPDATE transcript_lines SET digestedAt = :at WHERE id IN (:ids)")
    abstract suspend fun markDigested(ids: List<Long>, at: Long)

    /** 一次整理的结果原子写入：事件、提取项、句子归属、整理标记 */
    @Transaction
    open suspend fun saveDigest(
        events: List<Pair<MemoryEventEntity, List<Long>>>,
        items: (eventIds: List<Long>) -> List<MemoryItemEntity>,
        allLineIds: List<Long>,
        at: Long,
    ) {
        // 云端请求期间原话可能被隐私操作删除；整批旧结果作废，不复活事件和条目。
        if (refreshLines(allLineIds).size != allLineIds.distinct().size) return
        val ids = events.map { (event, lineIds) ->
            insertEvent(event).also { id -> if (lineIds.isNotEmpty()) assignLines(lineIds, id) }
        }
        val rows = items(ids)
        if (rows.isNotEmpty()) insertItems(rows)
        markDigested(allLineIds, at)
    }

    @Insert(onConflict = OnConflictStrategy.IGNORE) abstract suspend fun insertUsage(row: LlmUsageEntity)
    @Query("UPDATE llm_usage SET promptTokens = promptTokens + :p, completionTokens = completionTokens + :c, calls = calls + 1 WHERE day = :day")
    abstract suspend fun addUsageRow(day: String, p: Long, c: Long)
    @Transaction
    open suspend fun addUsage(day: String, prompt: Long, completion: Long) {
        insertUsage(LlmUsageEntity(day, 0, 0, 0))
        addUsageRow(day, prompt, completion)
    }
    @Query("SELECT * FROM llm_usage") abstract fun observeUsage(): Flow<List<LlmUsageEntity>>

    // ---------- 界面 ----------
    @Query("SELECT * FROM memory_events WHERE deleted = 0 AND startAt >= :start AND startAt < :end ORDER BY startAt DESC")
    abstract fun events(start: Long, end: Long): Flow<List<MemoryEventEntity>>

    @Query("SELECT * FROM memory_items WHERE eventId IN (SELECT id FROM memory_events WHERE deleted = 0 AND startAt >= :start AND startAt < :end)")
    abstract fun itemsBetween(start: Long, end: Long): Flow<List<MemoryItemEntity>>

    @Query("SELECT * FROM transcript_lines WHERE eventId = :eventId ORDER BY startAt, id")
    abstract fun eventLines(eventId: Long): Flow<List<TranscriptLineEntity>>

    @Query(
        """SELECT i.* FROM memory_items i JOIN memory_events e ON e.id = i.eventId
           WHERE e.deleted = 0 AND i.status = 'Pending' AND i.category IN ('待办', '约定', '备忘')
           ORDER BY i.createdAt DESC""",
    )
    abstract fun pendingItems(): Flow<List<MemoryItemEntity>>

    @Query(
        """SELECT i.* FROM memory_items i JOIN memory_events e ON e.id = i.eventId
           WHERE e.deleted = 0 AND i.category IN (:categories) ORDER BY i.createdAt DESC, i.id DESC LIMIT 500""",
    )
    abstract fun itemsByCategory(categories: List<String>): Flow<List<MemoryItemEntity>>

    @Query("""SELECT i.* FROM memory_items i JOIN memory_events e ON e.id = i.eventId
        WHERE e.deleted = 0 AND i.status = 'Accepted' AND i.category IN ('待办', '备忘')
        ORDER BY i.sortOrder IS NULL, i.sortOrder, i.createdAt DESC, i.id DESC""")
    abstract fun inProgress(): Flow<List<MemoryItemEntity>>

    @Query("""SELECT i.* FROM memory_items i JOIN memory_events e ON e.id = i.eventId
        WHERE e.deleted = 0 AND i.status = 'Accepted' AND i.category = '约定'
        ORDER BY i.dueAt IS NULL, i.dueAt, i.createdAt DESC, i.id DESC""")
    abstract fun appointments(): Flow<List<MemoryItemEntity>>

    // 单条 UPDATE 中取最小值并写状态，避免并发接受时拿到同一个队首值。
    @Query("""UPDATE memory_items SET status = :status, statusChangedAt = :at,
        doneAt = CASE WHEN :status = 'Done' THEN :at ELSE NULL END,
        sortOrder = CASE WHEN :status = 'Accepted' THEN
            (SELECT COALESCE(MIN(i.sortOrder) - 1, 0) FROM memory_items i
             JOIN memory_events e ON e.id = i.eventId
             WHERE e.deleted = 0 AND i.status = 'Accepted' AND i.category IN ('待办', '备忘'))
            ELSE sortOrder END WHERE id = :id""")
    abstract suspend fun setItemStatus(id: Long, status: String, at: Long = System.currentTimeMillis())

    @Query("UPDATE memory_items SET sortOrder = :order WHERE id = :id")
    abstract suspend fun setSortOrder(id: Long, order: Long)

    @Transaction
    open suspend fun reorder(ids: List<Long>) {
        ids.distinct().forEachIndexed { index, id -> setSortOrder(id, index.toLong()) }
    }

    @Query("""SELECT i.* FROM memory_items i JOIN memory_events e ON e.id = i.eventId
        WHERE e.deleted = 0 AND i.status != 'Ignored' AND
        ((i.createdAt >= :start AND i.createdAt < :end) OR
         (i.dueAt >= :start AND i.dueAt < :end) OR (i.doneAt >= :start AND i.doneAt < :end))
        ORDER BY i.createdAt, i.id""")
    abstract fun dayItems(start: Long, end: Long): Flow<List<MemoryItemEntity>>

    @Query("SELECT startAt FROM memory_events WHERE deleted = 0 AND startAt >= :start AND startAt < :end")
    abstract fun activeEventTimes(start: Long, end: Long): Flow<List<Long>>

    @Query("""SELECT i.createdAt FROM memory_items i JOIN memory_events e ON e.id = i.eventId
        WHERE e.deleted = 0 AND i.createdAt >= :start AND i.createdAt < :end""")
    abstract fun activeItemTimes(start: Long, end: Long): Flow<List<Long>>

    @Query("""SELECT l.* FROM transcript_lines l JOIN segments s ON s.id = l.segmentId
        LEFT JOIN memory_events e ON e.id = l.eventId
        WHERE s.status = 'Done' AND l.startAt >= :start AND l.startAt < :end
        AND (e.id IS NULL OR e.deleted = 0) ORDER BY l.startAt, l.id""")
    abstract suspend fun diaryLines(start: Long, end: Long): List<TranscriptLineEntity>
    @Query("""SELECT MAX(at) FROM (
        SELECT startAt AS at FROM memory_events WHERE deleted = 0 AND scene NOT IN ('媒体', '旁人')
        UNION ALL SELECT l.startAt AS at FROM transcript_lines l JOIN segments s ON s.id = l.segmentId
        LEFT JOIN memory_events e ON e.id = l.eventId WHERE :includeLines AND s.status = 'Done' AND l.text != '' AND (e.id IS NULL OR e.deleted = 0)
    )""")
    abstract suspend fun latestDiaryAt(includeLines: Boolean): Long?
    @Query("UPDATE memory_events SET important = :important WHERE id = :id") abstract suspend fun setImportant(id: Long, important: Boolean)
    @Query("UPDATE memory_events SET deleted = 1 WHERE id = :id") abstract suspend fun deleteEvent(id: Long)
}
