package app.resound.screentime

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

private const val T = 1_000_000_000_000L
private const val HOUR = 3_600_000L

/** 内存假库；写法与 ScreenTimePullerTest 对齐，供查询仓储直接造数。 */
private class QDao : ScreenTimeDao {
    val rows = mutableListOf<ScreenEventRow>()
    val names = mutableMapOf<String, ScreenAppNameRow>()
    val states = mutableMapOf<String, ScreenPullStateRow>()
    val gaps = mutableListOf<ScreenCoverageGapRow>()
    val playbacks = mutableListOf<PlaybackRow>()
    private var nextPlaybackId = 1L
    override suspend fun events(from: Long, until: Long) = rows.filter { it.at >= from && it.at < until }.sortedWith(compareBy({ it.at }, { it.id }))
    override suspend fun names() = names.values.sortedBy { it.pkg }
    override suspend fun state(source: String) = states[source]
    override suspend fun gaps(source: String, from: Long, until: Long) = gaps.filter { it.source == source && it.fromAt < until && it.untilAt > from }.sortedBy { it.fromAt }
    override suspend fun insertEvents(events: List<ScreenEventRow>) { for (e in events) rows += e }
    override suspend fun upsertNames(names: List<ScreenAppNameRow>) { names.forEach { this.names[it.pkg] = it } }
    override suspend fun insertStateRow(row: ScreenPullStateRow) = if (states.containsKey(row.source)) -1L else { states[row.source] = row; 1L }
    override suspend fun updateCoveredUntil(source: String, value: Long) { states[source]?.let { states[source] = it.copy(coveredUntil = maxOf(it.coveredUntil ?: 0L, value)) } }
    override suspend fun updateFloor(source: String, value: Long) { states[source]?.let { states[source] = it.copy(floor = maxOf(it.floor, value)) } }
    override suspend fun deleteAllNames() { names.clear() }
    override suspend fun deleteUnreferencedNames() {
        val used = rows.map { it.pkg }.toSet()
        names.keys.removeAll { it !in used }
    }
    var playbackReads = 0
    override suspend fun recordGap(gap: ScreenCoverageGapRow) { gaps += gap.copy(id = if (gap.id == 0L) gaps.size + 1L else gap.id) }
    override suspend fun overlappingGaps(from: Long, until: Long) =
        gaps.filter { it.fromAt < until && it.untilAt > from }.sortedBy { it.fromAt }
    override suspend fun deleteGap(id: Long) { gaps.removeAll { it.id == id } }
    override suspend fun deleteGaps(from: Long, until: Long) {
        val next = remainingGapsAfterDelete(gaps.toList(), from, until)
        gaps.clear(); gaps.addAll(next)
    }
    override suspend fun deleteEvents(from: Long, until: Long) { rows.removeAll { it.at >= from && it.at < until } }
    override suspend fun lastEventAt() = rows.maxOfOrNull { it.at }
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
    override suspend fun playbacksOverlapping(from: Long, until: Long): List<PlaybackRow> {
        playbackReads++
        return playbacks.filter { it.fromAt < until && (it.untilAt ?: it.lastSeenAt) > from }.sortedWith(compareBy({ it.fromAt }, { it.id }))
    }
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

/** 断言被调用即失败的严格事件源与假库；验证总开关关闭时两者都不被碰。 */
private class QStrictSource : ScreenEventSource {
    override fun permitted() = error("开关关闭时不得调用 permitted")
    override suspend fun since(from: Long, until: Long) = error("开关关闭时不得调用 since")
    override suspend fun appNames(pkgs: Set<String>) = error("开关关闭时不得调用 appNames")
}

private class StrictDao : ScreenTimeDao {
    private fun die(): Nothing = error("开关关闭时不得访问数据库")
    override suspend fun events(from: Long, until: Long): List<ScreenEventRow> = die()
    override suspend fun names(): List<ScreenAppNameRow> = die()
    override suspend fun state(source: String): ScreenPullStateRow? = die()
    override suspend fun gaps(source: String, from: Long, until: Long): List<ScreenCoverageGapRow> = die()
    override suspend fun insertEvents(events: List<ScreenEventRow>) = die()
    override suspend fun upsertNames(names: List<ScreenAppNameRow>) = die()
    override suspend fun insertStateRow(row: ScreenPullStateRow): Long = die()
    override suspend fun updateCoveredUntil(source: String, value: Long) = die()
    override suspend fun updateFloor(source: String, value: Long) = die()
    override suspend fun deleteAllNames() = die()
    override suspend fun deleteUnreferencedNames() = die()
    override suspend fun raiseFloor(source: String, value: Long) = die()
    override suspend fun recordGap(gap: ScreenCoverageGapRow) = die()
    override suspend fun overlappingGaps(from: Long, until: Long): List<ScreenCoverageGapRow> = die()
    override suspend fun deleteGap(id: Long) = die()
    override suspend fun deleteGaps(from: Long, until: Long) = die()
    override suspend fun deleteEvents(from: Long, until: Long) = die()
    override suspend fun lastEventAt(): Long? = die()
    override suspend fun openPlayback(sessionKey: String): PlaybackRow? = die()
    override suspend fun insertPlayback(row: PlaybackRow): Long = die()
    override suspend fun updatePlayback(row: PlaybackRow) = die()
    override suspend fun openPlaybacks(): List<PlaybackRow> = die()
    override suspend fun closeOpenPlaybacksBefore(enabledAt: Long) = die()
    override suspend fun playbacksOverlapping(from: Long, until: Long): List<PlaybackRow> = die()
    override suspend fun openEndedGaps(source: String, openUntil: Long): List<ScreenCoverageGapRow> = die()
    override suspend fun closeGap(id: Long, untilAt: Long) = die()
    override suspend fun deletePlaybackInside(from: Long, until: Long) = die()
    override suspend fun truncatePlaybackLeft(from: Long) = die()
    override suspend fun truncatePlaybackRight(from: Long, until: Long) = die()
}

/** 可控授权的空事件源；查询前拉取走真路径（授权缺失或抛错时不动数据库）。 */
private class QSource(var permitted: Boolean = true) : ScreenEventSource {
    override fun permitted() = permitted
    override suspend fun since(from: Long, until: Long): List<RawScreenEvent> = emptyList()
    override suspend fun appNames(pkgs: Set<String>) = emptyMap<String, String>()
}

/** 真拉取器（无法打桩）+ 注入时钟；lastPull 设为当前时刻可让查询前拉取节流跳过、不动数据库。 */
private class Env(var on: Boolean = true) {
    var clock = T
    var lastPull = 0L
    private val settings = ScreenTimeSettings(
        readBoolean = { _, _ -> on },
        persist = { bools, longs, _ ->
            bools[ScreenTimeSettings.KEY]?.let { on = it }
            longs[ScreenTimeSettings.LAST_PULL_KEY]?.let { lastPull = it }
            true
        },
        readLong = { _, _ -> lastPull }, now = { clock })
    fun queries(source: ScreenEventSource, dao: ScreenTimeDao, permitted: Boolean = true,
                playbackPermitted: Boolean = true, excluded: Set<String> = emptySet(),
                titlesAllowed: Boolean = true) =
        ScreenTimeQueries(dao, ScreenTimePuller(settings, source, dao, { clock }, { false }), settings,
            { permitted }, { playbackPermitted }, excluded, { clock }, PlaybackQueries(dao), { titlesAllowed })
}

class ScreenTimeQueriesTest {
    /** 必测 1：总开关关 → DISABLED，拉取器与 DAO 全程不被调用。 */
    @Test fun disabledReturnsEmptyWithoutTouchingDaoOrSource() = runBlocking {
        val result = Env(on = false).queries(QStrictSource(), StrictDao()).sessions(0, 1_000)
        assertEquals(ScreenTimeResult.DISABLED, result)
    }

    /** 必测 2：有事件 → 段带显示名；缺名称用包名；未知类型行丢弃。 */
    @Test fun sessionsUseDisplayNameAndFallbackToPackage() = runBlocking {
        val dao = QDao()
        val f = T - HOUR
        dao.insertEvents(listOf(
            ScreenEventRow(at = f, pkg = "com.a", cls = "A", type = 1),
            ScreenEventRow(at = f + 600_000, pkg = "com.a", cls = "A", type = 2),
            ScreenEventRow(at = f + 700_000, pkg = "com.b", cls = "B", type = 1),
            ScreenEventRow(at = f + 800_000, pkg = "com.x", cls = "X", type = 11), // 未知类型，查询时丢弃
        ))
        dao.upsertNames(listOf(ScreenAppNameRow("com.a", "示例应用", T)))
        dao.advanceCoveredUntil(SCREEN_SOURCE, T)
        val env = Env(on = true).apply { lastPull = T } // 拉取节流跳过，库内数据原样
        val result = env.queries(QSource(), dao).sessions(f, T)
        assertEquals(listOf(
            NamedAppSession("com.a", "示例应用", f, f + 600_000),
            NamedAppSession("com.b", "com.b", f + 700_000, T), // 仍在开 → 截止于 coveredUntil
        ), result.sessions)
        assertEquals(T, result.coverageUntil)
    }

    /** 必测 3：无授权 → 已落库历史照常返回，并带 usage_access_missing。 */
    @Test fun noPermissionStillReturnsHistoryAndAddsUsageAccessMissing() = runBlocking {
        val dao = QDao()
        val f = T - HOUR
        dao.insertEvents(listOf(ScreenEventRow(at = f, pkg = "com.a", cls = "A", type = 1)))
        dao.advanceCoveredUntil(SCREEN_SOURCE, f + 60_000)
        // 拉取器与查询用同一授权判据（生产装配传同一实现）：QSource(permitted=false) 走拉取器，queries 的 permitted=false 出缺口。
        val result = Env(on = true).queries(QSource(permitted = false), dao, permitted = false).sessions(f, T)
        assertEquals(listOf(NamedAppSession("com.a", "com.a", f, f + 60_000)), result.sessions)
        assertEquals(listOf(CoverageGap(GapCode.USAGE_ACCESS_MISSING, SCREEN_SOURCE, f, T, "设置 → 使用情况访问 → 涛声 开启")), result.gaps)
    }

    /** 必测 4：拉取 Failed → 返回已落库数据，并带 pull_failed（区间 [coveredUntil, 此刻) 与查询区间的交集）。 */
    @Test fun failedPullReturnsStoredDataAndAddsPullFailedGap() = runBlocking {
        val dao = QDao()
        val f = T - HOUR
        val covered = T - 10 * 60_000 // 10 分钟前：不触发 coverage_lag，隔离 pull_failed
        dao.advanceCoveredUntil(SCREEN_SOURCE, covered)
        dao.insertEvents(listOf(ScreenEventRow(at = f, pkg = "com.a", cls = "A", type = 1)))
        val broken = object : ScreenEventSource {
            override fun permitted() = true
            override suspend fun since(from: Long, until: Long): List<RawScreenEvent> = throw IllegalArgumentException("boom")
            override suspend fun appNames(pkgs: Set<String>) = emptyMap<String, String>()
        }
        val result = Env(on = true).queries(broken, dao).sessions(f, T)
        assertEquals(listOf(NamedAppSession("com.a", "com.a", f, covered)), result.sessions)
        assertEquals(covered, result.coverageUntil)
        assertEquals(listOf(CoverageGap(GapCode.PULL_FAILED, SCREEN_SOURCE, covered, T, "这段超出系统保留期或采集失败，无法补回")), result.gaps)
    }

    /** 必测 5：coveredUntil 落后 2 小时 → coverage_lag 现算；落库 disabled 缺口按区间返回且排在前。 */
    @Test fun coverageLagAndStoredDisabledGap() = runBlocking {
        val dao = QDao()
        val f = T - HOUR
        val covered = T - 2 * HOUR
        dao.advanceCoveredUntil(SCREEN_SOURCE, covered)
        dao.recordGap(ScreenCoverageGapRow(source = SCREEN_SOURCE, fromAt = covered, untilAt = f + 30 * 60_000, code = "disabled"))
        val env = Env(on = true).apply { lastPull = T } // 拉取节流跳过，coveredUntil 保持落后
        val result = env.queries(QSource(), dao).sessions(f, T)
        assertTrue(result.sessions.isEmpty())
        assertEquals(listOf(
            CoverageGap(GapCode.DISABLED, SCREEN_SOURCE, covered, f + 30 * 60_000, "屏幕时间关闭期间未采集"),
            CoverageGap(GapCode.COVERAGE_LAG, SCREEN_SOURCE, f, T, "打开涛声，等待下一次采集"),
        ), result.gaps)
    }

    /** until <= from 在开关判断之前抛 IllegalArgumentException（卡面流程顺序）。 */
    @Test fun untilNotGreaterThanFromThrows() {
        try {
            runBlocking { Env(on = false).queries(QStrictSource(), StrictDao()).sessions(100, 100) }
            fail("until <= from 应抛 IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
        }
    }

    /** 必测 7：未结束行按 lastSeenAt 截止并裁到区间；标题脱敏；无通知使用权时历史照常返回并带 notification_access_missing。 */
    @Test fun openPlaybackClippedAndHistoryKeptWithoutNotificationAccess() = runBlocking {
        val dao = QDao()
        val f = T - HOUR
        dao.insertPlayback(PlaybackRow(sessionKey = "s1", pkg = "com.ximalaya.ting.android", name = "小宇宙",
            title = "标题】｜当时在：x", subtitle = null, fromAt = f, untilAt = null, lastSeenAt = f + 30 * 60_000))
        dao.recordGap(ScreenCoverageGapRow(source = PLAYBACK_SOURCE, fromAt = f + 12 * 60_000, untilAt = f + 20 * 60_000, code = GapCode.LISTENER_DISCONNECTED))
        dao.advanceCoveredUntil(SCREEN_SOURCE, T)
        val env = Env(on = true).apply { lastPull = T }
        val result = env.queries(QSource(), dao, playbackPermitted = false).sessions(f + 10 * 60_000, T)
        assertEquals(
            listOf(DevicePlayback("com.ximalaya.ting.android", "小宇宙", "标题  当时在：x", null, f + 10 * 60_000, f + 30 * 60_000)),
            result.devicePlayback,
        )
        assertEquals(
            listOf(
                CoverageGap(GapCode.LISTENER_DISCONNECTED, PLAYBACK_SOURCE, f + 12 * 60_000, f + 20 * 60_000, "设置 → 通知使用权 → 涛声 重新开启"),
                CoverageGap(GapCode.NOTIFICATION_ACCESS_MISSING, PLAYBACK_SOURCE, f + 10 * 60_000, T, "设置 → 通知使用权 → 涛声 开启"),
            ),
            result.gaps,
        )
    }

    /** 删除缺口切断重放：区间前的 RESUMED 不得把已删时段再算成使用。 */
    @Test fun privacyDeletedGapStopsReplayThroughForgottenWindow() = runBlocking {
        val dao = QDao()
        val f = T - HOUR
        dao.insertEvents(listOf(
            ScreenEventRow(at = f, pkg = "private.app", cls = "Main", type = 1),
            ScreenEventRow(at = f + 180_000, pkg = "other.app", cls = "Main", type = 1),
        ))
        dao.recordGap(ScreenCoverageGapRow(source = SCREEN_SOURCE, fromAt = f + 60_000, untilAt = f + 120_000, code = GapCode.PRIVACY_DELETED))
        dao.advanceCoveredUntil(SCREEN_SOURCE, T)
        val env = Env(on = true).apply { lastPull = T }
        val forgotten = env.queries(QSource(), dao).sessions(f + 60_000, f + 120_000)
        assertTrue(forgotten.sessions.isEmpty())
        val around = env.queries(QSource(), dao).sessions(f, f + 240_000)
        assertTrue(around.sessions.none { it.from < f + 120_000 && it.until > f + 60_000 })
    }

    /** 停采、拉取失败缺口同样不得被推算成使用。 */
    @Test fun disabledAndPullFailedGapsStopReplay() = runBlocking {
        val dao = QDao()
        val f = T - HOUR
        dao.insertEvents(listOf(ScreenEventRow(at = f, pkg = "private.app", cls = "Main", type = 1)))
        dao.recordGap(ScreenCoverageGapRow(source = SCREEN_SOURCE, fromAt = f + 60_000, untilAt = f + 90_000, code = GapCode.DISABLED))
        dao.recordGap(ScreenCoverageGapRow(source = SCREEN_SOURCE, fromAt = f + 90_000, untilAt = f + 120_000, code = GapCode.PULL_FAILED))
        dao.advanceCoveredUntil(SCREEN_SOURCE, T)
        val env = Env(on = true).apply { lastPull = T }
        val around = env.queries(QSource(), dao).sessions(f, T)
        assertTrue(around.sessions.none { it.from < f + 120_000 && it.until > f + 60_000 })
    }

    /** 关闭记录播放标题后，查询路径一次都不碰播放表。 */
    @Test fun titleSwitchOffDoesNotQueryPlaybackTable() = runBlocking {
        val dao = QDao()
        val f = T - HOUR
        dao.insertPlayback(PlaybackRow(sessionKey = "s1", pkg = "com.radio", name = "电台",
            title = "旧节目", subtitle = null, fromAt = f, untilAt = f + 60_000, lastSeenAt = f + 60_000))
        dao.advanceCoveredUntil(SCREEN_SOURCE, T)
        val env = Env(on = true).apply { lastPull = T }
        val result = env.queries(QSource(), dao, titlesAllowed = false).sessions(f, T)
        assertEquals(0, dao.playbackReads)
        assertEquals(emptyList<DevicePlayback>(), result.devicePlayback)
    }
}

class ScreenTimeTextTest {
    /** 必测 6（前半）：【】｜《》与换行类字符各换成一个空格，去首尾空白。 */
    @Test fun cleanReplacesNewlinesAndBrackets() {
        assertEquals("微信  当时在：x", ScreenTimeText.clean("微信】｜当时在：x", 40))
        assertEquals("a  b", ScreenTimeText.clean("a\r\nb\t", 40))
        assertEquals("笔记", ScreenTimeText.clean("《笔记》", 40))
    }

    /** 必测 6（后半）：按 Unicode 码点截断，代理对不拆开，被截断时末尾加「…」（不计入上限）。 */
    @Test fun cleanTruncatesByCodePointsWithoutSplittingSurrogates() {
        assertEquals("😀…", ScreenTimeText.clean("😀😀", 1))
        assertEquals("a😀b…", ScreenTimeText.clean("a😀bc", 3)) // 😀 是代理对，不拆
        assertEquals("😀😀", ScreenTimeText.clean("😀😀", 2)) // 恰好装下不加…
        assertEquals("😀😀", ScreenTimeText.clean("😀😀", 5)) // 未超限原样返回
    }
}
