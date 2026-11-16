package app.resound.transcribe

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.PowerManager
import android.os.SystemClock
import app.resound.AppGraph
import app.resound.ResoundApp
import app.resound.PipelineProgress
import app.resound.digest.AndroidDigestTiming
import app.resound.digest.AndroidDigestSettings
import app.resound.digest.DigestMode
import app.resound.scheduling.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow

/** 安卓条件与资源适配；所有系统、进程内、手动和回顾机会都进入 offer。 */
internal class PipelineScheduler(private val app: ResoundApp) {
    private val store = PipelineScheduleStore(app, System.currentTimeMillis())
    private val stages = AndroidPipeline(app)
    private val pending = MutableStateFlow(0L)
    private val pendingReady = CompletableDeferred<Unit>()
    init {
        app.scope.launch {
            app.database.transcribe().observePendingMillis().collect { pending.value = it; pendingReady.complete(Unit) }
        }
    }

    fun nextIntervalAlarmAt(now: Long): Long = maxOf(now + 60_000L, PipelineDue.nextAttemptAt(
        PipelineDue.Mode.IntervalDigest, (AppGraph.digestSettings as AndroidDigestSettings).intervalMinutes.value,
        store.history(PipelineDue.Mode.IntervalDigest)))

    fun trigger(source: String) = app.scope.launch {
        try { offer(source, inProcess = true) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            // offer 已记录本轮；偏好存储本身失败时，仍保留一条系统日志。
            android.util.Log.e("PipelineScheduler", "流水线未完成：${e.javaClass.simpleName}")
        }
    }

    suspend fun offer(source: String, mode: String = PipelineModes.AUTO, periodic: Boolean = false,
                      manual: Boolean = false, inProcess: Boolean = false,
                      round: PipelineRound = app.pipelineDiagnostics.round(source, mode),
                      prepare: suspend () -> String? = { null }): PipelineOffer {
        val request = PipelineRequest(mode, periodic, manual)
        var entered = false
        var lockStart: Long? = null
        try {
            app.pipelineDiagnostics.refresh()
            if (mode == PipelineModes.REALTIME &&
                (AppGraph.transcribeSettings as? AndroidTranscribeSettings)?.realtime?.value != true) return PipelineOffer.Skipped
            if (mode != PipelineModes.REVIEW) pendingReady.await()
            if (periodic) app.pipelineDiagnostics.systemPeriodic()
            lockStart = SystemClock.elapsedRealtime()
            return gate.offer(request, {
                if (source in setOf("亮屏", "接电") && app.transcribeRunner.localResume?.readyToResume() == true)
                    PipelineDue.Decision.Run(PipelineDue.Mode.Transcribe, System.currentTimeMillis())
                else decision(mode)
            }) { due ->
                entered = true
                round.lockWaitMillis = SystemClock.elapsedRealtime() - requireNotNull(lockStart)
                round.mode = when (due?.mode) {
                    PipelineDue.Mode.IntervalDigest -> PipelineModes.DIGEST
                    PipelineDue.Mode.TimedDigest -> PipelineModes.TIMED
                    PipelineDue.Mode.Transcribe, PipelineDue.Mode.RealtimeFallback -> PipelineModes.TRANSCRIBE
                    null -> mode
                }
                round.dueAt = due?.dueAt ?: round.at
                var wake: PowerManager.WakeLock? = null
                var failure: Throwable? = null
                try {
                    if (inProcess) wake = app.getSystemService(PowerManager::class.java)
                        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "resound:pipeline")
                    wake?.acquire(30 * 60_000L)
                    suspend fun execute(): PipelineResult {
                        prepare()?.let(round::note)
                        val before = due?.let { store.history(it.mode) }
                        val started = System.currentTimeMillis()
                        if (due != null) store.save(due.mode, PipelineRunRules.started(requireNotNull(before), started))
                        app.pipelineDiagnostics.record(round)
                        val result = stages.run(round.mode, manual,
                            timing = { name, millis -> round.stages[name] = millis },
                            skipped = { round.note("跳过转写：$it") })
                        currentCoroutineContext().ensureActive()
                        if (due != null) {
                            store.save(due.mode, PipelineRunRules.completed(requireNotNull(before), started, System.currentTimeMillis(), result))
                            if (PipelineRunRules.consumeTicket(result)) due.ticket?.let {
                                (AppGraph.digestTiming as AndroidDigestTiming).completeTimed(it)
                            }
                        } else if (mode == PipelineModes.FULL) {
                            val now = System.currentTimeMillis()
                            for (historyMode in PipelineDue.Mode.entries)
                                store.save(historyMode, PipelineRunRules.manual(store.history(historyMode), historyMode, now, result))
                        }
                        round.result = when { result.blocked -> "需用户处理（按间隔再试）"; result.retry -> "可重试失败"; else -> "成功" }
                        round.note(result.reason)
                        return result
                    }
                    if (inProcess) withTimeout(30 * 60_000L) { execute() } else execute()
                } catch (e: CancellationException) {
                    failure = e; round.result = "取消"; round.note("未消耗完成凭据"); throw e
                } catch (e: Exception) {
                    failure = e; round.result = "失败"; round.note(e.javaClass.simpleName); throw e
                } finally {
                    try {
                        withContext(NonCancellable) { app.pipelineDiagnostics.record(round) }
                    } catch (e: Exception) {
                        if (failure != null) failure.addSuppressed(e) else throw e
                    } finally {
                        PipelineProgress.finish()
                        if (wake?.isHeld == true) wake.release()
                    }
                }
            }
        } catch (e: Exception) {
            // 快照/锁前异常也要留下原因；执行中的异常已由该轮 finally 记录。
            if (!entered) withContext(NonCancellable) {
                lockStart?.let { round.lockWaitMillis = SystemClock.elapsedRealtime() - it }
                round.result = if (e is CancellationException) "取消" else "未开始"
                round.note(e.javaClass.simpleName)
                try { app.pipelineDiagnostics.record(round) }
                catch (recordError: Exception) { e.addSuppressed(recordError) }
            }
            throw e
        }
    }

    private fun decision(requested: String? = null): PipelineDue.Decision {
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
    companion object {
        internal val gate = PipelineGate()
    }
}
