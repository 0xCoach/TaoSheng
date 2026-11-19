package app.resound.digest

import android.content.Context
import app.resound.AppGraph
import app.resound.auth.Capability
import app.resound.capture.CaptureState
import app.resound.data.CaptureDatabase
import app.resound.digest.QuietDigestRules.State
import app.resound.transcribe.TranscribeScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 安静自动整理：录音服务健康聆听中、距最近人声片段结束超过阈值 → 触发一次整理。
 * 与 Interval / TimePoints 并存；整理本身仍走 runDigestNow 的快照/水位/幂等管线，
 * 同一批内容不会因触发来源不同被重复整理（DigestRunner 的 pendingDigests/inflight 保证）。
 * 评估每 60s 一次，不持额外 WakeLock：进程存活时才有意义——服务被杀则 listening=false。
 * 所有状态量（lastSpeechEndAt 在数据库、lastQuietDigestAt 在 digest-state prefs）均持久化，
 * 进程重启后按持久化时间恢复，不靠调度精度冒充准时。
 */
class QuietDigestWatcher(
    context: Context,
    private val scope: CoroutineScope,
    private val db: CaptureDatabase,
    private val scheduler: TranscribeScheduler,
    private val digestRunner: DigestRunner,
) {
    private val settings = context.getSharedPreferences("digest", Context.MODE_PRIVATE)
    private val state = context.getSharedPreferences("digest-state", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(State.Disabled)
    /** 设置页展示：当前等待原因/最近触发结果。 */
    val uiState = _state.asStateFlow()
    @Volatile private var job: kotlinx.coroutines.Job? = null

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            while (true) {
                tick()
                delay(60_000L)
            }
        }
    }

    fun stop() { job?.cancel(); job = null; _state.value = State.Disabled }

    internal suspend fun tick() {
        val s = QuietDigestRules.evaluate(collect())
        _state.value = s
        if (s != State.Trigger) return
        // 先记录再触发：进程在整理中途被杀时，下次评估仍在冷却内，不会重复发起同一批。
        state.edit().putLong("lastQuietDigestAt", System.currentTimeMillis()).apply()
        scheduler.runDigestNow()
    }

    private suspend fun collect(): QuietDigestRules.Input {
        val cs = AppGraph.capture?.status?.value
        val captureState = cs?.state ?: CaptureState.Stopped
        return QuietDigestRules.Input(
            enabled = settings.getBoolean("quietDigestEnabled", false),
            thresholdMs = settings.getInt("quietThresholdMin", 20).coerceIn(5, 240) * 60_000L,
            cooldownMs = settings.getInt("quietCooldownMin", 60).coerceIn(15, 720) * 60_000L,
            now = System.currentTimeMillis(),
            lastSpeechEndAt = db.capture().latestSpeechEndAt() ?: 0L,
            listening = captureState == CaptureState.Listening || captureState == CaptureState.Recording,
            speechActive = captureState == CaptureState.Recording,
            paused = captureState == CaptureState.Paused,
            privacyPaused = captureState == CaptureState.Paused && cs?.pausedUntil != null,
            interrupted = captureState == CaptureState.MicBusy || captureState == CaptureState.NoPermission,
            hasUndigested = db.memory().undigestedLines().isNotEmpty() || db.memory().pendingDigests().isNotEmpty(),
            transcribeBacklog = db.transcribe().pendingMillis() > 0,
            digestRunning = digestRunner.running.value,
            lastQuietDigestAt = state.getLong("lastQuietDigestAt", 0L),
            authAllowed = AppGraph.auth?.allowed(Capability.AiAnalysis) ?: true,
            aiReady = digestRunner.ready(),
        )
    }
}
