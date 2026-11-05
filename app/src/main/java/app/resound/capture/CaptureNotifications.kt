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
    /** capture 偏好里的“让出麦克风”时长（分钟），界面侧可写入；未设置时 30 分钟。 */
    const val YIELD_MINUTES = "yieldMinutes"
    fun yieldMinutes(context: Context): Int =
        context.getSharedPreferences("capture", Context.MODE_PRIVATE).getInt(YIELD_MINUTES, 30).coerceIn(1, 10080)

    /** 通知上的文案。Android 9 同一时间只能一个应用录音，别的应用要用只能我们先松手，所以把“暂停”说成“让出麦克风”。 */
    internal data class Texts(val title: String, val text: String, val button: String)
    internal fun texts(appName: String, paused: Boolean, legacyMic: Boolean): Texts = when {
        legacyMic && paused -> Texts("$appName · 已让出麦克风", "别的应用可以录音了；到点自动继续，也可点此继续", "继续")
        legacyMic -> Texts(appName, "后台运行中；别的应用要录音时点“让出麦克风”", "让出麦克风")
        paused -> Texts("$appName · 已暂停", "到点自动恢复，也可点此立即恢复", "恢复")
        else -> Texts(appName, "后台运行中", "暂停 30 分钟")
    }
    private fun legacyMic() = Build.VERSION.SDK_INT < 29

    private fun action(context: Context, action: String, minutes: Int = 30): PendingIntent = PendingIntent.getForegroundService(context, action.hashCode(),
        Intent(context, CaptureService::class.java).setAction(action).putExtra("route", "notification").putExtra("minutes", minutes), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    private fun deleteRequest(context: Context): PendingIntent = PendingIntent.getActivity(context, 24,
        Intent(context, app.resound.MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra("open", "deleteRecent"), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    private fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(context, 25,
        Intent(context, app.resound.MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    fun ongoing(context: Context, paused: Boolean): Notification {
        val legacy = legacyMic()
        val t = texts(context.appName, paused, legacy)
        // 让出麦克风就是暂停：录音协程取消、AudioRecord 释放；到点或点“继续”恢复，对方还占着时服务按“麦克风被占用”自动重试
        val pause = action(context, CaptureService.PAUSE, if (legacy) yieldMinutes(context) else 30)
        return NotificationCompat.Builder(context, "listening")
            .setSmallIcon(app.resound.R.drawable.ic_notify).setContentTitle(t.title).setContentText(t.text)
            .setOngoing(true).setOnlyAlertOnce(true).setShowWhen(false)
            // 正在听时点通知打开涛声；暂停时照旧点一下就恢复（文案就是这么写的）
            .setPriority(NotificationCompat.PRIORITY_MIN).setContentIntent(if (paused) action(context, CaptureService.RESUME) else openApp(context))
            .addAction(0, t.button, if (paused) action(context, CaptureService.RESUME) else pause)
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
