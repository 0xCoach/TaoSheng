package app.resound.screentime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScreenTimeViewTest {
    private val min = 60_000L
    private val hour = 60 * min
    private val bounds = (0..24).map { it * hour }
    private fun s(pkg: String, from: Long, until: Long, name: String = pkg) = NamedAppSession(pkg, name, from, until)

    @Test fun minutesByHourSplitsAcrossHours() {
        val byHour = ScreenTimeView.minutesByHour(listOf(s("wx", 9 * hour + 50 * min, 10 * hour + 20 * min), s("bili", 21 * hour, 21 * hour + 26 * min)), bounds)
        assertEquals(10, byHour[9]); assertEquals(20, byHour[10]); assertEquals(26, byHour[21]); assertEquals(0, byHour[0])
        assertEquals(24, byHour.size)
    }

    @Test fun minutesByHourCapsAtSixty() {
        val byHour = ScreenTimeView.minutesByHour(listOf(s("a", 8 * hour, 9 * hour), s("b", 8 * hour, 9 * hour)), bounds)
        assertEquals(60, byHour[8])
    }

    @Test fun inUsePicksLongestOverlap() {
        val use = ScreenTimeView.inUse(listOf(s("wx", 0, 6 * min, "微信"), s("fs", 6 * min, 8 * min, "飞书")), 0, 10 * min)
        assertEquals(ScreenTimeView.InUse("wx", "微信", 6), use)
    }

    @Test fun inUseIgnoresGlances() {
        // 不到 2 分钟不算
        assertNull(ScreenTimeView.inUse(listOf(s("wx", 0, 90_000)), 0, 5 * min))
        // 一小时的会只看了 5 分钟手机：不到五分之一，不算
        assertNull(ScreenTimeView.inUse(listOf(s("wx", 0, 5 * min)), 0, hour))
        assertNull(ScreenTimeView.inUse(emptyList(), 0, hour))
    }

    @Test fun inUseSumsSplitSessionsOfSameApp() {
        val use = ScreenTimeView.inUse(listOf(s("wx", 0, 2 * min), s("fs", 2 * min, 4 * min), s("wx", 4 * min, 6 * min)), 0, 6 * min)
        assertEquals("wx", use?.pkg); assertEquals(4, use?.minutes)
    }

    @Test fun appsDuringClipsToRange() {
        val apps = ScreenTimeView.appsDuring(listOf(s("wx", 14 * hour + 50 * min, 15 * hour + 12 * min, "微信"), s("fs", 15 * hour + 20 * min, 15 * hour + 30 * min, "飞书")),
            15 * hour, 16 * hour)
        assertEquals(listOf(AppMinutes("wx", "微信", 12), AppMinutes("fs", "飞书", 10)), apps)
    }

    @Test fun timelineJoinsSameAppAndDropsFragments() {
        val rows = ScreenTimeView.timeline(listOf(
            s("wx", 0, 3 * min), s("wx", 4 * min, 6 * min), // 隔 1 分钟，合成一段，用了 5 分钟
            s("fs", 10 * min, 10 * min + 30_000),            // 半分钟，不列
            s("bili", 20 * min, 46 * min)))
        assertEquals(listOf("bili", "wx"), rows.map { it.pkg })
        assertEquals(5, rows[1].minutes); assertEquals(6 * min, rows[1].until)
    }

    @Test fun averageSkipsEmptyDays() {
        assertEquals(150, ScreenTimeView.averageMinutes(listOf(0, 0, 100, 200)))
        assertNull(ScreenTimeView.averageMinutes(listOf(0, 0)))
    }

    @Test fun compareText() {
        assertEquals(ScreenTimeView.Compare(18, "比近 7 天平均多"), ScreenTimeView.compare(192, 174, "近 7 天平均"))
        assertEquals(ScreenTimeView.Compare(-21, "比上周少"), ScreenTimeView.compare(174, 195, "上周"))
        assertEquals("和上周差不多", ScreenTimeView.compare(100, 100, "上周")?.text)
        assertNull(ScreenTimeView.compare(100, null, "上周"))
    }

    @Test fun displaySwitchesDefaultOnAndPersist() {
        val saved = HashMap<String, Boolean>()
        val settings = ScreenTimeSettings(readBoolean = { k, d -> saved[k] ?: d }, persist = { b, _, _ -> saved.putAll(b); true },
            readLong = { _, d -> d }, now = { 0L })
        assertEquals(true, settings.enabled.value)
        assertEquals(true, settings.showOnHome.value)
        assertEquals(true, settings.eventInUse.value)
        settings.setEventInUse(false)
        assertEquals(false, saved[ScreenTimeSettings.EVENT_IN_USE_KEY])
        assertEquals(false, settings.eventInUse.value)
    }
}
