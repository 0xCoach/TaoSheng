package app.resound.screentime

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.resound.AppGraph
import java.util.concurrent.TimeUnit

class ScreenTimePullWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = when (val result = AppGraph.screenTimePuller.pull()) {
        is PullResult.Failed -> if (runAttemptCount < 3) Result.retry() else Result.failure()
        else -> Result.success()
    }

    companion object {
        internal const val PERIODIC = "screen-time-pull"

        /** 总开关打开时排定每小时周期任务；关闭即取消。 */
        fun schedule(context: Context, enabled: Boolean) {
            val work = WorkManager.getInstance(context)
            if (!enabled) { work.cancelUniqueWork(PERIODIC); return }
            work.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<ScreenTimePullWorker>(1, TimeUnit.HOURS).build())
        }
    }
}
