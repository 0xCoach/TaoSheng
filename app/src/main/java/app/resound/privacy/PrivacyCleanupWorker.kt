package app.resound.privacy

import android.content.Context
import androidx.work.*
import app.resound.ResoundApp
import app.resound.data.CaptureDatabase
import app.resound.scheduling.DemandWorkPolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect

/** 即使删除后进程退出，待清理路径仍在数据库；启动补查和 WorkManager 退避重试均可续做。 */
class PrivacyCleanupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        val actions = RoomPrivacyActions((applicationContext as ResoundApp).database)
        if (actions.retryFileCleanup()) Result.success() else Result.retry()
    } catch (e: kotlinx.coroutines.CancellationException) { throw e }
      catch (_: Exception) { Result.retry() }

    companion object {
        fun enqueue(context: Context) {
            val app = context.applicationContext as ResoundApp
            app.scope.launch { reconcile(context, app.database) }
        }
        internal suspend fun reconcile(context: Context, db: CaptureDatabase) {
            if (DemandWorkPolicy.privacy(db.privacy().pendingCleanup().size))
                WorkManager.getInstance(context).enqueueUniqueWork("privacy-file-cleanup", ExistingWorkPolicy.APPEND_OR_REPLACE,
                    OneTimeWorkRequestBuilder<PrivacyCleanupWorker>().build()).await()
        }
        internal fun observe(context: Context, db: CaptureDatabase, scope: CoroutineScope) = scope.launch {
            WorkManager.getInstance(context).cancelUniqueWork("privacy-file-cleanup-catchup")
            var previous = emptySet<String>()
            db.invalidationTracker.createFlow("privacy_file_cleanup").collect {
                val pending = db.privacy().pendingCleanup().map { it.path }.toSet()
                if ((pending - previous).isNotEmpty()) reconcile(context, db)
                previous = pending
            }
        }
        fun start(context: Context) {
            val app = context.applicationContext as ResoundApp
            observe(context, app.database, app.scope)
        }
    }
}
