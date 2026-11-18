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
