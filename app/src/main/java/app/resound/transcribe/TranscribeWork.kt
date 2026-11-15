package app.resound.transcribe

import android.content.Context
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.work.*
import app.resound.ResoundApp
import app.resound.scheduling.PipelineModes
import app.resound.scheduling.PipelineRequest
import kotlinx.coroutines.CancellationException

class TranscribeWork(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    // Android 10/11 的加急任务由 WorkManager 以前台服务实现。
    override suspend fun getForegroundInfo(): ForegroundInfo {
        applicationContext.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("transcribe", "录音转写", NotificationManager.IMPORTANCE_LOW))
        val notification = NotificationCompat.Builder(applicationContext, "transcribe")
            .setSmallIcon(app.resound.R.drawable.ic_notify).setContentTitle("正在转写录音")
            .setOngoing(true).build()
        return ForegroundInfo(3003, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }
    override suspend fun doWork(): Result {
        val app = applicationContext as ResoundApp
        val request = request(inputData, tags)
        val mode = request.mode
        val periodic = request.periodic
        val source = when {
            periodic -> "系统周期"
            mode == MODE_TIMED -> "系统补漏"
            mode == MODE_REALTIME -> "实时转写"
            else -> "手动"
        }
        val result = app.pipelineScheduler.offer(source, mode, periodic,
            manual = request.manual,
            prepare = {
                if (mode == MODE_REALTIME) {
                    TranscribePowerDiagnostics.execution(app, inputData.getBoolean(BATCHED, false))
                    BackgroundTranscribeDiagnostics.delayed(applicationContext, inputData)
                    null
                } else {
                    try { setForeground(getForegroundInfo()); null }
                    catch (e: CancellationException) { throw e }
                    catch (e: Exception) { "前台通知未获准：${e.javaClass.simpleName}" }
                }
            })
        return if (result.retry) Result.retry() else Result.success()
    }

    companion object {
        internal fun request(data: Data, tags: Set<String>): PipelineRequest {
            val mode = data.getString(MODE) ?: MODE_FULL
            val periodic = isPeriodic(mode, data.getBoolean(PERIODIC_TRIGGER, false) ||
                data.getBoolean(LEGACY_PERIODIC_TRIGGER, false))
            return PipelineRequest(mode, periodic, mode == MODE_FULL && MANUAL_DIGEST in tags)
        }
        internal const val PERIODIC_TRIGGER = "pipeline-periodic"
        // 已排队的旧 Worker 输入仍须识别；新任务不再写此键。
        internal const val LEGACY_PERIODIC_TRIGGER = "periodic188"
        internal fun busyResult(mode: String, guard: Boolean): Result =
            if (isPeriodic(mode, guard)) Result.success() else Result.retry()
        internal fun isPeriodic(mode: String, guard: Boolean) = PipelineModes.periodic(mode, guard)
        const val MODE = "mode"
        const val MODE_REALTIME = PipelineModes.REALTIME
        internal const val BATCHED = "earlyBatched"
        const val MODE_TRANSCRIBE = PipelineModes.TRANSCRIBE
        const val MODE_DIGEST = PipelineModes.DIGEST
        const val MODE_FULL = PipelineModes.FULL
        const val MANUAL_DIGEST = "digest-manual"
        const val MODE_TIMED = PipelineModes.TIMED
        internal const val MODE_AUTO = PipelineModes.AUTO
    }
}
