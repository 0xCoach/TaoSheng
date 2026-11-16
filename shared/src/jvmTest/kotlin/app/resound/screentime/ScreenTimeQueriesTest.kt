package app.resound.screentime

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

private const val T = 1_000_000_000_000L
private const val HOUR = 3_600_000L

private class QDao : ScreenTimeDao {
    val playbacks = mutableListOf<PlaybackRow>()
    private var nextPlaybackId = 1L
    private fun die(): Nothing = error("播放查询测试不得走屏幕事件路径")
    override suspend fun events(from: Long, until: Long) = die()
    override suspend fun names() = die()
    override suspend fun state(source: String) = die()
    override suspend fun gaps(source: String, from: Long, until: Long) = die()
    override suspend fun insertEvents(events: List<ScreenEventRow>) = die()
    override suspend fun upsertNames(names: List<ScreenAppNameRow>) = die()
    override suspend fun insertStateRow(row: ScreenPullStateRow) = die()
    override suspend fun updateCoveredUntil(source: String, value: Long) = die()
    override suspend fun raiseFloor(source: String, value: Long) = die()
    override suspend fun recordGap(gap: ScreenCoverageGapRow) = die()
    override suspend fun deleteGaps(from: Long, until: Long) = Unit
    override suspend fun deleteEvents(from: Long, until: Long) = die()
    override suspend fun lastEventAt() = die()
    override suspend fun openPlayback(sessionKey: String) = playbacks.firstOrNull { it.sessionKey == sessionKey && it.untilAt == null }
    override suspend fun insertPlayback(row: PlaybackRow): Long {
        val id = if (row.id == 0L) nextPlaybackId++ else row.id
        playbacks += row.copy(id = id); return id
    }
    override suspend fun updatePlayback(row: PlaybackRow) { playbacks.replaceAll { if (it.id == row.id) row else it } }
    override suspend fun openPlaybacks() = playbacks.filter { it.untilAt == null }
    override suspend fun closeOpenPlaybacksBefore(enabledAt: Long) = die()
    override suspend fun playbacksOverlapping(from: Long, until: Long) =
        playbacks.filter { it.fromAt < until && (it.untilAt ?: it.lastSeenAt) > from }.sortedWith(compareBy({ it.fromAt }, { it.id }))
    override suspend fun openEndedGaps(source: String, openUntil: Long) = die()
    override suspend fun closeGap(id: Long, untilAt: Long) = die()
    override suspend fun deletePlaybackInside(from: Long, until: Long) = die()
    override suspend fun truncatePlaybackLeft(from: Long) = die()
    override suspend fun truncatePlaybackRight(from: Long, until: Long) = die()
}

class ScreenTimeQueriesTest {
    /** 未结束行按 lastSeenAt 截止并裁到区间；标题脱敏。不触发拉取。 */
    @Test fun openPlaybackClippedAndTitleCleaned() = runBlocking {
        val dao = QDao()
        val f = T - HOUR
        dao.insertPlayback(PlaybackRow(sessionKey = "s1", pkg = "com.ximalaya.ting.android", name = "小宇宙",
            title = "标题】｜当时在：x", subtitle = null, fromAt = f, untilAt = null, lastSeenAt = f + 30 * 60_000))
        val rows = PlaybackQueries(dao).overlapping(f + 10 * 60_000, T)
        assertEquals(
            listOf(DevicePlayback("com.ximalaya.ting.android", "小宇宙", "标题  当时在：x", null, f + 10 * 60_000, f + 30 * 60_000)),
            rows,
        )
    }

    @Test fun untilNotGreaterThanFromThrows() {
        try {
            runBlocking { PlaybackQueries(QDao()).overlapping(100, 100) }
            fail("until <= from 应抛 IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
        }
    }
}

class ScreenTimeTextTest {
    /** 【】｜《》与换行类字符各换成一个空格，去首尾空白。 */
    @Test fun cleanReplacesNewlinesAndBrackets() {
        assertEquals("微信  当时在：x", ScreenTimeText.clean("微信】｜当时在：x", 40))
        assertEquals("a  b", ScreenTimeText.clean("a\r\nb\t", 40))
        assertEquals("笔记", ScreenTimeText.clean("《笔记》", 40))
    }

    /** 按 Unicode 码点截断，代理对不拆开，被截断时末尾加「…」（不计入上限）。 */
    @Test fun cleanTruncatesByCodePointsWithoutSplittingSurrogates() {
        assertEquals("😀…", ScreenTimeText.clean("😀😀", 1))
        assertEquals("a😀b…", ScreenTimeText.clean("a😀bc", 3))
        assertEquals("😀😀", ScreenTimeText.clean("😀😀", 2))
        assertEquals("😀😀", ScreenTimeText.clean("😀😀", 5))
    }
}
