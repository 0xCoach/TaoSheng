package app.resound.digest.core

/** 取消通过协程取消异常传播；不能返回 Done 或消耗完成凭据。 */
sealed interface DigestOutcome {
    data object Done : DigestOutcome
    data object NothingToDo : DigestOutcome
    data class Retryable(val reason: String) : DigestOutcome
    data class NeedsUser(val reason: String) : DigestOutcome
}

object DigestRecovery {
    /** 已持久化回复、来源已全部处理/删除、用户明确重试，才能解除未知请求的保护。 */
    fun mayClearInFlight(replyPersisted: Boolean, noRemainingSource: Boolean, manual: Boolean): Boolean =
        replyPersisted || noRemainingSource || manual
}
