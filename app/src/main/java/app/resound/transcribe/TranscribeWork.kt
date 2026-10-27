package app.resound.transcribe

import android.os.SystemClock
import android.content.Context
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.work.*
import app.resound.PipelineProgress
import app.resound.PipelineProgress.Stage
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
            // 提前转写（录完就转）只做转写和认人，几十秒内结束；整理留给定时的完整一轮
            val early = inputData.getBoolean(EARLY, false)
            // 转写、声纹、整理串在一个任务里，普通后台任务最多 10 分钟；一直有人说话（培训、吃饭）时积压多，
            // 会被系统中途停掉、等下一轮，看起来像不转写。申请成长时间任务就没有这个上限；系统不允许时照旧跑。
            if (!early) runCatching { setForeground(getForegroundInfo()) }
            val app = applicationContext as ResoundApp
            val began = SystemClock.elapsedRealtime()
            var mark = began
            fun lap(): Long = SystemClock.elapsedRealtime().let { now -> (now - mark).also { mark = now } }
            val pendingBefore = app.database.transcribe().pendingMillis()
            PipelineProgress.stage(Stage.Transcribe)
            val transcribeRetry = app.transcribeRunner.run()
            val transcribeMs = lap()
            PipelineProgress.stage(Stage.Voiceprint)
            val voiceprintRetry = app.voiceprintRepository.run(PipelineProgress::progress)
            app.voiceprintRepository.cleanupAudio()
            val voiceprintMs = lap()
            var digestRetry = false
            if (!early) {
                PipelineProgress.stage(Stage.Digest)
                digestRetry = app.voiceprintRepository.withStableResults { app.digestRunner.run() }
                app.voiceprintRepository.cleanupAudio()
            }
            PipelineTimes.record(applicationContext, "${System.currentTimeMillis()} ${if (early) "提前" else "完整"} 待转 ${pendingBefore / 1000}s · " +
                "转写 ${transcribeMs / 1000}s · 声纹 ${voiceprintMs / 1000}s" + (if (early) "" else " · 整理 ${lap() / 1000}s"))
            return if (transcribeRetry || voiceprintRetry || digestRetry) Result.retry() else Result.success()
        } finally { PipelineProgress.finish(); pipeline.unlock() }
    }
    companion object {
        private val pipeline = Mutex()
        const val EARLY = "early"
    }
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
        PipelineProgress.queued(waitingReason() ?: "马上开始…")
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
    /** 点了马上跑但条件不满足时，给人看的原因；满足返回 null */
    private fun waitingReason(): String? {
        if ((settings as? AndroidTranscribeSettings)?.source?.value == TranscribeSource.Local) return null
        val cm = context.getSystemService(android.net.ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return "等网络连上后开始"
        if (!caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)) return "等网络连上后开始"
        if (settings.wifiOnly.value && !caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED))
            return "已设为只在 WiFi 下转写，连上 WiFi 后开始"
        return null
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
    /** 每存好一段录音调用：本机转写马上转，云端攒满 10 分钟再转；电量低或偏热时不提前 */
    suspend fun segmentSaved(pendingMillis: Long) {
        val local = (settings as? AndroidTranscribeSettings)?.source?.value == TranscribeSource.Local
        if (!settings.ready.value || !EarlyTranscribe.deviceAllows(context, local, pendingMillis)) return
        // 正在跑时追加一轮，接上这段；还在排队时不重复排
        val running = manager.getWorkInfosForUniqueWork(EARLY_WORK).get().any { it.state == WorkInfo.State.RUNNING }
        manager.enqueueUniqueWork(EARLY_WORK, if (running) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<TranscribeWork>().setConstraints(constraints()).addTag(TAG)
                .setInputData(workDataOf(TranscribeWork.EARLY to true))
                .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.SECONDS).build())
    }
    fun observe() = manager.getWorkInfosByTagFlow(TAG)
    companion object {
        const val TAG = "transcribe"
        const val PERIODIC = "transcribe-periodic"
        const val IMMEDIATE = "transcribe-now"
        const val EARLY_WORK = "transcribe-early"
    }
}
