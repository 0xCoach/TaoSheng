package app.resound.capture

import android.app.Application
import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], application = Application::class)
class QuietSchedule056Test {
    private val zone = ZoneId.of("Asia/Shanghai")
    private fun at(value: String) = LocalDateTime.parse(value).atZone(zone).toInstant().toEpochMilli()
    private val sleep = QuietRule("sleep", "睡眠", 23 * 60, 7 * 60)
    private fun prefs() = RuntimeEnvironment.getApplication().getSharedPreferences("capture", Context.MODE_PRIVATE)

    @Test fun midnightWindowEndsAtSevenAndNextBoundaryMatches() {
        val now = at("2026-10-31T02:00")
        assertEquals(at("2026-10-31T07:00"), QuietRules.windowAt(listOf(sleep), now, zone)!!.endsAt)
        assertEquals(at("2026-10-31T07:00"), QuietRules.nextChange(listOf(sleep), now, zone))
        assertEquals(at("2026-10-31T23:00"), QuietRules.nextChange(listOf(sleep), at("2026-10-31T07:00"), zone))
    }
    @Test fun repeatDayBelongsToStartDate() {
        val friday = sleep.copy(days = setOf(DayOfWeek.FRIDAY))
        assertNotNull(QuietRules.windowAt(listOf(friday), at("2026-10-31T02:00"), zone))
        assertNull(QuietRules.windowAt(listOf(friday), at("2026-10-31T23:00"), zone))
    }
    @Test fun touchingAndOverlappingWindowsMergeWithFirstName() {
        val rules = listOf(sleep, QuietRule("morning", "早间", 7 * 60, 9 * 60), QuietRule("overlap", "重叠", 8 * 60, 10 * 60))
        val window = QuietRules.windowAt(rules, at("2026-10-31T08:30"), zone)!!
        assertEquals("睡眠", window.rule.name)
        assertEquals(at("2026-10-31T10:00"), window.endsAt)
    }
    @Test fun resumeSkipsOnlyCurrentMergedWindowAndSurvivesReload() {
        var now = at("2026-10-31T02:00")
        val schedule = AndroidQuietSchedule(prefs(), { now }, { zone })
        schedule.save(sleep); assertNotNull(schedule.active.value)
        schedule.resume(); assertNull(schedule.active.value)
        val loaded = AndroidQuietSchedule(prefs(), { now }, { zone })
        assertNull(loaded.active.value)
        now = at("2026-10-31T23:01"); loaded.refresh()
        assertNotNull(loaded.active.value)
    }
    @Test fun manualPauseAndQuietChooseLaterEndAndCorrectName() {
        val now = at("2026-10-31T02:00")
        val quiet = QuietRules.windowAt(listOf(sleep), now, zone)!!
        assertEquals(CapturePausePlan(quiet.endsAt, "睡眠"), capturePausePlan(now, at("2026-10-31T03:00"), quiet))
        assertEquals(CapturePausePlan(at("2026-10-31T08:00"), null), capturePausePlan(now, at("2026-10-31T08:00"), quiet))
        assertNull(capturePausePlan(quiet.endsAt, 0, null))
    }
    /** 假服务消费与真实服务相同的 pause plan；规则变动回调立即触发。 */
    private class FakeService(private val now: Long) {
        var enabled = true
        val state = MutableStateFlow(CaptureState.Listening)
        var schedule: AndroidQuietSchedule? = null
        fun reconcile() {
            if (!enabled) return
            state.value = if (capturePausePlan(now, 0, schedule?.refresh()) == null) CaptureState.Listening else CaptureState.Paused
        }
    }
    @Test fun disablingActiveRuleImmediatelyResumesExistingService() {
        val now = at("2026-10-31T02:00")
        val service = FakeService(now)
        val schedule = AndroidQuietSchedule(prefs(), { now }, { zone }, changed = service::reconcile)
        service.schedule = schedule
        schedule.save(sleep); assertEquals(CaptureState.Paused, service.state.value)
        schedule.save(sleep.copy(enabled = false)); assertEquals(CaptureState.Listening, service.state.value)
    }
    @Test fun stoppedServiceIsUntouchedAndStartInsideWindowPauses() {
        val now = at("2026-10-31T02:00")
        val service = FakeService(now).apply { enabled = false; state.value = CaptureState.Stopped }
        val schedule = AndroidQuietSchedule(prefs(), { now }, { zone }, changed = service::reconcile)
        service.schedule = schedule
        schedule.save(sleep); assertEquals(CaptureState.Stopped, service.state.value)
        service.enabled = true; service.reconcile(); assertEquals(CaptureState.Paused, service.state.value)
    }
    @Test fun upgradeSeedsDisabledSleepOnceAndKeepsManualPause() {
        val prefs = prefs(); prefs.edit().putLong("pausedUntil", 1234).commit()
        val schedule = AndroidQuietSchedule(prefs())
        assertEquals(sleep.copy(enabled = false), schedule.rules.value.single())
        assertEquals(1234, prefs.getLong("manualPausedUntil", 0))
        schedule.delete("sleep")
        assertTrue(AndroidQuietSchedule(prefs).rules.value.isEmpty())
        assertEquals("[]", prefs.getString("quietRules", null))
    }
    @Test fun killedGapsExcludeQuietAndManualPause() {
        val from = at("2026-10-30T22:00"); val until = at("2026-10-31T08:00")
        val gaps = QuietRules.recordingGaps(listOf(sleep), from, until, zone, at("2026-10-30T22:30"), 0)
        assertEquals(listOf(at("2026-10-30T22:30") to at("2026-10-30T23:00"), at("2026-10-31T07:00") to until), gaps)
        assertTrue(QuietRules.recordingGaps(listOf(sleep), at("2026-10-31T02:00"), at("2026-10-31T06:00"), zone, 0, 0).isEmpty())
    }
}
