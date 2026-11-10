package app.resound.transcribe

import android.os.SystemClock
import android.os.PowerManager
import android.os.BatteryManager
import app.resound.capture.CaptureTuning
import android.content.Context
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.work.*
import app.resound.PipelineProgress
import app.resound.PipelineProgress.Stage
import app.resound.ResoundApp
import app.resound.AppGraph
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
        if (mode == MODE_REALTIME) {
            if ((AppGraph.transcribeSettings as? AndroidTranscribeSettings)?.realtime?.value != true)
                return Result.success()
            TranscribePowerDiagnostics.execution(app, inputData.getBoolean(BATCHED, false))
            BackgroundTranscribeDiagnostics.delayed(applicationContext, inputData)
        }
        fun expired(): Boolean {
            if (mode != MODE_TIMED) return false
            val target = inputData.getLong(DigestAlarm.TARGET, 0)
            val expires = inputData.getLong(DigestAlarm.EXPIRES, 0)
            return !app.timedDigestValid(target) || DigestTime.missed(System.currentTimeMillis(), target, expires)
        }
        if (expired()) { missed(); return Result.success() }
        // 周期、手动、到点与回顾前整理共用同一把锁。
        if (!pipeline.tryLock()) {
            if (mode == MODE_REALTIME && !BackgroundTranscribeDiagnostics.foreground)
                BackgroundTranscribeDiagnostics.record(applicationContext, "realtime-pipeline-busy", "false")
            return Result.retry()
        }
        try {
            if (expired()) { missed(); return Result.success() }
            // 已有麦克风 FGS 不是文档保证的后台启动豁免；实时任务按加急/普通批次调度，不主动再起 FGS。
            if (mode != MODE_REALTIME) {
                try { setForeground(getForegroundInfo()) }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { /* 系统拒绝前台时仍按普通任务执行。 */ }
            }
            val retry = executePipeline(app, mode, manual = mode == MODE_FULL && MANUAL_DIGEST in tags)
            // 到点任务本轮失败不补跑；普通转写/间隔整理保持原来的重试行为。
            return if (retry && mode != MODE_TIMED) Result.retry() else Result.success()
        } catch (e: CancellationException) {
            if (mode == MODE_REALTIME && !BackgroundTranscribeDiagnostics.foreground)
                BackgroundTranscribeDiagnostics.record(applicationContext, "realtime-stopped", "false")
            throw e
        } finally { PipelineProgress.finish(); pipeline.unlock() }
    }
    private fun missed() = PipelineTimes.record(applicationContext, "${System.currentTimeMillis()} 到点整理 已错过，跳过")

    companion object {
        private val pipeline = Mutex()
        const val MODE = "mode"
        const val MODE_REALTIME = "realtime"
        internal const val BATCHED = "earlyBatched"
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
            var mark = SystemClock.elapsedRealtime()
            fun lap(): Long = SystemClock.elapsedRealtime().let { now -> (now - mark).also { mark = now } }
            val pendingBefore = app.database.transcribe().pendingMillis()
            val parts = mutableListOf<String>()
            var transcribeRetry = false
            var voiceprintRetry = false
            val skip = if (mode == MODE_DIGEST) {
                val settings = AppGraph.transcribeSettings
                val cm = app.getSystemService(android.net.ConnectivityManager::class.java)
                val unmetered = cm.getNetworkCapabilities(cm.activeNetwork)
                    ?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == true
                digestTranscribeSkip(
                    (settings as? AndroidTranscribeSettings)?.source?.value == TranscribeSource.Local,
                    settings.wifiOnly.value, unmetered,
                    EarlyTranscribe.deviceAllows(app, realtime = true, pendingMillis = pendingBefore), pendingBefore)
            } else null
            if (skip != null) parts += "跳过转写：$skip"
            var digestRetry = false
            runPipelineStages(mode, skip, transcribe = {
                PipelineProgress.stage(Stage.Transcribe)
                // 到点/回顾前与“现在整理”一样，把此前失败、正在退避的片段也补转一次。
                if (mode == MODE_TIMED || mode == MODE_REVIEW) app.database.transcribe().retryFailed()
                transcribeRetry = app.transcribeRunner.run()
                parts += "转写 ${formatElapsed(lap())}"
                if (app.voiceprintRepository.hasPending()) {
                    PipelineProgress.stage(Stage.Voiceprint)
                    voiceprintRetry = app.voiceprintRepository.run(PipelineProgress::progress)
                }
                app.voiceprintRepository.cleanupAudio()
                parts += "声纹 ${formatElapsed(lap())}"
            }, organize = {
                PipelineProgress.stage(Stage.Digest)
                digestRetry = app.voiceprintRepository.withStableResults {
                    app.digestRunner.run(force = manual || mode == MODE_TIMED || mode == MODE_REVIEW, manual = manual)
                }
                app.voiceprintRepository.cleanupAudio()
                parts += "整理 ${formatElapsed(lap())}"
                // 顺便查一下整理模型余额（最多每小时一次）
                if (AppGraph.isLlmBalanceReady) runCatching { AppGraph.llmBalance.refresh() }
            })
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
    /** 每存好一段录音调用；锁屏未充电攒批，低电/偏热仍交给原周期兜底。 */
    suspend fun segmentSaved(pendingMillis: Long) = earlyMutex.withLock {
        if (!settings.ready.value || !EarlyTranscribe.deviceAllows(context, realtime, pendingMillis)) return@withLock
        enqueueEarly(EarlyTranscribe.shouldBatch(
            context.getSystemService(PowerManager::class.java).isInteractive,
            context.getSystemService(BatteryManager::class.java).isCharging))
    }

    private fun enqueueEarly(batch: Boolean) {
        val infos = manager.getWorkInfosForUniqueWork(EARLY_WORK).get()
        val running = infos.any { it.state == WorkInfo.State.RUNNING }
        val waitingBatch = infos.any { BATCH_TAG in it.tags &&
            it.state in setOf(WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED) }
        // 正在跑时仍补一轮，但已有等待批次就合并进去，避免每段追加一个空批次。
        val append = running && !(batch && waitingBatch)
        manager.enqueueUniqueWork(EARLY_WORK, if (append) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.KEEP,
            earlyRequest(constraints(), batch)).result.get()
    }

    /** 由录音服务的亮屏/接电广播调用；不创建空任务，也不取消当前正在推理的一轮。 */
    suspend fun resumeWaitingBatch(action: String?) = earlyMutex.withLock {
        if (action != android.content.Intent.ACTION_SCREEN_ON && action != android.content.Intent.ACTION_POWER_CONNECTED) return@withLock
        if (!realtime || !settings.ready.value) return@withLock
        val infos = manager.getWorkInfosForUniqueWork(EARLY_WORK).get()
        val waiting = infos.filter { BATCH_TAG in it.tags &&
            it.state in setOf(WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED) }
        if (waiting.isEmpty()) return@withLock
        // 原位替换等待任务的请求参数，去掉延迟并加急。即使查询后它刚开始运行，
        // updateWork 也不会取消该轮推理；BLOCKED 任务保留父依赖，父轮结束即可接上。
        waiting.forEach { info ->
            val updated = runCatching { manager.updateWork(earlyRequest(constraints(), id = info.id)).get() }
            // 个别系统不允许原位改成加急：还在排队的就替换成立即执行（正在跑的不动，避免取消推理）
            if (updated.isFailure && info.state == WorkInfo.State.ENQUEUED)
                manager.enqueueUniqueWork(EARLY_WORK, ExistingWorkPolicy.REPLACE, earlyRequest(constraints()))
        }
    }
    fun observe() = manager.getWorkInfosByTagFlow(TAG)
    companion object {
        private val earlyMutex = Mutex()
        internal const val BATCH_TAG = "transcribe-early-batch"
        internal fun earlyRequest(constraints: Constraints, batch: Boolean = false,
                                  id: java.util.UUID? = null): OneTimeWorkRequest =
            OneTimeWorkRequestBuilder<TranscribeWork>().setConstraints(constraints).addTag(TAG)
                .setInputData(Data.Builder().putAll(BackgroundTranscribeDiagnostics.input())
                    .putBoolean(TranscribeWork.BATCHED, batch).build())
                .apply {
                    if (id != null) setId(id)
                    if (batch) {
                        addTag(BATCH_TAG)
                        setInitialDelay(CaptureTuning.BATCH_DELAY_MS, TimeUnit.MILLISECONDS)
                    } else setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                }
                .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.SECONDS).build()
        const val TAG = "transcribe"
        const val TIMED_TAG = "digest-timed"
        const val PERIODIC = "transcribe-periodic"
        const val IMMEDIATE = "transcribe-now"
        const val EARLY_WORK = "transcribe-early"
        const val DIGEST_PERIODIC = "digest-periodic"
        const val REALTIME_FALLBACK_MINUTES = 30L
    }
}

/** 间隔整理的前置转写遵守自身条件，整理仍可继续。 */
internal fun digestTranscribeSkip(local: Boolean, wifiOnly: Boolean, unmetered: Boolean,
                                  deviceAllows: Boolean, pendingMillis: Long): String? = when {
    !local && wifiOnly && !unmetered -> "只在 WiFi 下转写，当前不是不计流量的网络"
    pendingMillis <= 0 -> "没有待转写片段"
    !deviceAllows -> "电量低或手机偏热"
    else -> null
}

internal suspend fun runPipelineStages(mode: String, skip: String?,
                                       transcribe: suspend () -> Unit, organize: suspend () -> Unit) {
    if (mode != TranscribeWork.MODE_DIGEST || skip == null) transcribe()
    if (mode in setOf(TranscribeWork.MODE_FULL, TranscribeWork.MODE_DIGEST, TranscribeWork.MODE_TIMED, "review")) organize()
}
