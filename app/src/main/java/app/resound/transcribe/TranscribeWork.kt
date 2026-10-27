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
import app.resound.formatElapsed
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
            // 转写和整理各管各的：实时/定时转写只转写和认人，定时整理只整理；“现在整理”先补转再整理
            val mode = inputData.getString(MODE) ?: MODE_FULL
            val early = mode == MODE_REALTIME
            val transcribe = mode != MODE_DIGEST
            val digest = mode == MODE_FULL || mode == MODE_DIGEST
            // 普通后台任务最多 10 分钟；积压多或整理慢时会被系统中途停掉。申请成长时间任务就没有这个上限；
            // 系统不允许时照旧跑。实时转写一次只转刚录的一两段，不申请，免得通知栏频繁闪
            if (!early) runCatching { setForeground(getForegroundInfo()) }
            val app = applicationContext as ResoundApp
            val began = SystemClock.elapsedRealtime()
            var mark = began
            fun lap(): Long = SystemClock.elapsedRealtime().let { now -> (now - mark).also { mark = now } }
            val pendingBefore = app.database.transcribe().pendingMillis()
            var transcribeRetry = false
            var voiceprintRetry = false
            val parts = mutableListOf<String>()
            if (transcribe) {
                PipelineProgress.stage(Stage.Transcribe)
                transcribeRetry = app.transcribeRunner.run()
                parts += "转写 ${formatElapsed(lap())}"
                PipelineProgress.stage(Stage.Voiceprint)
                voiceprintRetry = app.voiceprintRepository.run(PipelineProgress::progress)
                app.voiceprintRepository.cleanupAudio()
                parts += "声纹 ${formatElapsed(lap())}"
            }
            var digestRetry = false
            if (digest) {
                PipelineProgress.stage(Stage.Digest)
                digestRetry = app.voiceprintRepository.withStableResults { app.digestRunner.run() }
                app.voiceprintRepository.cleanupAudio()
                parts += "整理 ${formatElapsed(lap())}"
            }
            PipelineTimes.record(applicationContext, "${System.currentTimeMillis()} ${MODE_NAMES[mode] ?: mode} 待转录音 ${formatElapsed(pendingBefore)} · " +
                parts.joinToString(" · "))
            return if (transcribeRetry || voiceprintRetry || digestRetry) Result.retry() else Result.success()
        } finally { PipelineProgress.finish(); pipeline.unlock() }
    }
    companion object {
        private val pipeline = Mutex()
        const val MODE = "mode"
        const val MODE_REALTIME = "realtime"
        const val MODE_TRANSCRIBE = "transcribe"
        const val MODE_DIGEST = "digest"
        const val MODE_FULL = "full"
        private val MODE_NAMES = mapOf(MODE_REALTIME to "实时转写", MODE_TRANSCRIBE to "定时转写", MODE_DIGEST to "定时整理", MODE_FULL to "现在整理")
    }
}
class TranscribeScheduler(private val context: Context, private val settings: TranscribeSettings) {
    private val manager get() = WorkManager.getInstance(context)
    /** 整理要调大模型，只要求联网（与转写的 WiFi 设置无关） */
    private fun digestConstraints() = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
    /** 多久整理一次；由 ResoundApp 在整理设置就绪后装配 */
    var digestMinutes: () -> Int = { 30 }
    private val realtime get() = (settings as? AndroidTranscribeSettings)?.realtime?.value == true
    private fun constraints() = Constraints.Builder().setRequiredNetworkType(
        if ((settings as? AndroidTranscribeSettings)?.source?.value == TranscribeSource.Local) NetworkType.NOT_REQUIRED
        else if (settings.wifiOnly.value) NetworkType.UNMETERED else NetworkType.CONNECTED).build()
    /** 定时转写和定时整理各排各的。选了实时转写时，定时转写只做兜底：每 30 分钟补转电量低等原因攒下的录音 */
    fun schedule() {
        scheduleDigest()
        val minutes = if (realtime) REALTIME_FALLBACK_MINUTES else settings.intervalMinutes.value.toLong()
        manager.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<TranscribeWork>(minutes, TimeUnit.MINUTES)
                .setInputData(workDataOf(TranscribeWork.MODE to TranscribeWork.MODE_TRANSCRIBE))
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
    fun scheduleDigest() {
        val minutes = digestMinutes().toLong()
        manager.enqueueUniquePeriodicWork(DIGEST_PERIODIC, ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<TranscribeWork>(minutes, TimeUnit.MINUTES)
                .setInputData(workDataOf(TranscribeWork.MODE to TranscribeWork.MODE_DIGEST))
                .setInitialDelay(minutes, TimeUnit.MINUTES).setConstraints(digestConstraints()).addTag(TAG).build())
    }
    /** 每存好一段录音调用：选了实时就马上转；电量低或偏热时先攒着，交给定时转写兜底 */
    suspend fun segmentSaved(pendingMillis: Long) {
        if (!settings.ready.value || !EarlyTranscribe.deviceAllows(context, realtime, pendingMillis)) return
        // 正在跑时追加一轮，接上这段；还在排队时不重复排
        val running = manager.getWorkInfosForUniqueWork(EARLY_WORK).get().any { it.state == WorkInfo.State.RUNNING }
        manager.enqueueUniqueWork(EARLY_WORK, if (running) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<TranscribeWork>().setConstraints(constraints()).addTag(TAG)
                .setInputData(workDataOf(TranscribeWork.MODE to TranscribeWork.MODE_REALTIME))
                .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.SECONDS).build())
    }
    fun observe() = manager.getWorkInfosByTagFlow(TAG)
    companion object {
        const val TAG = "transcribe"
        const val PERIODIC = "transcribe-periodic"
        const val IMMEDIATE = "transcribe-now"
        const val EARLY_WORK = "transcribe-early"
        const val DIGEST_PERIODIC = "digest-periodic"
        const val REALTIME_FALLBACK_MINUTES = 30L
    }
}
