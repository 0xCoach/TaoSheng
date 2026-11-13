package app.resound.topic

import app.resound.scheduling.BackgroundRefreshPolicy
import app.resound.scheduling.refreshWhileNeeded
import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.*
import app.resound.capture.QuietRules
import app.resound.capture.ClassModeTileService
import app.resound.usage.UsagePurpose
import app.resound.search.EmbeddingScheduler
import app.resound.AppGraph
import app.resound.ResoundApp
import app.resound.capture.CaptureNotifications
import app.resound.capture.CaptureService
import app.resound.data.CaptureDatabase
import app.resound.digest.*
import app.resound.skills.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

internal class TopicFeature(val repository: RoomTopicRepository, val engine: TopicEngine) {
    companion object {
        fun create(app: ResoundApp, db: CaptureDatabase, settings: AndroidDigestSettings, llm: LlmClient,
            hotwords: () -> List<String>): TopicFeature {
            val checkpoints = TopicCheckpointFiles(app) { id ->
                db.topics().get(id)?.takeIf { it.row.status != "Cancelled" }?.let { topicSource(it, db.topics().lines(id)) }
            }
            val repository = RoomTopicRepository(db, app.scope, quiet = { start, end ->
                val rules = app.quietSchedule.rules.value
                QuietRules.windowAt(rules, start, ZoneId.systemDefault()) != null ||
                    QuietRules.nextChange(rules, start, ZoneId.systemDefault())?.let { it < end } == true
            }, recordingEnabled = { app.store.prefs.getBoolean("enabled", false) }, checkpoints = checkpoints)
            val generator = TopicGenerator({ AppGraph.prompts.text("digest") }, { kind ->
                val id = "import_${kind.name.lowercase()}"
                SkillJson.parse(app.assets.open("skills/$id/skill.json").bufferedReader().use { it.readText() }, SkillOrigin.Builtin).skill to
                    app.assets.open("skills/$id/prompt.md").bufferedReader().use { it.readText() }
            }, request = { system, user ->
                val (preset, key) = settings.skillCredentials(null)
                if (key.isBlank() && !settings.canUseWithoutKey(preset) && !settings.failover.hasBackup(preset)) throw LlmException(LlmError.Auth)
                val reply = settings.failover.request("专题整理", db.memory(), UsagePurpose.Digest, settings.digestRequest(llm))(preset, key, system, user)
                withContext(NonCancellable) { db.memory().addUsage(LocalDate.now().toString(), reply.promptTokens, reply.completionTokens, UsagePurpose.Digest) }
                reply
            }, hotwords)
            val engine = TopicEngine(db, repository, generator, app.voiceprintRepository::isReady,
                app.segmentFiles::pendingInRange, checkpoints = checkpoints, ready = { id -> app.scope.launch {
                    db.topics().get(id)?.row?.let { TopicNotifications.show(app, id, TopicReminder.Ready, TopicKind.valueOf(it.kind)) }
                }
                    EmbeddingScheduler.enqueue(app) })
            repository.onChanged = {
                val rows = db.topics().all()
                checkpoints.retain(rows.filter { it.row.status != "Cancelled" }.map { it.row.id }.toSet())
                TopicAlarm(app).schedule(rows)
                CaptureService.recheck()
                CaptureService.refreshOngoing()
                ClassModeTileService.refresh(app)
                TopicNotifications.clearObsolete(app, repository.current.value)
            }
            repository.onEnded = { app.runTranscriptionNow(); TopicWork.enqueue(app) }
            repository.onReminder = { id, type -> TopicNotifications.show(app, id, type) }
            app.scope.launch {
                db.invalidationTracker.createFlow("topics", "topic_spans", "topic_marks").collect { checkpoints.refresh() }
            }
            app.scope.launch {
                repository.tick()
                TopicWork.enqueue(app)
                repository.current.map { BackgroundRefreshPolicy.topicNeedsTick(it?.status) }
                    .refreshWhileNeeded(60_000) {
                        repository.tick()
                        if (db.topics().all().any { it.row.status == "Ended" }) TopicWork.enqueue(app)
                    }
            }
            app.scope.launch {
                db.topics().observePendingIds().whileTopicsPending { ids ->
                    db.topics().observeProgress(ids).map { ids to it }
                }.collect { (ids, _) ->
                    checkpoints.refresh(ids.toSet())
                    TopicWork.enqueue(app)
                }
            }
            app.scope.launch {
                db.topics().observeLateTranscriptIds().distinctUntilChanged().collect { ids ->
                    if (ids.isNotEmpty()) { checkpoints.refresh(ids.toSet()); TopicWork.enqueue(app) }
                }
            }
            return TopicFeature(repository, engine)
        }
    }
}

/** 始终只排下一个边界；无精确闹钟权限时采用现有普通闹钟兜底。 */
internal class TopicAlarm(private val context: Context) {
    fun schedule(groups: List<TopicGroup>, now: Long = System.currentTimeMillis()) {
        val manager = context.getSystemService(AlarmManager::class.java)
        val intent = PendingIntent.getBroadcast(context, 1350, Intent(context, TopicAlarmReceiver::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val times = groups.flatMap { g -> when (g.row.status) {
            "Scheduled" -> listOfNotNull(g.row.plannedStartAt)
            "Running", "Paused" -> buildList {
                add(g.deadline())
                if (g.row.plannedEndAt != null && g.row.endingNotifiedAt != g.deadline()) add(g.deadline() - 5 * 60_000L)
                if (g.row.status == "Running") add(now + 60_000)
            }
            else -> emptyList()
        } }
        val target = times.minOrNull()?.coerceAtLeast(now + 1000) ?: run { manager.cancel(intent); return }
        try {
            if (Build.VERSION.SDK_INT < 31 || manager.canScheduleExactAlarms()) manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, target, intent)
            else manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, target, intent)
        } catch (_: SecurityException) { manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, target, intent) }
    }
}
class TopicAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val app = context.applicationContext as ResoundApp
        app.scope.launch { try { app.topics.repository.tick() } finally { pending.finish() } }
    }
}

class TopicActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val app = context.applicationContext as ResoundApp
        app.scope.launch {
            try {
                val repository = app.topics.repository
                val id = intent.getLongExtra("topicId", 0)
                // 老通知不能作用于另一个后来开始的专题。
                if (id != 0L && !TopicQuickPolicy.active(app.database.topics().get(id)?.row?.status)) return@launch
                when (intent.action) {
                    "start" -> {
                        val result = repository.start(AppGraph.quickPrefs.lastTopicKind.value, null)
                        when {
                            result.error != null -> TopicNotifications.error(context, requireNotNull(result.error))
                            result.recordingDisabled -> TopicNotifications.show(context, result.topicId!!, TopicReminder.RecordingOff, scheduled = false)
                            result.quietOverride -> TopicNotifications.error(context, TopicQuickPolicy.QUIET_STARTED)
                        }
                    }
                    "mark" -> repository.mark()
                    "pause" -> repository.pause()
                    "resume" -> repository.resume()
                    "end" -> repository.end()
                    "extend" -> repository.extend(30)
                    "continue" -> NotificationManagerCompat.from(context).cancel(TopicNotifications.QUIET)
                }
            } catch (e: CancellationException) { throw e }
            catch (e: TopicException) { TopicNotifications.error(context, e.message ?: TopicQuickPolicy.FAILED) }
            catch (_: Exception) { TopicNotifications.error(context, TopicQuickPolicy.FAILED) }
            finally { pending.finish() }
        }
    }
    companion object {
        internal fun intent(context: Context, action: String, topicId: Long = 0): PendingIntent = PendingIntent.getBroadcast(context,
            13500 + action.hashCode(), Intent(context, TopicActionReceiver::class.java).setAction(action).putExtra("topicId", topicId)
                .setData(android.net.Uri.parse("resound://topic/$topicId/$action")),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }
}

internal object TopicNotifications {
    const val QUIET = 1352
    private const val ENDING = 1351
    private const val OFF = 1353
    private const val READY = 1354
    private fun open(context: Context, id: Long): PendingIntent = PendingIntent.getActivity(context, id.hashCode(),
        Intent(context, app.resound.MainActivity::class.java).putExtra("open", "topic").putExtra("topicId", id)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    fun line(topic: Topic, now: Long = System.currentTimeMillis()): String {
        val endClock = topic.plannedEndAt?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("H:mm")) }
        return TopicQuickPolicy.line(topic.name, topic.kind, topic.status == TopicStatus.Paused,
            topic.spans.minOfOrNull { it.startAt } ?: topic.createdAt, now, endClock)
    }
    fun actions(builder: NotificationCompat.Builder, context: Context, topic: Topic) {
        TopicQuickPolicy.actions(topic.status == TopicStatus.Paused).forEach {
            builder.addAction(0, it.label, TopicActionReceiver.intent(context, it.action, topic.id))
        }
    }
    fun show(context: Context, id: Long, reminder: TopicReminder, kind: TopicKind = TopicKind.Meeting, scheduled: Boolean = true) {
        val builder = base(context, TopicQuickPolicy.reminderTitle(reminder, kind, scheduled)).setContentIntent(open(context, id))
        TopicQuickPolicy.reminderActions(reminder).forEach { button ->
            val action = if (button.action == "start-listening") PendingIntent.getForegroundService(context, 1353,
                Intent(context, CaptureService::class.java).setAction(CaptureService.START).putExtra("route", "topic-notification"),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            else TopicActionReceiver.intent(context, button.action, id)
            builder.addAction(0, button.label, action)
        }
        send(context, when (reminder) { TopicReminder.Ending -> ENDING; TopicReminder.Quiet -> QUIET; TopicReminder.RecordingOff -> OFF; TopicReminder.Ready -> READY }, builder)
    }
    private fun base(context: Context, title: String): NotificationCompat.Builder {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("topics", "专题录音", NotificationManager.IMPORTANCE_DEFAULT))
        return NotificationCompat.Builder(context, "topics").setSmallIcon(app.resound.R.drawable.ic_notify).setContentTitle(title).setAutoCancel(true)
    }
    private fun send(context: Context, id: Int, builder: NotificationCompat.Builder) {
        if (!CaptureNotifications.permitted(context)) return
        try { NotificationManagerCompat.from(context).notify(id, builder.build()) } catch (_: SecurityException) { }
    }
    fun error(context: Context, text: String) = send(context, 1355, base(context, text))
    fun clearObsolete(context: Context, current: Topic?) {
        val manager = NotificationManagerCompat.from(context)
        if (current == null) listOf(ENDING, QUIET, OFF).forEach(manager::cancel)
        else {
            if (current.status == TopicStatus.Paused) manager.cancel(QUIET)
            if (current.plannedEndAt?.let { it - System.currentTimeMillis() > 5 * 60_000L } == true) manager.cancel(ENDING)
        }
    }
}

class TopicWork(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun getForegroundInfo(): ForegroundInfo {
        applicationContext.getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("topic-work", "专题整理", NotificationManager.IMPORTANCE_LOW))
        return ForegroundInfo(3135, NotificationCompat.Builder(applicationContext, "topic-work").setSmallIcon(app.resound.R.drawable.ic_notify)
            .setContentTitle("正在整理专题").setOngoing(true).build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            try { setForeground(getForegroundInfo()) } catch (e: CancellationException) { throw e } catch (_: Exception) { }
            val app = applicationContext as ResoundApp
            if (app.voiceprintRepository.withStableResults { app.topics.engine.run() }) Result.retry() else Result.success()
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { Result.retry() }
    }
    companion object {
        fun enqueue(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork("topic-summaries", ExistingWorkPolicy.APPEND_OR_REPLACE,
                OneTimeWorkRequestBuilder<TopicWork>().setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build())
        }
    }
}
