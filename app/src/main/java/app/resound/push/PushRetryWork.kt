package app.resound.push

import android.content.Context
import androidx.work.*
import app.resound.AppGraph
import java.util.concurrent.TimeUnit

class PushRetryWork(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val job = inputData.getString("job") ?: return Result.failure()
        val repository = AppGraph.push as AndroidPushChannels
        return if (repository.retry(job, runAttemptCount)) Result.retry() else Result.success()
    }
}

internal class PushRetryScheduler(private val context: Context) {
    fun enqueue(job: String) {
        WorkManager.getInstance(context).enqueueUniqueWork("push-$job", ExistingWorkPolicy.KEEP, request(job)).result.get()
    }
    companion object {
        internal fun request(job: String) = OneTimeWorkRequestBuilder<PushRetryWork>()
            .setInputData(workDataOf("job" to job)).addTag("push-retry")
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInitialDelay(30, TimeUnit.SECONDS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build()
    }
}
