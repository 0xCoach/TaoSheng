package app.resound.review

import android.content.Context
import androidx.work.*
import app.resound.AppGraph
import java.time.Duration
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

class ReviewWork(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val repository = AppGraph.review as RoomReviewRepository
        val date = ReviewTime.targetDate(ZonedDateTime.now(), repository.state.value.dailyAtMinutes)
        val retry = withReviewForeground { repository.run(date, scheduled = true) }
        return if (retry && runAttemptCount < 2) Result.retry() else Result.success()
    }
}

class ReviewScheduler(private val context: Context) {
    fun schedule(minutes: Int, changed: Boolean = false) {
        val request = PeriodicWorkRequestBuilder<ReviewWork>(1, TimeUnit.DAYS)
            .setInitialDelay(delayMinutes(minutes), TimeUnit.MILLISECONDS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.MINUTES).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(NAME,
            if (changed) ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE else ExistingPeriodicWorkPolicy.KEEP, request)
    }
    companion object {
        const val NAME = "daily-review"
        internal fun delayMinutes(minutes: Int, now: ZonedDateTime = ZonedDateTime.now()): Long {
            var next = now.toLocalDate().atStartOfDay(now.zone).plusMinutes(minutes.coerceIn(0, 1439).toLong())
            if (!next.isAfter(now)) next = next.plusDays(1)
            return Duration.between(now, next).toMillis()
        }
    }
}
