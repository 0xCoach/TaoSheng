package app.resound.screentime

import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenTimeReportTest {
    private val date = LocalDate(2026, 11, 14)
    private val zone = TimeZone.of("Asia/Shanghai")

    private fun session(pkg: String, name: String, ms: Long, from: Long = 0) =
        NamedAppSession(pkg, name, from, from + ms)

    @Test fun buildTotalsSortAndBothTitleForms() {
        val underHour = ScreenTimeReports.build(date, zone, ScreenTimeResult(
            enabled = true,
            sessions = listOf(session("a.one", "甲", 59 * 60_000L)),
            coverageUntil = 1,
            gaps = emptyList(),
        ))!!
        assertEquals("今日屏幕时间 59 分", underHour.title)
        assertEquals(59, underHour.totalMinutes)
        assertEquals("甲 59 分", underHour.body)
        assertEquals(listOf(AppMinutes("a.one", "甲", 59)), underHour.apps)

        val overHour = ScreenTimeReports.build(date, zone, ScreenTimeResult(
            enabled = true,
            sessions = listOf(
                session("tv.danmaku.bili", "哔哩哔哩", 64 * 60_000L, 0),
                session("com.tencent.mm", "微信", 40 * 60_000L, 10_000_000),
                session("tv.danmaku.bili", "哔哩哔哩", 28 * 60_000L, 20_000_000),
                session("com.same.ten", "同十分乙", 10 * 60_000L, 30_000_000),
                session("com.same.aaa", "同十分甲", 10 * 60_000L, 40_000_000),
                session("z.last", "第六名", 8 * 60_000L, 50_000_000),
                session("z.tail", "垫底", 1 * 60_000L, 60_000_000),
            ),
            coverageUntil = 1,
            gaps = listOf(
                CoverageGap(GapCode.COVERAGE_LAG, SCREEN_SOURCE, 1, 2, "等待下一次采集"),
                CoverageGap(GapCode.PULL_FAILED, SCREEN_SOURCE, 3, 4, "等待下一次采集"),
                CoverageGap(GapCode.USAGE_ACCESS_MISSING, SCREEN_SOURCE, 5, 6, "设置 → 使用情况访问 → 涛声 开启"),
            ),
        ), topApps = 5)!!
        assertEquals(161, overHour.totalMinutes)
        assertEquals("今日屏幕时间 2 小时 41 分", overHour.title)
        assertEquals(
            listOf(
                AppMinutes("tv.danmaku.bili", "哔哩哔哩", 92),
                AppMinutes("com.tencent.mm", "微信", 40),
                AppMinutes("com.same.aaa", "同十分甲", 10),
                AppMinutes("com.same.ten", "同十分乙", 10),
                AppMinutes("z.last", "第六名", 8),
                AppMinutes("z.tail", "垫底", 1),
            ),
            overHour.apps,
        )
        assertEquals(
            "哔哩哔哩 92 分\n微信 40 分\n同十分甲 10 分\n同十分乙 10 分\n第六名 8 分\n部分时段没有数据：等待下一次采集；设置 → 使用情况访问 → 涛声 开启",
            overHour.body,
        )
        assertEquals(
            "今日共 2 小时 41 分\n\n- 哔哩哔哩 92 分\n- 微信 40 分\n- 同十分甲 10 分\n- 同十分乙 10 分\n- 第六名 8 分\n\n部分时段没有数据：等待下一次采集；设置 → 使用情况访问 → 涛声 开启",
            overHour.markdown,
        )
        assertTrue(overHour.apps.any { it.name == "垫底" })
        assertTrue(!overHour.body.contains("垫底"))
        assertTrue(!overHour.markdown.contains("垫底"))
        assertEquals(1, ScreenTimeReports.roundMinutes(40_000L))
        assertEquals(1, ScreenTimeReports.roundMinutes(80_000L))
        val split = ScreenTimeReports.build(date, zone, ScreenTimeResult(
            enabled = true,
            sessions = listOf(session("tiny.a", "短A", 20_000L), session("tiny.b", "短B", 20_000L)),
            coverageUntil = null,
            gaps = emptyList(),
        ))!!
        assertEquals(1, split.totalMinutes)
        assertEquals(0, split.apps[0].minutes)
        assertEquals(0, split.apps[1].minutes)
        assertTrue(split.totalMinutes != split.apps.sumOf { it.minutes })
    }

    @Test fun disabledResultReturnsNull() {
        assertNull(ScreenTimeReports.build(date, zone, ScreenTimeResult.DISABLED))
        assertNull(ScreenTimeReports.build(date, zone, ScreenTimeResult(false, listOf(session("a", "A", 60_000L)), 1, emptyList())))
    }
}
