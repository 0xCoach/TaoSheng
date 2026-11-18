package app.resound.screentime

/**
 * 只读播放记录，不触发屏幕时间拉取、不看屏幕时间开关。
 * 未结束行按 lastSeenAt 截止，并裁到查询区间内；名称与标题按 [ScreenTimeText] 脱敏。
 */
class PlaybackQueries(
    private val dao: ScreenTimeDao,
    private val titleMaxCodePoints: Int = 40,
) {
    /** 收段可能早于下一次播放器心跳。仅在最近一次心跳仍有效时把开放行延至采样终点。 */
    suspend fun duringCapture(from: Long, until: Long, now: Long): List<DevicePlayback> {
        val closed = dao.playbacksOverlapping(from, until)
        val open = dao.openPlaybacks().filter { it.fromAt < until && now - it.lastSeenAt in 0..65_000 }
        val ids = open.map { it.id }.toSet()
        return clipped(closed.filter { it.id !in ids } + open.map { it.copy(untilAt = minOf(until, now)) }, from, until)
    }

    suspend fun overlapping(from: Long, until: Long): List<DevicePlayback> {
        require(until > from) { "until($until) 必须晚于 from($from)" }
        return clipped(dao.playbacksOverlapping(from, until), from, until)
    }
    private fun clipped(rows: List<PlaybackRow>, from: Long, until: Long): List<DevicePlayback> =
        rows.mapNotNull { row ->
            val start = maxOf(row.fromAt, from)
            val end = minOf(row.untilAt ?: row.lastSeenAt, until)
            if (end <= start) null
            else DevicePlayback(
                row.pkg,
                ScreenTimeText.clean(row.name, titleMaxCodePoints),
                row.title?.let { ScreenTimeText.clean(it, titleMaxCodePoints) },
                row.subtitle?.let { ScreenTimeText.clean(it, titleMaxCodePoints) },
                start, end,
            )
        }.sortedBy { it.from }
}
