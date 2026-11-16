package app.resound.devices

import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import android.os.Looper
import androidx.work.*
import androidx.work.impl.WorkManagerImpl
import androidx.work.testing.*
import app.resound.importer.AndroidAudioImporter
import app.resound.importer.ImportWork
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, application = Application::class)
class DeviceBackgroundSchedulingTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private lateinit var work: WorkManagerImpl
    @Before fun setup() {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("scheduling-test","test",NotificationManager.IMPORTANCE_LOW))
        val factory = object : WorkerFactory() {
            override fun createWorker(context: Context,name: String,params: WorkerParameters): ListenableWorker =
                object : CoroutineWorker(context,params) {
                    override suspend fun getForegroundInfo() = ForegroundInfo(123,
                        Notification.Builder(context,"scheduling-test").setSmallIcon(android.R.drawable.stat_sys_upload).build())
                    override suspend fun doWork(): Result {
                        if (name.endsWith("DeviceRetentionWork") && !inputData.getBoolean("backup_available",false))
                            return Result.retry()
                        awaitCancellation()
                    }
                }
        }
        WorkManagerTestInitHelper.initializeTestWorkManager(context,Configuration.Builder()
            .setExecutor(SynchronousExecutor()).setWorkerFactory(factory).build())
        work = WorkManager.getInstance(context) as WorkManagerImpl
    }
    @After fun close() {
        work.cancelAllWork().result.get()
        WorkManagerTestInitHelper.closeWorkDatabase()
    }
    private fun active(name: String) = work.getWorkInfosForUniqueWork(name).get().filter { !it.state.isFinished }
    private suspend fun until(predicate: () -> Boolean) = withTimeout(5000) {
        while (true) { shadowOf(Looper.getMainLooper()).idle();if (predicate()) break;delay(10) }
    }

    @Test fun confirmedImportsKeepSerializedDurableChainAndSupportApi29Foreground() = runBlocking {
        AndroidAudioImporter.enqueue(context)
        // Keep the test main looper paused: persistence/dependencies do not require
        // starting a real SystemForegroundService in this manifest-free JVM harness.
        val first = active("audio-import").single().id
        AndroidAudioImporter.enqueue(context)
        val queued = active("audio-import")
        assertEquals(2,queued.size)
        val second = queued.single { it.id != first }
        assertEquals(WorkInfo.State.BLOCKED,second.state)
        assertEquals(listOf(first.toString()),work.workDatabase.dependencyDao().getPrerequisites(second.id.toString()))
        for (row in queued)
            assertTrue(requireNotNull(work.workDatabase.workSpecDao().getWorkSpec(row.id.toString())).expedited)
        val info = TestListenableWorkerBuilder<ImportWork>(context).build().getForegroundInfo()
        assertEquals(3028,info.notificationId)
        assertNotNull(info.notification.channelId)
    }

    @Test fun restoredTransportHasFreshOpportunityWithoutReplacingBackoffOrRunningCleanup() = runBlocking {
        DeviceRetentionScheduler.configure(context,true)
        val periodic = active("device-archive-maintenance").single()
        assertEquals(TimeUnit.HOURS.toMillis(24),
            requireNotNull(work.workDatabase.workSpecDao().getWorkSpec(periodic.id.toString())).initialDelay)
        DeviceRetentionScheduler.runNow(context)
        until { active("device-archive-maintenance-now").size == 1 }
        val ordinary = active("device-archive-maintenance-now").single().id
        WorkManagerTestInitHelper.getTestDriver(context)!!.setAllConstraintsMet(ordinary)
        until { active("device-archive-maintenance-now").singleOrNull()?.runAttemptCount == 1 }

        DeviceRetentionScheduler.backupAvailable(context).await()
        val available = active("device-archive-maintenance-available").single().id
        assertNotEquals(ordinary,available)
        assertTrue(requireNotNull(work.workDatabase.workSpecDao().getWorkSpec(available.toString())).expedited)
        WorkManagerTestInitHelper.getTestDriver(context)!!.setAllConstraintsMet(available)
        until { active("device-archive-maintenance-available").singleOrNull()?.state == WorkInfo.State.RUNNING }
        DeviceRetentionScheduler.backupAvailable(context).await()
        assertEquals(available,active("device-archive-maintenance-available").single().id)
        assertEquals(ordinary,active("device-archive-maintenance-now").single().id)
        DeviceRetentionScheduler.configure(context,false)
        until { listOf("device-archive-maintenance","device-archive-maintenance-now","device-archive-maintenance-available")
            .all { active(it).isEmpty() } }
    }

    @Test fun expeditedOpportunityStillRejectsLowOrUnknownPhoneBattery() {
        assertFalse(phoneBatteryNotLow(null))
        assertFalse(phoneBatteryNotLow(Intent()))
        assertFalse(phoneBatteryNotLow(Intent().putExtra(BatteryManager.EXTRA_BATTERY_LOW,true)))
        assertTrue(phoneBatteryNotLow(Intent().putExtra(BatteryManager.EXTRA_BATTERY_LOW,false)))
        fun battery(level: Int) = Intent().putExtra(BatteryManager.EXTRA_LEVEL,level).putExtra(BatteryManager.EXTRA_SCALE,100)
        assertFalse(phoneBatteryNotLow(battery(15)))
        assertTrue(phoneBatteryNotLow(battery(16)))
    }
}
