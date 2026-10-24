package app.resound.data

import androidx.room.*
import app.resound.usage.UsagePurpose
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
    @ColumnInfo(defaultValue = "0") val starred: Boolean = false,
    val starredAt: Long? = null,
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
    val mergedInto: Long? = null,
    @ColumnInfo(defaultValue = "0") val noMerge: Boolean = false,
)

data class ItemRepeatCount(val id: Long, val repeats: Int)

/** 兼容现有每日总量消费者，查询时跨用途求和。 */
data class LlmUsageEntity(val day: String, val promptTokens: Long, val completionTokens: Long, val calls: Int)

@Entity(tableName = "llm_usage_v2", primaryKeys = ["day", "purpose"])
data class LlmUsageV2Entity(val day: String, val purpose: String, val promptTokens: Long, val completionTokens: Long, val calls: Int)

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

    @Insert(onConflict = OnConflictStrategy.IGNORE) abstract suspend fun insertUsage(row: LlmUsageV2Entity)
    @Query("UPDATE llm_usage_v2 SET promptTokens = promptTokens + :p, completionTokens = completionTokens + :c, calls = calls + 1 WHERE day = :day AND purpose = :purpose")
    abstract suspend fun addUsageRow(day: String, purpose: String, p: Long, c: Long)
    @Transaction
    open suspend fun addUsage(day: String, prompt: Long, completion: Long, purpose: UsagePurpose = UsagePurpose.Other) {
        insertUsage(LlmUsageV2Entity(day, purpose.name, 0, 0, 0))
        addUsageRow(day, purpose.name, prompt, completion)
    }
    @Query("SELECT day, SUM(promptTokens) AS promptTokens, SUM(completionTokens) AS completionTokens, SUM(calls) AS calls FROM llm_usage_v2 GROUP BY day ORDER BY day")
    abstract fun observeUsage(): Flow<List<LlmUsageEntity>>

    @Query("SELECT * FROM llm_usage_v2 WHERE day >= :from AND day <= :to ORDER BY day, purpose")
    abstract fun usageByPurpose(from: String, to: String): Flow<List<LlmUsageV2Entity>>

    // ---------- 界面 ----------
    @Query("SELECT * FROM memory_events WHERE deleted = 0 AND startAt >= :start AND startAt < :end ORDER BY startAt DESC")
    abstract fun events(start: Long, end: Long): Flow<List<MemoryEventEntity>>

    @Query("SELECT * FROM memory_items WHERE mergedInto IS NULL AND eventId IN (SELECT id FROM memory_events WHERE deleted = 0 AND startAt >= :start AND startAt < :end)")
    abstract fun itemsBetween(start: Long, end: Long): Flow<List<MemoryItemEntity>>

    @Query("SELECT * FROM transcript_lines WHERE eventId = :eventId ORDER BY startAt, id")
    abstract fun eventLines(eventId: Long): Flow<List<TranscriptLineEntity>>

    @Query(
        """SELECT i.* FROM memory_items i JOIN memory_events e ON e.id = i.eventId
           WHERE e.deleted = 0 AND i.mergedInto IS NULL AND i.status = 'Pending' AND i.category IN ('待办', '约定', '备忘')
           ORDER BY i.createdAt DESC""",
    )
    abstract fun pendingItems(): Flow<List<MemoryItemEntity>>

    @Query(
        """SELECT i.* FROM memory_items i JOIN memory_events e ON e.id = i.eventId
           WHERE e.deleted = 0 AND i.mergedInto IS NULL AND i.category IN (:categories) ORDER BY i.createdAt DESC, i.id DESC LIMIT 500""",
    )
    abstract fun itemsByCategory(categories: List<String>): Flow<List<MemoryItemEntity>>

    @Query("""SELECT i.* FROM memory_items i JOIN memory_events e ON e.id = i.eventId
        WHERE e.deleted = 0 AND i.mergedInto IS NULL AND i.status = 'Accepted' AND i.category IN ('待办', '备忘')
        ORDER BY i.sortOrder IS NULL, i.sortOrder, i.createdAt DESC, i.id DESC""")
    abstract fun inProgress(): Flow<List<MemoryItemEntity>>

    @Query("""SELECT i.* FROM memory_items i JOIN memory_events e ON e.id = i.eventId
        WHERE e.deleted = 0 AND i.mergedInto IS NULL AND i.status = 'Accepted' AND i.category = '约定'
        ORDER BY i.dueAt IS NULL, i.dueAt, i.createdAt DESC, i.id DESC""")
    abstract fun appointments(): Flow<List<MemoryItemEntity>>

    // 单条 UPDATE 中取最小值并写状态，避免并发接受时拿到同一个队首值。
    @Query("""UPDATE memory_items SET status = :status, statusChangedAt = :at,
        doneAt = CASE WHEN :status = 'Done' THEN :at ELSE NULL END,
        sortOrder = CASE WHEN :status = 'Accepted' THEN
            (SELECT COALESCE(MIN(i.sortOrder) - 1, 0) FROM memory_items i
             JOIN memory_events e ON e.id = i.eventId
             WHERE e.deleted = 0 AND i.mergedInto IS NULL AND i.status = 'Accepted' AND i.category IN ('待办', '备忘'))
            ELSE sortOrder END WHERE id = :id""")
    abstract suspend fun setItemStatus(id: Long, status: String, at: Long = System.currentTimeMillis())

    @Query("UPDATE memory_items SET sortOrder = :order WHERE id = :id")
    abstract suspend fun setSortOrder(id: Long, order: Long)

    @Transaction
    open suspend fun reorder(ids: List<Long>) {
        ids.distinct().forEachIndexed { index, id -> setSortOrder(id, index.toLong()) }
    }

    @Query("""SELECT i.* FROM memory_items i JOIN memory_events e ON e.id = i.eventId
        WHERE e.deleted = 0 AND i.mergedInto IS NULL AND i.status != 'Ignored' AND
        ((i.createdAt >= :start AND i.createdAt < :end) OR
         (i.dueAt >= :start AND i.dueAt < :end) OR (i.doneAt >= :start AND i.doneAt < :end))
        ORDER BY i.createdAt, i.id""")
    abstract fun dayItems(start: Long, end: Long): Flow<List<MemoryItemEntity>>

    @Query("SELECT startAt FROM memory_events WHERE deleted = 0 AND startAt >= :start AND startAt < :end")
    abstract fun activeEventTimes(start: Long, end: Long): Flow<List<Long>>

    @Query("""SELECT i.createdAt FROM memory_items i JOIN memory_events e ON e.id = i.eventId
        WHERE e.deleted = 0 AND i.mergedInto IS NULL AND i.createdAt >= :start AND i.createdAt < :end""")
    abstract fun activeItemTimes(start: Long, end: Long): Flow<List<Long>>

    @Query("""SELECT l.* FROM transcript_lines l JOIN segments s ON s.id = l.segmentId
        LEFT JOIN memory_events e ON e.id = l.eventId
        WHERE s.status = 'Done' AND l.startAt >= :start AND l.startAt < :end
        AND (e.id IS NULL OR e.deleted = 0) ORDER BY l.startAt, l.id""")
    abstract suspend fun diaryLines(start: Long, end: Long): List<TranscriptLineEntity>
    @Query("""SELECT MAX(at) FROM (
        SELECT startAt AS at FROM memory_events WHERE deleted = 0 AND (scene NOT IN ('媒体', '旁人') OR starred = 1)
        UNION ALL SELECT l.startAt AS at FROM transcript_lines l JOIN segments s ON s.id = l.segmentId
        LEFT JOIN memory_events e ON e.id = l.eventId WHERE :includeLines AND s.status = 'Done' AND l.text != '' AND (e.id IS NULL OR e.deleted = 0)
    )""")
    abstract suspend fun latestDiaryAt(includeLines: Boolean): Long?
    @Query("UPDATE memory_events SET important = :important WHERE id = :id") abstract suspend fun setImportant(id: Long, important: Boolean)
    @Query("UPDATE memory_events SET starred = :starred, starredAt = :at WHERE id = :id AND deleted = 0")
    protected abstract suspend fun updateStarred(id: Long, starred: Boolean, at: Long?)

    @Query("""SELECT i.id FROM memory_items i JOIN memory_events e ON e.id = i.eventId
        WHERE e.id = :id AND e.deleted = 0 AND i.mergedInto IS NULL
        AND i.category IN ('待办', '备忘') AND i.status = 'Pending' ORDER BY i.createdAt, i.id""")
    protected abstract suspend fun pendingStarredItems(id: Long): List<Long>

    @Transaction
    open suspend fun setStarred(id: Long, starred: Boolean, at: Long = System.currentTimeMillis()) {
        updateStarred(id, starred, if (starred) at else null)
        if (starred) pendingStarredItems(id).forEach { setItemStatus(it, "Accepted", at) }
    }

    @Query("SELECT * FROM memory_events WHERE deleted = 0 AND starred = 1 ORDER BY starredAt DESC, id DESC LIMIT 500")
    abstract fun starredEvents(): Flow<List<MemoryEventEntity>>

    @Query("""SELECT * FROM memory_items WHERE mergedInto IS NULL AND eventId IN
        (SELECT id FROM memory_events WHERE deleted = 0 AND starred = 1 ORDER BY starredAt DESC, id DESC LIMIT 500)""")
    abstract fun starredItems(): Flow<List<MemoryItemEntity>>

    @Query("SELECT * FROM memory_events WHERE deleted = 0 AND important = 1 ORDER BY startAt DESC, id DESC LIMIT 500")
    abstract fun importantEvents(): Flow<List<MemoryEventEntity>>

    @Query("""SELECT i.* FROM memory_items i JOIN memory_events e ON e.id = i.eventId
        WHERE e.deleted = 0 AND e.important = 1 AND i.mergedInto IS NULL
        AND e.id IN (SELECT id FROM memory_events WHERE deleted = 0 AND important = 1 ORDER BY startAt DESC, id DESC LIMIT 500)""")
    abstract fun importantItems(): Flow<List<MemoryItemEntity>>

    @Query("""SELECT i.mergedInto AS id, COUNT(*) + 1 AS repeats FROM memory_items i
        JOIN memory_events e ON e.id = i.eventId WHERE e.deleted = 0 AND i.mergedInto IS NOT NULL GROUP BY i.mergedInto""")
    abstract fun repeatCounts(): Flow<List<ItemRepeatCount>>

    @Query("""SELECT i.* FROM memory_items i JOIN memory_events e ON e.id = i.eventId
        WHERE e.deleted = 0 AND i.mergedInto = :id ORDER BY i.createdAt, i.id""")
    abstract fun mergedItems(id: Long): Flow<List<MemoryItemEntity>>

    @Query("UPDATE memory_items SET mergedInto = NULL, noMerge = 1 WHERE id = :id AND mergedInto IS NOT NULL")
    abstract suspend fun unmerge(id: Long)

    @Query("UPDATE memory_items SET mergedInto = :target WHERE id = :id OR mergedInto = :id")
    abstract suspend fun mergeInto(id: Long, target: Long)

    @Query("""SELECT i.* FROM memory_items i JOIN memory_events e ON e.id = i.eventId
        WHERE e.deleted = 0 AND i.mergedInto IS NOT NULL AND NOT EXISTS
        (SELECT 1 FROM memory_items p JOIN memory_events pe ON pe.id = p.eventId WHERE p.id = i.mergedInto AND pe.deleted = 0)
        ORDER BY i.createdAt, i.id""")
    abstract suspend fun orphanedMergedItems(): List<MemoryItemEntity>

    @Query("UPDATE memory_items SET mergedInto = :target WHERE id = :id")
    abstract suspend fun setMergeParent(id: Long, target: Long?)

    /** 删除整批完成后再选幸存主条，避免选到同批待删条目。调用方与删除共用事务。 */
    @Transaction
    open suspend fun repairMergedItems() {
        orphanedMergedItems().groupBy { it.mergedInto }.values.forEach { survivors ->
            val first = survivors.first().id
            survivors.forEach { setMergeParent(it.id, if (it.id == first) null else first) }
        }
    }

    @Query("UPDATE memory_events SET deleted = 1 WHERE id = :id")
    abstract suspend fun hideEvent(id: Long)

    @Transaction
    open suspend fun deleteEvent(id: Long) {
        hideEvent(id)
        repairMergedItems()
    }
}
