package app.resound.push

import android.content.Context
import androidx.work.*
import app.resound.AppGraph
import app.resound.backup.AndroidBackupRepository
import app.resound.data.CaptureDatabase
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.time.ZoneId
import java.util.concurrent.TimeUnit

class RecordPushWork(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val id = inputData.getLong("channelId", -1)
        val channels = AppGraph.push as? AndroidPushChannels ?: return Result.retry()
        return if (channels.subscriptions.scheduled(id)) Result.retry() else Result.success()
    }
}
internal class RecordPushScheduler(private val context: Context) {
    fun enqueue(id: Long, at: Long, continuation: Boolean) {
        WorkManager.getInstance(context).enqueueUniqueWork("push-subscription-$id",
            if (continuation) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.REPLACE, request(id, at)).result.get()
    }
    fun cancel(id: Long) { WorkManager.getInstance(context).cancelUniqueWork("push-subscription-$id") }
    companion object {
        fun request(id: Long, at: Long) = OneTimeWorkRequestBuilder<RecordPushWork>()
            .setInputData(workDataOf("channelId" to id)).addTag("push-subscription")
            .setInitialDelay((at - System.currentTimeMillis()).coerceAtLeast(0), TimeUnit.MILLISECONDS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build()
    }
}

@OptIn(FlowPreview::class)
internal fun AndroidPushChannels.attachSubscriptions(context: Context, database: CaptureDatabase, backup: AndroidBackupRepository, scope: CoroutineScope) {
    linkedCredentials = backup::pushCredentials
    linkedAccount = backup.state.map { if (it.hasPassword) it.account else null }.distinctUntilChanged()
    subscriptions.source = { since, lines -> database.push().snapshot(since?.atStartOfDay(ZoneId.systemDefault())?.toInstant()?.toEpochMilli() ?: Long.MIN_VALUE, lines) }
    subscriptions.reminderSettings = { AppGraph.memory.appointmentReminders.value }
    phoneReminders = PhoneReminders(context.getSharedPreferences("phone-reminders", Context.MODE_PRIVATE),
        { subscriptions.source(null, false) }, subscriptions.reminderSettings, LocalNotifier(context), { at, continuation -> schedulePhoneReminder(context, at, continuation) },
        strongNotifier = LocalNotifier(context, LocalNotifier.TODO_CHANNEL))
    scope.launch(Dispatchers.IO) {
        AppGraph.memory.appointmentReminders.collect {
            recordsChanged()
            phoneReminders?.refresh()
        }
    }
    scope.launch(Dispatchers.IO) {
        restoreSubscriptions()
        // Room 在事务提交后才通知，覆盖整理完成、状态/标星、软删除及“这段别记”。启动也校对一次。
        database.invalidationTracker.createFlow("memory_events", "memory_items", "transcript_lines", "skill_cards", "reviews", emitInitialState = true)
            .debounce(1000).collect { recordsChanged(); phoneReminders?.refresh() }
    }
}
