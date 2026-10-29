package app.resound.envsound

import androidx.room.*
import app.resound.data.SegmentEntity
import app.resound.data.MemoryEventEntity
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "env_sounds", foreignKeys = [ForeignKey(entity = SegmentEntity::class, parentColumns = ["id"], childColumns = ["segmentId"], onDelete = ForeignKey.CASCADE)], indices = [Index("startAt")])
data class EnvSoundRow(@PrimaryKey val segmentId: Long, val startAt: Long, val endAt: Long,
    val path: String, val verdict: String, val reason: String?, val tags: String, val sounds: String,
    val scores: String, val skipped: Boolean, val markMedia: Boolean, val restored: Boolean = false,
    val reportedWrong: Boolean = false, val elapsedMs: Long = 0)

@Dao
interface EnvSoundDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insert(row: EnvSoundRow)
    @Query("SELECT * FROM env_sounds WHERE segmentId = :id") suspend fun get(id: Long): EnvSoundRow?
    @Query("SELECT * FROM env_sounds ORDER BY startAt") fun observe(): Flow<List<EnvSoundRow>>
    @Query("SELECT * FROM memory_events WHERE deleted = 0 ORDER BY startAt DESC") fun events(): Flow<List<MemoryEventEntity>>
    @Query("UPDATE env_sounds SET restored = 1 WHERE segmentId = :id") suspend fun restored(id: Long)
    @Query("UPDATE env_sounds SET reportedWrong = 1, reason = CASE WHEN verdict IN ('Skipped','Skippable') THEN NULL ELSE reason END, verdict = CASE WHEN verdict IN ('Skipped','Skippable') THEN 'Keep' ELSE verdict END WHERE segmentId = :id") suspend fun wrong(id: Long)
    @Query("UPDATE segments SET status = 'Pending', attempts = 0, completedAt = NULL, error = NULL, nextRetryAt = NULL, firstFailedAt = NULL WHERE id = :id AND status = 'SkippedEnv'") suspend fun requeue(id: Long): Int
    @Query("UPDATE segments SET status = 'Pending', attempts = 0, completedAt = NULL, error = NULL, nextRetryAt = NULL, firstFailedAt = NULL WHERE id = :id AND status IN ('SkippedEnv', 'Failed', 'Quiet', 'Abandoned')") suspend fun requeueCorrection(id: Long): Int
    @Query("SELECT EXISTS(SELECT 1 FROM env_sounds WHERE startAt < :end AND endAt > :start AND markMedia = 1 AND reportedWrong = 0)") suspend fun media(start: Long, end: Long): Boolean
}
