package app.resound.screentime

import app.resound.diagnostics.FailureNotes
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** 内存假库；无事务，用「先校验后落盘」模拟 commitPull 的原子性，语义与 Room @Transaction 对齐。 */
private class FakeDao : ScreenTimeDao {
    val rows = mutableListOf<ScreenEventRow>()
    val names = mutableMapOf<String, ScreenAppNameRow>()
    val states = mutableMapOf<String, ScreenPullStateRow>()
    val gaps = mutableListOf<ScreenCoverageGapRow>()
    var failNames = false
    override suspend fun events(from: Long, until: Long) = rows.filter { it.at >= from && it.at < until }.sortedWith(compareBy({ it.at }, { it.id }))
    override suspend fun names() = names.values.sortedBy { it.pkg }
    override suspend fun state(source: String) = states[source]
    override suspend fun gaps(source: String, from: Long, until: Long) = gaps.filter { it.source == source && it.fromAt < until && it.untilAt > from }.sortedBy { it.fromAt }
    override suspend fun insertEvents(events: List<ScreenEventRow>) {
        for (e in events) if (rows.none { it.at == e.at && it.pkg == e.pkg && it.type == e.type && it.cls == e.cls }) rows += e
    }
    override suspend fun upsertNames(names: List<ScreenAppNameRow>) {
        if (failNames) throw IllegalStateException("names failed")
        names.forEach { this.names[it.pkg] = it }
    }
    override suspend fun insertStateRow(row: ScreenPullStateRow) = if (states.containsKey(row.source)) -1L else { states[row.source] = row; 1L }
    override suspend fun updateCoveredUntil(source: String, value: Long) { states[source]?.let { states[source] = it.copy(coveredUntil = maxOf(it.coveredUntil ?: 0L, value)) } }
    override suspend fun updateFloor(source: String, value: Long) { states[source]?.let { states[source] = it.copy(floor = maxOf(it.floor, value)) } }
    override suspend fun deleteAllNames() { names.clear() }
    override suspend fun deleteUnreferencedNames() {
        val used = rows.map { it.pkg }.toSet()
        names.keys.removeAll { it !in used }
    }
    override suspend fun recordGap(gap: ScreenCoverageGapRow) { gaps += gap }
    override suspend fun overlappingGaps(from: Long, until: Long) =
        gaps.filter { it.fromAt < until && it.untilAt > from }.sortedBy { it.fromAt }
    override suspend fun deleteGap(id: Long) { gaps.removeAll { it.id == id } }
    override suspend fun deleteGaps(from: Long, until: Long) {
        val next = remainingGapsAfterDelete(gaps.toList(), from, until)
        gaps.clear(); gaps.addAll(next)
    }
    override suspend fun deleteEvents(from: Long, until: Long) { rows.removeAll { it.at >= from && it.at < until } }
    override suspend fun lastEventAt() = rows.maxOfOrNull { it.at }
    override suspend fun openPlayback(sessionKey: String): PlaybackRow? = null
    override suspend fun insertPlayback(row: PlaybackRow) = 0L
    override suspend fun updatePlayback(row: PlaybackRow) {}
    override suspend fun openPlaybacks() = emptyList<PlaybackRow>()
    override suspend fun closeOpenPlaybacksBefore(enabledAt: Long) {}
    override suspend fun playbacksOverlapping(from: Long, until: Long) = emptyList<PlaybackRow>()
    override suspend fun openEndedGaps(source: String, openUntil: Long) = emptyList<ScreenCoverageGapRow>()
    override suspend fun closeGap(id: Long, untilAt: Long) {}
    override suspend fun deletePlaybackInside(from: Long, until: Long) {}
    override suspend fun truncatePlaybackLeft(from: Long) {}
    override suspend fun truncatePlaybackRight(from: Long, until: Long) {}
    override suspend fun commitPull(events: List<ScreenEventRow>, names: List<ScreenAppNameRow>, coveredUntil: Long,
                                    gaps: List<ScreenCoverageGapRow>) {
        val kept = retainPullAfterFloor(events, names, gaps, state(SCREEN_SOURCE)?.floor ?: 0L)
        if (failNames && kept.names.isNotEmpty()) throw IllegalStateException("names failed")
        if (kept.events.isNotEmpty()) insertEvents(kept.events)
        if (kept.names.isNotEmpty()) upsertNames(kept.names)
        kept.gaps.forEach { recordGap(it) }
        if (insertStateRow(ScreenPullStateRow(SCREEN_SOURCE, coveredUntil, 0)) == -1L) updateCoveredUntil(SCREEN_SOURCE, coveredUntil)
    }
}

/** 断言被调用即失败的严格事件源；验证开关关闭时不碰任何方法。 */
private class StrictSource : ScreenEventSource {
    override fun permitted() = error("开关关闭时不得调用 permitted")
    override suspend fun since(from: Long, until: Long) = error("开关关闭时不得调用 since")
    override suspend fun appNames(pkgs: Set<String>) = error("开关关闭时不得调用 appNames")
}

private class FakeSource(var permitted: Boolean = true, var events: List<RawScreenEvent> = emptyList(),
    var names: Map<String, String> = emptyMap()) : ScreenEventSource {
    val calls = mutableListOf<Pair<Long, Long>>()
    override fun permitted() = permitted
    override suspend fun since(from: Long, until: Long): List<RawScreenEvent> { calls += from to until; return events }
    override suspend fun appNames(pkgs: Set<String>) = names.filterKeys { it in pkgs }
}

/** 固定时钟与内存开关的测试环境；clock 默认 1_000_000_000_000。 */
private class FakeSettings(var on: Boolean = true) {
    var clock = 1_000_000_000_000L
    var lastPull = 0L
    var enabledAt = 0L
    var disabledAt = 0L
    var disabledWindows = ""
    val settings = ScreenTimeSettings(
        readBoolean = { _, _ -> on },
        persist = { bools, longs, strs ->
            bools[ScreenTimeSettings.KEY]?.let { on = it }
            longs[ScreenTimeSettings.ENABLED_AT_KEY]?.let { enabledAt = it }
            longs[ScreenTimeSettings.DISABLED_AT_KEY]?.let { disabledAt = it }
            longs[ScreenTimeSettings.LAST_PULL_KEY]?.let { lastPull = it }
            strs[ScreenTimeSettings.DISABLED_WINDOWS_KEY]?.let { disabledWindows = it }
            true
        },
        readLong = { key, _ -> when (key) {
            ScreenTimeSettings.ENABLED_AT_KEY -> enabledAt
            ScreenTimeSettings.DISABLED_AT_KEY -> disabledAt
            else -> lastPull } },
        now = { clock },
        readString = { key, def -> if (key == ScreenTimeSettings.DISABLED_WINDOWS_KEY) disabledWindows else def })
    fun puller(source: ScreenEventSource, dao: ScreenTimeDao, denied: (Throwable) -> Boolean = { false }) =
        ScreenTimePuller(settings, source, dao, now = { clock }, denied = denied)
}

private fun resumed(at: Long, pkg: String = "com.example.app", cls: String? = "com.example.MainActivity") = RawScreenEvent(at, pkg, cls, 1)

class ScreenTimePullerTest {
    @Test fun disabledSkipsSourceEntirely() = runBlocking {
        val dao = FakeDao()
        val result = FakeSettings(on = false).puller(StrictSource(), dao).pull()
        assertEquals(PullResult.Disabled, result)
        assertEquals(0, dao.rows.size); assertNull(dao.state(SCREEN_SOURCE)); assertEquals(0, dao.gaps.size)
    }

    @Test fun noPermissionLeavesEverythingUntouched() = runBlocking {
        val dao = FakeDao()
        val source = FakeSource(permitted = false, events = listOf(resumed(500)))
        val env = FakeSettings()
        assertEquals(PullResult.NoPermission, env.puller(source, dao).pull())
        assertEquals(0, source.calls.size); assertEquals(0, dao.rows.size); assertEquals(0, dao.gaps.size)
        assertEquals(0L, env.lastPull)
    }

    @Test fun failedNameWriteLeavesNoSideEffects() = runBlocking {
        val dao = FakeDao().apply { failNames = true }
        val source = FakeSource(events = listOf(resumed(500), RawScreenEvent(600, "sys", null, 16)),
            names = mapOf("com.example.app" to "示例"))
        val env = FakeSettings()
        val result = env.puller(source, dao).pull()
        assertEquals("IllegalStateException", (result as PullResult.Failed).type)
        assertEquals(0, dao.rows.size); assertNull(dao.state(SCREEN_SOURCE)); assertEquals(0, dao.gaps.size)
        assertEquals(0L, env.lastPull)
    }

    @Test fun emptyPullStillAdvancesCoveredUntil() = runBlocking {
        val dao = FakeDao()
        val env = FakeSettings()
        val clock = env.clock
        assertEquals(PullResult.Pulled(0), env.puller(FakeSource(), dao).pull())
        assertEquals(clock, dao.state(SCREEN_SOURCE)?.coveredUntil)
    }

    @Test fun duplicatesAndUnknownTypesAreDropped() = runBlocking {
        val dao = FakeDao()
        val env = FakeSettings()
        val source = FakeSource(events = listOf(resumed(500), resumed(500),
            resumed(501, cls = null), RawScreenEvent(502, "sys", "X", 11)))
        assertEquals(PullResult.Pulled(3), env.puller(source, dao).pull()) // 过滤后 3 条，落库 2 行
        assertEquals(listOf(500L, 501L), dao.rows.map { it.at })
        assertEquals("", dao.rows.single { it.at == 501L }.cls) // 无类名事件存空串
        env.clock += 31_000
        assertEquals(PullResult.Pulled(3), env.puller(source, dao).pull()) // 同一批重拉不产生重复行
        assertEquals(listOf(500L, 501L), dao.rows.map { it.at })
    }

    @Test fun firstPullStartsAtTenDaysBack() = runBlocking {
        val dao = FakeDao()
        val env = FakeSettings()
        val source = FakeSource()
        env.puller(source, dao).pull()
        assertEquals(env.clock - ScreenTimePuller.HORIZON_MILLIS to env.clock, source.calls.single())
    }

    @Test fun staleCoverageBeyondHorizonRecordsPullFailedGap() = runBlocking {
        val dao = FakeDao()
        val covered = 1_000_000_000_000L - 12 * 24 * 3_600_000L
        dao.advanceCoveredUntil(SCREEN_SOURCE, covered)
        val env = FakeSettings()
        val source = FakeSource()
        assertEquals(PullResult.Pulled(0), env.puller(source, dao).pull())
        val gap = dao.gaps.single()
        assertEquals("pull_failed", gap.code)
        assertEquals(covered, gap.fromAt)
        assertEquals(1_000_000_000_000L - ScreenTimePuller.HORIZON_MILLIS, gap.untilAt)
        assertEquals(1_000_000_000_000L - ScreenTimePuller.HORIZON_MILLIS, source.calls.single().first) // 起点收到保留期下限
    }

    @Test fun coveredUntilNeverGoesBackwards() = runBlocking {
        val dao = FakeDao()
        dao.advanceCoveredUntil(SCREEN_SOURCE, 1_000)
        dao.advanceCoveredUntil(SCREEN_SOURCE, 500)
        assertEquals(1_000L, dao.state(SCREEN_SOURCE)?.coveredUntil)
    }

    @Test fun secondPullWithinThrottleIsRejected() = runBlocking {
        val dao = FakeDao()
        val env = FakeSettings()
        val puller = env.puller(FakeSource(), dao)
        assertEquals(PullResult.Pulled(0), puller.pull())
        env.clock += 10_000
        assertEquals(PullResult.Throttled, puller.pull())
        env.clock += 21_000 // 距上次成功已超 30 秒
        assertEquals(PullResult.Pulled(0), puller.pull())
    }

    @Test fun repullStartsAtPreviousCoveredUntil() = runBlocking {
        val dao = FakeDao()
        val env = FakeSettings()
        val source = FakeSource()
        env.puller(source, dao).pull()
        val first = source.calls.single()
        env.clock += 31_000
        assertEquals(PullResult.Pulled(0), env.puller(source, dao).pull())
        assertEquals(first.second, source.calls.last().first) // 起点接续上次覆盖终点（含）
    }

    @Test fun securityExceptionBecomesNoPermissionAndOtherErrorsBecomeFailed() = runBlocking {
        val dao = FakeDao()
        val env = FakeSettings()
        val denied = object : ScreenEventSource {
            override fun permitted() = true
            override suspend fun since(from: Long, until: Long): List<RawScreenEvent> = throw SecurityException("denied")
            override suspend fun appNames(pkgs: Set<String>) = emptyMap<String, String>()
        }
        assertEquals(PullResult.NoPermission, env.puller(denied, dao, denied = { it is SecurityException }).pull())
        assertNull(dao.state(SCREEN_SOURCE))
        val broken = object : ScreenEventSource {
            override fun permitted() = true
            override suspend fun since(from: Long, until: Long): List<RawScreenEvent> = throw IllegalArgumentException("boom")
            override suspend fun appNames(pkgs: Set<String>) = emptyMap<String, String>()
        }
        assertEquals("IllegalArgumentException", (env.puller(broken, dao).pull() as PullResult.Failed).type)
    }

    /** 关闭前的使用不丢。T0 已覆盖、T1 事件、T2 关、T3 事件（关闭期间）、T4 开、T5 事件；
     *  拉取保留 T1、T5，丢弃 T3；缺口从关闭时刻 T2 起算（不是上次采集时刻），且只写一条 disabled [T2, T4)。 */
    @Test fun eventsBeforeSwitchOffAreKeptAndDisabledGapStartsAtDisabledAt() = runBlocking {
        val dao = FakeDao()
        val env = FakeSettings(on = true)
        val t0 = env.clock
        dao.commitPull(emptyList(), emptyList(), t0, emptyList()) // 已覆盖到 t0
        val t1 = t0 + 60_000
        val t2 = t0 + 120_000
        env.clock = t2
        env.settings.setEnabled(false) // 关：写入 disabledAt
        assertEquals(t2, env.disabledAt)
        val t3 = t0 + 180_000 // 关闭期间系统仍有事件
        val t4 = t0 + 240_000
        env.clock = t4
        env.settings.setEnabled(true) // 开：写入 enabledAt
        assertEquals(t4, env.enabledAt)
        val t5 = t0 + 300_000
        env.clock = t5 + 31_000
        val source = FakeSource(events = listOf(resumed(t1), resumed(t3), resumed(t5)))
        assertEquals(PullResult.Pulled(2), env.puller(source, dao).pull())
        assertEquals(listOf(t1, t5), dao.rows.map { it.at }) // T1、T5 保留，T3 丢弃
        val gap = dao.gaps.single()
        assertEquals("disabled", gap.code)
        assertEquals(t2, gap.fromAt)
        assertEquals(t4, gap.untilAt)
    }

    /** 第一次打开（从未提交过）起点不早于 enabledAt，不写任何缺口。 */
    @Test fun firstPullAfterEnableStartsAtEnabledAtAndWritesNoGap() = runBlocking {
        val dao = FakeDao()
        val env = FakeSettings(on = false)
        env.clock += 3_600_000
        env.settings.setEnabled(true)
        val source = FakeSource()
        assertEquals(PullResult.Pulled(0), env.puller(source, dao).pull())
        assertEquals(env.enabledAt, source.calls.single().first)
        assertEquals(0, dao.gaps.size)
    }

    /** 升级兼容：关闭过但没有关闭时刻（disabledAt == 0）
     *  且上次覆盖早于本次打开，起点圈到 enabledAt，缺口 disabled [上次覆盖, enabledAt)；
     *  覆盖推进后不重复写。 */
    @Test fun legacyReopenWithoutDisabledAtClipsToEnabledAt() = runBlocking {
        val dao = FakeDao()
        val coveredBefore = 1_000_000_000_000L - 3_600_000
        dao.commitPull(emptyList(), emptyList(), coveredBefore, emptyList()) // 关闭前覆盖到 coveredBefore
        val env = FakeSettings(on = false)
        env.clock = coveredBefore + 1_800_000 // 关闭 30 分钟后重新打开
        env.settings.setEnabled(true)
        assertEquals(env.clock, env.enabledAt) // 关→开写入 enabledAt
        assertEquals(0L, env.disabledAt) // 升级用户：没有关闭时刻
        val source = FakeSource()
        assertEquals(PullResult.Pulled(0), env.puller(source, dao).pull())
        assertEquals(env.enabledAt, source.calls.single().first) // since 起点正好是 enabledAt
        assertEquals(1, dao.gaps.size)
        val gap = dao.gaps.single()
        assertEquals("disabled", gap.code)
        assertEquals(coveredBefore, gap.fromAt)
        assertEquals(env.enabledAt, gap.untilAt)
        env.clock += 31_000
        assertEquals(PullResult.Pulled(0), env.puller(source, dao).pull())
        assertEquals(1, dao.gaps.size) // 覆盖已推进，不重复写
    }

    /** 缺口不重复。事件源在 since 抛错时不写任何缺口、覆盖进度不变；随后一次成功拉取只写一条缺口。 */
    @Test fun gapIsNotWrittenWhenSourceThrowsAndCommitsOnceOnSuccess() = runBlocking {
        val dao = FakeDao()
        val env = FakeSettings(on = true)
        val covered = env.clock - 3_600_000
        dao.commitPull(emptyList(), emptyList(), covered, emptyList())
        env.clock += 1_800_000
        env.settings.setEnabled(false) // 期间关过一次（有打开时刻之前先关闭，disabledAt 落库）
        env.clock += 600_000
        env.settings.setEnabled(true)
        val broken = object : ScreenEventSource by FakeSource() {
            override suspend fun since(from: Long, until: Long): List<RawScreenEvent> = throw IllegalArgumentException("boom")
        }
        assertEquals("IllegalArgumentException", (env.puller(broken, dao).pull() as PullResult.Failed).type)
        assertEquals(0, dao.gaps.size) // 抛错时不写缺口
        assertEquals(covered, dao.state(SCREEN_SOURCE)?.coveredUntil) // 覆盖进度不变
        assertEquals(PullResult.Pulled(0), env.puller(FakeSource(), dao).pull())
        assertEquals(1, dao.gaps.size) // 成功后只写一条
        assertEquals("disabled", dao.gaps.single().code)
    }

    /** 拉取失败与无使用情况访问权必须进问题记录（不写标题、包名、异常原文），且不落缺口。 */
    @Test fun pullFailureAndMissingUsageAccessAreVisibleInFailureNotes() = runBlocking {
        FailureNotes.resetForTest()
        try {
            val dao = FakeDao()
            val env = FakeSettings()
            assertEquals(PullResult.NoPermission, env.puller(FakeSource(permitted = false), dao).pull())
            assertTrue(FailureNotes.dump().contains("screentime/ScreenTimePuller:NoPermission"))
            assertEquals(0, dao.gaps.size)
            val broken = object : ScreenEventSource {
                override fun permitted() = true
                override suspend fun since(from: Long, until: Long): List<RawScreenEvent> =
                    throw IllegalArgumentException("secret-pkg-title")
                override suspend fun appNames(pkgs: Set<String>) = emptyMap<String, String>()
            }
            assertEquals("IllegalArgumentException", (env.puller(broken, dao).pull() as PullResult.Failed).type)
            val notes = FailureNotes.dump()
            assertTrue(notes.contains("screentime/ScreenTimePuller:Failed IllegalArgumentException"))
            assertFalse(notes.contains("secret-pkg-title"))
            assertEquals(0, dao.gaps.size)
        } finally {
            FailureNotes.resetForTest()
        }
    }

    @Test fun twoDisableCyclesWithoutPullDoNotBackfillEarlierOffPeriod() = runBlocking {
        val dao = FakeDao()
        val env = FakeSettings(on = true)
        val t0 = env.clock
        dao.commitPull(emptyList(), emptyList(), t0, emptyList())
        env.clock = t0 + 60_000
        env.settings.setEnabled(false)
        env.clock = t0 + 120_000
        env.settings.setEnabled(true)
        env.clock = t0 + 180_000
        env.settings.setEnabled(false)
        env.clock = t0 + 240_000
        env.settings.setEnabled(true)
        val duringFirstOff = t0 + 90_000
        val duringSecondOff = t0 + 210_000
        val after = t0 + 300_000
        env.clock = after + 31_000
        val source = FakeSource(events = listOf(
            resumed(duringFirstOff, "disabled.private.app"),
            resumed(duringSecondOff),
            resumed(after),
        ))
        assertEquals(PullResult.Pulled(1), env.puller(source, dao).pull())
        assertEquals(listOf(after), dao.rows.map { it.at })
        assertTrue(source.calls.none { (from, until) -> duringFirstOff >= from && duringFirstOff < until })
        assertEquals(2, dao.gaps.count { it.code == GapCode.DISABLED })
    }

    @Test fun secondSuccessfulPullDoesNotDuplicateDisabledGaps() = runBlocking {
        val dao = FakeDao()
        val env = FakeSettings(on = true)
        val t0 = env.clock
        dao.commitPull(emptyList(), emptyList(), t0, emptyList())
        env.clock = t0 + 60_000
        env.settings.setEnabled(false)
        env.clock = t0 + 120_000
        env.settings.setEnabled(true)
        env.clock = t0 + 180_000
        env.settings.setEnabled(false)
        env.clock = t0 + 240_000
        env.settings.setEnabled(true)
        env.clock = t0 + 271_000
        assertEquals(PullResult.Pulled(0), env.puller(FakeSource(), dao).pull())
        assertEquals(2, dao.gaps.count { it.code == GapCode.DISABLED })
        env.clock += 31_000
        assertEquals(PullResult.Pulled(0), env.puller(FakeSource(), dao).pull())
        assertEquals(2, dao.gaps.count { it.code == GapCode.DISABLED })
        val windows = dao.gaps.filter { it.code == GapCode.DISABLED }.map { it.fromAt to it.untilAt }
        assertEquals(windows.distinct(), windows)
    }

    @Test fun commitDropsEventsBelowFloorRaisedDuringSince() = runBlocking {
        val dao = FakeDao()
        val env = FakeSettings()
        val source = object : ScreenEventSource {
            override fun permitted() = true
            override suspend fun since(from: Long, until: Long): List<RawScreenEvent> {
                dao.raiseFloor(SCREEN_SOURCE, 999_999_999_000L)
                return listOf(resumed(999_999_950_000L, "private.app"))
            }
            override suspend fun appNames(pkgs: Set<String>) = mapOf("private.app" to "私")
        }
        assertEquals(PullResult.Pulled(0), env.puller(source, dao).pull())
        assertTrue(dao.rows.isEmpty())
        assertTrue(dao.names.isEmpty())
        assertEquals(999_999_999_000L, dao.state(SCREEN_SOURCE)?.floor)
    }
}

class ScreenTimeSettingsTest {
    @Test fun reenablingWritesEnabledAtOnlyWhenSwitchingFromOff() {
        val env = FakeSettings(on = false)
        env.settings.setEnabled(true)
        assertEquals(env.clock, env.enabledAt) // 关→开写入 enabledAt
        assertEquals(0L, env.disabledAt) // 关→开不动 disabledAt
        env.settings.setEnabled(true)
        assertEquals(env.clock, env.enabledAt) // 已开再开不重写
        env.clock += 60_000
        env.settings.setEnabled(false)
        assertEquals(env.clock - 60_000, env.enabledAt) // 开→关不动 enabledAt，保持上次打开时刻
        assertEquals(env.clock, env.disabledAt) // 开→关写入关闭时刻
        env.clock += 60_000
        env.settings.setEnabled(false)
        assertEquals(env.clock - 60_000, env.disabledAt) // 已关再关不重写
        env.settings.setEnabled(true)
        assertEquals(env.clock, env.enabledAt) // 再次关→开重写 enabledAt
        assertEquals(env.clock - 60_000, env.disabledAt) // 关→开不动 disabledAt
        assertTrue(env.settings.enabled.value)
        assertEquals(0L, FakeSettings(on = false).enabledAt) // 从未打开为 0
        assertEquals(0L, FakeSettings(on = false).disabledAt) // 从未关闭为 0
    }

    @Test fun twoOffOnCyclesKeepBothDisabledWindows() {
        val env = FakeSettings(on = true)
        env.clock += 10
        env.settings.setEnabled(false)
        val firstOff = env.clock
        env.clock += 10
        env.settings.setEnabled(true)
        val firstOn = env.clock
        env.clock += 10
        env.settings.setEnabled(false)
        val secondOff = env.clock
        env.clock += 10
        env.settings.setEnabled(true)
        val secondOn = env.clock
        assertEquals(listOf(firstOff to firstOn, secondOff to secondOn), env.settings.disabledWindows())
    }

    @Test fun defaultIsOff() {
        assertFalse(FakeSettings(on = false).settings.enabled.value)
    }

    @Test fun statePublishesOnlyAfterSaveCommits() {
        val settings = ScreenTimeSettings(
            readBoolean = { _, _ -> false }, persist = { _, _, _ -> false },
            readLong = { _, def -> def },
            now = { error("提交失败不得写 enabledAt") })
        try { settings.setEnabled(true) } catch (_: IllegalStateException) {}
        assertFalse(settings.enabled.value) // 提交失败不发布状态
    }

    @Test fun failedEnableCommitDoesNotBackfillClosedPeriodAfterRebuild() = runBlocking {
        val t = 1_000_000_000_000L
        val persistedBool = mutableMapOf(ScreenTimeSettings.KEY to false)
        val persistedLong = mutableMapOf(
            ScreenTimeSettings.ENABLED_AT_KEY to t - 600_000,
            ScreenTimeSettings.DISABLED_AT_KEY to t - 300_000,
        )
        val persistedStr = mutableMapOf<String, String>()
        val broken = ScreenTimeSettings(
            readBoolean = { key, def -> persistedBool[key] ?: def },
            persist = persist@{ bools, longs, strings ->
                if (longs.isNotEmpty() || strings.isNotEmpty()) return@persist false
                persistedBool.putAll(bools)
                true
            },
            readLong = { key, def -> persistedLong[key] ?: def },
            now = { t },
            readString = { key, def -> persistedStr[key] ?: def },
        )
        try { broken.setEnabled(true) } catch (_: IllegalStateException) {}
        assertFalse(broken.enabled.value)
        assertEquals(false, persistedBool[ScreenTimeSettings.KEY])
        val restored = ScreenTimeSettings(
            readBoolean = { key, def -> persistedBool[key] ?: def },
            persist = { bools, longs, strs ->
                persistedBool.putAll(bools); persistedLong.putAll(longs); persistedStr.putAll(strs); true
            },
            readLong = { key, def -> persistedLong[key] ?: def },
            now = { t },
            readString = { key, def -> persistedStr[key] ?: def },
        )
        val dao = FakeDao()
        dao.states[SCREEN_SOURCE] = ScreenPullStateRow(SCREEN_SOURCE, t - 400_000, 0)
        val closedAt = t - 200_000
        val source = FakeSource(events = listOf(resumed(closedAt, "off.private.app")))
        val result = ScreenTimePuller(restored, source, dao, now = { t }, denied = { false }).pull()
        assertEquals(PullResult.Disabled, result)
        assertTrue(source.calls.none { (from, until) -> closedAt >= from && closedAt < until })
        assertTrue(dao.rows.none { it.at == closedAt })
    }
}
