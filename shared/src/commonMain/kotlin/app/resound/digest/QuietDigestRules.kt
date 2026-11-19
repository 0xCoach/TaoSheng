package app.resound.digest

/**
 * 安静自动整理判定：与 DigestMode.Interval / TimePoints 并存的第三种触发来源。
 * 所有输入由调用方注入（服务状态/水位/授权/AI 配置），纯逻辑可单测；
 * 「真安静」要求录音健康在听——麦被占、隐私暂停、服务死掉都不能算安静。
 */
object QuietDigestRules {

    /** 给设置页/状态栏看的当前等待原因。 */
    enum class State {
        Disabled, NotListening, Paused, PrivacyPaused, MicInterrupted,
        WaitingForSilence, WaitingForTranscript, WaitingForCooldown,
        NoContent, AuthRequired, AiNotConfigured, Running, Trigger
    }

    data class Input(
        val enabled: Boolean,
        val thresholdMs: Long,
        val cooldownMs: Long,
        val now: Long,
        /** 最近一段有效人声片段的结束/入库时刻；从未有过为 0。 */
        val lastSpeechEndAt: Long,
        /** 录音服务正在健康聆听（前台、initialized、采集协程活着）。 */
        val listening: Boolean,
        /** VAD 正开着一段人声（段落尚未落库，endAt 还没更新）——说话中不算安静。 */
        val speechActive: Boolean,
        val paused: Boolean,
        val privacyPaused: Boolean,
        /** 麦克风被抢占/采集错误等异常态。 */
        val interrupted: Boolean,
        /** 有待整理的已转写内容（undigested 或 pendingDigests 非空）。 */
        val hasUndigested: Boolean,
        /** 转写仍积压——不把它误判成"没内容"。 */
        val transcribeBacklog: Boolean,
        val digestRunning: Boolean,
        val lastQuietDigestAt: Long,
        val authAllowed: Boolean,
        val aiReady: Boolean,
    )

    fun evaluate(i: Input): State {
        if (!i.enabled) return State.Disabled
        if (!i.authAllowed) return State.AuthRequired
        if (!i.aiReady) return State.AiNotConfigured
        if (i.privacyPaused) return State.PrivacyPaused
        if (i.paused) return State.Paused
        if (i.interrupted) return State.MicInterrupted
        if (!i.listening) return State.NotListening
        if (i.digestRunning) return State.Running // 运行中不外发新触发
        val sinceSpeech = i.now - i.lastSpeechEndAt
        if (i.speechActive || i.lastSpeechEndAt <= 0L || sinceSpeech < i.thresholdMs) return State.WaitingForSilence
        if (i.transcribeBacklog) return State.WaitingForTranscript
        if (!i.hasUndigested) return State.NoContent
        if (i.now - i.lastQuietDigestAt < i.cooldownMs) return State.WaitingForCooldown
        return State.Trigger
    }

    /** 只有这个状态才允许发起一次自动整理。 */
    fun shouldTrigger(i: Input): Boolean = evaluate(i) == State.Trigger

    /** PENDING 任务状态：派发前持久化；只有整理真正成功（lastSuccessAt 追平）才消费进冷却。 */
    enum class Pending { Promote, Retry, Hold }

    /**
     * @param pendingAt 上次触发落账时刻（0=无待办）
     * @param lastSuccessAt DigestRunner 只在 Done/NothingToDo（成功收尾）时写的时刻——失败不写
     */
    fun resolvePending(pendingAt: Long, lastSuccessAt: Long, now: Long, retryMs: Long): Pending = when {
        pendingAt <= 0L -> Pending.Hold
        lastSuccessAt >= pendingAt -> Pending.Promote
        now - pendingAt >= retryMs -> Pending.Retry
        else -> Pending.Hold // 窗口内：上次触发仍在途/刚失败不久，不重复派发
    }
}
