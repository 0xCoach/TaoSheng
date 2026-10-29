package app.resound.capture

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.*
import app.resound.ResoundApp
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

class CaptureWatchdog(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as ResoundApp
        val store = app.store
        if (capturePausePlan(System.currentTimeMillis(), store.prefs.getLong("manualPausedUntil", 0), app.quietSchedule.refresh()) == null && store.prefs.getBoolean("enabled", false) && System.currentTimeMillis() - store.prefs.getLong("heartbeat", 0) > CaptureTuning.HEARTBEAT_MS * 3)
            CaptureNotifications.interrupted(applicationContext)
        return Result.success()
    }
    companion object {
        fun schedule(context: Context) = WorkManager.getInstance(context).enqueueUniquePeriodicWork("capture-watchdog",
            ExistingPeriodicWorkPolicy.KEEP, PeriodicWorkRequestBuilder<CaptureWatchdog>(15, TimeUnit.MINUTES).build())
    }
}
class CaptureBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED,
                Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED, android.app.AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED)) return
        val app = context.applicationContext as ResoundApp
        app.rescheduleDigest(changed = true)
        app.quietSchedule.refresh()
        CaptureService.recheck()
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val pending = goAsync()
        app.scope.launch {
            try {
                if (app.store.prefs.getBoolean("enabled", false)) {
                    app.store.event(WatchdogEventType.BootRestart, "${intent.action}：等待点击通知恢复，未从后台启动麦克风")
                    if (capturePausePlan(System.currentTimeMillis(), app.store.prefs.getLong("manualPausedUntil", 0), app.quietSchedule.refresh()) == null) CaptureNotifications.interrupted(context)
                    CaptureWatchdog.schedule(context)
                }
            } finally { pending.finish() }
        }
    }
}
class CaptureResumeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as ResoundApp
        val prefs = app.store.prefs
        val quiet = app.quietSchedule.refresh()
        if (!prefs.getBoolean("enabled", false)) return
        if (CaptureService.alive) CaptureService.recheck()
        else if (capturePausePlan(System.currentTimeMillis(), prefs.getLong("manualPausedUntil", 0), quiet) == null) CaptureNotifications.interrupted(context)
    }
}
