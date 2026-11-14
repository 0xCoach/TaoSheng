package app.resound.scheduling

/** 日历候选时间由平台传入；合并漏掉的时间点，一轮只处理最新到期点。 */
object TimedDigestRecovery {
    data class Ticket(val generation: String, val target: Long)
    fun pending(generation: String, enabled: Boolean, since: Long, completed: Long,
                now: Long, candidates: List<Long>): Ticket? {
        if (!enabled) return null
        val watermark = maxOf(since, completed)
        return candidates.filter { it > watermark && it <= now }.maxOrNull()?.let { Ticket(generation, it) }
    }
}
