package app.resound.redo

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.work.*
import app.resound.AppGraph
import app.resound.MainActivity
import app.resound.R
import kotlinx.coroutines.CancellationException

class RedoWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun getForegroundInfo() = RedoNotifications.foreground(applicationContext, null)
    override suspend fun doWork(): Result = try {
        setForeground(getForegroundInfo())
        (AppGraph.redo as AndroidRedoService).drain { setForeground(RedoNotifications.foreground(applicationContext, it)) }
        Result.success()
    } catch (e: CancellationException) { throw e } catch (_: Exception) { Result.retry() }
}
class CancelRedoReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (AppGraph.isRedoReady) intent.getStringExtra(EXTRA_REDO_JOB)?.let { AppGraph.redo.cancel(it) }
    }
}
internal object RedoNotifications {
    private const val CHANNEL = "redo"
    private fun manager(context: Context) = context.getSystemService(NotificationManager::class.java).also {
        it.createNotificationChannel(NotificationChannel(CHANNEL, "重做", NotificationManager.IMPORTANCE_LOW))
    }
    private fun resultId(id: String) = 0x50000000 or (id.hashCode() and 0x0fffffff)
    fun foreground(context: Context, job: RedoJob?): ForegroundInfo {
        manager(context)
        val state = job?.state as? RedoJobState.Running
        val builder = NotificationCompat.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(state?.stage ?: "准备重做").setOngoing(true).setOnlyAlertOnce(true)
            .setProgress(100, ((state?.fraction ?: 0f) * 100).toInt(), state == null)
        if (job != null) builder.addAction(0, "取消", PendingIntent.getBroadcast(context, resultId(job.id),
            Intent(context, CancelRedoReceiver::class.java).setAction("app.resound.cancelRedo.${job.id}").putExtra(EXTRA_REDO_JOB, job.id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        return ForegroundInfo(3071, builder.build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }
    fun finished(context: Context, job: RedoJob) {
        val text = when (val state = job.state) {
            is RedoJobState.Done -> state.summary
            is RedoJobState.Failed -> state.message
            else -> return
        }
        val open = PendingIntent.getActivity(context, resultId(job.id), Intent(context, MainActivity::class.java)
            .setAction("app.resound.redoResult.${job.id}").putExtra(EXTRA_REDO_JOB, job.id)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        runCatching { manager(context).notify(resultId(job.id), NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notify).setContentTitle(if (job.state is RedoJobState.Done) "重做完成" else "重做失败")
            .setContentText(text).setContentIntent(open).setAutoCancel(true).build()) }
    }
    fun clear(context: Context, id: String) { runCatching { manager(context).cancel(resultId(id)) } }
}
