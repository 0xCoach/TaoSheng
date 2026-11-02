package app.resound.privacy

import android.content.Context
import androidx.work.*
import app.resound.ResoundApp
import java.util.concurrent.TimeUnit

/** 即使删除后进程退出，待清理路径仍在数据库；启动和周期任务均可续做。 */
class PrivacyCleanupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        val actions = RoomPrivacyActions((applicationContext as ResoundApp).database)
        if (actions.retryFileCleanup()) Result.success() else Result.retry()
    } catch (e: kotlinx.coroutines.CancellationException) { throw e }
      catch (_: Exception) { Result.retry() }

    companion object {
        fun enqueue(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork("privacy-file-cleanup", ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<PrivacyCleanupWorker>().build())
        }
        fun start(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork("privacy-file-cleanup-catchup", ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<PrivacyCleanupWorker>(15, TimeUnit.MINUTES).build())
            enqueue(context)
        }
    }
}
