package app.resound.backup

import android.app.Application
import androidx.work.*
import androidx.work.testing.WorkManagerTestInitHelper
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, application = Application::class)
class BackupSchedulerTest {
    @Test fun periodicAndImmediateHaveDifferentConstraintsAndAreDeduplicated() {
        val context = RuntimeEnvironment.getApplication()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().build())
        val scheduler = BackupScheduler(context)
        val manager = WorkManager.getInstance(context)
        scheduler.schedule(true); scheduler.schedule(true)
        val periodic = manager.getWorkInfosForUniqueWork(BackupScheduler.PERIODIC).get()
        assertEquals(1, periodic.size)
        assertTrue(periodic.single().constraints.requiresCharging())
        assertTrue(periodic.single().constraints.requiresDeviceIdle())
        assertEquals(NetworkType.UNMETERED, periodic.single().constraints.requiredNetworkType)
        scheduler.runNow(); scheduler.runNow()
        val immediate = manager.getWorkInfosForUniqueWork(BackupScheduler.IMMEDIATE).get()
        assertEquals(1, immediate.size)
        assertFalse(immediate.single().constraints.requiresCharging())
        assertFalse(immediate.single().constraints.requiresDeviceIdle())
        assertEquals(NetworkType.CONNECTED, immediate.single().constraints.requiredNetworkType)
        scheduler.cancel()
        manager.getWorkInfosForUniqueWork(BackupScheduler.PERIODIC).get().forEach { assertEquals(WorkInfo.State.CANCELLED, it.state) }
        manager.getWorkInfosForUniqueWork(BackupScheduler.IMMEDIATE).get().forEach { assertEquals(WorkInfo.State.CANCELLED, it.state) }
    }
    @Test fun localOnlyNeedsNoNetworkAndCloudCancellationLeavesLocalScheduled() {
        val context = RuntimeEnvironment.getApplication()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().build())
        val scheduler = BackupScheduler(context)
        val manager = WorkManager.getInstance(context)
        scheduler.schedule(false, true)
        scheduler.runLocalNow()
        scheduler.cancelCloud()
        val local = manager.getWorkInfosForUniqueWork(BackupScheduler.LOCAL_PERIODIC).get().single()
        assertEquals(NetworkType.NOT_REQUIRED, local.constraints.requiredNetworkType)
        assertTrue(local.constraints.requiresCharging()); assertTrue(local.constraints.requiresDeviceIdle())
        assertFalse(local.state.isFinished)
        assertTrue(manager.getWorkInfosForUniqueWork(BackupScheduler.PERIODIC).get().isEmpty())
        assertEquals(NetworkType.NOT_REQUIRED, manager.getWorkInfosForUniqueWork(BackupScheduler.LOCAL_IMMEDIATE).get().single().constraints.requiredNetworkType)
        scheduler.cancel()
    }

}
