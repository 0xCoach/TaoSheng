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
    suspend fun updateFloor(source: String, value: Long)

    /** 隐私下限只升不降；尚无状态行时插入，避免首次拉取把已删事件写回。 */
    @Transaction
    suspend fun raiseFloor(source: String, value: Long) {
        if (insertStateRow(ScreenPullStateRow(source, null, value)) == -1L) updateFloor(source, value)
    }

    @Query("DELETE FROM screen_app_names")
    suspend fun deleteAllNames()

    @Query("DELETE FROM screen_app_names WHERE pkg NOT IN (SELECT DISTINCT pkg FROM screen_events)")
    suspend fun deleteUnreferencedNames()

    @Insert
    suspend fun recordGap(gap: ScreenCoverageGapRow)

    @Query("DELETE FROM screen_events WHERE at >= :from AND at < :until")
    suspend fun deleteEvents(from: Long, until: Long)

    @Query("SELECT * FROM screen_coverage_gaps WHERE fromAt < :until AND untilAt > :from ORDER BY fromAt, id")
    suspend fun overlappingGaps(from: Long, until: Long): List<ScreenCoverageGapRow>

    @Query("DELETE FROM screen_coverage_gaps WHERE id = :id")
    suspend fun deleteGap(id: Long)

    /** 只去掉与遗忘区间重叠的那部分：完全落在区间内的整行删除，部分重叠的裁短或拆开，区间外保留。 */
    @Transaction
    suspend fun deleteGaps(from: Long, until: Long) {
        val overlapping = overlappingGaps(from, until)
        val remaining = remainingGapsAfterDelete(overlapping, from, until)
        for (gap in overlapping) deleteGap(gap.id)
        for (piece in remaining) recordGap(piece.copy(id = 0))
    }

    @Query("SELECT MAX(at) FROM screen_events")
    suspend fun lastEventAt(): Long?

    /** 一次拉取的事件、名称、缺口与覆盖指针在同一事务落库；失败整体回滚、不留副作用（缺口与指针同生同灭，不重复写）。
     *  提交前按此刻的隐私下限过滤，避免在途拉取写回已删数据。 */
    @Transaction
    suspend fun commitPull(events: List<ScreenEventRow>, names: List<ScreenAppNameRow>, coveredUntil: Long,
                           gaps: List<ScreenCoverageGapRow>) {
        val floor = state(SCREEN_SOURCE)?.floor ?: 0L
        val kept = retainPullAfterFloor(events, names, gaps, floor)
        if (kept.events.isNotEmpty()) insertEvents(kept.events)
        if (kept.names.isNotEmpty()) upsertNames(kept.names)
        for (gap in kept.gaps) recordGap(gap)
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

/** 提交拉取时按隐私下限留下的事件、名称、缺口。 */
internal data class FloorFilteredPull(
    val events: List<ScreenEventRow>,
    val names: List<ScreenAppNameRow>,
    val gaps: List<ScreenCoverageGapRow>,
)

internal fun retainPullAfterFloor(
    events: List<ScreenEventRow>,
    names: List<ScreenAppNameRow>,
    gaps: List<ScreenCoverageGapRow>,
    floor: Long,
): FloorFilteredPull {
    if (floor <= 0L) return FloorFilteredPull(events, names, gaps)
    val keptEvents = events.filter { it.at >= floor }
    val keptPkgs = keptEvents.map { it.pkg }.toSet()
    val keptNames = names.filter { it.pkg in keptPkgs }
    val keptGaps = gaps.mapNotNull { gap ->
        val from = maxOf(gap.fromAt, floor)
        when {
            gap.untilAt <= floor -> null
            from >= gap.untilAt -> null
            from == gap.fromAt -> gap
            else -> gap.copy(fromAt = from)
        }
    }
    return FloorFilteredPull(keptEvents, keptNames, keptGaps)
}

/** 删除 [from, until) 后缺口还该留下的片段。完全落在区间内的去掉；只重叠一部分的裁短或拆成两端。 */
internal fun remainingGapsAfterDelete(
    gaps: List<ScreenCoverageGapRow>,
    from: Long,
    until: Long,
): List<ScreenCoverageGapRow> = gaps.flatMap { gap ->
    when {
        gap.untilAt <= from || gap.fromAt >= until -> listOf(gap)
        else -> buildList {
            if (gap.fromAt < from) add(gap.copy(id = 0, untilAt = from))
            if (gap.untilAt > until) add(gap.copy(id = 0, fromAt = until))
        }
    }
}
