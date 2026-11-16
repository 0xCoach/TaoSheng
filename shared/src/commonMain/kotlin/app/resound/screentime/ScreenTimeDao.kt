package app.resound.screentime

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert

/** 屏幕时间读写。读四个方法是查询侧固定消费的签名；写方法只服务拉取与隐私删除。 */
@Dao
interface ScreenTimeDao {
    @Query("SELECT * FROM screen_events WHERE at >= :from AND at < :until ORDER BY at, id")
    suspend fun events(from: Long, until: Long): List<ScreenEventRow>

    @Query("SELECT * FROM screen_app_names ORDER BY pkg")
    suspend fun names(): List<ScreenAppNameRow>

    @Query("SELECT * FROM screen_pull_state WHERE source = :source")
    suspend fun state(source: String): ScreenPullStateRow?

    /** 与 [from, until) 有重叠的缺口。 */
    @Query("SELECT * FROM screen_coverage_gaps WHERE source = :source AND fromAt < :until AND untilAt > :from ORDER BY fromAt, id")
    suspend fun gaps(source: String, from: Long, until: Long): List<ScreenCoverageGapRow>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertEvents(events: List<ScreenEventRow>)

    @Upsert
    suspend fun upsertNames(names: List<ScreenAppNameRow>)

    /** 行已存在返回 -1；由此区分首建与推进。 */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertStateRow(row: ScreenPullStateRow): Long

    @Query("UPDATE screen_pull_state SET coveredUntil = MAX(COALESCE(coveredUntil, 0), :value) WHERE source = :source")
    suspend fun updateCoveredUntil(source: String, value: Long)

    /** 覆盖指针只增不减；行不存在时首建。 */
    @Transaction
    suspend fun advanceCoveredUntil(source: String, value: Long) {
        if (insertStateRow(ScreenPullStateRow(source, value, 0)) == -1L) updateCoveredUntil(source, value)
    }

    @Query("UPDATE screen_pull_state SET floor = MAX(floor, :value) WHERE source = :source")
    suspend fun raiseFloor(source: String, value: Long)

    @Insert
    suspend fun recordGap(gap: ScreenCoverageGapRow)

    @Query("DELETE FROM screen_events WHERE at >= :from AND at < :until")
    suspend fun deleteEvents(from: Long, until: Long)

    @Query("SELECT MAX(at) FROM screen_events")
    suspend fun lastEventAt(): Long?

    /** 一次拉取的事件、名称、缺口与覆盖指针在同一事务落库；失败整体回滚、不留副作用（缺口与指针同生同灭，不重复写）。 */
    @Transaction
    suspend fun commitPull(events: List<ScreenEventRow>, names: List<ScreenAppNameRow>, coveredUntil: Long,
                           gaps: List<ScreenCoverageGapRow>) {
        if (events.isNotEmpty()) insertEvents(events)
        if (names.isNotEmpty()) upsertNames(names)
        for (gap in gaps) recordGap(gap)
        if (insertStateRow(ScreenPullStateRow(SCREEN_SOURCE, coveredUntil, 0)) == -1L)
            updateCoveredUntil(SCREEN_SOURCE, coveredUntil)
    }

    @Query("SELECT * FROM playback_rows WHERE sessionKey = :sessionKey AND untilAt IS NULL LIMIT 1")
    suspend fun openPlayback(sessionKey: String): PlaybackRow?

    @Insert
    suspend fun insertPlayback(row: PlaybackRow): Long

    @Update
    suspend fun updatePlayback(row: PlaybackRow)

    @Query("SELECT * FROM playback_rows WHERE untilAt IS NULL")
    suspend fun openPlaybacks(): List<PlaybackRow>

    /** 总开关重新打开后：上次开启期间留下的未结束行收尾在各自 lastSeenAt，不另写缺口。 */
    @Query("UPDATE playback_rows SET untilAt = lastSeenAt WHERE untilAt IS NULL AND lastSeenAt < :enabledAt")
    suspend fun closeOpenPlaybacksBefore(enabledAt: Long)

    /** 与 [from, until) 重叠的播放行；有效终点 = COALESCE(untilAt, lastSeenAt)。 */
    @Query("SELECT * FROM playback_rows WHERE fromAt < :until AND COALESCE(untilAt, lastSeenAt) > :from ORDER BY fromAt, id")
    suspend fun playbacksOverlapping(from: Long, until: Long): List<PlaybackRow>

    @Query("SELECT * FROM screen_coverage_gaps WHERE source = :source AND untilAt = :openUntil ORDER BY fromAt, id")
    suspend fun openEndedGaps(source: String, openUntil: Long): List<ScreenCoverageGapRow>

    @Query("UPDATE screen_coverage_gaps SET untilAt = :untilAt WHERE id = :id")
    suspend fun closeGap(id: Long, untilAt: Long)

    @Query("DELETE FROM playback_rows WHERE fromAt >= :from AND COALESCE(untilAt, lastSeenAt) <= :until")
    suspend fun deletePlaybackInside(from: Long, until: Long)

    @Query("UPDATE playback_rows SET untilAt = :from, lastSeenAt = MIN(lastSeenAt, :from) WHERE fromAt < :from AND COALESCE(untilAt, lastSeenAt) > :from")
    suspend fun truncatePlaybackLeft(from: Long)

    @Query("UPDATE playback_rows SET fromAt = :until WHERE fromAt >= :from AND fromAt < :until AND COALESCE(untilAt, lastSeenAt) > :until")
    suspend fun truncatePlaybackRight(from: Long, until: Long)

    /** 隐私删除：[from, until) 内整行删除，跨左边界（含同时跨两侧）只留左半段，跨右边界把起点挪到 until。 */
    @Transaction
    suspend fun forgetPlayback(from: Long, until: Long) {
        deletePlaybackInside(from, until)
        truncatePlaybackLeft(from)
        truncatePlaybackRight(from, until)
    }

    @Transaction
    suspend fun closeOpenPlaybacksAtLastSeen() {
        for (row in openPlaybacks()) updatePlayback(row.copy(untilAt = row.lastSeenAt))
    }

    @Transaction
    suspend fun closePlaybackDisconnectGaps(at: Long): Int {
        val open = openEndedGaps(PLAYBACK_SOURCE, Long.MAX_VALUE)
        for (gap in open) closeGap(gap.id, at)
        return open.size
    }
}
