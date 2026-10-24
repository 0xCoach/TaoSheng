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

class LocalNotifier(private val context: Context) : Notifier {
    init {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "每日回顾", NotificationManager.IMPORTANCE_DEFAULT))
    }

    override suspend fun send(title: String, body: String, deepLink: String?, topic: PushTopic?) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (!CaptureNotifications.permitted(context) || manager.getNotificationChannel(CHANNEL)?.importance == NotificationManager.IMPORTANCE_NONE)
            throw IllegalStateException("请开启每日回顾通知权限")
        val uri = deepLink?.let(Uri::parse)
        val intent = Intent(context, MainActivity::class.java)
            .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .setData(uri).putExtra("open", uri?.getQueryParameter("open"))
            .putExtra("date", uri?.getQueryParameter("date"))
        val pending = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        try {
            NotificationManagerCompat.from(context).notify(deepLink ?: CHANNEL, NOTIFICATION_ID,
                NotificationCompat.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_notify)
                    .setContentTitle(title).setContentText(body).setStyle(NotificationCompat.BigTextStyle().bigText(body))
                    .setContentIntent(pending).setAutoCancel(true).setOnlyAlertOnce(true)
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT).build())
        } catch (_: SecurityException) { throw IllegalStateException("请开启每日回顾通知权限") }
    }

    companion object {
        const val CHANNEL = "daily-review"
        const val NOTIFICATION_ID = 100
    }
}
