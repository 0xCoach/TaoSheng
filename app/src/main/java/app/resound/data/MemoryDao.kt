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
    val editedAt: Long? = null,
    val originalTitle: String? = null,
    val originalSummary: String? = null,
    val originalPointsJson: String? = null,
)

/** 提取项：待办、约定、备忘、灵感、关键信息、决定、知识。 */
@Entity(
    tableName = "memory_items",
    indices = [Index("eventId"), Index("status"), Index("dueAt")],
    foreignKeys = [ForeignKey(entity = MemoryEventEntity::class, parentColumns = ["id"], childColumns = ["eventId"], onDelete = ForeignKey.CASCADE)],
)
data class MemoryItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val eventId: Long?,
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
    @ColumnInfo(defaultValue = "0") val deleted: Boolean = false,
    val editedAt: Long? = null,
    val doneHintLineId: Long? = null,
    val doneHintAt: Long? = null,
    @ColumnInfo(defaultValue = "'[]'") val dismissedDoneLinesJson: String = "[]",
    @ColumnInfo(defaultValue = "0") val reminderVersion: Long = 0,
    @ColumnInfo(defaultValue = "'digest'") val origin: String = "digest",
    val sourceText: String? = null,
    /** 到点强提醒：弹出、响铃、震动（用户在待办卡或编辑里打开） */
    @ColumnInfo(defaultValue = "0") val strongReminder: Boolean = false,
)

data class ItemRepeatCount(val id: Long, val repeats: Int)

/** 兼容现有每日总量消费者，查询时跨用途求和。 */
data class LlmUsageEntity(val day: String, val promptTokens: Long, val completionTokens: Long, val calls: Int)

@Entity(tableName = "llm_usage_v2", primaryKeys = ["day", "purpose"])
data class LlmUsageV2Entity(val day: String, val purpose: String, val promptTokens: Long, val completionTokens: Long, val calls: Int)

@Dao
abstract class MemoryDao {
    @Query("SELECT * FROM memory_events WHERE id = :id AND deleted = 0")
    abstract suspend fun event(id: Long): MemoryEventEntity?

    @Query("""UPDATE memory_events SET
        originalTitle = CASE WHEN editedAt IS NULL THEN title ELSE originalTitle END,
        originalSummary = CASE WHEN editedAt IS NULL THEN summary ELSE originalSummary END,
        originalPointsJson = CASE WHEN editedAt IS NULL THEN pointsJson ELSE originalPointsJson END,
        title = :title, summary = :summary, pointsJson = :points, editedAt = :at
        WHERE id = :id AND deleted = 0""")
    protected abstract suspend fun editEventRaw(id: Long, title: String, summary: String, points: String, at: Long)

    @Query("UPDATE memory_events SET pointsJson = :points, originalPointsJson = :original WHERE id = :id")
    protected abstract suspend fun normalizeEventPoints(id: Long, points: String, original: String?)

    private fun validPoints(text: String): String = try { org.json.JSONArray(text).toString() } catch (_: org.json.JSONException) { "[]" }

    @Transaction open suspend fun editEvent(id: Long, title: String, summary: String, points: String, at: Long) {
        val row = event(id) ?: return
        normalizeEventPoints(id, validPoints(row.pointsJson), row.originalPointsJson?.let(::validPoints))
        editEventRaw(id, title, summary, validPoints(points), at)
    }

    @Query("""UPDATE memory_events SET title = originalTitle, summary = originalSummary,
        pointsJson = originalPointsJson, editedAt = NULL, originalTitle = NULL,
        originalSummary = NULL, originalPointsJson = NULL
        WHERE id = :id AND deleted = 0 AND editedAt IS NOT NULL
        AND originalTitle IS NOT NULL AND originalSummary IS NOT NULL AND originalPointsJson IS NOT NULL""")
    protected abstract suspend fun restoreEventRaw(id: Long)

    @Transaction open suspend fun restoreEvent(id: Long) {
        val row = event(id) ?: return
        normalizeEventPoints(id, validPoints(row.pointsJson), row.originalPointsJson?.let(::validPoints))
        restoreEventRaw(id)
    }

    // ---------- 整理 ----------
    /** 已转写、还没整理过的句子（按时间） */
    @Query(
        """SELECT l.* FROM transcript_lines l JOIN segments s ON s.id = l.segmentId
           WHERE l.digestedAt IS NULL AND s.status = 'Done' AND l.voiceInputPendingUntil IS NULL ORDER BY l.startAt, l.id""",
    )
    abstract suspend fun undigestedLines(): List<TranscriptLineEntity>

    @Query("SELECT COUNT(*) FROM segments WHERE id IN (:ids) AND (separateSpeakers IS NULL OR separateSpeakers = 1) AND voiceprintProcessedAt IS NULL")
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
    @Insert protected abstract suspend fun insertManualItem(row: MemoryItemEntity): Long
    @Transaction
    open suspend fun addManualItems(rows: List<MemoryItemEntity>) {
        // 逆序插入再接受，使用户确认页的顺序保留在队首。
        rows.asReversed().forEach { row ->
            val id = insertManualItem(row)
            setItemStatus(id, "Accepted", row.createdAt)
        }
    }

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
        doneHints: List<Pair<Long, Long>> = emptyList(),
        hintCandidates: Set<Long> = emptySet(),
    ) {
        // 云端请求期间原话可能被隐私操作删除；整批旧结果作废，不复活事件和条目。
        if (refreshLines(allLineIds).size != allLineIds.distinct().size) return
        // 必须在事务内重读：云端请求期间用户可能删除或编辑。
        val protected = protectedItems()
        val sourceEvents = refreshLines(allLineIds).associate { it.id to it.eventId }
        val oldEvents = sourceEvents.values.filterNotNull().toSet()
        val ids = events.map { (event, lineIds) ->
            insertEvent(event).also { id -> if (lineIds.isNotEmpty()) assignLines(lineIds, id) }
        }
        val rows = items(ids).filterNot { row -> protected.any { old ->
            (old.category == row.category || old.category in setOf("待办", "约定", "备忘") && row.category in setOf("待办", "约定", "备忘")) && (
                (row.lineId != null && row.lineId == old.lineId) ||
                old.content.trim() == row.content.trim() ||
                // 同一旧事件内换行或换措辞时保守保护；缺失行号则按本批旧事件保护。
                (old.eventId != null && sourceEvents[row.lineId] == old.eventId) || (row.lineId == null && old.eventId in oldEvents)
            )
        } }
        if (rows.isNotEmpty()) insertItems(rows)
        doneHints.filter { it.first in hintCandidates && it.second in allLineIds }.forEach { (id, line) ->
            val row = itemIncludingDeleted(id) ?: return@forEach
            val source = refreshLines(listOf(line)).singleOrNull() ?: return@forEach
            val dismissed = org.json.JSONArray(row.dismissedDoneLinesJson)
            if ((0 until dismissed.length()).none { dismissed.optLong(it) == line } && source.startAt > row.createdAt)
                writeDoneHint(id, line, source.startAt)
        }
        markDigested(allLineIds, at)
    }

    // 内部保护/修改读取有意包含墓碑，不能作为展示或模型输入。
    @Query("SELECT * FROM memory_items WHERE deleted = 1 OR editedAt IS NOT NULL OR mergedInto IN (SELECT id FROM memory_items WHERE deleted = 1)")
    abstract suspend fun protectedItems(): List<MemoryItemEntity>
    @Query("SELECT * FROM memory_items WHERE id = :id")
    abstract suspend fun itemIncludingDeleted(id: Long): MemoryItemEntity?

    @Query("""SELECT i.* FROM memory_items i LEFT JOIN memory_events e ON e.id = i.eventId
        WHERE (e.id IS NULL OR e.deleted = 0) AND i.deleted = 0 AND i.mergedInto IS NULL
        AND i.category IN ('待办', '约定') AND i.status IN ('Pending', 'Accepted')
        ORDER BY i.createdAt DESC, i.id DESC LIMIT 50""")
    abstract suspend fun unfinishedForDigest(): List<MemoryItemEntity>
    @Query("""SELECT i.* FROM memory_items i LEFT JOIN memory_events e ON e.id = i.eventId
        WHERE (e.id IS NULL OR e.deleted = 0) AND i.deleted = 0 AND i.status = 'Ignored' AND i.statusChangedAt >= :since
        ORDER BY i.statusChangedAt DESC, i.id DESC LIMIT 30""")
    abstract suspend fun ignoredForDigest(since: Long): List<MemoryItemEntity>

    @Query("""UPDATE memory_items SET doneHintLineId = :line, doneHintAt = :at
        WHERE id = :id AND deleted = 0 AND mergedInto IS NULL AND status IN ('Pending', 'Accepted')
        AND category IN ('待办', '约定') AND doneHintLineId IS NULL
        AND (eventId IS NULL OR eventId IN (SELECT id FROM memory_events WHERE deleted = 0))""")
    protected abstract suspend fun writeDoneHint(id: Long, line: Long, at: Long)
    @Query("SELECT l.* FROM transcript_lines l LEFT JOIN memory_events e ON e.id = l.eventId WHERE (e.id IS NULL OR e.deleted = 0) AND l.id IN (SELECT doneHintLineId FROM memory_items WHERE deleted = 0)")
    abstract fun doneHintLines(): Flow<List<TranscriptLineEntity>>
    @Query("UPDATE memory_items SET doneHintLineId = NULL, doneHintAt = NULL, dismissedDoneLinesJson = :lines WHERE id = :id")
    protected abstract suspend fun clearDoneHint(id: Long, lines: String)
    @Transaction
    open suspend fun dismissDoneHint(id: Long) {
        val row = itemIncludingDeleted(id) ?: return
        val line = row.doneHintLineId ?: return
        val dismissed = org.json.JSONArray(row.dismissedDoneLinesJson).put(line)
        clearDoneHint(id, dismissed.toString())
    }

    @Query("UPDATE memory_items SET strongReminder = :on WHERE id = :id")
    abstract suspend fun setStrongReminder(id: Long, on: Boolean)

    @Query("""UPDATE memory_items SET deleted = 1, doneHintLineId = NULL, doneHintAt = NULL
        WHERE id = :id""")
    abstract suspend fun deleteItem(id: Long)

    @Query("""UPDATE memory_items SET content = :content, dueAt = :dueAt, dueText = :dueText, who = :who,
        editedAt = :at, reminderVersion = reminderVersion + CASE WHEN dueAt IS NOT :dueAt OR dueText IS NOT :dueText THEN 1 ELSE 0 END
        WHERE id = :id AND deleted = 0""")
    protected abstract suspend fun updateItemRow(id: Long, content: String, dueAt: Long?, dueText: String?, who: String?, at: Long)
    open suspend fun updateItem(id: Long, content: String, dueAt: Long?, hasTime: Boolean, who: String?, at: Long = System.currentTimeMillis()) {
        require(content.isNotBlank())
        val date = dueAt?.let { java.time.Instant.ofEpochMilli(it).atZone(java.time.ZoneId.systemDefault()) }
        val normalized = if (hasTime) dueAt else date?.toLocalDate()?.atStartOfDay(date.zone)?.toInstant()?.toEpochMilli()
        val text = date?.format(java.time.format.DateTimeFormatter.ofPattern(if (hasTime) "yyyy-MM-dd HH:mm" else "yyyy-MM-dd"))
        updateItemRow(id, content.trim(), normalized, text, who?.trim()?.takeIf { it.isNotEmpty() }, at)
    }
    @Transaction
    open suspend fun setItemsStatus(ids: List<Long>, status: String, at: Long = System.currentTimeMillis()) {
        ids.distinct().forEach { setItemStatus(it, status, at) }
    }

    @Query("""SELECT i.* FROM memory_items i LEFT JOIN memory_events e ON e.id = i.eventId
        WHERE (e.id IS NULL OR e.deleted = 0) AND i.deleted = 0 AND i.mergedInto IS NULL AND i.status = 'Accepted'
        AND i.category IN ('待办', '约定', '备忘') ORDER BY i.sortOrder IS NULL, i.sortOrder, i.createdAt DESC, i.id DESC""")
    abstract fun ongoing(): Flow<List<MemoryItemEntity>>
    @Query("""SELECT i.* FROM memory_items i LEFT JOIN memory_events e ON e.id = i.eventId
        WHERE (e.id IS NULL OR e.deleted = 0) AND i.deleted = 0 AND i.mergedInto IS NULL AND i.status = 'Done'
        AND i.category IN ('待办', '约定', '备忘') AND i.doneAt >= :from ORDER BY i.doneAt DESC, i.id DESC""")
    abstract fun doneItems(from: Long): Flow<List<MemoryItemEntity>>

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

    /** 按句子开始时刻归日；与媒体时间段有重叠的整句排除（同 dayRhythm）。
     * 查询依赖两张表，原话或事件变化都会使 Flow 更新；跨日媒体也参与排除。
     */
    @Query("""SELECT l.* FROM transcript_lines l
        LEFT JOIN memory_events e ON e.id = l.eventId
        WHERE l.startAt >= :start AND l.startAt < :end AND (e.id IS NULL OR e.deleted = 0)
        AND NOT EXISTS (SELECT 1 FROM memory_events m WHERE m.deleted = 0 AND m.scene = '媒体'
            AND l.startAt < m.endAt AND l.endAt > m.startAt)
        ORDER BY l.startAt, l.id""")
    abstract fun rhythmLines(start: Long, end: Long): Flow<List<TranscriptLineEntity>>

    // ---------- 界面 ----------
    @Query("SELECT * FROM memory_events WHERE deleted = 0 AND startAt >= :start AND startAt < :end ORDER BY startAt DESC")
    abstract fun events(start: Long, end: Long): Flow<List<MemoryEventEntity>>

    @Query("SELECT * FROM memory_items WHERE deleted = 0 AND mergedInto IS NULL AND eventId IN (SELECT id FROM memory_events WHERE deleted = 0 AND startAt >= :start AND startAt < :end)")
    abstract fun itemsBetween(start: Long, end: Long): Flow<List<MemoryItemEntity>>

    @Query("SELECT * FROM transcript_lines WHERE eventId = :eventId ORDER BY startAt, id")
    abstract fun eventLines(eventId: Long): Flow<List<TranscriptLineEntity>>

    @Query(
        """SELECT i.* FROM memory_items i LEFT JOIN memory_events e ON e.id = i.eventId
           WHERE (e.id IS NULL OR e.deleted = 0) AND i.deleted = 0 AND i.mergedInto IS NULL AND i.status = 'Pending' AND i.category IN ('待办', '约定', '备忘')
           ORDER BY i.createdAt DESC""",
    )
    abstract fun pendingItems(): Flow<List<MemoryItemEntity>>

    @Query(
        """SELECT i.* FROM memory_items i LEFT JOIN memory_events e ON e.id = i.eventId
           WHERE (e.id IS NULL OR e.deleted = 0) AND i.deleted = 0 AND i.mergedInto IS NULL AND i.category IN (:categories) ORDER BY i.createdAt DESC, i.id DESC LIMIT 500""",
    )
    abstract fun itemsByCategory(categories: List<String>): Flow<List<MemoryItemEntity>>

    @Query("""SELECT i.* FROM memory_items i LEFT JOIN memory_events e ON e.id = i.eventId
        WHERE (e.id IS NULL OR e.deleted = 0) AND i.deleted = 0 AND i.mergedInto IS NULL AND i.status = 'Accepted' AND i.category IN ('待办', '备忘')
        ORDER BY i.sortOrder IS NULL, i.sortOrder, i.createdAt DESC, i.id DESC""")
    abstract fun inProgress(): Flow<List<MemoryItemEntity>>

    @Query("""SELECT i.* FROM memory_items i LEFT JOIN memory_events e ON e.id = i.eventId
        WHERE (e.id IS NULL OR e.deleted = 0) AND i.deleted = 0 AND i.mergedInto IS NULL AND i.status = 'Accepted' AND i.category = '约定'
        ORDER BY i.dueAt IS NULL, i.dueAt, i.createdAt DESC, i.id DESC""")
    abstract fun appointments(): Flow<List<MemoryItemEntity>>

    // 单条 UPDATE 中取最小值并写状态，避免并发接受时拿到同一个队首值。
    @Query("""UPDATE memory_items SET status = :status, statusChangedAt = :at,
        doneHintLineId = CASE WHEN :status IN ('Done', 'Ignored') THEN NULL ELSE doneHintLineId END,
        doneHintAt = CASE WHEN :status IN ('Done', 'Ignored') THEN NULL ELSE doneHintAt END,
        doneAt = CASE WHEN :status = 'Done' THEN :at ELSE NULL END,
        sortOrder = CASE WHEN :status = 'Accepted' THEN
            (SELECT COALESCE(MIN(i.sortOrder) - 1, 0) FROM memory_items i
             LEFT JOIN memory_events e ON e.id = i.eventId
             WHERE (e.id IS NULL OR e.deleted = 0) AND i.deleted = 0 AND i.mergedInto IS NULL AND i.status = 'Accepted' AND i.category IN ('待办', '约定', '备忘'))
            ELSE sortOrder END WHERE id = :id AND deleted = 0""")
    abstract suspend fun setItemStatus(id: Long, status: String, at: Long = System.currentTimeMillis())

    @Query("UPDATE memory_items SET sortOrder = :order WHERE id = :id")
    abstract suspend fun setSortOrder(id: Long, order: Long)

    @Transaction
    open suspend fun reorder(ids: List<Long>) {
        ids.distinct().forEachIndexed { index, id -> setSortOrder(id, index.toLong()) }
    }

    @Query("""SELECT i.* FROM memory_items i LEFT JOIN memory_events e ON e.id = i.eventId
        WHERE (e.id IS NULL OR e.deleted = 0) AND i.deleted = 0 AND i.mergedInto IS NULL AND i.status != 'Ignored' AND
        ((i.createdAt >= :start AND i.createdAt < :end) OR
         (i.dueAt >= :start AND i.dueAt < :end) OR (i.doneAt >= :start AND i.doneAt < :end))
        ORDER BY i.createdAt, i.id""")
    abstract fun dayItems(start: Long, end: Long): Flow<List<MemoryItemEntity>>

    @Query("SELECT startAt FROM memory_events WHERE deleted = 0 AND startAt >= :start AND startAt < :end")
    abstract fun activeEventTimes(start: Long, end: Long): Flow<List<Long>>

    @Query("""SELECT i.createdAt FROM memory_items i LEFT JOIN memory_events e ON e.id = i.eventId
        WHERE (e.id IS NULL OR e.deleted = 0) AND i.deleted = 0 AND i.mergedInto IS NULL AND i.createdAt >= :start AND i.createdAt < :end""")
    abstract fun activeItemTimes(start: Long, end: Long): Flow<List<Long>>

    @Query("""SELECT l.* FROM transcript_lines l JOIN segments s ON s.id = l.segmentId
        LEFT JOIN memory_events e ON e.id = l.eventId
        WHERE s.status = 'Done' AND l.startAt >= :start AND l.startAt < :end
        AND (e.id IS NULL OR e.deleted = 0) ORDER BY l.startAt, l.id""")
    abstract suspend fun diaryLines(start: Long, end: Long): List<TranscriptLineEntity>
    @Query("""SELECT MAX(at) FROM (
        SELECT startAt AS at FROM memory_events WHERE deleted = 0 AND (scene != '媒体' OR starred = 1)
        UNION ALL SELECT l.startAt AS at FROM transcript_lines l JOIN segments s ON s.id = l.segmentId
        LEFT JOIN memory_events e ON e.id = l.eventId WHERE :includeLines AND s.status = 'Done' AND l.text != '' AND (e.id IS NULL OR e.deleted = 0)
    )""")
    abstract suspend fun latestDiaryAt(includeLines: Boolean): Long?
    @Query("UPDATE memory_events SET important = :important WHERE id = :id") abstract suspend fun setImportant(id: Long, important: Boolean)
    @Query("UPDATE memory_events SET starred = :starred, starredAt = :at WHERE id = :id AND deleted = 0")
    protected abstract suspend fun updateStarred(id: Long, starred: Boolean, at: Long?)

    @Query("""SELECT i.id FROM memory_items i LEFT JOIN memory_events e ON e.id = i.eventId
        WHERE e.id = :id AND (e.id IS NULL OR e.deleted = 0) AND i.deleted = 0 AND i.mergedInto IS NULL
        AND i.category IN ('待办', '备忘') AND i.status = 'Pending' ORDER BY i.createdAt, i.id""")
    protected abstract suspend fun pendingStarredItems(id: Long): List<Long>

    @Transaction
    open suspend fun setStarred(id: Long, starred: Boolean, at: Long = System.currentTimeMillis()) {
        updateStarred(id, starred, if (starred) at else null)
        if (starred) pendingStarredItems(id).forEach { setItemStatus(it, "Accepted", at) }
    }

    @Query("SELECT * FROM memory_events WHERE deleted = 0 AND starred = 1 ORDER BY starredAt DESC, id DESC LIMIT 500")
    abstract fun starredEvents(): Flow<List<MemoryEventEntity>>

    @Query("""SELECT * FROM memory_items WHERE deleted = 0 AND mergedInto IS NULL AND eventId IN
        (SELECT id FROM memory_events WHERE deleted = 0 AND starred = 1 ORDER BY starredAt DESC, id DESC LIMIT 500)""")
    abstract fun starredItems(): Flow<List<MemoryItemEntity>>

    @Query("SELECT * FROM memory_events WHERE deleted = 0 AND important = 1 ORDER BY startAt DESC, id DESC LIMIT 500")
    abstract fun importantEvents(): Flow<List<MemoryEventEntity>>

    @Query("""SELECT i.* FROM memory_items i LEFT JOIN memory_events e ON e.id = i.eventId
        WHERE (e.id IS NULL OR e.deleted = 0) AND i.deleted = 0 AND e.important = 1 AND i.mergedInto IS NULL
        AND e.id IN (SELECT id FROM memory_events WHERE deleted = 0 AND important = 1 ORDER BY startAt DESC, id DESC LIMIT 500)""")
    abstract fun importantItems(): Flow<List<MemoryItemEntity>>

    @Query("""SELECT i.mergedInto AS id, COUNT(*) + 1 AS repeats FROM memory_items i
        LEFT JOIN memory_events e ON e.id = i.eventId WHERE (e.id IS NULL OR e.deleted = 0) AND i.deleted = 0 AND i.mergedInto IS NOT NULL AND EXISTS (SELECT 1 FROM memory_items p JOIN memory_events pe ON pe.id = p.eventId WHERE p.id = i.mergedInto AND p.deleted = 0 AND pe.deleted = 0) GROUP BY i.mergedInto""")
    abstract fun repeatCounts(): Flow<List<ItemRepeatCount>>

    @Query("""SELECT i.* FROM memory_items i LEFT JOIN memory_events e ON e.id = i.eventId
        WHERE (e.id IS NULL OR e.deleted = 0) AND i.deleted = 0 AND i.mergedInto = :id AND EXISTS (SELECT 1 FROM memory_items p WHERE p.id = :id AND p.deleted = 0) ORDER BY i.createdAt, i.id""")
    abstract fun mergedItems(id: Long): Flow<List<MemoryItemEntity>>

    @Query("UPDATE memory_items SET mergedInto = NULL, noMerge = 1 WHERE id = :id AND deleted = 0 AND mergedInto IS NOT NULL AND EXISTS (SELECT 1 FROM memory_items p WHERE p.id = memory_items.mergedInto AND p.deleted = 0)")
    abstract suspend fun unmerge(id: Long)

    @Query("UPDATE memory_items SET mergedInto = :target WHERE deleted = 0 AND editedAt IS NULL AND (id = :id OR mergedInto = :id) AND :id != :target AND EXISTS (SELECT 1 FROM memory_items src WHERE src.id = :id AND src.deleted = 0 AND src.editedAt IS NULL) AND EXISTS (SELECT 1 FROM memory_items p WHERE p.id = :target AND p.deleted = 0 AND p.mergedInto IS NULL)")
    abstract suspend fun mergeInto(id: Long, target: Long)

    @Query("""SELECT i.* FROM memory_items i LEFT JOIN memory_events e ON e.id = i.eventId
        WHERE (e.id IS NULL OR e.deleted = 0) AND i.deleted = 0 AND i.mergedInto IS NOT NULL AND NOT EXISTS (SELECT 1 FROM memory_items d WHERE d.id = i.mergedInto AND d.deleted = 1)
        AND NOT EXISTS
        (SELECT 1 FROM memory_items p JOIN memory_events pe ON pe.id = p.eventId WHERE p.id = i.mergedInto AND pe.deleted = 0 AND p.deleted = 0)
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
