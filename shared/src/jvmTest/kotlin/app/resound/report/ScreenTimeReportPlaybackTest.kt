package app.resound.report

import app.resound.screentime.CoverageGap
import app.resound.screentime.DevicePlayback
import app.resound.screentime.GapCode
import app.resound.screentime.NamedAppSession
import app.resound.screentime.SCREEN_SOURCE
import app.resound.screentime.ScreenTimeReports
import app.resound.screentime.ScreenTimeResult
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Test

class ScreenTimeReportPlaybackTest {
    private val date = LocalDate(2026, 11, 14)
    private val zone = TimeZone.of("Asia/Shanghai")

    /** 必测 8：有设备播放记录时 body / markdown 多出一段；0 分组丢掉；同 (name, title) 合并。 */
    @Test fun playbackSectionAppearsBetweenAppsAndGaps() {
        val report = ScreenTimeReports.build(date, zone, ScreenTimeResult(
            enabled = true,
            sessions = listOf(NamedAppSession("a.one", "甲", 0, 59 * 60_000L)),
            coverageUntil = 1,
            gaps = listOf(CoverageGap(GapCode.COVERAGE_LAG, SCREEN_SOURCE, 1, 2, "等待下一次采集")),
            devicePlayback = listOf(
                DevicePlayback("app.podcast.cosmos", "小宇宙", "某集", null, 0, 10 * 60_000L),
                DevicePlayback("app.podcast.cosmos", "小宇宙", "某集", null, 10 * 60_000L, 20 * 60_000L),
                DevicePlayback("com.tencent.qqmusic", "QQ音乐", null, null, 0, 5 * 60_000L),
                DevicePlayback("drop.zero", "零分", "短", null, 0, 20_000L),
            ),
        ))!!
        assertEquals(
            "甲 59 分\n设备播放记录：\n小宇宙《某集》 20 分\nQQ音乐 5 分\n部分时段没有数据：等待下一次采集",
            report.body,
        )
        assertEquals(
            "今日共 59 分\n\n- 甲 59 分\n\n设备播放记录（只表示手机在播放，不代表本人在听）：\n- 小宇宙《某集》 20 分\n- QQ音乐 5 分\n\n部分时段没有数据：等待下一次采集",
            report.markdown,
        )
    }

    /** 没有设备播放记录时与现报告逐字相同。基线恒绿，锁的是实现后空列表不插入新段。 */
    @Test fun emptyPlaybackKeepsCurrentBodyAndMarkdown() {
        val report = ScreenTimeReports.build(date, zone, ScreenTimeResult(
            enabled = true,
            sessions = listOf(NamedAppSession("a.one", "甲", 0, 59 * 60_000L)),
            coverageUntil = 1,
            gaps = emptyList(),
        ))!!
        assertEquals("甲 59 分", report.body)
        assertEquals("今日共 59 分\n\n- 甲 59 分", report.markdown)
    }
}
