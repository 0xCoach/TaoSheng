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
            val state = dao.state(SCREEN_SOURCE)
            val horizon = at - HORIZON_MILLIS
            val covered = state?.coveredUntil ?: 0L
            // 关闭时刻未知（升级用户）且上次覆盖早于本次打开：沿用旧口径把关闭前整段当关闭期间，起点圈到 enabledAt。
            val legacyDisabled = state != null && covered < settings.enabledAt && settings.disabledAt == 0L
            // 从未提交过时不补采历史；已提交过则起点不再受 enabledAt 约束，关闭前仍开着的使用照常补拉。
            val start = when {
                state == null -> maxOf(dao.lastEventAt() ?: 0L, horizon, settings.enabledAt)
                legacyDisabled -> maxOf(dao.lastEventAt() ?: 0L, covered, state.floor, horizon, settings.enabledAt)
                else -> maxOf(dao.lastEventAt() ?: 0L, covered, state.floor, horizon)
            }
            val disabled = disabledWindowsForPull(state, covered, settings)
            // 缺口随本次事务一起落库：事件源抛错时与覆盖指针一起回滚，下次拉取不会重复写。
            val existingDisabled = if (state == null) emptyList() else {
                val lookupFrom = minOf(start, disabled.minOfOrNull { it.first } ?: start)
                dao.gaps(SCREEN_SOURCE, lookupFrom, at).filter { it.code == GapCode.DISABLED }
            }
            val gaps = buildList {
                state?.coveredUntil?.takeIf { it < horizon }?.let {
                    add(ScreenCoverageGapRow(source = SCREEN_SOURCE, fromAt = it, untilAt = horizon, code = GapCode.PULL_FAILED))
                }
                for ((offFrom, offUntil) in disabled) {
                    if (existingDisabled.any { it.fromAt == offFrom && it.untilAt == offUntil }) continue
                    add(ScreenCoverageGapRow(source = SCREEN_SOURCE, fromAt = offFrom, untilAt = offUntil, code = GapCode.DISABLED))
                }
            }
            val allowed = allowedPullRanges(start, at, disabled).ifEmpty {
                if (start < at && disabled.isNotEmpty()) emptyList() else listOf(start to at)
            }
            val raw = if (allowed.size <= 1) {
                allowed.singleOrNull()?.let { source.since(it.first, it.second) } ?: emptyList()
            } else {
                allowed.flatMap { (from, until) ->
                    source.since(from, until).filter { it.at >= from && it.at < until }
                }
            }
            val floor = dao.state(SCREEN_SOURCE)?.floor ?: 0L
            val events = raw.filter { it.type in SCREEN_EVENT_TYPES }
                .map { ScreenEventRow(at = it.at, pkg = it.pkg, cls = it.cls ?: "", type = it.type) }
                .filter { row -> disabled.none { row.at >= it.first && row.at < it.second } }
                .filter { it.at >= floor }
            val names = if (events.isEmpty()) emptyList() else
                source.appNames(events.map { it.pkg }.toSet()).map { (pkg, name) -> ScreenAppNameRow(pkg, name, at) }
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
    val windows = settings.disabledWindows().toMutableList()
    if (state != null && covered < settings.enabledAt && settings.disabledAt > 0) {
        val offFrom = maxOf(covered, settings.disabledAt)
        if (offFrom < settings.enabledAt) windows += offFrom to settings.enabledAt
    } else if (state != null && covered < settings.enabledAt && settings.disabledAt == 0L) {
        windows += covered to settings.enabledAt
    }
    return mergeIntervals(windows)
}

internal fun allowedPullRanges(start: Long, end: Long, blocked: List<Pair<Long, Long>>): List<Pair<Long, Long>> {
    if (start >= end) return emptyList()
    val cuts = mergeIntervals(blocked.map { maxOf(it.first, start) to minOf(it.second, end) }.filter { it.first < it.second })
    val out = mutableListOf<Pair<Long, Long>>()
    var cursor = start
    for ((bFrom, bUntil) in cuts) {
        if (cursor < bFrom) out += cursor to bFrom
        if (bUntil > cursor) cursor = bUntil
    }
    if (cursor < end) out += cursor to end
    return out
}

internal fun mergeIntervals(intervals: List<Pair<Long, Long>>): List<Pair<Long, Long>> {
    if (intervals.isEmpty()) return emptyList()
    val sorted = intervals.sortedBy { it.first }
    val out = mutableListOf(sorted.first())
    for (i in 1 until sorted.size) {
        val last = out.last()
        val cur = sorted[i]
        if (cur.first <= last.second) out[out.lastIndex] = last.first to maxOf(last.second, cur.second)
        else out += cur
    }
    return out
}
