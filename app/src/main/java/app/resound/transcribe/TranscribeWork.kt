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
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.CancellationException
import app.resound.digest.*

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
        val mode = inputData.getString(MODE) ?: MODE_FULL
        fun expired(): Boolean {
            if (mode != MODE_TIMED) return false
            val target = inputData.getLong(DigestAlarm.TARGET, 0)
            val expires = inputData.getLong(DigestAlarm.EXPIRES, 0)
            return !app.timedDigestValid(target) || DigestTime.missed(System.currentTimeMillis(), target, expires)
        }
        if (expired()) { missed(); return Result.success() }
        // 周期、手动、到点与回顾前整理共用同一把锁。
        if (!pipeline.tryLock()) return Result.retry()
        try {
            if (expired()) { missed(); return Result.success() }
            if (mode != MODE_REALTIME) {
                try { setForeground(getForegroundInfo()) }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { /* 系统拒绝前台时仍按普通任务执行。 */ }
            }
            val retry = executePipeline(app, mode, manual = mode == MODE_FULL && MANUAL_DIGEST in tags)
            // 到点任务本轮失败不补跑；普通转写/间隔整理保持原来的重试行为。
            return if (retry && mode != MODE_TIMED) Result.retry() else Result.success()
        } finally { PipelineProgress.finish(); pipeline.unlock() }
    }
    private fun missed() = PipelineTimes.record(applicationContext, "${System.currentTimeMillis()} 到点整理 已错过，跳过")

    companion object {
        private val pipeline = Mutex()
        const val MODE = "mode"
        const val MODE_REALTIME = "realtime"
        const val MODE_TRANSCRIBE = "transcribe"
        const val MODE_DIGEST = "digest"
        const val MODE_FULL = "full"
        const val MANUAL_DIGEST = "digest-manual"
        const val MODE_TIMED = "timed"
        private const val MODE_REVIEW = "review"
        private val MODE_NAMES = mapOf(MODE_REALTIME to "实时转写", MODE_TRANSCRIBE to "定时转写", MODE_DIGEST to "定时整理",
            MODE_FULL to "现在整理", MODE_TIMED to "到点整理", MODE_REVIEW to "回顾前整理")

        /** 调用方的 10 分钟超时包含排队等锁，拿到锁后才操作全局进度。 */
        internal suspend fun beforeReview(app: ResoundApp) = pipeline.withLock {
            try { executePipeline(app, MODE_REVIEW) }
            finally { PipelineProgress.finish() }
        }

        private suspend fun executePipeline(app: ResoundApp, mode: String, manual: Boolean = false): Boolean {
            val transcribe = mode != MODE_DIGEST
            val digest = mode in setOf(MODE_FULL, MODE_DIGEST, MODE_TIMED, MODE_REVIEW)
            var mark = SystemClock.elapsedRealtime()
            fun lap(): Long = SystemClock.elapsedRealtime().let { now -> (now - mark).also { mark = now } }
            val pendingBefore = app.database.transcribe().pendingMillis()
            val parts = mutableListOf<String>()
            var transcribeRetry = false
            var voiceprintRetry = false
            if (transcribe) {
                PipelineProgress.stage(Stage.Transcribe)
                // 到点/回顾前与“现在整理”一样，把此前失败、正在退避的片段也补转一次。
                if (mode == MODE_TIMED || mode == MODE_REVIEW) app.database.transcribe().retryFailed()
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
                digestRetry = app.voiceprintRepository.withStableResults {
                    app.digestRunner.run(force = manual || mode == MODE_TIMED || mode == MODE_REVIEW, manual = manual)
                }
                app.voiceprintRepository.cleanupAudio()
                parts += "整理 ${formatElapsed(lap())}"
            }
            // 回顾前整理由外层统一记一次诊断（含等锁、超时和失败）。
            if (mode != MODE_REVIEW) PipelineTimes.record(app, "${System.currentTimeMillis()} ${MODE_NAMES[mode] ?: mode} 待转录音 ${formatElapsed(pendingBefore)} · " + parts.joinToString(" · "))
            return transcribeRetry || voiceprintRetry || digestRetry
        }
    }
}
class TranscribeScheduler(private val context: Context, private val settings: TranscribeSettings) {
    private val manager get() = WorkManager.getInstance(context)
    /** 整理要调大模型，只要求联网（与转写的 WiFi 设置无关） */
    private fun digestConstraints() = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
    /** 多久整理一次；由 ResoundApp 在整理设置就绪后装配 */
    var digestMinutes: () -> Int = { 30 }
    var digestTiming: AndroidDigestTiming? = null
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
    fun runNow() = enqueueNow(manualDigest = false)
    fun runDigestNow() = enqueueNow(manualDigest = true)
    private fun enqueueNow(manualDigest: Boolean) {
        PipelineProgress.queued(waitingReason() ?: "马上开始…")
        Thread {
            val running = runCatching {
                manager.getWorkInfosForUniqueWork(IMMEDIATE).get().any { it.state == WorkInfo.State.RUNNING }
            }.getOrDefault(false)
            manager.enqueueUniqueWork(IMMEDIATE, if (running) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<TranscribeWork>().setConstraints(constraints()).addTag(TAG)
                    .apply { if (manualDigest) addTag(TranscribeWork.MANUAL_DIGEST) }
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
                    .apply { if (TranscribeWork.MANUAL_DIGEST in it.tags) addTag(TranscribeWork.MANUAL_DIGEST) }
                    .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES).build()).get()
            }
        }
    }
    fun scheduleDigest() {
        digestTiming?.let { timing ->
            val alarm = DigestAlarm(context, timing)
            if (timing.mode.value == DigestMode.TimePoints) {
                manager.cancelUniqueWork(DIGEST_PERIODIC)
                alarm.schedule()
                return
            }
            alarm.cancel()
        }
        val minutes = digestMinutes().toLong()
        manager.enqueueUniquePeriodicWork(DIGEST_PERIODIC, ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<TranscribeWork>(minutes, TimeUnit.MINUTES)
                .setInputData(workDataOf(TranscribeWork.MODE to TranscribeWork.MODE_DIGEST))
                .setInitialDelay(minutes, TimeUnit.MINUTES).setConstraints(digestConstraints()).addTag(TAG).build())
    }
    fun enqueueTimed(target: Long, expires: Long): Operation? {
        if (digestTiming?.mode?.value != DigestMode.TimePoints) return null
        return manager.enqueueUniqueWork("digest-timed-$target", ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<TranscribeWork>()
                .setInputData(workDataOf(TranscribeWork.MODE to TranscribeWork.MODE_TIMED,
                    DigestAlarm.TARGET to target, DigestAlarm.EXPIRES to expires))
                .setConstraints(digestConstraints()).addTag(TAG).addTag(TIMED_TAG)
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST).build())
    }
    /** 设置/系统时钟改变后旧窗口失效；进程启动和闹钟接续不取消已触发的任务。 */
    fun timingChanged() { manager.cancelAllWorkByTag(TIMED_TAG); scheduleDigest() }
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
        const val TIMED_TAG = "digest-timed"
        const val PERIODIC = "transcribe-periodic"
        const val IMMEDIATE = "transcribe-now"
        const val EARLY_WORK = "transcribe-early"
        const val DIGEST_PERIODIC = "digest-periodic"
        const val REALTIME_FALLBACK_MINUTES = 30L
    }
}
