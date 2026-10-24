package app.resound.push

import android.content.Context
import android.content.SharedPreferences
import androidx.work.*
import app.resound.AppGraph
import app.resound.digest.AppointmentReminders
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/** 本地通知独立于网络订阅；每次执行前重读当前状态，旧任务不能发送已删/已完成/旧时间的条目。 */
internal class PhoneReminders(
    private val prefs: SharedPreferences,
    private val source: suspend () -> PushSnapshot,
    private val settings: () -> AppointmentReminders,
    private val notifier: Notifier,
    private val schedule: (Long?, Boolean) -> Unit,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val gate = Mutex()
    suspend fun refresh(send: Boolean = false): Boolean = gate.withLock {
        val sent = JSONObject(prefs.getString("sent", "{}")!!)
        val candidates = SubscriptionReminders.candidates(source(), PushSubscription.PhoneNotice, sent, settings(), ZoneId.systemDefault(), now())
        var failed = false
        if (send) for (reminder in candidates.filter { it.at <= now() }) {
            try {
                notifier.send("回响 · 待办提醒", reminder.text, "resound://today?open=todos&reminder=${reminder.key}", PushTopic.TodoReminder)
                reminder.keys.forEach { sent.put(it, now()) }
                check(prefs.edit().putString("sent", sent.toString()).commit())
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { failed = true; break }
        }
        val next = candidates.filter { r -> r.keys.any { !sent.has(it) } }.minOfOrNull { it.at }
        if (!failed && (next != null || !send)) schedule(next, send)
        failed
    }
}

class PhoneReminderWork(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val runtime = (AppGraph.push as? AndroidPushChannels)?.phoneReminders ?: return Result.retry()
        return if (runtime.refresh(send = true)) Result.retry() else Result.success()
    }
}
internal fun schedulePhoneReminder(context: Context, at: Long?, continuation: Boolean) {
    val work = WorkManager.getInstance(context)
    if (at == null) { work.cancelUniqueWork("phone-reminders"); return }
    val request = OneTimeWorkRequestBuilder<PhoneReminderWork>()
        .setInitialDelay((at - System.currentTimeMillis()).coerceAtLeast(0), TimeUnit.MILLISECONDS)
        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build()
    // APPEND_OR_REPLACE 避免在当前 Worker 内重排时取消自己；旧任务执行时也会重新检查数据库。
    work.enqueueUniqueWork("phone-reminders", if (continuation) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.REPLACE, request)
}
