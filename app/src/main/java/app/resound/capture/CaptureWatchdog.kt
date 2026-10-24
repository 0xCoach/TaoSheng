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
        val store = (applicationContext as ResoundApp).store
        if (store.prefs.getBoolean("enabled", false) && System.currentTimeMillis() - store.prefs.getLong("heartbeat", 0) > CaptureTuning.HEARTBEAT_MS * 3)
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
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val pending = goAsync()
        val app = context.applicationContext as ResoundApp
        app.scope.launch {
            try {
                if (app.store.prefs.getBoolean("enabled", false)) {
                    app.store.event(WatchdogEventType.BootRestart, "${intent.action}：等待点击通知恢复，未从后台启动麦克风")
                    CaptureNotifications.interrupted(context)
                    CaptureWatchdog.schedule(context)
                }
            } finally { pending.finish() }
        }
    }
}
class CaptureResumeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val prefs = context.getSharedPreferences("capture", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("enabled", false)) return
        if (CaptureService.alive) {
            try { context.startService(Intent(context, CaptureService::class.java).setAction(CaptureService.RESUME).putExtra("route", "pause-alarm")) }
            catch (_: RuntimeException) { CaptureNotifications.interrupted(context) }
        } else CaptureNotifications.interrupted(context)
    }
}
