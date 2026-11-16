package app.resound.screentime

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private const val T = 1_000_000_000_000L

/** 内存假库，只服务播放记录与缺口。 */
private class RecDao : ScreenTimeDao {
    val playbacks = mutableListOf<PlaybackRow>()
    val gaps = mutableListOf<ScreenCoverageGapRow>()
    private var nextPlaybackId = 1L
    private var nextGapId = 1L
    private fun die(): Nothing = error("播放测试不得走屏幕事件路径")
    override suspend fun events(from: Long, until: Long) = die()
    override suspend fun names() = die()
    override suspend fun state(source: String) = die()
    override suspend fun gaps(source: String, from: Long, until: Long) =
        gaps.filter { it.source == source && it.fromAt < until && it.untilAt > from }.sortedBy { it.fromAt }
    override suspend fun insertEvents(events: List<ScreenEventRow>) = die()
    override suspend fun upsertNames(names: List<ScreenAppNameRow>) = die()
    override suspend fun insertStateRow(row: ScreenPullStateRow) = die()
    override suspend fun updateCoveredUntil(source: String, value: Long) = die()
    override suspend fun raiseFloor(source: String, value: Long) = die()
    override suspend fun recordGap(gap: ScreenCoverageGapRow) { gaps += gap.copy(id = if (gap.id == 0L) nextGapId++ else gap.id) }
    override suspend fun deleteEvents(from: Long, until: Long) = die()
    override suspend fun lastEventAt() = die()
    override suspend fun openPlayback(sessionKey: String) = playbacks.firstOrNull { it.sessionKey == sessionKey && it.untilAt == null }
    override suspend fun insertPlayback(row: PlaybackRow): Long {
        val id = if (row.id == 0L) nextPlaybackId++ else row.id
        playbacks += row.copy(id = id); return id
    }
    override suspend fun updatePlayback(row: PlaybackRow) { playbacks.replaceAll { if (it.id == row.id) row else it } }
    override suspend fun openPlaybacks() = playbacks.filter { it.untilAt == null }
    override suspend fun closeOpenPlaybacksBefore(enabledAt: Long) {
        playbacks.replaceAll {
            if (it.untilAt == null && it.lastSeenAt < enabledAt) it.copy(untilAt = it.lastSeenAt) else it
        }
    }
    override suspend fun playbacksOverlapping(from: Long, until: Long) =
        playbacks.filter { it.fromAt < until && (it.untilAt ?: it.lastSeenAt) > from }.sortedWith(compareBy({ it.fromAt }, { it.id }))
    override suspend fun openEndedGaps(source: String, openUntil: Long) = gaps.filter { it.source == source && it.untilAt == openUntil }
    override suspend fun closeGap(id: Long, untilAt: Long) { gaps.replaceAll { if (it.id == id) it.copy(untilAt = untilAt) else it } }
    override suspend fun deletePlaybackInside(from: Long, until: Long) {
        playbacks.removeAll { it.fromAt >= from && (it.untilAt ?: it.lastSeenAt) <= until }
    }
    override suspend fun truncatePlaybackLeft(from: Long) {
        playbacks.replaceAll {
            val end = it.untilAt ?: it.lastSeenAt
            if (it.fromAt < from && end > from) it.copy(untilAt = from, lastSeenAt = minOf(it.lastSeenAt, from)) else it
        }
    }
    override suspend fun truncatePlaybackRight(from: Long, until: Long) {
        playbacks.replaceAll {
            val end = it.untilAt ?: it.lastSeenAt
            if (it.fromAt >= from && it.fromAt < until && end > until) it.copy(fromAt = until) else it
        }
    }
}

private class RecEnv(var on: Boolean = true) {
    var clock = T
    var enabledAt = 0L
    val dao = RecDao()
    val settings = PlaybackTitleSettings(
        readBoolean = { _, _ -> on }, saveBoolean = { _, v -> on = v; true },
        readLong = { key, def -> if (key == PlaybackTitleSettings.ENABLED_AT_KEY) enabledAt else def },
        saveLong = { key, value ->
            if (key == PlaybackTitleSettings.ENABLED_AT_KEY) enabledAt = value
            true
        },
        now = { clock },
    )
    val names = mutableMapOf<String, String>()
    val recorder = PlaybackRecorder(dao, settings, { pkgs -> names.filterKeys { it in pkgs } })
}

class PlaybackRecorderTest {
    /** 必测 2：标题从一个非空值变为另一个非空值时切成两行，前一行收尾在切换时刻。 */
    @Test fun nonemptyTitleChangeSplitsRow() = runBlocking {
        val env = RecEnv()
        env.recorder.changed("k", "pkg.a", "第一集", null, true, T)
        env.recorder.changed("k", "pkg.a", "第二集", null, true, T + 1_000)
        assertEquals(2, env.dao.playbacks.size)
        assertEquals(T, env.dao.playbacks[0].fromAt)
        assertEquals(T + 1_000, env.dao.playbacks[0].untilAt)
        assertEquals("第一集", env.dao.playbacks[0].title)
        assertEquals(T + 1_000, env.dao.playbacks[1].fromAt)
        assertEquals(null, env.dao.playbacks[1].untilAt)
        assertEquals("第二集", env.dao.playbacks[1].title)
    }

    /** 必测 3：标题从空变为有值是补写，不切行。 */
    @Test fun nullTitleFilledWithoutSplitting() = runBlocking {
        val env = RecEnv()
        env.recorder.changed("k", "pkg.a", null, null, true, T)
        env.recorder.changed("k", "pkg.a", "  第一集  ", "副", true, T + 1_000)
        assertEquals(1, env.dao.playbacks.size)
        val row = env.dao.playbacks.single()
        assertEquals(T, row.fromAt)
        assertEquals(null, row.untilAt)
        assertEquals("第一集", row.title)
        assertEquals("副", row.subtitle)
        assertEquals(T + 1_000, row.lastSeenAt)
    }

    /** 必测 4：心跳刷新最后观测时刻；disconnected 收尾在 lastSeenAt 而非断开时刻；connected 把缺口闭合为一条。 */
    @Test fun heartbeatDisconnectThenConnectClosesOneGapAtDisconnectTime() = runBlocking {
        val env = RecEnv()
        env.recorder.changed("k", "pkg.a", "A", null, true, T)
        env.recorder.heartbeat("k", T + 5_000)
        env.recorder.disconnected(T + 9_000)
        val closed = env.dao.playbacks.single()
        assertEquals(T + 5_000, closed.untilAt)
        assertEquals(T + 5_000, closed.lastSeenAt)
        assertEquals(listOf(T + 9_000 to Long.MAX_VALUE), env.dao.gaps.map { it.fromAt to it.untilAt })
        assertEquals(GapCode.LISTENER_DISCONNECTED, env.dao.gaps.single().code)
        env.recorder.connected(T + 20_000)
        assertEquals(1, env.dao.gaps.size)
        assertEquals(T + 9_000, env.dao.gaps.single().fromAt)
        assertEquals(T + 20_000, env.dao.gaps.single().untilAt)
        assertTrue(env.dao.playbacks.none { it.untilAt == null })
    }

    /** 必测 5：进程被杀（只调 connected）：未结束行收尾在 lastSeenAt，补一条 [lastSeenAt, 重连时刻)。 */
    @Test fun connectedWithoutDisconnectClosesAtLastSeenAndWritesGap() = runBlocking {
        val env = RecEnv()
        env.recorder.changed("k", "pkg.a", "A", null, true, T)
        env.recorder.heartbeat("k", T + 3_000)
        env.recorder.connected(T + 10_000)
        val row = env.dao.playbacks.single()
        assertEquals(T + 3_000, row.untilAt)
        assertEquals(T + 3_000, row.lastSeenAt)
        assertEquals(listOf(T + 3_000L to T + 10_000L), env.dao.gaps.map { it.fromAt to it.untilAt })
        assertEquals(GapCode.LISTENER_DISCONNECTED, env.dao.gaps.single().code)
    }

    /** 「记录播放标题」关闭时四个方法都不写库。 */
    @Test fun disabledWritesNothing() = runBlocking {
        val env = RecEnv(on = false)
        env.recorder.changed("k", "pkg.a", "A", null, true, T)
        env.recorder.heartbeat("k", T + 1)
        env.recorder.disconnected(T + 2)
        env.recorder.connected(T + 3)
        assertTrue(env.dao.playbacks.isEmpty())
        assertTrue(env.dao.gaps.isEmpty())
    }

    /** 总开关关闭期间不续接：重新开启后同一 key 的 changed 开新行，旧行收尾在上次 lastSeenAt。 */
    @Test fun reenableClosesStaleOpenRowAndStartsNew() = runBlocking {
        val env = RecEnv()
        val t1 = T
        val t3 = T + 3_000
        val t4 = T + 4_000
        env.recorder.changed("k", "pkg.a", "A", null, true, t1)
        env.settings.setEnabled(false)
        env.clock = t3
        env.settings.setEnabled(true)
        env.recorder.changed("k", "pkg.a", "A", null, true, t4)
        assertEquals(2, env.dao.playbacks.size)
        assertEquals(t1, env.dao.playbacks[0].untilAt)
        assertEquals(t4, env.dao.playbacks[1].fromAt)
        assertEquals(null, env.dao.playbacks[1].untilAt)
        assertTrue(env.dao.gaps.isEmpty())
    }

    /** 关闭期间的会话变化不落库；打开后再发 changed，立即记一行开始。 */
    @Test fun enableThenChangedWritesStartRow() = runBlocking {
        val env = RecEnv(on = false)
        env.recorder.changed("k", "pkg.a", "A", null, true, T)
        env.clock = T + 1_000
        env.settings.setEnabled(true)
        env.recorder.changed("k", "pkg.a", "A", null, true, T + 1_000)
        assertEquals(1, env.dao.playbacks.size)
        val row = env.dao.playbacks.single()
        assertEquals(T + 1_000, row.fromAt)
        assertEquals(null, row.untilAt)
        assertEquals("A", row.title)
    }
}
