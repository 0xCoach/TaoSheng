package app.resound.transcribe

import android.content.Context
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.work.*
import app.resound.ResoundApp
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex

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
        // 周期任务与立即任务不同 unique name，也必须整条流水线互斥。
        if (!pipeline.tryLock()) return Result.retry()
        try {
            val app = applicationContext as ResoundApp
            val transcribeRetry = app.transcribeRunner.run()
            val voiceprintRetry = app.voiceprintRepository.run()
            app.voiceprintRepository.cleanupAudio()
            val digestRetry = app.voiceprintRepository.withStableResults { app.digestRunner.run() }
            app.voiceprintRepository.cleanupAudio()
            return if (transcribeRetry || voiceprintRetry || digestRetry) Result.retry() else Result.success()
        } finally { pipeline.unlock() }
    }
    companion object { private val pipeline = Mutex() }
}
class TranscribeScheduler(private val context: Context, private val settings: TranscribeSettings) {
    private val manager get() = WorkManager.getInstance(context)
    private fun constraints() = Constraints.Builder().setRequiredNetworkType(
        if ((settings as? AndroidTranscribeSettings)?.source?.value == TranscribeSource.Local) NetworkType.NOT_REQUIRED
        else if (settings.wifiOnly.value) NetworkType.UNMETERED else NetworkType.CONNECTED).build()
    fun schedule() {
        val minutes = settings.intervalMinutes.value.toLong()
        manager.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<TranscribeWork>(minutes, TimeUnit.MINUTES)
                .setInitialDelay(minutes, TimeUnit.MINUTES).setConstraints(constraints()).addTag(TAG)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES).build())
    }
    /**
     * 用户主动触发：旧的立即任务如果只是在等退避重试，就替换掉马上跑（否则点了没反应）；
     * 如果正在执行（上传中），追加一次后续运行，覆盖当前快照之后新增的导入段。查询 WorkManager 状态放到后台线程。
     */
    fun runNow() {
        Thread {
            val running = runCatching {
                manager.getWorkInfosForUniqueWork(IMMEDIATE).get().any { it.state == WorkInfo.State.RUNNING }
            }.getOrDefault(false)
            manager.enqueueUniqueWork(IMMEDIATE, if (running) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<TranscribeWork>().setConstraints(constraints()).addTag(TAG)
                    .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES).build())
        }.start()
    }
    fun settingsChanged(scope: CoroutineScope) {
        schedule()
        scope.launch(Dispatchers.IO) {
            // 已排队的立即任务同步新网络约束，不取消正在上传的片段。
            manager.getWorkInfosForUniqueWork(IMMEDIATE).get().filter { it.state == WorkInfo.State.ENQUEUED }.forEach {
                manager.updateWork(OneTimeWorkRequestBuilder<TranscribeWork>().setId(it.id)
                    .setConstraints(constraints()).addTag(TAG)
                    .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES).build()).get()
            }
        }
    }
    fun observe() = manager.getWorkInfosByTagFlow(TAG)
    companion object {
        const val TAG = "transcribe"
        const val PERIODIC = "transcribe-periodic"
        const val IMMEDIATE = "transcribe-now"
    }
}
