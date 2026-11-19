package app.resound.digest

import android.content.Context
import app.resound.AppGraph
import app.resound.auth.Capability
import app.resound.capture.CaptureState
import app.resound.data.CaptureDatabase
import app.resound.digest.QuietDigestRules.Pending
import app.resound.digest.QuietDigestRules.State
import app.resound.transcribe.TranscribeScheduler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 安静自动整理：录音服务健康聆听中、距最近人声片段结束超过阈值 → 触发一次整理。
 * 与 Interval / TimePoints 并存；触发走自动管线 `runNow`（manual=false、force=false，
 * 不绕过 DigestRunner 的在途凭据/防重复付费保护；会话按原有停顿规则结算）。
 * PENDING 语义：派发前写 quietPendingAt；仅当 DigestRunner 成功收尾（lastSuccessAt 追平，
 * 失败不写该字段）才提升为冷却锚点。未消费 pending ≥15min 重试，进程被杀可恢复。
 * 评估每 60s 一次：循环体异常记 FailureNotes 不崩溃；开关关闭时立即返回不查数据库。
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
    private val capturePrefs = context.getSharedPreferences("capture", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(State.Disabled)
    /** 设置页展示：当前等待原因/最近触发结果。 */
    val uiState = _state.asStateFlow()
    @Volatile private var job: kotlinx.coroutines.Job? = null

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            while (true) {
                try { tick() }
                catch (e: CancellationException) { throw e }
                catch (t: Throwable) {
                    app.resound.diagnostics.FailureNotes.record("digest/QuietDigestWatcher.kt:tick", t)
                }
                delay(60_000L)
            }
        }
    }

    fun stop() { job?.cancel(); job = null; _state.value = State.Disabled }

    internal suspend fun tick() {
        if (!settings.getBoolean("quietDigestEnabled", false)
            && state.getLong("quietPendingAt", 0L) <= 0L) {
            _state.value = State.Disabled; return
        }
        // PENDING 恢复：派发后进程被杀/AI 失败 → lastSuccessAt 没追平 pendingAt → 未消费；
        // 追平才提升为冷却锚点 lastQuietDigestAt。失败不再被"乐观冷却"吞掉。
        val pendingAt = state.getLong("quietPendingAt", 0L)
        when (QuietDigestRules.resolvePending(pendingAt, state.getLong("lastSuccessAt", 0L),
            System.currentTimeMillis(), RETRY_MS)) {
            Pending.Promote ->
                state.edit().putLong("lastQuietDigestAt", pendingAt).remove("quietPendingAt").apply()
            Pending.Retry -> {
                val i = collect()
                // 重试是"到期任务"，不再要求安静/在听；但开关、授权、AI、隐私暂停、积压仍把关。
                _state.value = if (retryAllowed(i)) {
                    state.edit().putLong("quietPendingAt", System.currentTimeMillis()).apply() // 重试节流
                    scheduler.runNow(); State.Trigger
                } else QuietDigestRules.evaluate(i).let { if (it == State.Trigger) State.NoContent else it }
                return
            }
            Pending.Hold -> {
                if (pendingAt > 0L) {
                    // 上次触发仍在途/重试窗口内：只更新展示，不重复派发。
                    _state.value = QuietDigestRules.evaluate(collect())
                        .let { if (it == State.Trigger) State.Running else it }
                    return
                }
            }
        }
        val s = QuietDigestRules.evaluate(collect())
        _state.value = s
        if (s != State.Trigger) return
        // 记 PENDING 再派发：任务创建≠成功；水位只随成功收尾推进。
        state.edit().putLong("quietPendingAt", System.currentTimeMillis()).apply()
        scheduler.runNow()
    }

    /** 重试只看"该不该跑"，不看"还安不安静"——内容当时已判可整理。 */
    private fun retryAllowed(i: QuietDigestRules.Input) =
        i.enabled && i.authAllowed && i.aiReady && !i.privacyPaused &&
            !i.digestRunning && i.hasUndigested && !i.transcribeBacklog

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
            privacyPaused = capturePrefs.getBoolean(app.resound.capture.AndroidScene.KEY_PRIVACY, false),
            interrupted = captureState == CaptureState.MicBusy || captureState == CaptureState.NoPermission,
            hasUndigested = db.memory().undigestedCount() > 0 || db.memory().pendingDigestCount() > 0,
            transcribeBacklog = db.transcribe().pendingMillis() > 0,
            digestRunning = digestRunner.running.value,
            lastQuietDigestAt = state.getLong("lastQuietDigestAt", 0L),
            authAllowed = AppGraph.auth?.allowed(Capability.AiAnalysis) ?: true,
            aiReady = digestRunner.ready(),
        )
    }

    companion object { private const val RETRY_MS = 15 * 60_000L }
}
