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
        // 负责人定（10-24）：不等充电、WiFi、空闲，到点就备份；云端有网就传（手机流量也行），本机不需要网络。
        // UPDATE：已排好的任务也换成新条件（KEEP 会沿用旧的“充电 + 空闲 + WiFi”）。
        manager.enqueueUniquePeriodicWork(name, ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<BackupWork>(1, TimeUnit.DAYS)
                .setInitialDelay(next.timeInMillis - now, TimeUnit.MILLISECONDS)
                .setInputData(workDataOf("periodic" to true, "local" to local))
                .setConstraints(Constraints.Builder()
                    .setRequiredNetworkType(if (local) NetworkType.NOT_REQUIRED else NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
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
