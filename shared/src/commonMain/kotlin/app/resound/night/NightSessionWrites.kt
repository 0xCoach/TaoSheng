package app.resound.night

/** 会话仍按每个 pulse 更新；仅在状态变化或一分钟到期后写完整快照。 */
class NightSessionWrites {
    private var writtenState: NightState? = null
    private var elapsedAt = 0L

    suspend fun checkpoint(state: NightState, elapsed: Long, write: suspend () -> Unit) {
        if (writtenState == state && elapsed - elapsedAt < 60_000) return
        write()
        writtenState = state
        elapsedAt = elapsed
    }
}

/** 从最后一个成功落盘的 pulse 起补未知区间。启动后继续等待再进入时，接长同一缺口。 */
fun NightSession.interruptedUntil(at: Long): NightSession {
    if (at <= lastAt) return this
    val tail = signals.lastOrNull()
    val next = if (tail?.kind == "gap" && tail.text == "没有记录" && tail.end == lastAt)
        signals.dropLast(1) + tail.copy(end=at)
    else signals + NightSignal(lastAt,at,"gap",text="没有记录")
    return copy(lastAt=at,signals=next)
}
