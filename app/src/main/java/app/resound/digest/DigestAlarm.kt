package app.resound.digest

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import app.resound.ResoundApp
import app.resound.transcribe.PipelineTimes
import kotlinx.coroutines.launch
import androidx.work.await

/** 只安排下一次唤醒；漏点是否补做由持久化完成凭据决定。 */
class DigestAlarm(private val context: Context, private val timing: AndroidDigestTiming) {
    private val alarms get() = context.getSystemService(AlarmManager::class.java)
    private fun pending() = PendingIntent.getBroadcast(context, 530,
        Intent(context, DigestAlarmReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    fun cancel() { alarms.cancel(pending()); timing.next(null) }
    fun schedule(now: Long = System.currentTimeMillis()) {
        if (timing.mode.value != DigestMode.TimePoints) { cancel(); return }
        val next = DigestTime.next(timing.timePoints.value, now) ?: run { cancel(); return }
        val intent = pending()
        try {
            if (timing.canScheduleExact()) alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, intent)
            else alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, intent)
        } catch (_: SecurityException) {
            // 检查后权限被收回也保留普通闹钟兜底。
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, intent)
        }
        timing.next(next)
    }
    companion object {
        fun online(context: Context): Boolean {
            val cm = context.getSystemService(ConnectivityManager::class.java)
            return cm.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        }
    }
}

class DigestAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as ResoundApp
        val pending = goAsync()
        app.scope.launch {
            try {
                PipelineTimes.record(context, "${System.currentTimeMillis()} 到点整理 闹钟唤醒")
                // 不以广播迟到判定过期；完成凭据决定是否仍需补做。
                app.rescheduleDigest()
                app.recoverTimedDigest()?.await()
            } finally { pending.finish() }
        }
    }
}
