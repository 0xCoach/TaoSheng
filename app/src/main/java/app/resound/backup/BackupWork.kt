package app.resound.backup

import android.content.Context
import androidx.work.*
import app.resound.AppGraph
import java.util.Calendar
import java.util.concurrent.TimeUnit

class BackupWork(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val repository = AppGraph.backup as AndroidBackupRepository
        val periodic = inputData.getBoolean("periodic", false)
        val retry = if (inputData.getBoolean("local", false)) repository.runLocalBackup(periodic)
            else repository.runBackup(periodic)
        return if (retry) Result.retry() else Result.success()
    }
}
internal class BackupScheduler(private val context: Context) {
    private val manager get() = WorkManager.getInstance(context)
    fun schedule(enabled: Boolean, local: Boolean = false) {
        scheduleOne(enabled, false)
        scheduleOne(local, true)
    }
    private fun scheduleOne(enabled: Boolean, local: Boolean) {
        val name = if (local) LOCAL_PERIODIC else PERIODIC
        if (!enabled) { manager.cancelUniqueWork(name); return }
        val now = System.currentTimeMillis()
        val next = Calendar.getInstance().apply {
            timeInMillis = now; set(Calendar.HOUR_OF_DAY, 3); set(Calendar.MINUTE, 10); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= now) add(Calendar.DAY_OF_YEAR, 1)
        }
        manager.enqueueUniquePeriodicWork(name, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<BackupWork>(1, TimeUnit.DAYS)
                .setInitialDelay(next.timeInMillis - now, TimeUnit.MILLISECONDS)
                .setInputData(workDataOf("periodic" to true, "local" to local))
                .setConstraints(Constraints.Builder().setRequiresCharging(true).setRequiresDeviceIdle(true)
                    .setRequiredNetworkType(if (local) NetworkType.NOT_REQUIRED else NetworkType.UNMETERED).build())
                // requiresDeviceIdle 与显式 backoff 在 WorkManager 中不兼容；使用默认指数退避。
                .build())
    }
    fun runNow() {
        manager.enqueueUniqueWork(IMMEDIATE, ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<BackupWork>().setConstraints(Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES).build())
    }
    fun runLocalNow() {
        manager.enqueueUniqueWork(LOCAL_IMMEDIATE, ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<BackupWork>().setInputData(workDataOf("local" to true)).build())
    }
    fun cancelCloud() { manager.cancelUniqueWork(PERIODIC); manager.cancelUniqueWork(IMMEDIATE) }
    fun cancel() { cancelCloud(); manager.cancelUniqueWork(LOCAL_PERIODIC); manager.cancelUniqueWork(LOCAL_IMMEDIATE) }
    companion object {
        const val LOCAL_PERIODIC = "backup-local-periodic"
        const val LOCAL_IMMEDIATE = "backup-local-now"
        const val PERIODIC = "backup-periodic"
        const val IMMEDIATE = "backup-now"
    }
}
