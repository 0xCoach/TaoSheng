package app.resound.screentime

import app.resound.diagnostics.FailureNotes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 一次拉取的结果。Failed.type 是异常类名，只用于计数与告警，不携带包名或时间线。 */
sealed class PullResult {
    data class Pulled(val count: Int) : PullResult()
    data object Disabled : PullResult()
    data object NoPermission : PullResult()
    data object Throttled : PullResult()
    data class Failed(val type: String) : PullResult()
}

/** 把系统使用情况事件搬进 screen_events。定时任务与查询前拉取共用同一实例（AppGraph 装配为单例），全程串行。 */
class ScreenTimePuller(
    private val settings: ScreenTimeSettings,
    private val source: ScreenEventSource,
    private val dao: ScreenTimeDao,
    private val now: () -> Long,
    /** 拉取途中的授权异常判据（安卓传 SecurityException）；命中返回 NoPermission。 */
    private val denied: (Throwable) -> Boolean,
    private val diagnostic: (String) -> Unit = {},
) {
    private val gate = Mutex()

    suspend fun pull(): PullResult = gate.withLock {
        // 开关关闭时不碰事件源的任何方法。
        if (!settings.enabled.value) return@withLock PullResult.Disabled
        if (!source.permitted()) {
            FailureNotes.note("screentime/ScreenTimePuller:NoPermission")
            return@withLock PullResult.NoPermission
        }
        val at = now()
        if (at - settings.lastPullAt < PULL_THROTTLE_MILLIS) return@withLock PullResult.Throttled
        try {
            val startedAt = now()
            val previous = dao.state(SCREEN_SOURCE)
            val horizon = at - HORIZON_MILLIS
            val latestEvent = dao.lastEventAt() ?: 0L
            val lastCoverage = previous?.coveredUntil
            val covered = lastCoverage ?: 0L
            val earliest = maxOf(horizon, latestEvent)
            val start = if (previous == null) {
                maxOf(earliest, settings.enabledAt)
            } else {
                val checkpoint = maxOf(earliest, covered, previous.floor)
                // 旧版本未保存关停时刻，只能从本次启用时起补采。
                if (settings.disabledAt == 0L && covered < settings.enabledAt) {
                    maxOf(checkpoint, settings.enabledAt)
                } else {
                    checkpoint
                }
            }
            val disabled = disabledWindowsForPull(previous, covered, settings)
            val persisted = if (previous == null) emptySet() else {
                val lookupFrom = minOf(start, disabled.firstOrNull()?.first ?: start)
                dao.gaps(SCREEN_SOURCE, lookupFrom, at).filter { it.code == GapCode.DISABLED }
                    .mapTo(HashSet()) { it.fromAt to it.untilAt }
            }
            val gaps = buildList {
                if (lastCoverage != null && lastCoverage < horizon) {
                    add(ScreenCoverageGapRow(source = SCREEN_SOURCE, fromAt = lastCoverage, untilAt = horizon, code = GapCode.PULL_FAILED))
                }
                for (window in disabled) {
                    if (window !in persisted) {
                        add(ScreenCoverageGapRow(
                            source = SCREEN_SOURCE, fromAt = window.first, untilAt = window.second, code = GapCode.DISABLED,
                        ))
                    }
                }
            }
            val windows = allowedPullRanges(start, at, disabled).ifEmpty {
                if (start >= at || disabled.isEmpty()) listOf(start to at) else emptyList()
            }
            val raw = buildList {
                for ((from, until) in windows) {
                    val batch = source.since(from, until)
                    if (windows.size == 1) addAll(batch)
                    else batch.filterTo(this) { it.at >= from && it.at < until }
                }
            }
            val floor = dao.state(SCREEN_SOURCE)?.floor ?: 0L
            val events = raw.mapNotNull { item ->
                val allowed = item.at >= floor && item.type in SCREEN_EVENT_TYPES &&
                    disabled.none { (begin, end) -> item.at >= begin && item.at < end }
                if (allowed) ScreenEventRow(at = item.at, pkg = item.pkg, cls = item.cls.orEmpty(), type = item.type) else null
            }
            val packages = events.mapTo(LinkedHashSet()) { it.pkg }
            val labels = if (packages.isEmpty()) emptyMap() else source.appNames(packages)
            val names = labels.map { (pkg, name) -> ScreenAppNameRow(pkg, name, at) }
            dao.commitPull(events, names, at, gaps)
            settings.markPulled(at)
            diagnostic("screentime: pulled ${events.size} events (${raw.size} raw) in ${now() - startedAt}ms")
            PullResult.Pulled(events.size)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (denied(e)) {
                FailureNotes.record("screentime/ScreenTimePuller:NoPermission", e)
                diagnostic("screentime: pull denied by ${e::class.simpleName}")
                return@withLock PullResult.NoPermission
            }
            FailureNotes.record("screentime/ScreenTimePuller:Failed", e)
            diagnostic("screentime: pull failed by ${e::class.simpleName}")
            PullResult.Failed(e::class.simpleName ?: "Unknown")
        }
    }

    companion object {
        /** 两次成功拉取的最小间隔。 */
        const val PULL_THROTTLE_MILLIS = 30_000L
        /** 系统使用情况保留期；更早的区间不可补回。 */
        const val HORIZON_MILLIS = 10 * 24 * 60 * 60 * 1000L
    }
}

internal fun disabledWindowsForPull(
    state: ScreenPullStateRow?,
    covered: Long,
    settings: ScreenTimeSettings,
): List<Pair<Long, Long>> {
    val existing = settings.disabledWindows()
    if (state == null || covered >= settings.enabledAt || settings.disabledAt < 0) return mergeIntervals(existing)
    val offAt = if (settings.disabledAt > 0) maxOf(covered, settings.disabledAt) else covered
    return if (offAt >= settings.enabledAt) {
        mergeIntervals(existing)
    } else {
        mergeIntervals(existing + (offAt to settings.enabledAt))
    }
}

internal fun allowedPullRanges(start: Long, end: Long, blocked: List<Pair<Long, Long>>): List<Pair<Long, Long>> {
    if (start >= end) return emptyList()
    return buildList {
        var position = start
        for ((left, right) in blocked.sortedBy { it.first }) {
            if (position >= end || left >= end) break
            if (left >= right || right <= position) continue
            if (position < left) add(position to left)
            position = minOf(right, end)
        }
        if (position < end) add(position to end)
    }
}

internal fun mergeIntervals(intervals: List<Pair<Long, Long>>): List<Pair<Long, Long>> {
    if (intervals.isEmpty()) return emptyList()
    val sorted = intervals.sortedBy { it.first }
    return buildList {
        var (from, until) = sorted.first()
        for (index in 1..sorted.lastIndex) {
            val (start, end) = sorted[index]
            if (start > until) {
                add(from to until)
                from = start
                until = end
            } else {
                until = maxOf(until, end)
            }
        }
        add(from to until)
    }
}
