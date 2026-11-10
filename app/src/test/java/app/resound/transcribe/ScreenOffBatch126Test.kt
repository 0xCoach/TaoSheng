package app.resound.transcribe

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import android.os.PowerManager
import androidx.work.*
import androidx.work.impl.WorkManagerImpl
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import app.resound.capture.CaptureTuning
import app.resound.transcribe.local.InferenceWakeLock
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, application = Application::class)
class ScreenOffBatch126Test {
    private lateinit var context: Context
    private lateinit var manager: WorkManagerImpl
    private lateinit var settings: AndroidTranscribeSettings
    private lateinit var scheduler: TranscribeScheduler
    private val specs get() = manager.workDatabase.workSpecDao()
    private fun infos() = manager.getWorkInfosForUniqueWork(TranscribeScheduler.EARLY_WORK).get()
    private fun spec(id: java.util.UUID) = specs.getWorkSpec(id.toString())!!
    private fun power(interactive: Boolean, charging: Boolean) {
        shadowOf(context.getSystemService(PowerManager::class.java)).setIsInteractive(interactive)
        shadowOf(context.getSystemService(BatteryManager::class.java)).apply {
            setIsCharging(charging)
            setIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY, 80)
        }
    }

    @Before fun setup() {
        context = RuntimeEnvironment.getApplication()
        context.noBackupFilesDir.resolve("transcribe-power").deleteRecursively()
        context.getSharedPreferences("pipeline-times", 0).edit().clear().commit()
        context.getSharedPreferences("transcribe", 0).edit().clear().commit()
        WorkManagerTestInitHelper.initializeTestWorkManager(context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).build())
        manager = WorkManager.getInstance(context) as WorkManagerImpl
        settings = AndroidTranscribeSettings(context).apply {
            setSource(TranscribeSource.Custom)
            saveCustom("https://example.com/v1", "test-model")
            setRealtime(true)
            setWifiOnly(true) // 测试不满足网络约束，检查真实 WorkManager 队列而不运行推理。
        }
        scheduler = TranscribeScheduler(context, settings)
        power(false, false)
    }
    @After fun close() {
        manager.cancelAllWork().result.get()
        WorkManagerTestInitHelper.closeWorkDatabase()
    }

    @Test fun screenOffUnpluggedKeepsFirstBatchAndItsTimer() = runBlocking {
        assertTrue(EarlyTranscribe.shouldBatch(false, false))
        assertFalse(EarlyTranscribe.shouldBatch(true, false))
        assertFalse(EarlyTranscribe.shouldBatch(false, true))
        scheduler.segmentSaved(1000)
        val first = infos().single()
        val enqueued = spec(first.id).lastEnqueueTime
        ShadowSystemClock.advanceBy(Duration.ofMinutes(2))
        scheduler.segmentSaved(2000)
        assertEquals(first.id, infos().single().id)
        val work = spec(first.id)
        assertFalse(work.expedited)
        assertTrue(work.input.getBoolean(TranscribeWork.BATCHED, false))
        assertEquals(600_000L, work.initialDelay)
        assertEquals(CaptureTuning.BATCH_DELAY_MS, work.initialDelay)
        assertEquals(enqueued, work.lastEnqueueTime)
        assertEquals("", TranscribePowerDiagnostics.dump(context)) // 入队不计为执行，也不持锁。
    }

    @Test fun screenOnEnqueuesImmediately() = runBlocking {
        power(true, false)
        scheduler.segmentSaved(1000)
        val work = spec(infos().single().id)
        assertTrue(work.expedited)
        assertEquals(0L, work.initialDelay)
        assertEquals(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST, work.outOfQuotaPolicy)
    }

    @Test fun chargingEnqueuesImmediatelyEvenWithScreenOff() = runBlocking {
        power(false, true)
        scheduler.segmentSaved(1000)
        val work = spec(infos().single().id)
        assertTrue(work.expedited)
        assertEquals(0L, work.initialDelay)
    }

    @Test fun screenOnAndPowerConnectedReplaceWaitingParametersWithoutExtraWork() = runBlocking {
        for (action in listOf(Intent.ACTION_SCREEN_ON, Intent.ACTION_POWER_CONNECTED)) {
            manager.cancelUniqueWork(TranscribeScheduler.EARLY_WORK).result.get()
            scheduler.segmentSaved(1000)
            val before = infos().single { !it.state.isFinished }
            scheduler.resumeWaitingBatch(action)
            scheduler.resumeWaitingBatch(action)
            val after = infos().single { !it.state.isFinished }
            assertEquals(before.id, after.id)
            assertTrue(spec(after.id).expedited)
            assertEquals(0L, spec(after.id).initialDelay)
            assertFalse(spec(after.id).input.getBoolean(TranscribeWork.BATCHED, true))
            assertFalse(TranscribeScheduler.BATCH_TAG in after.tags)
        }
    }

    @Test fun runningRoundAppendsDelayedBatchAndPromotionPreservesParent() = runBlocking {
        scheduler.segmentSaved(1000)
        val parent = infos().single()
        specs.setState(WorkInfo.State.RUNNING, parent.id.toString())
        scheduler.segmentSaved(2000)
        val child = infos().single { it.id != parent.id }
        assertEquals(WorkInfo.State.BLOCKED, child.state)
        assertFalse(spec(child.id).expedited)
        assertEquals(600_000L, spec(child.id).initialDelay)
        val enqueued = spec(child.id).lastEnqueueTime
        scheduler.segmentSaved(3000)
        assertEquals(2, infos().size)
        assertEquals(enqueued, spec(child.id).lastEnqueueTime)
        scheduler.resumeWaitingBatch(Intent.ACTION_SCREEN_ON)
        assertEquals(WorkInfo.State.RUNNING, spec(parent.id).state)
        assertEquals(WorkInfo.State.BLOCKED, spec(child.id).state)
        assertTrue(spec(child.id).expedited)
        assertEquals(0L, spec(child.id).initialDelay)
        assertEquals(listOf(parent.id.toString()), manager.workDatabase.dependencyDao().getPrerequisites(child.id.toString()))
    }

    @Test fun disabledRealtimeOrUnrelatedBroadcastDoesNotPromoteOrCreateWork() = runBlocking {
        scheduler.resumeWaitingBatch(Intent.ACTION_SCREEN_ON)
        assertTrue(infos().isEmpty())
        scheduler.segmentSaved(1000)
        val first = infos().single()
        scheduler.resumeWaitingBatch(Intent.ACTION_POWER_DISCONNECTED)
        assertFalse(spec(first.id).expedited)
        settings.setRealtime(false)
        scheduler.resumeWaitingBatch(Intent.ACTION_SCREEN_ON)
        scheduler.segmentSaved(2000)
        assertEquals(1, infos().size)
        assertFalse(spec(first.id).expedited)
    }

    @Test fun promotionDoesNotTouchPeriodicFallbackOrDigest() = runBlocking {
        scheduler.schedule()
        val fallback = manager.getWorkInfosForUniqueWork(TranscribeScheduler.PERIODIC).get().single()
        val digest = manager.getWorkInfosForUniqueWork(TranscribeScheduler.DIGEST_PERIODIC).get().single()
        scheduler.segmentSaved(1000)
        scheduler.resumeWaitingBatch(Intent.ACTION_POWER_CONNECTED)
        assertEquals(fallback.id, manager.getWorkInfosForUniqueWork(TranscribeScheduler.PERIODIC).get().single().id)
        assertEquals(30 * 60_000L, spec(fallback.id).intervalDuration)
        assertEquals(digest.id, manager.getWorkInfosForUniqueWork(TranscribeScheduler.DIGEST_PERIODIC).get().single().id)
    }

    @Test fun dailyDiagnosticsAccumulateSplitMidnightAndExportThroughPipeline() {
        val midnight = LocalDate.of(2026, 11, 10).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        TranscribePowerDiagnostics.execution(context, false, midnight)
        repeat(2) { TranscribePowerDiagnostics.execution(context, true, midnight) }
        TranscribePowerDiagnostics.held(context, midnight - 100, 300)
        TranscribePowerDiagnostics.held(context, midnight, 50)
        val rows = PipelineTimes.dump(context).lines().filter { it.startsWith("转写省电") }
        assertEquals(2, rows.size)
        assertTrue(rows[0].contains("day=2026-11-09 localAsrHeldMillis=100 realtimeExecutions=0"))
        assertTrue(rows[1].contains("day=2026-11-10 localAsrHeldMillis=250 realtimeExecutions=3 expedited=1 batched=2"))
    }

    @Test fun inferenceCancellationRecordsOnceAndOnlyWhileHeld() = runBlocking {
        val lock = context.getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "test:126")
        val durations = mutableListOf<Long>()
        val lease = InferenceWakeLock(lock) { _, millis -> assertFalse(lock.isHeld); durations += millis }
        ShadowSystemClock.advanceBy(Duration.ofSeconds(10)) // 排队不计时。
        lease.run(1000) {
            ShadowSystemClock.advanceBy(Duration.ofMillis(200))
            lease.release() // 模拟 IPC 取消，JNI 尚未返回。
            ShadowSystemClock.advanceBy(Duration.ofMillis(300))
        }
        lease.release()
        assertEquals(listOf(200L), durations)
        lease.run(1000) { fail("取消后不能重新获取锁") }
    }

    @Test fun heldDurationIsCappedAtAutomaticTimeoutEvenIfInferenceReturnsLate() = runBlocking {
        val lock = context.getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "test:126")
        val durations = mutableListOf<Long>()
        try {
            InferenceWakeLock(lock) { _, millis -> durations += millis }.run(1000) {
                ShadowSystemClock.advanceBy(Duration.ofMillis(2000))
                throw IllegalStateException("推理失败")
            }
        } catch (_: IllegalStateException) { }
        assertEquals(listOf(1000L), durations)
        assertFalse(lock.isHeld)
    }
}
