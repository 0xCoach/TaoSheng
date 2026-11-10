package app.resound.topic

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
            val repository = RoomTopicRepository(db, app.scope, quiet = { start, end ->
                val rules = app.quietSchedule.rules.value
                QuietRules.windowAt(rules, start, ZoneId.systemDefault()) != null ||
                    QuietRules.nextChange(rules, start, ZoneId.systemDefault())?.let { it < end } == true
            }, recordingEnabled = { app.store.prefs.getBoolean("enabled", false) })
            val generator = TopicGenerator({ AppGraph.prompts.text("digest") }, { kind ->
                val id = "import_${kind.name.lowercase()}"
                SkillJson.parse(app.assets.open("skills/$id/skill.json").bufferedReader().use { it.readText() }, SkillOrigin.Builtin).skill to
                    app.assets.open("skills/$id/prompt.md").bufferedReader().use { it.readText() }
            }, request = { system, user ->
                val (preset, key) = settings.skillCredentials(null)
                if (key.isBlank() && !settings.canUseWithoutKey(preset) && !settings.failover.hasBackup(preset)) throw LlmException(LlmError.Auth)
                val reply = settings.failover.request("专题整理", db.memory(), UsagePurpose.Digest, llm::chatJson)(preset, key, system, user)
                withContext(NonCancellable) { db.memory().addUsage(LocalDate.now().toString(), reply.promptTokens, reply.completionTokens, UsagePurpose.Digest) }
                reply
            }, hotwords)
            val engine = TopicEngine(db, repository, generator, app.voiceprintRepository::isReady,
                app.segmentFiles::pendingInRange, ready = { id -> app.scope.launch {
                    db.topics().get(id)?.row?.let { TopicNotifications.show(app, id, TopicReminder.Ready, TopicKind.valueOf(it.kind)) }
                }
                    EmbeddingScheduler.enqueue(app) })
            repository.onChanged = {
                TopicAlarm(app).schedule(db.topics().all())
                CaptureService.recheck()
                CaptureService.refreshOngoing()
                ClassModeTileService.refresh(app)
                TopicNotifications.clearObsolete(app, repository.current.value)
            }
            repository.onEnded = { app.runTranscriptionNow(); TopicWork.enqueue(app) }
            repository.onReminder = { id, type -> TopicNotifications.show(app, id, type) }
            app.scope.launch {
                repository.tick()
                TopicWork.enqueue(app)
                while (isActive) {
                    delay(60_000)
                    repository.tick()
                    if (db.topics().all().any { it.row.status == "Ended" }) TopicWork.enqueue(app)
                }
            }
            app.scope.launch {
                db.transcribe().observeSegments().map { rows -> rows.map { Triple(it.id, it.status, it.voiceprintProcessedAt) } }
                    .distinctUntilChanged().collect {
                        if (db.topics().all().any { it.row.status in setOf("Ended", "Summarizing", "Done") }) TopicWork.enqueue(app)
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
                if (id != 0L && app.database.topics().get(id)?.row?.status !in setOf("Running", "Paused")) return@launch
                when (intent.action) {
                    "start" -> {
                        val result = repository.start(TopicKind.Meeting, null)
                        when {
                            result.error != null -> TopicNotifications.error(context, result.error)
                            result.recordingDisabled -> TopicNotifications.show(context, result.topicId!!, TopicReminder.RecordingOff, scheduled = false)
                            result.quietOverride -> TopicNotifications.error(context, "专题已开始，这段时间原本设为不录音")
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
            catch (e: TopicException) { TopicNotifications.error(context, e.message ?: "专题操作失败，请重试") }
            catch (_: Exception) { TopicNotifications.error(context, "专题操作失败，请重试") }
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
        val minutes = ((now - (topic.spans.minOfOrNull { it.startAt } ?: topic.createdAt)) / 60_000).coerceAtLeast(0)
        val state = if (topic.status == TopicStatus.Paused) "已暂停" else "中"
        val end = topic.plannedEndAt?.let { " · ${Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("H:mm"))} 结束" }.orEmpty()
        return "${topic.name ?: topic.kind.title}$state · 已 $minutes 分钟$end"
    }
    fun actions(builder: NotificationCompat.Builder, context: Context, topic: Topic) {
        builder.addAction(0, "记一下", TopicActionReceiver.intent(context, "mark", topic.id))
            .addAction(0, if (topic.status == TopicStatus.Paused) "继续" else "暂停", TopicActionReceiver.intent(context,
                if (topic.status == TopicStatus.Paused) "resume" else "pause", topic.id))
            .addAction(0, "结束", TopicActionReceiver.intent(context, "end", topic.id))
    }
    fun show(context: Context, id: Long, reminder: TopicReminder, kind: TopicKind = TopicKind.Meeting, scheduled: Boolean = true) {
        val title = when (reminder) {
            TopicReminder.Ending -> "还有 5 分钟结束"
            TopicReminder.Quiet -> "15 分钟没人说话，要结束专题吗？"
            TopicReminder.RecordingOff -> if (scheduled) "预约的专题开始了，录音没开" else "专题已开始，录音没开"
            TopicReminder.Ready -> when (kind) {
                TopicKind.Meeting -> "会议纪要好了"; TopicKind.Training -> "培训笔记好了"; TopicKind.Call -> "通话记录好了"
                TopicKind.Script -> "口播稿好了"; TopicKind.Normal -> "专题整理好了"
            }
        }
        val builder = base(context, title).setContentIntent(open(context, id))
        when (reminder) {
            TopicReminder.Ending -> builder.addAction(0, "延长 30 分钟", TopicActionReceiver.intent(context, "extend", id))
            TopicReminder.Quiet -> builder.addAction(0, "结束", TopicActionReceiver.intent(context, "end", id))
                .addAction(0, "继续录", TopicActionReceiver.intent(context, "continue", id))
            TopicReminder.RecordingOff -> builder.addAction(0, "打开录音", PendingIntent.getForegroundService(context, 1353,
                Intent(context, CaptureService::class.java).setAction(CaptureService.START).putExtra("route", "topic-notification"),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            TopicReminder.Ready -> Unit
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
