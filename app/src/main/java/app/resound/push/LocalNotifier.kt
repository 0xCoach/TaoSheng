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
    private val channelName = if (channel == SKILL_GENERATION_CHANNEL) "技能生成" else "每日回顾"
    init {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(channel, channelName, NotificationManager.IMPORTANCE_DEFAULT))
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
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT).build())
        } catch (_: SecurityException) { throw IllegalStateException("请开启${channelName}通知权限") }
    }

    companion object {
        const val CHANNEL = "daily-review"
        const val SKILL_GENERATION_CHANNEL = "skill-generation"
        const val NOTIFICATION_ID = 100
    }
}
