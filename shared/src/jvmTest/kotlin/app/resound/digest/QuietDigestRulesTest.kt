package app.resound.digest

import app.resound.digest.QuietDigestRules.State
import org.junit.Assert.assertEquals
import org.junit.Test

class QuietDigestRulesTest {

    private fun base(now: Long = 100_000_000_000L) = QuietDigestRules.Input(
        enabled = true, thresholdMs = 20 * 60_000L, cooldownMs = 60 * 60_000L,
        now = now, lastSpeechEndAt = now - 25 * 60_000L, // 已安静 25 分钟 > 阈值 20
        listening = true, speechActive = false, paused = false, privacyPaused = false,
        interrupted = false, hasUndigested = true, transcribeBacklog = false,
        digestRunning = false, lastQuietDigestAt = 0L, authAllowed = true, aiReady = true,
    )

    @Test fun `连续安静达到阈值触发一次`() =
        assertEquals(State.Trigger, QuietDigestRules.evaluate(base()))

    @Test fun `新出现的人声重置安静计时`() {
        // 阈值内：距上次人声只过了 10 分钟
        assertEquals(State.WaitingForSilence,
            QuietDigestRules.evaluate(base().copy(lastSpeechEndAt = 100_000_000_000L - 10 * 60_000L)))
        // VAD 段落还开着（正在说）也绝不能算安静
        assertEquals(State.WaitingForSilence, QuietDigestRules.evaluate(base().copy(speechActive = true)))
    }

    @Test fun `录音服务异常停止和隐私暂停不触发`() {
        assertEquals(State.NotListening, QuietDigestRules.evaluate(base().copy(listening = false)))
        assertEquals(State.Paused, QuietDigestRules.evaluate(base().copy(paused = true)))
        assertEquals(State.PrivacyPaused, QuietDigestRules.evaluate(base().copy(privacyPaused = true)))
        assertEquals(State.MicInterrupted, QuietDigestRules.evaluate(base().copy(interrupted = true)))
    }

    @Test fun `转写积压时报告等待而不是误判无内容`() =
        assertEquals(State.WaitingForTranscript, QuietDigestRules.evaluate(base().copy(transcribeBacklog = true)))

    @Test fun `无有效语音和冷却中不触发`() {
        assertEquals(State.NoContent, QuietDigestRules.evaluate(base().copy(hasUndigested = false)))
        assertEquals(State.WaitingForCooldown,
            QuietDigestRules.evaluate(base().copy(lastQuietDigestAt = 100_000_000_000L - 10 * 60_000L)))
    }

    @Test fun `权限失效和未配置AI不触发`() {
        assertEquals(State.AuthRequired, QuietDigestRules.evaluate(base().copy(authAllowed = false)))
        assertEquals(State.AiNotConfigured, QuietDigestRules.evaluate(base().copy(aiReady = false)))
    }

    @Test fun `整理运行中和开关关闭不外发`() {
        assertEquals(State.Running, QuietDigestRules.evaluate(base().copy(digestRunning = true)))
        assertEquals(State.Disabled, QuietDigestRules.evaluate(base().copy(enabled = false)))
    }

    // ---- PENDING 恢复：失败不被当成成功，窗口内不重复派发 ----

    @Test fun `整理成功后才提升为冷却锚点`() {
        // 成功追平 pending → 消费进冷却
        assertEquals(QuietDigestRules.Pending.Promote, QuietDigestRules.resolvePending(1_000L, 1_000L, 2_000L, 900_000L))
        // 失败（lastSuccessAt 没追上）→ 不消费
        assertEquals(QuietDigestRules.Pending.Hold, QuietDigestRules.resolvePending(1_000L, 500L, 2_000L, 900_000L))
        // 失败且超重试窗 → 允许重试
        assertEquals(QuietDigestRules.Pending.Retry, QuietDigestRules.resolvePending(1_000L, 500L, 1_000_000L, 900_000L))
        // 无 pending → Hold
        assertEquals(QuietDigestRules.Pending.Hold, QuietDigestRules.resolvePending(0L, 0L, 1_000_000L, 900_000L))
    }
}
