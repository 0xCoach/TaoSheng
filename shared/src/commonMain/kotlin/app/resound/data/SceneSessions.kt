package app.resound.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

/*
 * R4 场景会话：一段“同一场景”的起止记录，供录音完整性视图回答
 * “这段时间为什么没录 / 处于什么策略”。
 * 麦克风占用沿用 watchdog MicBusy/MicReleased 事件（带归因），会议沿用 topic_spans，本表不重复记。
 */
@Entity(tableName = "scene_sessions", indices = [Index("startedAt"), Index("kind")])
data class SceneSessionRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** RecordingScene.name：PrivacyPaused / Paused / QuietHours / ResourceLimited */
    val kind: String,
    /** SceneSource.name：Manual / Rule / System */
    val source: String,
    val startedAt: Long,
    val endedAt: Long? = null,
    /** 触发原因：停录时段名 / 资源受限说明等 */
    val reason: String? = null,
)

@Dao
abstract class SceneSessionDao {
    @Insert abstract suspend fun insert(row: SceneSessionRow): Long

    @Query("SELECT * FROM scene_sessions WHERE endedAt IS NULL ORDER BY startedAt DESC LIMIT 1")
    abstract suspend fun openSession(): SceneSessionRow?

    @Query("UPDATE scene_sessions SET endedAt = :at WHERE id = :id AND endedAt IS NULL")
    abstract suspend fun close(id: Long, at: Long)

    @Query("SELECT * FROM scene_sessions ORDER BY startedAt DESC LIMIT :limit")
    abstract fun recent(limit: Int): Flow<List<SceneSessionRow>>

    @Query("SELECT * FROM scene_sessions WHERE startedAt >= :start AND startedAt < :end ORDER BY startedAt")
    abstract suspend fun between(start: Long, end: Long): List<SceneSessionRow>
}
