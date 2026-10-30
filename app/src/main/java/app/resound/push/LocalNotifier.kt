package app.resound.push

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import app.resound.MainActivity
import app.resound.R
import app.resound.capture.CaptureNotifications

class LocalNotifier(private val context: Context, private val channel: String = CHANNEL) : Notifier {
    private val channelName = when (channel) { SKILL_GENERATION_CHANNEL -> "技能生成"; TODO_CHANNEL -> "待办提醒"; BALANCE_CHANNEL -> "余额提醒"; else -> "每日回顾" }
    // 待办到点要像闹钟一样弹出来，用高重要级；其余照旧
    private val urgent = channel == TODO_CHANNEL
    init {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(channel, channelName,
            if (urgent) NotificationManager.IMPORTANCE_HIGH else NotificationManager.IMPORTANCE_DEFAULT).apply {
            if (urgent) {
                // 到点要做的事：响铃 + 连震三下 + 锁屏也显示
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 600, 300, 600, 300, 600)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            }
        })
        // 渠道建好后系统不让改震动，旧的无震动渠道删掉
        if (urgent) manager.deleteNotificationChannel("todo-reminder")
    }

    override suspend fun send(title: String, body: String, deepLink: String?, topic: PushTopic?) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (!CaptureNotifications.permitted(context) || manager.getNotificationChannel(channel)?.importance == NotificationManager.IMPORTANCE_NONE)
            throw IllegalStateException("请开启${channelName}通知权限")
        val uri = deepLink?.let(Uri::parse)
        val intent = Intent(context, MainActivity::class.java)
            .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .setData(uri).putExtra("open", uri?.getQueryParameter("open"))
            .putExtra("date", uri?.getQueryParameter("date"))
        val pending = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        try {
            NotificationManagerCompat.from(context).notify(deepLink ?: channel, NOTIFICATION_ID,
                NotificationCompat.Builder(context, channel).setSmallIcon(R.drawable.ic_notify)
                    .setContentTitle(title).setContentText(body).setStyle(NotificationCompat.BigTextStyle().bigText(body))
                    .setContentIntent(pending).setAutoCancel(true).setOnlyAlertOnce(true)
                    .setPriority(if (urgent) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
                    .apply { if (urgent) setCategory(NotificationCompat.CATEGORY_REMINDER).setVisibility(NotificationCompat.VISIBILITY_PUBLIC) }.build())
        } catch (_: SecurityException) { throw IllegalStateException("请开启${channelName}通知权限") }
    }

    companion object {
        const val CHANNEL = "daily-review"
        const val SKILL_GENERATION_CHANNEL = "skill-generation"
        const val TODO_CHANNEL = "todo-reminder-2"
        const val BALANCE_CHANNEL = "llm-balance"
        const val NOTIFICATION_ID = 100
    }
}
