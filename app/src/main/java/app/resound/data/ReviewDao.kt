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

data class ReviewInput(val events: List<MemoryEventEntity>, val items: List<MemoryItemEntity>)

@Dao
abstract class ReviewDao {
    @Query("SELECT * FROM reviews WHERE type = 'daily' AND startDate = :date AND endDate = :date LIMIT 1")
    abstract fun daily(date: String): Flow<ReviewEntity?>

    @Query("SELECT * FROM reviews WHERE type = 'daily' ORDER BY startDate DESC LIMIT 30")
    abstract fun recent(): Flow<List<ReviewEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun save(row: ReviewEntity)

    // 事件按开始时刻归属；两端与关联提取项一致，避免相邻回顾重叠或遗漏。
    @Query("SELECT * FROM memory_events WHERE deleted = 0 AND scene NOT IN ('媒体', '旁人') AND startAt > :start AND startAt <= :end ORDER BY startAt, id")
    abstract suspend fun events(start: Long, end: Long): List<MemoryEventEntity>

    @Query("SELECT i.* FROM memory_items i JOIN memory_events e ON e.id = i.eventId WHERE e.deleted = 0 AND i.mergedInto IS NULL AND e.scene NOT IN ('媒体', '旁人') AND e.startAt > :start AND e.startAt <= :end ORDER BY i.createdAt, i.id")
    abstract suspend fun items(start: Long, end: Long): List<MemoryItemEntity>

    @Transaction
    open suspend fun input(start: Long, end: Long) = ReviewInput(events(start, end), items(start, end))
}
