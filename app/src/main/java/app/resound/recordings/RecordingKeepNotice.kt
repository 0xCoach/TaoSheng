package app.resound.recordings

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import app.resound.MainActivity
import app.resound.R
import java.time.LocalDate

/** 卡214：open=push 由推送设置界面接入导航；此处仅提供通知入口。 */
internal class RecordingKeepNotice(private val context: Context) {
    fun show(held: HeldSummary) {
        if (held.segments == 0 || context.filesDir.usableSpace >= 1_000_000_000L) return
        val prefs = context.getSharedPreferences("recording-keep", Context.MODE_PRIVATE)
        val today = LocalDate.now().toString()
        if (prefs.getString("low-space-day", null) == today || !NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("recording-keep", "录音保留", NotificationManager.IMPORTANCE_DEFAULT))
        val intent = PendingIntent.getActivity(context, 3214, Intent(context, MainActivity::class.java)
            .putExtra("open", "push").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        try {
            manager.notify(3214, NotificationCompat.Builder(context, "recording-keep").setSmallIcon(R.drawable.ic_notify)
                .setContentTitle("有 ${held.days} 天录音还没推上去，手机空间不足")
                .setContentText("点开查看推送设置").setContentIntent(intent).setAutoCancel(true).build())
            check(prefs.edit().putString("low-space-day", today).commit())
        } catch (_: SecurityException) { /* 用户尚未允许通知，下次获得权限后再提醒。 */ }
    }
}
