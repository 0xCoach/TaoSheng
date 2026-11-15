package app.resound.push

/** 同一日期、同一通道最多生成三次；时间由平台传入，单位为 Unix 毫秒。 */
object FootprintRetry {
    const val MAX_ATTEMPTS = 3
    const val RETRY_MILLIS = 5 * 60_000L
    data class Failure(val attempts: Int, val handled: Boolean, val retryAt: Long?)

    fun failed(savedDay: String?, day: String, savedAttempts: Int, now: Long): Failure {
        val previous = if (savedDay == day) savedAttempts.coerceIn(0, MAX_ATTEMPTS) else 0
        val attempts = (previous + 1).coerceAtMost(MAX_ATTEMPTS)
        val handled = attempts == MAX_ATTEMPTS
        return Failure(attempts, handled, if (handled) null else now + RETRY_MILLIS)
    }
}
