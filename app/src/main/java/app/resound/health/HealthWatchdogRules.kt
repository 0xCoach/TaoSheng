package app.resound.health

/** 类型沿用守护日志的稳定英文名称；不依赖数据库实体。 */
data class HealthWatchdogEvent(val at: Long, val type: String, val detail: String?)

object HealthWatchdogRules {
    const val LONG_BUSY_MS = 5 * 60_000L

    /** 输出每次异常的发生时刻；同一停止后的多条 Killed 缺口及 Recovered 只算一次。 */
    fun interruptions(events: List<HealthWatchdogEvent>, now: Long, microphoneStillBusy: Boolean): List<Long> {
        val result = mutableListOf<Long>()
        var abnormalStop = false
        var busy: HealthWatchdogEvent? = null
        fun finishBusy(end: Long) {
            busy?.let { if (end - it.at > LONG_BUSY_MS) result += it.at }
            busy = null
        }
        for (event in events.sortedBy { it.at }.filter { it.at <= now }) {
            when (event.type) {
                "MicBusy" -> {
                    if (event.detail?.startsWith("通话中") == true) finishBusy(event.at)
                    else if (busy == null) busy = event
                }
                "MicReleased", "Paused", "InputSwitched" -> finishBusy(event.at)
                "ServiceStopped" -> {
                    finishBusy(event.at)
                    // 仅现有 fail() 的明确标记算异常；未知老日志保守不算。
                    if (event.detail?.contains("等待手动恢复，保留未正常停止标记") == true) {
                        if (!abnormalStop) result += event.at
                        abnormalStop = true
                    } else abnormalStop = false
                }
                "Killed" -> {
                    finishBusy(event.at)
                    if (!abnormalStop) result += event.at
                    abnormalStop = true
                }
                "Recovered", "ServiceStarted", "BootRestart" -> {
                    finishBusy(event.at)
                    abnormalStop = false
                }
            }
        }
        // 日志可能在进程退出前未写释放；仅当前仍被占用时把未闭合区间延长到现在。
        if (microphoneStillBusy) finishBusy(now)
        return result.distinct()
    }
}
