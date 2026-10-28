package app.resound.digest

import android.app.AlarmManager
import android.app.Application
import android.content.Context
import androidx.work.*
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import app.resound.transcribe.AndroidTranscribeSettings
import app.resound.transcribe.TranscribeScheduler
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.ZoneId
import java.time.ZonedDateTime

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, application = Application::class)
class DigestTiming053Test {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val zone = ZoneId.of("Asia/Shanghai")
    private fun at(time: String) = ZonedDateTime.parse("2026-10-28T$time+08:00").toInstant().toEpochMilli()
    @Before fun clear() { context.getSharedPreferences("digest", 0).edit().clear().commit() }

    @Test fun pointsAreSortedUniqueBoundedAndPersistedWithoutChangingLegacyMode() {
        val timing = AndroidDigestTiming(context)
        assertEquals(DigestMode.Interval, timing.mode.value)
        listOf(900, 60, 30, 900, -1, 1440, 120, 180, 240, 300, 360, 5).forEach(timing::addTimePoint)
        assertEquals(listOf(30, 60, 120, 180, 240, 300, 360, 900), timing.timePoints.value)
        timing.removeTimePoint(120); timing.addTimePoint(5); timing.setMode(DigestMode.TimePoints)
        val restored = AndroidDigestTiming(context)
        assertEquals(listOf(5, 30, 60, 180, 240, 300, 360, 900), restored.timePoints.value)
        assertEquals(DigestMode.TimePoints, restored.mode.value)
    }

    @Test fun nextPointUsesTodayTomorrowAndCrossesMidnight() {
        assertEquals(at("18:00:00"), DigestTime.next(listOf(720, 1080), at("12:20:00"), zone))
        assertEquals(at("12:00:00") + 86_400_000, DigestTime.next(listOf(720, 1080), at("19:00:00"), zone))
        assertEquals(at("00:30:00") + 86_400_000, DigestTime.next(listOf(30, 1430), at("23:50:00"), zone))
        assertNull(DigestTime.next(emptyList(), at("12:00:00"), zone))
    }

    @Test fun delayedWorkExpiresAtNextPointIncludingBoundary() {
        assertTrue(DigestTime.missed(at("19:00:00"), at("12:00:00"), at("18:00:00")))
        assertTrue(DigestTime.missed(at("18:00:00"), at("12:00:00"), at("18:00:00")))
        assertFalse(DigestTime.missed(at("12:20:00"), at("12:00:00"), at("18:00:00")))
    }

    @Test fun switchingBackCancelsAlarmAndRestoresPeriodicWithoutChangingTranscription() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().setExecutor(SynchronousExecutor()).build())
        val manager = WorkManager.getInstance(context)
        try {
            val timing = AndroidDigestTiming(context)
            val scheduler = TranscribeScheduler(context, AndroidTranscribeSettings(context)).apply { digestTiming = timing }
            timing.onChanged = scheduler::timingChanged
            scheduler.schedule()
            val transcribe = manager.getWorkInfosForUniqueWork(TranscribeScheduler.PERIODIC).get().single()
            timing.addTimePoint(720); timing.setMode(DigestMode.TimePoints)
            assertTrue(manager.getWorkInfosForUniqueWork(TranscribeScheduler.DIGEST_PERIODIC).get().all { it.state.isFinished })
            assertNotNull(timing.nextDigestAt.value)
            val alarm = shadowOf(context.getSystemService(AlarmManager::class.java))
            fun digestAlarms() = alarm.scheduledAlarms.filter {
                shadowOf(it.operation).savedIntent.component?.className == DigestAlarmReceiver::class.java.name
            }
            assertEquals(1, digestAlarms().size)
            val target = timing.nextDigestAt.value!!
            scheduler.enqueueTimed(target, target + 86_400_000)!!.result.get()
            val work = manager.getWorkInfosByTag(TranscribeScheduler.TIMED_TAG).get().single()
            assertEquals(NetworkType.CONNECTED, work.constraints.requiredNetworkType)
            timing.setMode(DigestMode.Interval)
            assertNull(timing.nextDigestAt.value)
            assertTrue(digestAlarms().isEmpty())
            assertTrue(manager.getWorkInfosForUniqueWork(TranscribeScheduler.DIGEST_PERIODIC).get().any { !it.state.isFinished })
            assertTrue(manager.getWorkInfosByTag(TranscribeScheduler.TIMED_TAG).get().all { it.state.isFinished })
            assertEquals(transcribe.id, manager.getWorkInfosForUniqueWork(TranscribeScheduler.PERIODIC).get().single().id)
        } finally { manager.cancelAllWork().result.get(); WorkManagerTestInitHelper.closeWorkDatabase() }
    }

    @Test fun manualIntentTravelsWithWorkAndSurvivesSettingsUpdate() = runBlocking {
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().setExecutor(SynchronousExecutor()).build())
        val manager = WorkManager.getInstance(context)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val settings = AndroidTranscribeSettings(context)
            val scheduler = TranscribeScheduler(context, settings)
            scheduler.runDigestNow()
            val first = withTimeout(3000) {
                var works = manager.getWorkInfosForUniqueWork(TranscribeScheduler.IMMEDIATE).get()
                while (works.isEmpty()) { delay(10); works = manager.getWorkInfosForUniqueWork(TranscribeScheduler.IMMEDIATE).get() }
                works.single()
            }
            assertTrue(app.resound.transcribe.TranscribeWork.MANUAL_DIGEST in first.tags)
            scheduler.settingsChanged(scope)
            scope.coroutineContext[Job]!!.children.toList().joinAll()
            assertTrue(app.resound.transcribe.TranscribeWork.MANUAL_DIGEST in manager.getWorkInfoById(first.id).get()!!.tags)
            scheduler.runNow()
            withTimeout(3000) {
                while (manager.getWorkInfosForUniqueWork(TranscribeScheduler.IMMEDIATE).get().single().id == first.id) delay(10)
            }
            assertFalse(app.resound.transcribe.TranscribeWork.MANUAL_DIGEST in manager.getWorkInfosForUniqueWork(TranscribeScheduler.IMMEDIATE).get().single().tags)
        } finally { scope.cancel(); manager.cancelAllWork().result.get(); WorkManagerTestInitHelper.closeWorkDatabase() }
    }

    @Test @Config(sdk = [32]) fun deniedExactPermissionStillSchedulesOneOrdinaryAlarm() {
        val timing = AndroidDigestTiming(context)
        val shadow = shadowOf(context.getSystemService(AlarmManager::class.java))
        org.robolectric.shadows.ShadowAlarmManager.setCanScheduleExactAlarms(false)
        timing.addTimePoint(720); timing.setMode(DigestMode.TimePoints)
        assertFalse(timing.canScheduleExact())
        val alarm = DigestAlarm(context, timing)
        alarm.schedule(at("11:00:00")); alarm.schedule(at("11:00:00"))
        assertEquals(1, shadow.scheduledAlarms.size)
        assertNotNull(timing.nextDigestAt.value)
        timing.removeTimePoint(720); alarm.schedule()
        assertNull(timing.nextDigestAt.value); assertTrue(shadow.scheduledAlarms.isEmpty())
    }
}
