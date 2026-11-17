@file:OptIn(kotlin.time.ExperimentalTime::class)
package app.resound.context

import app.resound.platform.ZoneHistory
import app.resound.screentime.*
import kotlinx.datetime.*
import org.junit.Test
import org.junit.Assert.*

class DayThread200Test {
    private val zones = ZoneHistory(emptyList(), "Asia/Shanghai")
    private val date = LocalDate(2026, 11, 16)
    private val start = zones.zonesOn(date).first().from
    private fun t(m: Int) = start + m * 60_000L
    private fun app(a: Int, b: Int, name: String = "微信") = NamedAppSession(name, name, t(a), t(b))
    private fun input() = ThreadInput(date, start, t(1440), 0)
    @Test fun appStrictGapAndFiveMinutes() {
        val rows = DayThreads.build(input().copy(apps = listOf(app(600,603), app(604,606), app(608,610))), zones).entries
        assertEquals(2, rows.size)
        assertEquals("📱 微信 5 分", rows[0].text)
        assertTrue(rows[1].hourly)
    }
    @Test fun shortAppsSplitHoursAndLimitThree() {
        val rows = DayThreads.build(input().copy(apps = listOf(app(659,662), app(663,664,"A"),app(665,666,"B"),app(667,668,"C"))), zones).entries
        assertEquals(2, rows.size)
        assertTrue(rows[0].text.contains("微信 1 分"))
        assertTrue(rows[1].text.endsWith("等"))
        assertTrue(rows[1].text.contains("微信 2 分"))
    }
    @Test fun placesIncludeRoadAndOutsideTime() {
        val result = DayThreads.build(input().copy(places = listOf(PlaceAt("家",true,t(0),t(510)), PlaceAt("公司",false,t(532),t(1080)))), zones)
        assertEquals("📍 家 → 公司（路上）", result.entries[1].text)
        assertEquals(570 * 60_000L, result.overview.outsideMillis)
        assertEquals(2, result.overview.places)
    }
    @Test fun eventContextAndOrdering() {
        val result = DayThreads.build(input().copy(events = listOf(ThreadEvent(12,t(600),t(630),"通话", "公司", call=true)),
            places = listOf(PlaceAt("公司",false,t(600),t(700))),apps=listOf(app(600,606,"飞书"))),zones)
        assertEquals(listOf(ThreadKind.Place,ThreadKind.Event,ThreadKind.App),result.entries.map { it.kind })
        assertEquals("📞 通话（E12） · 当时在用 飞书 6 分",result.entries[1].text)
    }
    @Test fun gapsUseUnionAndKnownPause() {
        val gaps = recordingGaps(t(0), t(120), listOf(RecordingSpan(t(30),t(60)),RecordingSpan(t(45),t(90))), listOf(RecordingState(t(90),true,"停录时段：睡眠")))
        assertEquals(listOf("没有录音","停录时段：睡眠"),gaps.map { it.reason })
        assertEquals(2,DayThreads.build(input().copy(gaps=gaps + ThreadGap(t(130),t(159))),zones).entries.size)
    }
    @Test fun togglesRemoveEntireClassesAndOverview() {
        val result=DayThreads.build(input().copy(location=false,screen=false,events=listOf(ThreadEvent(1,t(1),t(10),"事情","秘密地点")),places=listOf(PlaceAt("秘密地点",false,t(0),t(30))), apps=listOf(app(1,10)), playback=listOf(DevicePlayback("p","播放器","节目",null,t(0),t(30))),gaps=listOf(ThreadGap(t(0),t(60),"屏幕时间缺口",true))),zones)
        assertEquals(1,result.entries.size)
        assertFalse(DayThreads.markdown(result,zones).contains("秘密地点"))
        assertNull(result.overview.phoneMillis); assertNull(result.overview.places)
    }
    @Test fun markdownGolden() {
        val result=DayThreads.build(input().copy(speechMillis=144*60_000L,
            events=listOf(ThreadEvent(12,t(90),t(100),"安排今天要做的事","公司")),
            places=listOf(PlaceAt("家",true,t(0),t(30)),PlaceAt("公司",false,t(60),t(120))),
            apps=listOf(app(90,96,"飞书"),app(180,182)),
            gaps=listOf(ThreadGap(t(1200),t(1260))),
            playback=listOf(DevicePlayback("p","小宇宙","AI",null,t(1320),t(1350)))),zones)
        assertEquals("""## 今天概览
说话 2 小时 24 分 · 手机 8 分 · 外出 1 小时 30 分 · 去了 2 个地方 · 1 件事

## 一天的线索
本节时间为 UTC+8
- 00:00–00:30 📍 家
- 00:30–01:00 📍 家 → 公司（路上）
- 01:00–02:00 📍 公司
- 01:30–01:40 💬 安排今天要做的事（E12） · 当时在用 飞书 6 分
- 01:30–01:36 📱 飞书 6 分
- 3 点 · 📱 微信 2 分
- 20:00–21:00 没有录音
- 22:00–22:30 🎧 小宇宙《AI》""",DayThreads.markdown(result,zones))
    }
    @Test fun contiguousPlaybackMergesButDifferentEpisodeDoesNot() {
        val rows=DayThreads.build(input().copy(playback=listOf(DevicePlayback("p","小宇宙","AI",null,t(0),t(10)),DevicePlayback("p","小宇宙","AI",null,t(10),t(20)),DevicePlayback("p","小宇宙","新节目",null,t(20),t(30)))),zones).entries
        assertEquals(2,rows.size); assertEquals(t(20),rows.first().end)
    }
}
