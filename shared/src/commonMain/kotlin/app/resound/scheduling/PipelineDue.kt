package app.resound.scheduling

/** 时间均为调用方提供的 Unix 毫秒；本规则不读取时钟、设置或数据库。 */
object PipelineDue {
    enum class Mode { IntervalDigest, TimedDigest, Transcribe, RealtimeFallback }
    enum class Failure { None, Retryable, Blocked }
    enum class Waiting { Busy, Offline, Metered }
    data class History(
        val lastAttempt: Long,
        val lastSuccess: Long? = null,
        val failure: Failure = Failure.None,
        val failures: Int = 0,
    )
    data class Input(
        val mode: Mode,
        val now: Long,
        val intervalMinutes: Int,
        val history: History,
        val pendingMillis: Long,
        val busy: Boolean,
        val online: Boolean,
        val wifiOnly: Boolean,
        val unmetered: Boolean,
        val ticket: TimedDigestRecovery.Ticket? = null,
    )
    sealed interface Decision {
        data object NotDue : Decision
        data class Wait(val reason: Waiting, val dueAt: Long) : Decision
        data class Run(val mode: Mode, val dueAt: Long, val ticket: TimedDigestRecovery.Ticket? = null) : Decision
    }

    fun nextAttemptAt(mode: Mode, intervalMinutes: Int, history: History): Long {
        val interval = (if (mode == Mode.RealtimeFallback) 30 else intervalMinutes).coerceAtLeast(1) * 60_000L
        val delay = if (history.failure == Failure.Retryable)
            minOf(interval, 5 * 60_000L * (1L shl (history.failures - 1).coerceIn(0, 30))) else interval
        return history.lastAttempt + delay
    }
    fun decide(input: Input): Decision = with(input) {
        val transcribeOnly = mode == Mode.Transcribe || mode == Mode.RealtimeFallback
        if (mode == Mode.TimedDigest && ticket == null) return Decision.NotDue
        if (transcribeOnly && pendingMillis <= 0) return Decision.NotDue
        val dueAt = if (history.failure == Failure.Retryable)
            maxOf(ticket?.target ?: Long.MIN_VALUE, nextAttemptAt(mode, intervalMinutes, history))
        else ticket?.target ?: nextAttemptAt(mode, intervalMinutes, history)
        // 密钥/余额等错误：不每分钟重试，但仍按设定间隔再试一次，用户改好设置后不用手动点也能恢复。
        if (now < dueAt) return Decision.NotDue
        if (busy) return Decision.Wait(Waiting.Busy, dueAt)
        if (!online) return Decision.Wait(Waiting.Offline, dueAt)
        // 整理的前置转写沿用既有 WiFi 跳过规则，不能因此阻止已有文本整理。
        if (transcribeOnly && wifiOnly && !unmetered) return Decision.Wait(Waiting.Metered, dueAt)
        Decision.Run(mode, dueAt, ticket)
    }
}
