package app.resound.digest

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import app.resound.ResoundApp
import kotlinx.coroutines.launch

/** 只安排下一次唤醒；漏点是否补做由持久化完成凭据决定。 */
class DigestAlarm(private val context: Context, private val timing: AndroidDigestTiming) {
    private val alarms get() = context.getSystemService(AlarmManager::class.java)
    private fun pending() = PendingIntent.getBroadcast(context, 530,
        Intent(context, DigestAlarmReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    fun cancel() { alarms.cancel(pending()); timing.next(null) }
    fun schedule(now: Long = System.currentTimeMillis()) {
        val interval = timing.mode.value != DigestMode.TimePoints
        val intervalAt = if (interval) (context.applicationContext as? ResoundApp)?.pipelineScheduler?.nextIntervalAlarmAt(now)
            ?: (now + 30 * 60_000L) else null
        val timedAt = if (!interval) DigestTime.next(timing.timePoints.value, now) else null
        val next = app.resound.scheduling.PipelineRunRules.nextAlarm(intervalAt, timedAt) ?: run { cancel(); return }
        val intent = pending()
        try {
            if (!interval && timing.canScheduleExact()) alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, intent)
            else alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, intent)
        } catch (_: SecurityException) {
            // 检查后权限被收回也保留普通闹钟兜底。
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, intent)
        }
        timing.next(if (interval) null else next)
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
        val application = context.applicationContext as ResoundApp
        val pending = goAsync()
        val handoff = context.getSystemService(android.os.PowerManager::class.java)
            .newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "resound:pipeline-handoff")
        runCatching { handoff.acquire(10_000L) }
        // 广播只做交接；不能让 goAsync 等待可能持续数分钟的网络流水线。
        application.scope.launch {
            try {
                val source = if (app.resound.AppGraph.digestTiming.mode.value == DigestMode.TimePoints) "到点闹钟" else "闹钟"
                application.rescheduleDigest() // 先留下一次机会，进程中断也不会断链。
                application.scope.launch {
                    try { application.pipelineScheduler.offer(source, inProcess = true) }
                    catch (e: kotlinx.coroutines.CancellationException) { throw e }
                    catch (e: Exception) {
                        // offer 已记录本轮；诊断存储不可写时保留系统日志，下次闹钟仍接续。
                        android.util.Log.e("DigestAlarm", "流水线未完成：${e.javaClass.simpleName}")
                    }
                    finally {
                        if (handoff.isHeld) handoff.release()
                        application.rescheduleDigest()
                    }
                }
            } catch (e: Exception) {
                if (handoff.isHeld) handoff.release()
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                    application.pipelineDiagnostics.failure("闹钟", "交接失败：${e.javaClass.simpleName}")
                }
                if (e is kotlinx.coroutines.CancellationException) throw e
            } finally { pending.finish() }
        }
    }
}
