package app.resound.push

import android.content.Context
import androidx.work.*
import app.resound.AppGraph
import app.resound.data.CaptureDatabase
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.time.*
import java.util.concurrent.TimeUnit

class DayThreadPushWork(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val channels = AppGraph.push as? AndroidPushChannels ?: return Result.retry()
        var failed = false
        for (channel in channels.channels.value.filter { it.enabled && DayThreadPolicy.eligible(it.kind, it.subscription) }) {
            if (channels.subscriptions.run(channel.id, manual = false, dayFinal = true).isFailure) failed = true
        }
        if (failed) return Result.retry()
        if (!inputData.getBoolean("refresh", false)) DayThreadPushScheduler.plan(applicationContext, next = true)
        return Result.success()
    }
}

internal object DayThreadPushScheduler {
    fun refresh(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork("push-day-thread-refresh", ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<DayThreadPushWork>().setInputData(workDataOf("refresh" to true))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build())
    }
    fun plan(context: Context, next: Boolean = false) {
        val now = ZonedDateTime.now()
        var target = now.toLocalDate().atTime(4, 0).atZone(now.zone)
        if (next) target = target.plusDays(1)
        WorkManager.getInstance(context).enqueueUniqueWork("push-day-thread", if (next) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<DayThreadPushWork>()
                .setInitialDelay((target.toInstant().toEpochMilli() - now.toInstant().toEpochMilli()).coerceAtLeast(0), TimeUnit.MILLISECONDS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build())
    }
}
internal fun AndroidPushChannels.attachDayThread(context: Context, database: CaptureDatabase, scope: CoroutineScope) {
    subscriptions.dayThreadSource = { date, data, s, zones, frozen -> loadDayThread(database, date, data, s, zones, frozen) }
    scope.launch(Dispatchers.IO) {
        channels.map { list -> list.filter { it.enabled && DayThreadPolicy.eligible(it.kind, it.subscription) }.map { it.id to it.subscription } }
            .distinctUntilChanged().collect { if (it.isNotEmpty()) DayThreadPushScheduler.plan(context) }
    }
}
