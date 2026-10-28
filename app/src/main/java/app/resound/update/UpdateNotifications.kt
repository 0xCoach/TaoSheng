package app.resound.update

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.core.app.NotificationCompat
import app.resound.MainActivity
import app.resound.R
import app.resound.capture.CaptureNotifications

internal object UpdateNotifications {
    private const val CHANNEL = "app-update"
    private const val AVAILABLE = 510
    private const val CONFIRM = 511
    private const val VOICE = 512
    fun available(context: Context, info: UpdateInfo): Boolean = post(context, AVAILABLE,
        "涛声 ${info.versionName} 可以更新", info.notes.lineSequence().firstOrNull().orEmpty(),
        Intent(context, MainActivity::class.java).putExtra(EXTRA_OPEN_UPDATE, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))

    fun confirmation(context: Context, intent: Intent) = post(context, CONFIRM, "涛声更新等待确认", "点这里继续安装", intent)
    fun voice(context: Context) = post(context, VOICE, "语音输入记录没在运行，点这里重新打开", "涛声需要重新连接语音输入服务",
        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    fun dismiss(context: Context) = context.getSystemService(NotificationManager::class.java).cancel(AVAILABLE)
    fun dismissConfirmation(context: Context) = context.getSystemService(NotificationManager::class.java).cancel(CONFIRM)

    private fun post(context: Context, id: Int, title: String, body: String, intent: Intent): Boolean {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "版本更新", NotificationManager.IMPORTANCE_DEFAULT))
        if (!CaptureNotifications.permitted(context) || manager.getNotificationChannel(CHANNEL)?.importance == NotificationManager.IMPORTANCE_NONE) return false
        val pending = PendingIntent.getActivity(context, id, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return try {
            manager.notify(id, NotificationCompat.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_notify)
                .setContentTitle(title).setContentText(body).setContentIntent(pending).setAutoCancel(true).setOnlyAlertOnce(true).build())
            true
        } catch (_: SecurityException) { false }
    }
}
