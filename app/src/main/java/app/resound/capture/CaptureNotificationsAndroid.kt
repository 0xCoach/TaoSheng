package app.resound.capture

import app.resound.appName
import app.resound.AppGraph
import app.resound.quick.QuickPrefsRules
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
    const val SILENT_MIC = 3
    fun channels(context: Context) {
        context.getSystemService(NotificationManager::class.java).createNotificationChannels(listOf(
            // 低调但不隐瞒：常驻通知用最低优先级，不占状态栏图标、排在通知栏末尾；系统麦克风绿点仍在
            NotificationChannel("listening", "后台运行", NotificationManager.IMPORTANCE_MIN),
            NotificationChannel("interrupted", "需要恢复", NotificationManager.IMPORTANCE_HIGH)))
    }
    fun permitted(context: Context) = NotificationManagerCompat.from(context).areNotificationsEnabled() &&
        (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
    private fun pauseMinutes() = if (AppGraph.isQuickPrefsReady) AppGraph.quickPrefs.lastPauseMinutes.value else QuickPrefsRules.DEFAULT_PAUSE
    private fun action(context: Context, action: String): PendingIntent = PendingIntent.getForegroundService(context, action.hashCode(),
        Intent(context, CaptureService::class.java).setAction(action).putExtra("route", "notification").putExtra("minutes", pauseMinutes()), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    private fun deleteRequest(context: Context): PendingIntent = PendingIntent.getActivity(context, 24,
        Intent(context, app.resound.MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra("open", "deleteRecent"), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    private fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(context, 25,
        Intent(context, app.resound.MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    private fun openTopic(context: Context, id: Long): PendingIntent = PendingIntent.getActivity(context, 26,
        Intent(context, app.resound.MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            .putExtra("open", "topic").putExtra("topicId", id), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    fun ongoing(context: Context, paused: Boolean): Notification {
        val prefs = context.getSharedPreferences("capture", Context.MODE_PRIVATE)
        val quietName = prefs.getString("quietName", null).takeIf { paused }
        val until = prefs.getLong("pausedUntil", 0)
        // 通知是界面：今天只写时刻，第二天写“明天”，更远才写日期（文件、推送正文才一律写全）
        val at = java.time.Instant.ofEpochMilli(until).atZone(java.time.ZoneId.systemDefault())
        val hm = at.format(java.time.format.DateTimeFormatter.ofPattern("H:mm"))
        val clock = CaptureNotificationPolicy.clock(java.time.temporal.ChronoUnit.DAYS.between(java.time.LocalDate.now(), at.toLocalDate()), hm, app.resound.OutputTime().moment(until))
        val topic = if (app.resound.AppGraph.isTopicsReady) app.resound.AppGraph.topics.current.value else null
        val topicLine = topic?.let { app.resound.topic.TopicNotifications.line(it) }
        val notice = CaptureNotificationPolicy.ongoing(context.appName, paused, quietName, clock, topicLine, pauseMinutes())
        val kind = if (AppGraph.isQuickPrefsReady) AppGraph.quickPrefs.lastTopicKind.value else QuickPrefsRules.defaultTopic
        return NotificationCompat.Builder(context, "listening")
        .setSmallIcon(app.resound.R.drawable.ic_notify).setContentTitle(notice.title)
        .setContentText(notice.text)
        .setOngoing(true).setOnlyAlertOnce(true).setShowWhen(false)
        // 正在听时点通知打开涛声；暂停时照旧点一下就恢复（文案就是这么写的）
        // 专题进行中点通知直接打开这场专题（大按钮记一下/暂停/结束）：系统常把常驻通知收起，按钮要长按才看得到
        .setPriority(NotificationCompat.PRIORITY_MIN).setContentIntent(when (notice.target) {
            CaptureNoticeTarget.Resume -> action(context, CaptureService.RESUME)
            CaptureNoticeTarget.Topic -> openTopic(context, requireNotNull(topic).id)
            CaptureNoticeTarget.App -> openApp(context)
        })
        .apply {
            if (topic != null) app.resound.topic.TopicNotifications.actions(this, context, topic)
            else {
                addAction(0, notice.toggle, action(context, if (paused) CaptureService.RESUME else CaptureService.PAUSE))
                addAction(0, CaptureNotificationPolicy.DELETE_RECENT, deleteRequest(context))
                addAction(0, CaptureNotificationPolicy.startTopic(kind), app.resound.topic.TopicActionReceiver.intent(context, "start"))
            }
        }
        .build()
    }
    fun interrupted(context: Context) {
        // 与现有中断判定共用入口，即使没有通知权限也保留事实。
        app.resound.onboarding.LocalOnboarding.captureInterrupted(context)
        channels(context)
        if (!permitted(context)) return
        try { NotificationManagerCompat.from(context).notify(INTERRUPTED, NotificationCompat.Builder(context, "interrupted")
            .setSmallIcon(app.resound.R.drawable.ic_notify).setContentTitle(CaptureNotificationPolicy.interrupted(context.appName))
            .setContentText(CaptureNotificationPolicy.INTERRUPTED_DETAIL).setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(action(context, CaptureService.RESUME)).setAutoCancel(true).build()) } catch (_: SecurityException) { }
    }
    /** 卡 112：麦克风一直收不到声音，重开也没用 */
    fun silentMic(context: Context) {
        channels(context)
        if (!permitted(context)) return
        try { NotificationManagerCompat.from(context).notify(SILENT_MIC, NotificationCompat.Builder(context, "interrupted")
            .setSmallIcon(app.resound.R.drawable.ic_notify).setContentTitle(CaptureNotificationPolicy.SILENT)
            .setContentText(CaptureNotificationPolicy.SILENT_DETAIL).setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(openApp(context)).setAutoCancel(true).build()) } catch (_: SecurityException) { }
    }
    fun cancelSilentMic(context: Context) = NotificationManagerCompat.from(context).cancel(SILENT_MIC)
}
