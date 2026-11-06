package app.resound.things

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.room.withTransaction
import app.resound.MainActivity
import app.resound.data.CaptureDatabase
import app.resound.data.TranscriptLineEntity
import app.resound.digest.*
import app.resound.usage.UsagePurpose
import kotlinx.coroutines.*
import java.time.LocalDate

internal fun createThings(context: Context, db: CaptureDatabase, scope: CoroutineScope, settings: AndroidDigestSettings,
    template: () -> String): Pair<RoomThingRepository, DigestThingExtraction> {
    val manualClient = LlmClient(okhttp3.OkHttpClient.Builder().callTimeout(20, java.util.concurrent.TimeUnit.SECONDS).build())
    suspend fun detect(lines: List<TranscriptLineEntity>, known: List<Thing>): List<ThingProposal> {
        val preset = settings.preset; val key = settings.readKey()
        if (key.isBlank() && !preset.allowsEmptyKey() && !settings.failover.hasBackup(preset)) throw LlmException(LlmError.Auth)
        val nearby = emptyList<TranscriptLineEntity>()
        val selected = ThingPrompt.known(lines, nearby, known)
        val system = ThingPrompt.system(template())
        val input = ThingPrompt.input(lines, nearby, selected, manual = true)
        var reply: LlmReply? = null
        var resultCount = 0
        var outcome = "failed"
        try {
            reply = settings.failover.request("寻物", db.memory(), app.resound.usage.UsagePurpose.Things, manualClient::chatJson)(preset, key, system, input)
            return ThingPrompt.parse(reply.content, lines, selected.map { it.id }.toSet(), System.currentTimeMillis()).also {
                resultCount = it.size; outcome = "ok"
            }
        } finally {
            withContext(NonCancellable) {
                reply?.let { db.memory().addUsage(LocalDate.now().toString(), it.promptTokens, it.completionTokens, UsagePurpose.Things) }
                // 仅记录计数，不写原话、物品名、密钥或模型回复。
                runCatching { db.capture().event(app.resound.data.EventEntity(at = System.currentTimeMillis(),
                    type = "ThingsUsage", detail = "mode=manual outcome=$outcome lines=${lines.size} context=${nearby.size} known=${selected.size} inputChars=${system.length + input.length} promptTokens=${reply?.promptTokens ?: 0} completionTokens=${reply?.completionTokens ?: 0} results=$resultCount",
                    batteryPercent = null, charging = null)) }
            }
        }
    }
    val repository = RoomThingRepository(db, scope, parseDraft = { text ->
        val rows = db.things().allThings(); val known = things(rows, db.things().allRecords())
            .sortedByDescending { t -> rows.first { it.id == t.id }.updatedAt }.take(200)
        val line = TranscriptLineEntity(id = 1, segmentId = 0, startAt = System.currentTimeMillis(), endAt = System.currentTimeMillis(), text = text, speakerTag = null, speakerName = "我")
        ThingDrafts(detect(listOf(line), known).map { p -> p.row.let {
            ThingDraft(it.name, ThingAction.valueOf(it.action), it.place, it.who, ThingOwner.valueOf(it.owner), it.thingId)
        } }, true)
    }, problem = { message -> scope.launch { db.capture().event(app.resound.data.EventEntity(at = System.currentTimeMillis(),
        type = "ThingsStorage", detail = message, batteryPercent = null, charging = null)) } })
    val notifications = ThingNotifications(context)
    val prefs = context.getSharedPreferences("things", Context.MODE_PRIVATE)
    val firstStartCutoff = System.currentTimeMillis() - 30 * 60_000L
    val extraction = DigestThingExtraction(db, repository, template, initialize = {
        if (!prefs.getBoolean("historySkipped", false)) {
            db.withTransaction {
                val state = db.things().state() ?: ThingWatchState()
                val cut = db.things().maxLineIdBefore(firstStartCutoff)
                db.things().state(state.copy(watermark = maxOf(state.watermark, cut), failures = 0, failedEnd = null))
            }
            prefs.edit().putBoolean("historySkipped", true).apply()
        }
    }, notify = notifications::show)
    return repository to extraction
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
