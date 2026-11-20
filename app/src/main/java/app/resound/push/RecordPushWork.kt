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
            if (continuation) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.REPLACE, request(id, at, AppGraph.isPushReady && (AppGraph.push as? AndroidPushChannels)?.channels?.value?.find { it.id == id }?.kind == PushKind.LocalFolder)).result.get()
    }
    fun cancel(id: Long) { WorkManager.getInstance(context).cancelUniqueWork("push-subscription-$id") }
    companion object {
        fun request(id: Long, at: Long, local: Boolean = false) = OneTimeWorkRequestBuilder<RecordPushWork>()
            .setInputData(workDataOf("channelId" to id)).addTag("push-subscription")
            .setInitialDelay((at - System.currentTimeMillis()).coerceAtLeast(0), TimeUnit.MILLISECONDS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(if (local) NetworkType.NOT_REQUIRED else NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build()
    }
}

@OptIn(FlowPreview::class)
internal fun AndroidPushChannels.attachSubscriptions(context: Context, database: CaptureDatabase, backup: AndroidBackupRepository, scope: CoroutineScope) {
    linkedCredentials = backup::pushCredentials
    linkedAccount = backup.state.map { if (it.hasPassword) it.account else null }.distinctUntilChanged()
    subscriptions.source = { since, lines -> database.push().snapshot(since?.atStartOfDay(ZoneId.systemDefault())?.toInstant()?.toEpochMilli() ?: Long.MIN_VALUE, lines) }
    subscriptions.automaticSource = { since, lines ->
        val today = java.time.LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val hidden = database.folderRecordings().quietLines(today).toSet()
        val cards = database.folderRecordings().quietCards(today).toSet()
        val raw = subscriptions.source(since,lines)
        val events = hidden.mapNotNull { raw.lineOrigins[it] }.toSet()
        raw.copy(events=raw.events.filter { it.id !in events }, items=raw.items.filter { it.lineId !in hidden && it.eventId !in events },
            cards=raw.cards.filter { it.id !in cards }, lines=raw.lines.filter { it.id !in hidden },
            lineOrigins=raw.lineOrigins.filterKeys { it !in hidden }, repeats=raw.items.filter { it.lineId !in hidden && it.eventId !in events }.groupingBy { it.mergedInto ?: it.id }.eachCount())
    }
    subscriptions.reminderSettings = { AppGraph.memory.appointmentReminders.value }
    phoneReminders = PhoneReminders(context.getSharedPreferences("phone-reminders", Context.MODE_PRIVATE),
        { requireNotNull(subscriptions.automaticSource)(null, false) }, subscriptions.reminderSettings, LocalNotifier(context), { at, continuation -> schedulePhoneReminder(context, at, continuation) },
        strongNotifier = StrongReminderNotifier(context),
        onRefresh = { snapshot -> StrongReminders.sweep(context, snapshot) })
    scope.launch(Dispatchers.IO) {
        AppGraph.memory.appointmentReminders.collect {
            recordsChanged()
            phoneReminders?.refresh()
        }
    }
    scope.launch(Dispatchers.IO) {
        restoreSubscriptions()
        observeRecordPush(database)
    }
}

@OptIn(FlowPreview::class)
internal suspend fun AndroidPushChannels.observeRecordPush(database: CaptureDatabase) {
    val records = database.invalidationTracker.createFlow("memory_events", "memory_items", "transcript_lines",
        "skill_cards", "reviews", emitInitialState = true).map { false }
    // 提醒只看事件和待办；每句新转写都会改 transcript_lines，录音时约每分钟一次，
    // 旧做法每次都把全部历史读两遍来算提醒。原话变了只需让推送按计划更新。
    val reminderTables = database.invalidationTracker.createFlow("memory_events", "memory_items", "folder_recordings",
        emitInitialState = true).map { true }
    val reminders = java.util.concurrent.atomic.AtomicBoolean(false)
    merge(records, reminderTables, privacyChanges.receiveAsFlow().map { true })
        .onEach { if (it) reminders.set(true) }.debounce(1000).collect {
            val refresh = reminders.getAndSet(false)
            recordsChanged(refresh)
            if (refresh) phoneReminders?.refresh()
        }
}
