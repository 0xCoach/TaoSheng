@file:OptIn(kotlin.time.ExperimentalTime::class)
package app.resound.screentime

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime

/** 由系统前后台、屏幕和锁屏事件计算各应用的使用区间。 */
object ScreenTimeRules {
    private data class Activity(val pkg: String, val cls: String)
    private data class OpenSession(val activity: Activity, val startedAt: Long)
    private data class ClosedSession(val interval: AppSession, val canJoinNext: Boolean)

    /**
     * 把 [events] 重放为使用段。调用方负责传入 `[from - config.lookbackMillis, until)` 的全部事件，
     * 函数从头重放建立「起点前已在前台」等初始状态，再裁剪到 `[from, until)`。
     * [coveredUntil] 是采集侧已覆盖的截止时刻，重放结束仍开着的段截止于 `min(until, coveredUntil)`。
     * [excluded] 中的包名不输出，但其 RESUMED 照样给上一段收尾。
     */
    fun sessions(
        events: List<ScreenEvent>,
        from: Long,
        until: Long,
        coveredUntil: Long,
        excluded: Set<String>,
        config: ScreenTimeConfig = ScreenTimeConfig(),
    ): List<AppSession> {
        var foreground: Activity? = null
        var screenLit = true
        var locked = false
        var current: OpenSession? = null
        val visible = ArrayList<ClosedSession>()

        fun finish(at: Long, canJoinNext: Boolean = false) {
            val session = current ?: return
            current = null
            val app = session.activity.pkg
            if (app in excluded) return
            val left = maxOf(from, session.startedAt)
            val right = minOf(until, at)
            if (left >= right) return

            // 只有相邻的可见使用段才有资格吸收切页时的短间隔。
            val previous = visible.lastOrNull()
            val interval = if (previous != null && previous.interval.pkg == app &&
                previous.canJoinNext && left - previous.interval.until <= config.mergeGapMillis
            ) {
                visible.removeAt(visible.lastIndex)
                previous.interval.copy(until = right)
            } else {
                AppSession(app, left, right)
            }
            visible.add(ClosedSession(interval, canJoinNext))
        }

        fun begin(at: Long) {
            val activity = foreground ?: return
            if (current == null) current = OpenSession(activity, at)
        }

        val orderedEvents = events.sortedWith(compareBy<ScreenEvent> { it.at }.thenBy { it.id })
        for (event in orderedEvents) {
            val activity = Activity(event.pkg, event.cls)
            when (event.type) {
                ScreenEventType.RESUMED -> {
                    // 部分设备恢复前台时只上报 RESUMED。
                    locked = false
                    if (activity != foreground) finish(event.at, canJoinNext = true)
                    foreground = activity
                    if (screenLit) begin(event.at)
                }
                ScreenEventType.PAUSED, ScreenEventType.STOPPED -> {
                    if (foreground == activity) {
                        finish(event.at, canJoinNext = true)
                        foreground = null
                    }
                }
                ScreenEventType.SCREEN_OFF -> {
                    finish(event.at)
                    screenLit = false
                }
                ScreenEventType.KEYGUARD_SHOWN -> {
                    finish(event.at)
                    locked = true
                }
                ScreenEventType.SHUTDOWN, ScreenEventType.STARTUP -> {
                    finish(event.at)
                    foreground = null
                    screenLit = true
                    locked = false
                }
                ScreenEventType.SCREEN_ON -> {
                    screenLit = true
                    if (!locked) begin(event.at)
                }
                ScreenEventType.KEYGUARD_HIDDEN -> {
                    locked = false
                    begin(event.at)
                }
            }
        }
        finish(minOf(until, coveredUntil))
        return visible.map { it.interval }
    }

    /**
     * 从使用段里挖掉 [blocked] 各左闭右开区间。已删除、停采、拉取失败的时间不得再被算成使用。
     * 一段可能被拆成多段；与缺口不相交的段原样保留。
     */
    fun exclude(sessions: List<AppSession>, blocked: List<Pair<Long, Long>>): List<AppSession> {
        if (blocked.isEmpty() || sessions.isEmpty()) return sessions
        val cuts = blocked.filter { it.first < it.second }.sortedBy { it.first }
        if (cuts.isEmpty()) return sessions
        return buildList {
            for (session in sessions) {
                var cursor = session.from
                for ((start, end) in cuts) {
                    if (cursor >= session.until || start >= session.until) break
                    if (end <= cursor) continue
                    if (start > cursor) {
                        add(session.copy(from = cursor, until = start))
                    }
                    cursor = end
                }
                if (cursor < session.until) add(session.copy(from = cursor))
            }
        }
    }

    /** 按包聚合总时长：时长降序，同时长按包名升序。 */
    fun totals(sessions: List<AppSession>): List<AppTotal> {
        val byPackage = mutableMapOf<String, Long>()
        for (session in sessions) {
            byPackage[session.pkg] = byPackage.getOrElse(session.pkg) { 0L } + (session.until - session.from)
        }
        return packageTotals(byPackage)
    }

    /** 按时区的自然日聚合；跨零点的段拆到两天。每天的列表排序同 [totals]。 */
    fun dayTotals(sessions: List<AppSession>, zone: TimeZone): Map<LocalDate, List<AppTotal>> {
        val byDay = HashMap<LocalDate, HashMap<String, Long>>()
        for (session in sessions) {
            var cursor = session.from
            while (cursor < session.until) {
                val date = Instant.fromEpochMilliseconds(cursor).toLocalDateTime(zone).date
                val dayEnd = date.plus(1, DateTimeUnit.DAY).atStartOfDayIn(zone).toEpochMilliseconds()
                val next = minOf(session.until, dayEnd)
                val day = byDay.getOrPut(date) { HashMap() }
                day[session.pkg] = day.getOrElse(session.pkg) { 0L } + (next - cursor)
                cursor = next
            }
        }
        return byDay.mapValues { (_, time) -> packageTotals(time) }
    }

    private fun packageTotals(time: Map<String, Long>): List<AppTotal> =
        time.map { (pkg, duration) -> AppTotal(pkg, duration) }
            .sortedWith(compareByDescending<AppTotal> { it.millis }.thenBy { it.pkg })
}
