package app.resound.transcribe

import android.content.Context
import android.os.PowerManager
import android.os.BatteryManager
import androidx.work.*
import app.resound.PipelineProgress
import app.resound.capture.CaptureTuning
import app.resound.digest.*
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
    /** 唯一周期兜底仅提供执行机会，网络与各模式到期条件由流水线入口采样。 */
    fun schedule() = scheduleDigest()

    private fun scheduleFallback() {
        for (legacy in listOf(PERIODIC, DIGEST_PERIODIC, TIMED_GUARD)) manager.cancelUniqueWork(legacy)
        manager.enqueueUniquePeriodicWork(PIPELINE_PERIODIC, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<TranscribeWork>(15, TimeUnit.MINUTES)
                .setInputData(workDataOf(TranscribeWork.MODE to TranscribeWork.MODE_AUTO))
                .setInitialDelay(15, TimeUnit.MINUTES).addTag(TAG).build())
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
        scheduleFallback()
        digestTiming?.let { timing ->
            DigestAlarm(context, timing).schedule()
            if (timing.mode.value == DigestMode.TimePoints && timing.timePoints.value.isNotEmpty()) recoverTimed()
            else manager.cancelUniqueWork(TIMED_NOW)
        }
    }
    fun recoverTimed(): Operation? {
        digestTiming?.pendingTimed() ?: return null
        return manager.enqueueUniqueWork(TIMED_NOW, ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<TranscribeWork>()
                .setInputData(workDataOf(TranscribeWork.MODE to TranscribeWork.MODE_TIMED))
                .setConstraints(digestConstraints()).addTag(TAG).addTag(TIMED_TAG)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST).build())
    }
    /** 设置/系统时钟改变后重新调度；进程启动和闹钟接续不取消已触发的任务。 */
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
        const val TIMED_GUARD = "digest-timed-guard"
        const val TIMED_NOW = "digest-timed-recovery"
        const val PIPELINE_PERIODIC = "pipeline-periodic"
        const val PERIODIC = "transcribe-periodic"
        const val IMMEDIATE = "transcribe-now"
        const val EARLY_WORK = "transcribe-early"
        const val DIGEST_PERIODIC = "digest-periodic"
        const val REALTIME_FALLBACK_MINUTES = 30L
    }
}
