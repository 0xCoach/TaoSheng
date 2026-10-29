package app.resound.transcribe

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import app.resound.AppGraph
import app.resound.MainActivity
import app.resound.R
import kotlinx.coroutines.CancellationException

class AudioExportWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private val jobId get() = inputData.getString("export-id") ?: error("导出任务缺少编号")
    private val jobs get() = AppGraph.exportJobs as AndroidExportJobs
    override suspend fun getForegroundInfo(): ForegroundInfo = ExportNotifications.foreground(applicationContext,
        jobs.job(jobId) ?: ExportJob(jobId, "录音", 0, ExportJobState.Running(0f)))

    override suspend fun doWork(): Result {
        if (jobs.job(jobId)?.state !is ExportJobState.Running) {
            jobs.job(jobId)?.let { ExportNotifications.finished(applicationContext, it) }
            return Result.success()
        }
        return try {
            setForeground(getForegroundInfo())
            val success = jobs.run(jobId) { setForeground(ExportNotifications.foreground(applicationContext, it)) }
            jobs.job(jobId)?.let { ExportNotifications.finished(applicationContext, it) }
            if (success) Result.success() else Result.failure()
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) {
            // 后台启动限制可能暂时阻止前台服务；保留请求由 WorkManager 重试。
            if (runAttemptCount < 3) Result.retry() else {
                jobs.update(jobId, ExportJobState.Failed("无法启动后台导出，请打开应用后重试"))
                jobs.job(jobId)?.let { ExportNotifications.finished(applicationContext, it) }
                Result.failure()
            }
        }
    }
}

class CancelExportReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        intent.getStringExtra(EXTRA_EXPORT_JOB)?.let { id ->
            if (AppGraph.isExportJobsReady) AppGraph.exportJobs.cancel(id)
        }
    }
}

internal object ExportNotifications {
    private const val CHANNEL = "audio-export"
    private fun manager(context: Context) = context.getSystemService(NotificationManager::class.java).also {
        it.createNotificationChannel(NotificationChannel(CHANNEL, "录音导出", NotificationManager.IMPORTANCE_LOW))
    }
    private fun foregroundId(id: String) = 0x20000000 or (id.hashCode() and 0x0fffffff)
    private fun resultId(id: String) = 0x30000000 or (id.hashCode() and 0x0fffffff)
    fun foreground(context: Context, job: ExportJob): ForegroundInfo {
        manager(context)
        val percent = (((job.state as? ExportJobState.Running)?.fraction ?: 0f) * 100).toInt().coerceIn(0, 100)
        val cancel = PendingIntent.getBroadcast(context, foregroundId(job.id),
            Intent(context, CancelExportReceiver::class.java).setAction("app.resound.cancelExport.${job.id}")
                .putExtra(EXTRA_EXPORT_JOB, job.id), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_notify)
            .setContentTitle("正在导出 · ${job.title} · $percent%")
            .setProgress(100, percent, false).setOngoing(true).setOnlyAlertOnce(true)
            .addAction(0, "取消", cancel).build()
        return ForegroundInfo(foregroundId(job.id), notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }
    internal fun result(context: Context, job: ExportJob): Notification {
        manager(context)
        val open = PendingIntent.getActivity(context, resultId(job.id),
            Intent(context, MainActivity::class.java).setAction("app.resound.exportResult.${job.id}")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(EXTRA_EXPORT_JOB, job.id), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val message = (job.state as? ExportJobState.Failed)?.message ?: "点此试听、保存或分享"
        return NotificationCompat.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_notify)
            .setContentTitle("${if (job.state is ExportJobState.Done) "录音已导出" else "录音导出失败"} · ${job.title}")
            .setContentText(message).setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setContentIntent(open).setAutoCancel(true).build()
    }
    fun finished(context: Context, job: ExportJob) {
        if (job.state is ExportJobState.Done || job.state is ExportJobState.Failed) {
            // 通知权限被拒绝不应把已生成的导出改成失败。
            runCatching { manager(context).notify(resultId(job.id), result(context, job)) }
        }
    }
    fun clear(context: Context, id: String) {
        manager(context).cancel(resultId(id))
        manager(context).cancel(foregroundId(id))
    }
}
