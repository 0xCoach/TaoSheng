package app.resound.screentime

/**
 * 只读播放记录，不触发屏幕时间拉取、不看屏幕时间开关。
 * 未结束行按 lastSeenAt 截止，并裁到查询区间内；名称与标题按 [ScreenTimeText] 脱敏。
 */
class PlaybackQueries(
    private val dao: ScreenTimeDao,
    private val titleMaxCodePoints: Int = 40,
) {
    suspend fun overlapping(from: Long, until: Long): List<DevicePlayback> {
        require(until > from) { "until($until) 必须晚于 from($from)" }
        return dao.playbacksOverlapping(from, until).mapNotNull { row ->
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
}
