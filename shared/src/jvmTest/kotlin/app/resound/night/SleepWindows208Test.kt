package app.resound.night

import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class SleepWindows208Test {
    private val minute = 60_000L
    private fun input(afterOne: Int) = NightInput(afterOne * minute, 780 + afterOne, 0, null, 0, 0,
        false, false, 80, false, false)
    private val pending = NightSession(0, 10 * minute, lastAt = 10 * minute, nap = true)
    private val active = pending.copy(confirmedAt = 20 * minute)

    @Test fun oldSettingsDecodeWithOriginalUserIntent() {
        val defaults = Json.decodeFromString<NightSettings>("{}").windows
        assertTrue(defaults.first().auto)
        assertFalse(defaults.last().enabled)
        val manual = Json.decodeFromString<NightSettings>("""{"windowStartMinute":1380,"windowEndMinute":690}""").windows
        assertFalse(manual.first().auto)
        assertEquals(1380 to 690, manual.first().startMinute to manual.first().endMinute)
    }

    @Test fun automaticMedianStraddlesMidnightAndNeedsSevenNights() {
        val samples = List(4) { 0 to 480 } + List(6) { 1410 to 420 } + List(4) { 1380 to 390 }
        val result = SleepWindows.automatic(samples)
        assertEquals(1290 to 660, result.startMinute to result.endMinute)
        val fallback = SleepWindows.automatic(samples.take(6))
        assertEquals(1200 to 720, fallback.startMinute to fallback.endMinute)
    }

    @Test fun confirmedNapEndsAtUnlockOrDeadline() {
        assertEquals(NightTransition.Wait, NapRules.transition(true, null, input(9)))
        assertEquals(NightTransition.Enter, NapRules.transition(true, null, input(10)))
        assertEquals(NightTransition.Confirm, NapRules.transition(true, pending, input(20)))
        val at = 42 * minute
        val woke = active.copy(signals = listOf(NightSignal(at, at, "unlock")))
        assertEquals(NightTransition.Finish, NapRules.transition(true, woke, input(42)))
        assertEquals(at, NapRules.wake(woke))
        assertEquals(32 * minute, SleepEstimate.calculate(woke, emptyList(), at).millis)
        assertEquals(NightTransition.Finish, NapRules.transition(false, active, input(120)))
        assertEquals(110 * minute, SleepEstimate.calculate(active, emptyList(), 120 * minute).millis)
    }

    @Test fun earlyUnlockDiscardsAndBriefScreenUsesAccumulate() {
        val early = pending.copy(signals = listOf(NightSignal(15 * minute, 15 * minute, "unlock")))
        assertEquals(NightTransition.Cancel, NapRules.transition(true, early, input(15)))
        val first = NightSignal(21 * minute, 21 * minute + 20_000, "screen")
        val second = NightSignal(23 * minute, 23 * minute + 11_000, "screen")
        assertEquals(23 * minute + 10_000, NapRules.wake(active.copy(signals = listOf(first, second))))
        assertEquals(22 * minute, NapRules.wake(active.copy(signals = listOf(NightSignal(22 * minute, 22 * minute, "pickup")))))
    }

    @Test fun daytimeCustomUsesNightRulesAndEndsToday() {
        val custom = SleepWindow("shift", SleepWindowKind.Custom, "补觉", true, false, 480, 960)
        val today = LocalDate(2026, 11, 18)
        val settings = NightSettings(enabled = true, windows = listOf(custom))
        assertEquals(custom, SleepWindows.current(settings, SleepWindows.fallback, 490))
        assertNull(SleepWindows.current(settings, SleepWindows.fallback, 960))
        assertEquals(480 * minute, custom.bounds(today, kotlinx.datetime.TimeZone.UTC).millis)
        assertEquals(today, custom.wakeDate(today, 490))
        assertEquals("night", custom.recordKind)
        assertEquals(NightTransition.Enter, NightRules.transition(custom.contains(490), false, null, input(10)))
        assertEquals(NightTransition.Finish, NightRules.transition(custom.contains(960), false, active.copy(nap=false), input(180)))
    }
}
