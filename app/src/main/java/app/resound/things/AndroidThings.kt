package app.resound.things

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import app.resound.MainActivity
import app.resound.data.CaptureDatabase
import app.resound.data.TranscriptLineEntity
import app.resound.digest.*
import app.resound.usage.UsagePurpose
import kotlinx.coroutines.*
import java.time.LocalDate

internal fun createThings(context: Context, db: CaptureDatabase, scope: CoroutineScope, settings: AndroidDigestSettings,
    client: LlmClient, template: () -> String): Pair<RoomThingRepository, ThingWatcher> {
    val manualClient = LlmClient(okhttp3.OkHttpClient.Builder().callTimeout(20, java.util.concurrent.TimeUnit.SECONDS).build())
    suspend fun detect(lines: List<TranscriptLineEntity>, previous: List<TranscriptLineEntity>, known: List<Thing>, manual: Boolean): List<ThingProposal> {
        val preset = settings.preset; val key = settings.readKey()
        if (key.isBlank() && preset.id != "custom") throw LlmException(LlmError.Auth)
        var reply: LlmReply? = null
        try {
            reply = (if (manual) manualClient else client).chatJson(preset, key, ThingPrompt.system(template()), ThingPrompt.input(lines, previous, known, manual))
            return ThingPrompt.parse(reply.content, lines, known.map { it.id }.toSet(), System.currentTimeMillis())
        } finally {
            withContext(NonCancellable) { db.memory().addUsage(LocalDate.now().toString(), reply?.promptTokens ?: 0,
                reply?.completionTokens ?: 0, UsagePurpose.Things) }
        }
    }
    val repository = RoomThingRepository(db, scope, parseDraft = { text ->
        val rows = db.things().allThings(); val known = things(rows, db.things().allRecords())
            .sortedByDescending { t -> rows.first { it.id == t.id }.updatedAt }.take(200)
        val line = TranscriptLineEntity(id = 1, segmentId = 0, startAt = System.currentTimeMillis(), endAt = System.currentTimeMillis(), text = text, speakerTag = null, speakerName = "我")
        ThingDrafts(detect(listOf(line), emptyList(), known, true).map { p -> p.row.let {
            ThingDraft(it.name, ThingAction.valueOf(it.action), it.place, it.who, ThingOwner.valueOf(it.owner), it.thingId)
        } }, true)
    }, problem = { message -> scope.launch { db.capture().event(app.resound.data.EventEntity(at = System.currentTimeMillis(),
        type = "ThingsStorage", detail = message, batteryPercent = null, charging = null)) } })
    val notifications = ThingNotifications(context)
    val watcher = ThingWatcher(db, repository, detect = { lines, previous, known -> detect(lines, previous, known, false) }, notify = notifications::show)
    return repository to watcher
}

internal class ThingNotifications(private val context: Context) {
    private val manager = context.getSystemService(NotificationManager::class.java)
    init { manager.createNotificationChannel(NotificationChannel("things", "寻物", NotificationManager.IMPORTANCE_LOW).apply {
        setSound(null, null); enableVibration(false); setShowBadge(false)
    }) }
    fun show(record: ThingRecordRow) {
        if (!record.explicit) return
        val target = when (ThingAction.valueOf(record.action)) {
            ThingAction.Placed -> record.place
            ThingAction.Lent -> "借给${record.who}"
            ThingAction.Returned -> "已还${record.place?.let { "，$it" }.orEmpty()}"
            ThingAction.TakenAway -> "带走了"
        }
        val intent = Intent(context, MainActivity::class.java).putExtra("open", "things_pending")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pending = PendingIntent.getActivity(context, 7200, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(context, "things").setSmallIcon(app.resound.R.drawable.ic_notify)
            .setContentTitle("已记：${record.name} → $target").setContentText("待确认，点开查看")
            .setContentIntent(pending).setAutoCancel(true).setSilent(true).setOnlyAlertOnce(true).build()
        // 不主动索取权限；拒绝通知不影响记录入库。
        try { manager.notify("thing-${record.id}", 7200, notification) } catch (_: SecurityException) { }
    }
}
