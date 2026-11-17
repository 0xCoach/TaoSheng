package app.resound.night

import kotlinx.datetime.LocalDate
import org.junit.Assert.*
import org.junit.Test

class NightDayTest {
    private val h = 3_600_000L; private val m = 60_000L
    private val hours = (0..24).map { it * h }
    private val today = LocalDate(2026, 11, 18); private val tomorrow = LocalDate(2026, 11, 19)
    private fun ev(kind: NightEventKind, a: Long, b: Long, fb: Feedback = Feedback.None) = NightEvent(a, kind, a, b, feedback = fb)

    @Test fun morningNightSplitsIntoSleepSnoreAwakeAndHours() {
        // 前一天 23:40 入睡（落在今天之前的部分要裁掉），7:05 起床；1:00–1:30 打鼾，3:10–3:16 醒了，5:12 梦话
        val night = NightDays.Input(today, -20 * m, 7 * h + 5 * m, listOf(
            ev(NightEventKind.Snore, h, h + 30 * m), ev(NightEventKind.Awake, 3 * h + 10 * m, 3 * h + 16 * m),
            ev(NightEventKind.SleepTalk, 5 * h + 12 * m, 5 * h + 12 * m + 9_000)))
        val day = NightDays.build(hours, listOf(night))!!
        assertEquals(0L, day.parts.first().start)
        assertEquals(listOf(NightDays.Kind.Sleep, NightDays.Kind.Snore, NightDays.Kind.Sleep, NightDays.Kind.Awake, NightDays.Kind.Sleep), day.parts.map { it.kind })
        assertEquals(30, day.hours[1].snoreMinutes); assertEquals(60, day.hours[1].sleepMinutes)
        assertEquals(54, day.hours[3].sleepMinutes)
        assertTrue(day.hours[5].talk)
        assertEquals(5, day.hours[7].sleepMinutes); assertFalse(day.hours[7].asleep)
        assertEquals(0, day.hours[12].sleepMinutes); assertNull(day.hours[12].date)
        assertEquals(today, day.nightAt(2 * h)); assertNull(day.nightAt(3 * h + 12 * m)); assertNull(day.nightAt(9 * h))
    }

    @Test fun eveningLiveNightBelongsToTomorrowAndRejectedSnoreIsIgnored() {
        val morning = NightDays.Input(today, 0, 6 * h, listOf(ev(NightEventKind.Snore, 2 * h, 3 * h, Feedback.No)))
        val evening = NightDays.Input(tomorrow, 23 * h + 30 * m, 24 * h + 2 * h, emptyList(), live = true)
        val day = NightDays.build(hours, listOf(morning, evening))!!
        assertEquals(0, day.hours[2].snoreMinutes)
        assertTrue(day.hours[2].asleep)
        assertEquals(30, day.hours[23].sleepMinutes)
        assertEquals(tomorrow, day.nightAt(23 * h + 45 * m))
        assertTrue(day.parts.last().live)
        assertEquals(24 * h, day.parts.last().end)
    }

    @Test fun nothingInsideTheDayReturnsNull() {
        assertNull(NightDays.build(hours, emptyList()))
        assertNull(NightDays.build(hours, listOf(NightDays.Input(today, -5 * h, -h, emptyList()))))
    }
}
