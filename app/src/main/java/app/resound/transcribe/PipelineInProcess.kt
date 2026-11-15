package app.resound.transcribe

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.PowerManager
import app.resound.AppGraph
import app.resound.ResoundApp
import app.resound.PipelineProgress
import app.resound.digest.AndroidDigestTiming
import app.resound.digest.AndroidDigestSettings
import app.resound.digest.DigestMode
import app.resound.scheduling.PipelineDue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/** 生命周期归 Application；所有入口最终在 TranscribeWork 的同一把锁内执行。 */
internal class PipelineInProcess(private val app: ResoundApp) {
    private val store = PipelineScheduleStore(app, System.currentTimeMillis())
    private val pending = MutableStateFlow(0L)
    private val pendingReady = kotlinx.coroutines.CompletableDeferred<Unit>()
    init { app.scope.launch { app.database.transcribe().observePendingMillis().collect { pending.value = it; pendingReady.complete(Unit) } } }

    fun nextIntervalAlarmAt(now: Long): Long = maxOf(now + 60_000L, PipelineDue.nextAttemptAt(
        PipelineDue.Mode.IntervalDigest, (AppGraph.digestSettings as AndroidDigestSettings).intervalMinutes.value,
        store.history(PipelineDue.Mode.IntervalDigest)))

    fun trigger(source: String) = app.scope.launch {
        try { app.pipelineDiagnostics.refresh(); runIfDue(source) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { PipelineTimes.record(app, "自动流水线 $source 未完成：${e.javaClass.simpleName}") }
    }
    suspend fun awaitSnapshot() { pendingReady.await() }
    fun decision(requested: String? = null): PipelineDue.Decision {
        val now = System.currentTimeMillis()
        val timing = AppGraph.digestTiming as AndroidDigestTiming
        val settings = AppGraph.transcribeSettings as AndroidTranscribeSettings
        val caps = app.getSystemService(ConnectivityManager::class.java).let { it.getNetworkCapabilities(it.activeNetwork) }
        val online = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        val unmetered = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == true
        val digest = if (timing.mode.value == DigestMode.TimePoints) PipelineDue.Mode.TimedDigest else PipelineDue.Mode.IntervalDigest
        val transcription = if (settings.realtime.value) PipelineDue.Mode.RealtimeFallback else PipelineDue.Mode.Transcribe
        val modes = when (requested) {
            TranscribeWork.MODE_DIGEST -> if (digest == PipelineDue.Mode.IntervalDigest) listOf(digest) else emptyList()
            TranscribeWork.MODE_TIMED -> if (digest == PipelineDue.Mode.TimedDigest) listOf(digest) else emptyList()
            TranscribeWork.MODE_TRANSCRIBE -> listOf(transcription)
            else -> listOf(digest, transcription)
        }
        var waiting: PipelineDue.Decision = PipelineDue.Decision.NotDue
        for (mode in modes) {
            val onlyTranscribe = mode == transcription
            if (onlyTranscribe && !settings.ready.value) continue
            val local = onlyTranscribe && settings.source.value == TranscribeSource.Local
            val result = PipelineDue.decide(PipelineDue.Input(mode, now,
                if (onlyTranscribe) settings.intervalMinutes.value else (AppGraph.digestSettings as AndroidDigestSettings).intervalMinutes.value,
                store.history(mode), pending.value, false, online || local, settings.wifiOnly.value && !local, unmetered,
                if (mode == PipelineDue.Mode.TimedDigest) timing.pendingTimed(now) else null))
            if (result is PipelineDue.Decision.Run) return result
            if (result is PipelineDue.Decision.Wait) waiting = result
        }
        return waiting
    }
    suspend fun runIfDue(source: String) {
        // 快速判断不访问数据库；拿锁后仍由 gate 再读一次状态。
        if (decision() !is PipelineDue.Decision.Run) return
        TranscribeWork.gate.runIfDue({ decision() }) { due ->
            val wake = app.getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "resound:pipeline188")
            try {
                wake.acquire(30 * 60_000L)
                withTimeout(30 * 60_000L) { execute(due, source) }
            } finally {
                PipelineProgress.finish()
                if (wake.isHeld) wake.release()
            }
        }
    }
    suspend fun execute(due: PipelineDue.Decision.Run, source: String): Boolean {
        val mode = when (due.mode) {
            PipelineDue.Mode.IntervalDigest -> TranscribeWork.MODE_DIGEST
            PipelineDue.Mode.TimedDigest -> TranscribeWork.MODE_TIMED
            else -> TranscribeWork.MODE_TRANSCRIBE
        }
        val before = store.history(due.mode)
        val started = System.currentTimeMillis()
        // 未完成尝试也至少等待五分钟，不能进程重启后立刻重打请求。
        store.save(due.mode, before.copy(lastAttempt = started, failure = PipelineDue.Failure.Retryable, failures = before.failures + 1))
        var result = "中断"
        try {
            app.pipelineDiagnostics.record(started, source, mode, due.dueAt, "执行中", 0)
            var digestBlocked = false
            val retry = TranscribeWork.executePipeline(app, mode, reportDigestBlocked = { digestBlocked = it })
            val blocked = mode != TranscribeWork.MODE_TRANSCRIBE && digestBlocked
            val failure = when { blocked -> PipelineDue.Failure.Blocked; retry -> PipelineDue.Failure.Retryable; else -> PipelineDue.Failure.None }
            store.save(due.mode, PipelineDue.History(started, if (failure == PipelineDue.Failure.None) System.currentTimeMillis() else before.lastSuccess,
                failure, if (failure == PipelineDue.Failure.None) 0 else before.failures + 1))
            due.ticket?.takeIf { !retry || blocked }?.let { (AppGraph.digestTiming as AndroidDigestTiming).completeTimed(it) }
            result = when (failure) { PipelineDue.Failure.None -> "成功"; PipelineDue.Failure.Blocked -> "需用户处理（按间隔再试）"; else -> "可重试失败" }
            return retry && !blocked
        } finally { kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
            app.pipelineDiagnostics.record(started, source, mode, due.dueAt, result, System.currentTimeMillis() - started)
        } }
    }
    /** 用户显式整理成功后解除错误锁定；自动入口不能清除不可重试错误。 */
    fun manualCompleted(retry: Boolean, digestBlocked: Boolean) {
        val now = System.currentTimeMillis()
        for (mode in PipelineDue.Mode.entries) {
            val blocked = mode in listOf(PipelineDue.Mode.IntervalDigest, PipelineDue.Mode.TimedDigest) && digestBlocked
            val failure = if (blocked) PipelineDue.Failure.Blocked else if (retry) PipelineDue.Failure.Retryable else PipelineDue.Failure.None
            store.save(mode, PipelineDue.History(now, if (failure == PipelineDue.Failure.None) now else store.history(mode).lastSuccess, failure, if (retry) 1 else 0))
        }
    }
}
