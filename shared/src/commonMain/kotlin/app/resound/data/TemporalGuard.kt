package app.resound.data

/**
 * 时间一致性守卫：提取出的"提及时间"（dueAt）与"说话时间"（recordedAt）互相校验。
 * dueText（模型摘的原文）始终保留；只有机器解析出的 dueAt 明显不可能时才作废——
 * 它基本是模型写错了日期字面量（比如把"明天"解析成去年/后年），留下会发出错误提醒。
 * 纯函数、平台无关。
 */
object TemporalGuard {
    /** 期限早于说话时刻的容差：一天半内"昨天就该交"这类话是合法的。 */
    const val PAST_GRACE_MS = 36L * 3600 * 1000
    /** 期限晚于说话时刻的上限：超过约两年基本是年份写错。 */
    const val FUTURE_CAP_MS = 750L * 24 * 3600 * 1000

    /**
     * 返回保留的 dueAt；明显越界时返回 null（此时调用方应记 FailureNotes）。
     * recordedAt 为 null（手打条目）时不做判断，原样放行。
     */
    fun sanitizeDue(dueAt: Long?, recordedAt: Long?): Long? {
        if (dueAt == null || recordedAt == null) return dueAt
        if (dueAt < recordedAt - PAST_GRACE_MS) return null
        if (dueAt > recordedAt + FUTURE_CAP_MS) return null
        return dueAt
    }

    /** 是否被判越界（与 sanitizeDue 同判定，便于记日志而不重复表达式）。 */
    fun dueViolated(dueAt: Long?, recordedAt: Long?): Boolean =
        dueAt != null && recordedAt != null &&
            (dueAt < recordedAt - PAST_GRACE_MS || dueAt > recordedAt + FUTURE_CAP_MS)
}
