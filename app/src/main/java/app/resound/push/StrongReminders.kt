package app.resound.push

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import app.resound.ui.alarm.StrongAlarmActivity
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import app.resound.AppGraph
import app.resound.MainActivity
import app.resound.R
import app.resound.ResoundApp
import app.resound.capture.CaptureNotifications
import app.resound.digest.ItemStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 强提醒：到点弹出、响铃、震动，带三个按钮“5 分钟后 / 已完成 / 不再响”。
 * 不理会就每 5 分钟再响一次，最多再响 [MAX_REPEATS] 次，之后安静地留在通知栏；划掉通知等于“不再响”。
 * 每次再响前重查这条待办：已完成、已删除或关了强提醒就不再响。
 */
internal class StrongReminderNotifier(private val context: Context) : Notifier {
    init { StrongReminders.channel(context) }

    override suspend fun send(title: String, body: String, deepLink: String?, topic: PushTopic?) {
        val id = deepLink?.let(Uri::parse)?.getQueryParameter("reminder")?.let(StrongReminders::itemId)
        if (id == null) { LocalNotifier(context, LocalNotifier.TODO_CHANNEL).send(title, body, deepLink, topic); return }
        if (!CaptureNotifications.permitted(context)) throw IllegalStateException("请开启待办提醒通知权限")
        StrongReminders.save(context, id, body)
        StrongReminders.ring(context, id, body, repeat = 0)
    }
}

internal object StrongReminders {
    const val MAX_REPEATS = 3
    private const val GAP_MS = 5 * 60_000L
    private const val PREFS = "strong-reminders"
    private const val ACTION_RING = "app.resound.strong.RING"
    private const val ACTION_SNOOZE = "app.resound.strong.SNOOZE"
    private const val ACTION_DONE = "app.resound.strong.DONE"
    private const val ACTION_STOP = "app.resound.strong.STOP"
    // 每条待办的四种按钮各用一个请求号，互不覆盖
    private val ACTIONS = listOf(ACTION_RING, ACTION_SNOOZE, ACTION_DONE, ACTION_STOP)
    const val EXTRA_ID = "itemId"
    const val EXTRA_TEXT = "text"
    private const val EXTRA_REPEAT = "repeat"

    /** 提醒键形如 item:183:1793184480000:1，取出条目编号 */
    fun itemId(key: String): Long? = Regex("""^item:(\d+):""").find(key)?.groupValues?.get(1)?.toLongOrNull()

    fun channel(context: Context) {
        // 和 LocalNotifier 的待办提醒是同一个渠道，谁先建都一样
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(LocalNotifier.TODO_CHANNEL, "待办提醒", NotificationManager.IMPORTANCE_HIGH).apply {
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 600, 300, 600, 300, 600)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            })
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    fun save(context: Context, id: Long, body: String) = prefs(context).edit().putString("$id", body).apply()
    private fun body(context: Context, id: Long) = prefs(context).getString("$id", null)
    private fun clear(context: Context, id: Long) = prefs(context).edit().remove("$id").apply()

    private fun intent(context: Context, action: String, id: Long, repeat: Int = 0) =
        Intent(context, StrongReminderReceiver::class.java).setAction(action).putExtra(EXTRA_ID, id).putExtra(EXTRA_REPEAT, repeat)
    private fun broadcast(context: Context, action: String, id: Long, repeat: Int = 0) = PendingIntent.getBroadcast(context,
        (id * 4 + ACTIONS.indexOf(action)).toInt(), intent(context, action, id, repeat), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    /** 弹出（或再弹一次）这条强提醒；[repeat] 是第几次再响，0 是到点那一次 */
    fun ring(context: Context, id: Long, body: String, repeat: Int) {
        val open = PendingIntent.getActivity(context, id.toInt(),
            Intent(context, MainActivity::class.java).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .setData(Uri.parse("resound://today?open=todos")).putExtra("open", "todos"),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val text = text(body)
        // 整屏提醒页：锁屏时直接亮屏显示；正在用手机时系统改为一直挂在屏幕上方的提醒条
        val full = PendingIntent.getActivity(context, id.toInt() + 1,
            Intent(context, StrongAlarmActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION)
                .putExtra(EXTRA_ID, id).putExtra(EXTRA_TEXT, text),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(context, LocalNotifier.TODO_CHANNEL)
            .setSmallIcon(R.drawable.ic_notify).setColor(0xFFE8582A.toInt())
            .setContentTitle(if (repeat == 0) "到点了" else "到点了 · 第 ${repeat + 1} 次提醒")
            .setContentText(text).setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_MAX).setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC).setFullScreenIntent(full, true)
            // 响着时划不掉，只能点按钮（或按电源键、音量键）
            .setOngoing(true).setOnlyAlertOnce(true)
            .setContentIntent(full).setDeleteIntent(broadcast(context, ACTION_STOP, id))
            .addAction(0, "5 分钟后", broadcast(context, ACTION_SNOOZE, id))
            .addAction(0, "已完成", broadcast(context, ACTION_DONE, id))
            .addAction(0, "不再响", broadcast(context, ACTION_STOP, id))
            .build()
        try { NotificationManagerCompat.from(context).notify(tag(id), ID, notification) } catch (_: SecurityException) { return }
        AlarmPlayer.start(context, id) { ringingId -> dismiss(context, ringingId) }
        // 不理会就 5 分钟后再响，最多再响三次
        if (repeat < MAX_REPEATS) alarm(context, id, System.currentTimeMillis() + GAP_MS, repeat + 1) else cancelAlarm(context, id)
    }

    private fun tag(id: Long) = "strong:$id"
    private const val ID = 101

    private fun alarm(context: Context, id: Long, at: Long, repeat: Int) {
        val alarms = context.getSystemService(AlarmManager::class.java)
        val pending = broadcast(context, ACTION_RING, id, repeat)
        val exact = Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms()
        try {
            if (exact) alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
            else alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
        } catch (_: SecurityException) { alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending) }
    }
    private fun cancelAlarm(context: Context, id: Long) =
        context.getSystemService(AlarmManager::class.java).cancel(broadcast(context, ACTION_RING, id))

    /** 在 App 里点了完成、删除或关了铃铛：正在响的那几条立刻停 */
    fun sweep(context: Context, snapshot: PushSnapshot) {
        val ringing = prefs(context).all.keys.mapNotNull { it.toLongOrNull() }
        if (ringing.isEmpty()) return
        val live = snapshot.items.filter { !it.deleted && it.status == ItemStatus.Accepted.name && it.strongReminder }.map { it.id }.toSet()
        ringing.filter { it !in live }.forEach { stop(context, it) }
    }

    /** 停掉这一条：不再响、收起通知 */
    fun stop(context: Context, id: Long) {
        AlarmPlayer.silence(id)
        cancelAlarm(context, id); clear(context, id)
        NotificationManagerCompat.from(context).cancel(tag(id), ID)
    }

    /** 不再响（按钮、电源键） */
    fun dismiss(context: Context, id: Long) = stop(context, id)

    /** 5 分钟后再响（按钮、音量键）；推迟后重新计次 */
    fun snooze(context: Context, id: Long) {
        AlarmPlayer.silence(id)
        NotificationManagerCompat.from(context).cancel(tag(id), ID)
        if (body(context, id) != null) alarm(context, id, System.currentTimeMillis() + GAP_MS, 0)
    }

    /** 已完成：停掉并把待办标为完成 */
    fun done(context: Context, id: Long) {
        stop(context, id)
        if (AppGraph.isReady) AppGraph.memory.setItemStatus(id, ItemStatus.Done)
    }

    fun text(body: String) = body.removePrefix("待办到期 · ")

    internal fun handle(context: Context, intent: Intent, scope: CoroutineScope, finished: () -> Unit) {
        val id = intent.getLongExtra(EXTRA_ID, -1)
        if (id < 0) { finished(); return }
        when (intent.action) {
            ACTION_STOP -> { dismiss(context, id); finished() }
            ACTION_SNOOZE -> { snooze(context, id); finished() }
            ACTION_DONE -> { done(context, id); finished() }
            ACTION_RING -> scope.launch(Dispatchers.IO) {
                try {
                    val body = body(context, id) ?: return@launch
                    val item = (context.applicationContext as ResoundApp).database.memory().itemIncludingDeleted(id)
                    if (item == null || item.deleted || item.status != ItemStatus.Accepted.name || !item.strongReminder) stop(context, id)
                    else ring(context, id, body, intent.getIntExtra(EXTRA_REPEAT, 0))
                } finally { finished() }
            }
            else -> finished()
        }
    }
}

class StrongReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        StrongReminders.handle(context, intent, scope) { pending.finish() }
    }
    private companion object { val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default) }
}
