package app.resound.screentime

/** 缺口的修复文案已定死；落库行遇到未知代码直接抛错（fail fast）。 */
private fun fixFor(code: String): String = when (code) {
    GapCode.USAGE_ACCESS_MISSING -> "设置 → 使用情况访问 → 涛声 开启"
    GapCode.NOTIFICATION_ACCESS_MISSING -> "设置 → 通知使用权 → 涛声 开启"
    GapCode.PULL_FAILED -> "这段超出系统保留期或采集失败，无法补回"
    GapCode.COVERAGE_LAG -> "打开涛声，等待下一次采集"
    GapCode.LISTENER_DISCONNECTED -> "设置 → 通知使用权 → 涛声 重新开启"
    GapCode.DISABLED -> "屏幕时间关闭期间未采集"
    GapCode.PRIVACY_DELETED -> "这段已按你的删除要求清除，无法恢复"
    else -> throw IllegalArgumentException("未知缺口代码: $code")
}

/**
 * [ScreenTimeReader] 的唯一实现：屏幕总开关门控、查询前增量拉取、按规则算使用段、
 * 拼显示名并脱敏、汇总覆盖缺口。播放段委托 [PlaybackQueries]，不复制查询。
 * 无授权时已落库历史照常返回，只追加 [GapCode.USAGE_ACCESS_MISSING]。
 */
class ScreenTimeQueries(
    private val dao: ScreenTimeDao,
    private val puller: ScreenTimePuller,
    private val settings: ScreenTimeSettings,
    /** 使用情况访问权限；只影响缺口的呈现，与拉取器内部判据同源，由装配层传同一实现。 */
    private val permitted: () -> Boolean,
    /** 通知使用权；只影响 playback 缺口的呈现。已落库的播放行在没有授权时照常返回。 */
    private val playbackPermitted: () -> Boolean,
    private val excluded: Set<String>,
    private val now: () -> Long,
    private val playbackQueries: PlaybackQueries,
    /** 当前是否允许读取播放标题；关时本实现不查播放表。不改 [ScreenTimeReader] 签名。 */
    private val titlesAllowed: () -> Boolean = { true },
    private val config: ScreenTimeConfig = ScreenTimeConfig(),
) : ScreenTimeReader {
    override suspend fun sessions(from: Long, until: Long): ScreenTimeResult {
        require(until > from) { "until($until) 必须晚于 from($from)" }
        if (!settings.enabled.value) return ScreenTimeResult.DISABLED
        val refresh = puller.pull()
        val coverage = dao.state(SCREEN_SOURCE)?.coveredUntil
        val uncoveredFrom = maxOf(from, coverage ?: from)
        val gaps = buildList {
            for (row in dao.gaps(SCREEN_SOURCE, from, until).sortedBy { it.fromAt }) {
                add(gap(row.code, row.source, row.fromAt, row.untilAt))
            }
            if (!permitted()) add(gap(GapCode.USAGE_ACCESS_MISSING, SCREEN_SOURCE, from, until))

            val queriedAt = now()
            val lagEnd = minOf(until, queriedAt)
            if (queriedAt - (coverage ?: 0) > COVERAGE_LAG_MILLIS && uncoveredFrom < lagEnd) {
                add(gap(GapCode.COVERAGE_LAG, SCREEN_SOURCE, uncoveredFrom, lagEnd))
            }

            if (refresh is PullResult.Failed) {
                val failEnd = minOf(until, now())
                if (uncoveredFrom < failEnd) add(gap(GapCode.PULL_FAILED, SCREEN_SOURCE, uncoveredFrom, failEnd))
            }

            val playbacksAsOf = now()
            for (row in dao.gaps(PLAYBACK_SOURCE, from, until).sortedBy { it.fromAt }) {
                add(gap(row.code, row.source, row.fromAt, minOf(row.untilAt, playbacksAsOf)))
            }
            if (!playbackPermitted()) {
                add(gap(GapCode.NOTIFICATION_ACCESS_MISSING, PLAYBACK_SOURCE, from, until))
            }
        }
        val labels = dao.names().associateBy { it.pkg }
        val sequence = buildList {
            for (record in dao.events(from - config.lookbackMillis, until)) {
                val type = ScreenEventType.of(record.type) ?: continue
                add(ScreenEvent(record.id, record.at, record.pkg, record.cls, type))
            }
        }
        val privacyIntervals = gaps.filter { it.code in SESSION_BLOCKING_GAP_CODES }
            .map { it.from to it.until } + settings.disabledWindows()
        val rawUsage = ScreenTimeRules.sessions(sequence, from, until, coverage ?: from, excluded, config)
        val sessions = ScreenTimeRules.exclude(rawUsage, privacyIntervals).map { session ->
            val caption = labels[session.pkg]?.name ?: session.pkg
            val title = ScreenTimeText.clean(caption, config.titleMaxCodePoints)
            NamedAppSession(session.pkg, title, session.from, session.until)
        }
        val tracks = if (titlesAllowed()) playbackQueries.overlapping(from, until) else emptyList()
        return ScreenTimeResult(
            enabled = true, sessions = sessions, coverageUntil = coverage, gaps = gaps,
            devicePlayback = tracks,
        )
    }

    private fun gap(code: String, source: String, from: Long, until: Long) =
        CoverageGap(code, source, from, until, fixFor(code))

    private companion object {
        /** coverage_lag 的触发阈值（设计 dx：此刻减 coveredUntil 超过 1 小时）。 */
        const val COVERAGE_LAG_MILLIS = 3_600_000L
    }
}
