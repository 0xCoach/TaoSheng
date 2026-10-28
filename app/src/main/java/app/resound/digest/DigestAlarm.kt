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

/** 始终只保留下一个闹钟；截止时间随闹钟快照保存，不因晚到而把窗口延到明天。 */
class DigestAlarm(private val context: Context, private val timing: AndroidDigestTiming) {
    private val alarms get() = context.getSystemService(AlarmManager::class.java)
    private fun pending(target: Long = 0, expires: Long = 0) = PendingIntent.getBroadcast(context, 530,
        Intent(context, DigestAlarmReceiver::class.java).putExtra(TARGET, target).putExtra(EXPIRES, expires),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    fun cancel() { alarms.cancel(pending()); timing.next(null) }
    fun schedule(now: Long = System.currentTimeMillis()) {
        if (timing.mode.value != DigestMode.TimePoints) { cancel(); return }
        val next = DigestTime.next(timing.timePoints.value, now) ?: run { cancel(); return }
        val expires = requireNotNull(DigestTime.next(timing.timePoints.value, next))
        val intent = pending(next, expires)
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
        const val TARGET = "digest-target"
        const val EXPIRES = "digest-expires"
        fun online(context: Context): Boolean {
            val cm = context.getSystemService(ConnectivityManager::class.java)
            return cm.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        }
    }
}

class DigestAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as ResoundApp
        val target = intent.getLongExtra(DigestAlarm.TARGET, 0)
        val expires = intent.getLongExtra(DigestAlarm.EXPIRES, 0)
        val pending = goAsync()
        // 先接续闹钟，不等本次流水线结束。
        app.rescheduleDigest()
        app.scope.launch {
            try {
                if (DigestTime.missed(System.currentTimeMillis(), target, expires) || !DigestAlarm.online(context)) {
                    PipelineTimes.record(context, "${System.currentTimeMillis()} 到点整理 已错过，跳过")
                } else app.enqueueTimedDigest(target, expires)?.await()
            } finally { pending.finish() }
        }
    }
}
