package app.resound.capture

import app.resound.appName
import android.Manifest
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

object CaptureNotifications {
    const val ONGOING = 1
    const val INTERRUPTED = 2
    fun channels(context: Context) {
        context.getSystemService(NotificationManager::class.java).createNotificationChannels(listOf(
            // 低调但不隐瞒：常驻通知用最低优先级，不占状态栏图标、排在通知栏末尾；系统麦克风绿点仍在
            NotificationChannel("listening", "后台运行", NotificationManager.IMPORTANCE_MIN),
            NotificationChannel("interrupted", "需要恢复", NotificationManager.IMPORTANCE_HIGH)))
    }
    fun permitted(context: Context) = NotificationManagerCompat.from(context).areNotificationsEnabled() &&
        (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
    private fun action(context: Context, action: String): PendingIntent = PendingIntent.getForegroundService(context, action.hashCode(),
        Intent(context, CaptureService::class.java).setAction(action).putExtra("route", "notification").putExtra("minutes", 30), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    private fun deleteRequest(context: Context): PendingIntent = PendingIntent.getActivity(context, 24,
        Intent(context, app.resound.MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra("open", "deleteRecent"), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    private fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(context, 25,
        Intent(context, app.resound.MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    fun ongoing(context: Context, paused: Boolean): Notification {
        val prefs = context.getSharedPreferences("capture", Context.MODE_PRIVATE)
        val quietName = prefs.getString("quietName", null).takeIf { paused }
        val until = prefs.getLong("pausedUntil", 0)
        val clock = java.time.Instant.ofEpochMilli(until).atZone(java.time.ZoneId.systemDefault()).format(java.time.format.DateTimeFormatter.ofPattern("H:mm"))
        return NotificationCompat.Builder(context, "listening")
        .setSmallIcon(app.resound.R.drawable.ic_notify).setContentTitle(if (quietName != null) "${context.appName} · ${quietName}中" else if (paused) "${context.appName} · 已暂停" else context.appName)
        .setContentText(if (quietName != null) "$clock 自动恢复，点此立即恢复" else if (paused) "到点自动恢复，也可点此立即恢复" else "后台运行中")
        .setOngoing(true).setOnlyAlertOnce(true).setShowWhen(false)
        // 正在听时点通知打开涛声；暂停时照旧点一下就恢复（文案就是这么写的）
        .setPriority(NotificationCompat.PRIORITY_MIN).setContentIntent(if (paused) action(context, CaptureService.RESUME) else openApp(context))
        .addAction(0, if (quietName != null) "现在恢复" else if (paused) "恢复" else "暂停 30 分钟", action(context, if (paused) CaptureService.RESUME else CaptureService.PAUSE))
        .addAction(0, "删最近 10 分钟", deleteRequest(context))
        .build()
    }
    fun interrupted(context: Context) {
        channels(context)
        if (!permitted(context)) return
        try { NotificationManagerCompat.from(context).notify(INTERRUPTED, NotificationCompat.Builder(context, "interrupted")
            .setSmallIcon(app.resound.R.drawable.ic_notify).setContentTitle("${context.appName}已停止，点此恢复")
            .setContentText("后台被系统关闭，点击重新开始").setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(action(context, CaptureService.RESUME)).setAutoCancel(true).build()) } catch (_: SecurityException) { }
    }
}
