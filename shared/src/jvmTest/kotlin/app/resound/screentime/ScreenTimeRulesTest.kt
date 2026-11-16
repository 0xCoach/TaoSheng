@file:OptIn(kotlin.time.ExperimentalTime::class)
package app.resound.screentime

import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atTime
import kotlinx.datetime.toInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScreenTimeRulesTest {
    private val t = 1_000_000_000_000L
    private val shanghai = TimeZone.of("Asia/Shanghai")

    private fun ev(id: Long, at: Long, pkg: String, type: ScreenEventType, cls: String = "Main") =
        ScreenEvent(id, at, pkg, cls, type)

    /** 查询窗口 [from, until) 内重放；coveredUntil 默认给到 until，视为覆盖完整。 */
    private fun run(events: List<ScreenEvent>, from: Long = t, until: Long = t + 3_600_000, coveredUntil: Long = until,
                    excluded: Set<String> = emptySet()) =
        ScreenTimeRules.sessions(events, from, until, coveredUntil, excluded)

    @Test fun eventTypeOfMapsKnownCodesAndRejectsUnknown() {
        assertEquals(ScreenEventType.RESUMED, ScreenEventType.of(1))
        assertEquals(ScreenEventType.STOPPED, ScreenEventType.of(23))
        assertEquals(ScreenEventType.SHUTDOWN, ScreenEventType.of(26))
        assertEquals(ScreenEventType.STARTUP, ScreenEventType.of(27))
        assertNull(ScreenEventType.of(99))
    }

    /** 测试 1：同包切页（页1暂停→页2恢复→页1停止被忽略），20 分钟熄屏才收尾，整段不断。 */
    @Test fun sameAppPageSwitchKeepsOneSession() {
        val events = listOf(
            ev(1, t, "A", ScreenEventType.RESUMED, "Page1"),
            ev(2, t + 60_000, "A", ScreenEventType.PAUSED, "Page1"),
            ev(3, t + 60_000, "A", ScreenEventType.RESUMED, "Page2"),
            ev(4, t + 120_000, "A", ScreenEventType.STOPPED, "Page1"),
            ev(5, t + 1_200_000, "", ScreenEventType.SCREEN_OFF),
        )
        assertEquals(listOf(AppSession("A", t, t + 1_200_000)), run(events))
    }

    /** 测试 2：熄屏收尾，一段 10 分钟。 */
    @Test fun screenOffClosesSession() {
        val events = listOf(
            ev(1, t, "A", ScreenEventType.RESUMED),
            ev(2, t + 600_000, "", ScreenEventType.SCREEN_OFF),
        )
        assertEquals(listOf(AppSession("A", t, t + 600_000)), run(events))
    }

    /** 锁屏后出现 RESUMED 即视为已解锁，不再空等 KEYGUARD_HIDDEN。 */
    @Test fun resumedWhileKeyguardShownStartsSession() {
        val events = listOf(
            ev(1, t, "", ScreenEventType.KEYGUARD_SHOWN),
            ev(2, t + 64_000, "B", ScreenEventType.RESUMED),
            ev(3, t + 106_000, "", ScreenEventType.KEYGUARD_HIDDEN),
            ev(4, t + 406_000, "", ScreenEventType.SCREEN_OFF),
        )
        assertEquals(listOf(AppSession("B", t + 64_000, t + 406_000)), run(events))
    }

    /** 真机常见序列：熄屏、锁屏显示、亮屏、天气 RESUMED/PAUSED；没有锁屏解除也要得出天气这一段。 */
    @Test fun lockThenScreenOnThenResumedCountsUsageWithoutKeyguardHidden() {
        val events = listOf(
            ev(1, t, "", ScreenEventType.SCREEN_OFF),
            ev(2, t + 1_000, "", ScreenEventType.KEYGUARD_SHOWN),
            ev(3, t + 2_000, "", ScreenEventType.SCREEN_ON),
            ev(4, t + 3_000, "weather", ScreenEventType.RESUMED),
            ev(5, t + 63_000, "weather", ScreenEventType.PAUSED),
        )
        assertEquals(listOf(AppSession("weather", t + 3_000, t + 63_000)), run(events))
    }

    /** 已删除区间把跨越它的使用段截断，缺口内不得再出现使用。 */
    @Test fun excludeRemovesOverlapWithDeletedWindow() {
        val sessions = listOf(AppSession("private.app", t, t + 180_000), AppSession("other.app", t + 180_000, t + 240_000))
        assertEquals(
            listOf(
                AppSession("private.app", t, t + 60_000),
                AppSession("private.app", t + 120_000, t + 180_000),
                AppSession("other.app", t + 180_000, t + 240_000),
            ),
            ScreenTimeRules.exclude(sessions, listOf(t + 60_000 to t + 120_000)),
        )
        assertEquals(emptyList<AppSession>(), ScreenTimeRules.exclude(listOf(AppSession("private.app", t + 60_000, t + 120_000)), listOf(t + 60_000 to t + 120_000)))
    }

    /** 测试 4：关机无熄屏，A 在 SHUTDOWN 时刻收尾；重启后的桌面被过滤。 */
    @Test fun shutdownClosesSessionWithoutScreenOff() {
        val events = listOf(
            ev(1, t, "A", ScreenEventType.RESUMED),
            ev(2, t + 1_800_000, "", ScreenEventType.SHUTDOWN),
            ev(3, t + 1_920_000, "", ScreenEventType.STARTUP),
            ev(4, t + 7_200_000, "com.launcher", ScreenEventType.RESUMED, "Home"),
        )
        val out = run(events, until = t + 7_800_000, excluded = setOf("com.launcher"))
        assertEquals(listOf(AppSession("A", t, t + 1_800_000)), out)
    }

    /** 测试 5：重放结束仍开着的段，截止于 min(until, coveredUntil)，这里止于 coveredUntil。 */
    @Test fun stillOpenSessionClampsToCoveredUntil() {
        val events = listOf(ev(1, t - 1_800_000, "A", ScreenEventType.RESUMED))
        val out = run(events, until = t + 7_200_000, coveredUntil = t + 3_600_000)
        assertEquals(listOf(AppSession("A", t, t + 3_600_000)), out)
    }

    /** 测试 6：起点前已在前台，A 段从查询起点算到 B RESUMED。 */
    @Test fun sessionStartedBeforeWindowIsClippedToFrom() {
        val events = listOf(
            ev(1, t - 1_800_000, "A", ScreenEventType.RESUMED),
            ev(2, t + 600_000, "B", ScreenEventType.RESUMED),
        )
        assertEquals(
            listOf(AppSession("A", t, t + 600_000), AppSession("B", t + 600_000, t + 3_600_000)),
            run(events),
        )
    }

    /** 测试 7：excluded 桌面的 RESUMED 照样给上一段收尾，但桌面自身不输出。 */
    @Test fun excludedPackageClosesPreviousButIsNotReported() {
        val events = listOf(
            ev(1, t, "A", ScreenEventType.RESUMED),
            ev(2, t + 300_000, "com.launcher", ScreenEventType.RESUMED, "Home"),
        )
        val out = run(events, excluded = setOf("com.launcher"))
        assertEquals(listOf(AppSession("A", t, t + 300_000)), out)
    }

    /** A 暂停 4.9 秒后同页 RESUMED，两段在输出里紧邻，合并为一段。 */
    @Test fun nearbySwitchClosedSessionsMerge() {
        val events = listOf(
            ev(1, t, "A", ScreenEventType.RESUMED, "Page1"),
            ev(2, t + 600_000, "A", ScreenEventType.PAUSED, "Page1"),
            ev(3, t + 604_900, "A", ScreenEventType.RESUMED, "Page1"),
        )
        assertEquals(
            listOf(AppSession("A", t, t + 700_000)),
            run(events, until = t + 700_000),
        )
    }

    /** A、B(2 秒)、A 不合并（中间隔了别的输出段），三段互不重叠，totals 总和等于实际亮屏时长。 */
    @Test fun sessionsSeparatedByOtherPackageDoNotMerge() {
        val events = listOf(
            ev(1, t, "A", ScreenEventType.RESUMED),
            ev(2, t + 600_000, "B", ScreenEventType.RESUMED),
            ev(3, t + 602_000, "A", ScreenEventType.RESUMED),
        )
        val out = run(events, until = t + 700_000)
        assertEquals(
            listOf(
                AppSession("A", t, t + 600_000),
                AppSession("B", t + 600_000, t + 602_000),
                AppSession("A", t + 602_000, t + 700_000),
            ),
            out,
        )
        assertEquals(700_000L, ScreenTimeRules.totals(out).sumOf { it.millis }) // 实际亮屏 [t, t+700000) 无重叠
    }

    /** 测试 8b：间隔仅 1 秒但前一段因熄屏收尾，不合并。 */
    @Test fun screenOffClosedSessionsDoNotMerge() {
        val events = listOf(
            ev(1, t, "A", ScreenEventType.RESUMED),
            ev(2, t + 600_000, "", ScreenEventType.SCREEN_OFF),
            ev(3, t + 601_000, "", ScreenEventType.SCREEN_ON),
        )
        assertEquals(
            listOf(AppSession("A", t, t + 600_000), AppSession("A", t + 601_000, t + 700_000)),
            run(events, until = t + 700_000),
        )
    }

    /** 测试 9：同一毫秒的 PAUSED 与 SCREEN_OFF，输入顺序打乱后结果稳定（按 at、id 排序）。 */
    @Test fun sameMillisecondEventsAreOrderStable() {
        val pauseFirst = listOf(
            ev(1, t, "A", ScreenEventType.RESUMED),
            ev(2, t + 600_000, "A", ScreenEventType.PAUSED),
            ev(3, t + 600_000, "", ScreenEventType.SCREEN_OFF),
        )
        val offFirst = listOf(pauseFirst[0], pauseFirst[2], pauseFirst[1])
        val expected = listOf(AppSession("A", t, t + 600_000))
        assertEquals(expected, run(pauseFirst))
        assertEquals(expected, run(offFirst))
    }

    /** 测试 10：23:50–00:10 的段在 Asia/Shanghai 拆成两天各 10 分钟。 */
    @Test fun dayTotalsSplitsAcrossMidnight() {
        val from = LocalDate(2026, 11, 13).atTime(23, 50).toInstant(shanghai).toEpochMilliseconds()
        val until = LocalDate(2026, 11, 14).atTime(0, 10).toInstant(shanghai).toEpochMilliseconds()
        val out = ScreenTimeRules.dayTotals(listOf(AppSession("A", from, until)), shanghai)
        assertEquals(
            mapOf(
                LocalDate(2026, 11, 13) to listOf(AppTotal("A", 600_000)),
                LocalDate(2026, 11, 14) to listOf(AppTotal("A", 600_000)),
            ),
            out,
        )
    }

    /** totals 排序：时长降序，同时长按包名升序。 */
    @Test fun totalsSortByMillisThenPackage() {
        val out = ScreenTimeRules.totals(
            listOf(
                AppSession("B", 0, 1_000),
                AppSession("A", 0, 1_000),
                AppSession("C", 0, 5_000),
                AppSession("A", 2_000, 3_000),
            ),
        )
        assertEquals(listOf(AppTotal("C", 5_000), AppTotal("A", 2_000), AppTotal("B", 1_000)), out)
    }
}
