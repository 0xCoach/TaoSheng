package app.resound.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "reviews", indices = [Index(value = ["type", "startDate", "endDate"], unique = true)])
data class ReviewEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: String = "daily",
    val startDate: String,
    val endDate: String,
    val brief: String,
    val sectionsJson: String,
    val generatedAt: Long,
    val model: String,
)

data class ReviewInput(
    val events: List<MemoryEventEntity>,
    val items: List<MemoryItemEntity>,
    val starredStale: List<MemoryEventEntity> = emptyList(),
)

data class ReviewEventVector(val refId: Long, val startAt: Long, val model: String, val dim: Int, val vector: ByteArray)

@Dao
abstract class ReviewDao {
    // 只订阅失效通知；内容规则仍由 reviewInput 唯一维护，向量变化也会影响历史重点。
    @RawQuery(observedEntities = [MemoryEventEntity::class, MemoryItemEntity::class,
        app.resound.search.EmbeddingEntity::class])
    abstract fun materialChanges(query: androidx.sqlite.db.SupportSQLiteQuery): Flow<Int>

    @Query("SELECT * FROM reviews WHERE type = 'daily' AND startDate = :date AND endDate = :date LIMIT 1")
    abstract fun daily(date: String): Flow<ReviewEntity?>

    @Query("SELECT * FROM reviews WHERE type = 'daily' ORDER BY startDate DESC LIMIT 30")
    abstract fun recent(): Flow<List<ReviewEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun save(row: ReviewEntity)

    // 事件按开始时刻归属；两端与关联提取项一致，避免相邻回顾重叠或遗漏。
    @Query("SELECT * FROM memory_events WHERE deleted = 0 AND (scene != '媒体' OR starred = 1) AND startAt > :start AND startAt <= :end ORDER BY startAt, id")
    abstract suspend fun events(start: Long, end: Long): List<MemoryEventEntity>

    @Query("SELECT i.* FROM memory_items i LEFT JOIN memory_events e ON e.id = i.eventId WHERE (e.id IS NULL OR e.deleted = 0) AND i.deleted = 0 AND i.mergedInto IS NULL AND (e.id IS NULL OR e.scene != '媒体' OR e.starred = 1) AND COALESCE(e.startAt,i.createdAt) > :start AND COALESCE(e.startAt,i.createdAt) <= :end ORDER BY i.createdAt, i.id")
    abstract suspend fun items(start: Long, end: Long): List<MemoryItemEntity>

    @Query("""SELECT e.* FROM memory_events e WHERE e.deleted = 0 AND e.starred = 1
        AND e.starredAt <= :before AND EXISTS
        (SELECT 1 FROM memory_items i WHERE i.deleted = 0 AND i.eventId = e.id AND i.mergedInto IS NULL
         AND i.category IN ('待办', '约定') AND i.status IN ('Accepted', 'Pending'))
        AND NOT EXISTS (SELECT 1 FROM memory_items i WHERE i.deleted = 0 AND i.eventId = e.id
            AND i.doneAt > e.starredAt AND i.doneAt <= :end)
        ORDER BY e.starredAt, e.id""")
    abstract suspend fun staleCandidates(before: Long, end: Long): List<MemoryEventEntity>

    @Query("""SELECT v.refId, e.startAt, v.model, v.dim, v.vector FROM embeddings v
        JOIN memory_events e ON e.id = v.refId WHERE v.kind = 'event' AND e.deleted = 0
        AND (e.id IN (:ids) OR (e.startAt > :after AND e.startAt <= :end))""")
    abstract suspend fun progressVectors(ids: List<Long>, after: Long, end: Long): List<ReviewEventVector>

    suspend fun stale(end: Long): List<MemoryEventEntity> {
        val candidates = staleCandidates(end - 3 * 24 * 60 * 60 * 1000L, end)
        if (candidates.isEmpty()) return emptyList()
        val vectors = progressVectors(candidates.map { it.id }, candidates.minOf { it.starredAt!! }, end)
        val decoded = vectors.mapNotNull { row ->
            runCatching { row to app.resound.search.Vectors.normalize(app.resound.search.Vectors.decode(row.vector, row.dim)) }.getOrNull()
        }
        return candidates.filter { event ->
            val own = decoded.firstOrNull { it.first.refId == event.id }
            own == null || decoded.none { (row, vector) ->
                row.refId != event.id && row.startAt > event.starredAt!! && row.startAt <= end &&
                    row.model == own.first.model && app.resound.search.Vectors.cosine(own.second, vector) >= 0.80
            }
        }.take(3)
    }

    @Transaction
    open suspend fun input(start: Long, end: Long) = ReviewInput(events(start, end), items(start, end))

    @Transaction
    open suspend fun reviewInput(start: Long, end: Long) = ReviewInput(events(start, end), items(start, end), stale(end))
}
