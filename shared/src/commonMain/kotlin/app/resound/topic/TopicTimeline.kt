package app.resound.topic

/*
 * 卡 148：专题页时间轴的纯计算（随改随搬，为 iOS 复用）。不引用 android.* / java.*。
 */

/** 时间轴上的一段：[from]～[to] 是 0～1 的位置；[included] 为 true 是算进专题的，false 是中间暂停或剪掉的 */
data class TimelineBar(val from: Float, val to: Float, val included: Boolean)

data class TopicTimelineLayout(
    val startAt: Long,
    val endAt: Long,
    val bars: List<TimelineBar>,
    /** 重点在轴上的位置（0～1）和时刻 */
    val marks: List<Pair<Float, Long>>,
    /** 进行中时“现在”的位置；已结束为 null */
    val now: Float?,
    /** 算进专题的总时长（毫秒） */
    val includedMs: Long,
)

object TopicTimeline {
    /**
     * [spans] 为各段（结束为 null 表示进行中）；[plannedEndAt] 有值且还在进行时，轴一直画到预定结束，右边留空。
     * 段与段之间的空档算“暂停或剪掉”。
     */
    fun layout(spans: List<Pair<Long, Long?>>, marks: List<Long>, now: Long, plannedEndAt: Long? = null): TopicTimelineLayout? {
        if (spans.isEmpty()) return null
        val sorted = spans.sortedBy { it.first }
        val running = sorted.any { it.second == null }
        val start = sorted.first().first
        val lastEnd = sorted.maxOf { it.second ?: now }
        val end = maxOf(lastEnd, if (running && plannedEndAt != null) plannedEndAt else lastEnd, start + 60_000)
        val total = (end - start).toFloat()
        fun pos(t: Long) = ((t - start) / total).coerceIn(0f, 1f)
        val bars = mutableListOf<TimelineBar>()
        var cursor = start
        for ((s, e) in sorted) {
            if (s > cursor) bars += TimelineBar(pos(cursor), pos(s), included = false)
            val segEnd = e ?: now
            bars += TimelineBar(pos(maxOf(s, cursor)), pos(segEnd), included = true)
            cursor = maxOf(cursor, segEnd)
        }
        val included = sorted.sumOf { (s, e) -> ((e ?: now) - s).coerceAtLeast(0) }
        return TopicTimelineLayout(start, end, bars, marks.filter { it in start..end }.map { pos(it) to it }, if (running) pos(now) else null, included)
    }

    /** “38:12”“1:05:20” */
    fun clock(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
        fun two(v: Long) = if (v < 10) "0$v" else "$v"
        return if (h > 0) "$h:${two(m)}:${two(sec)}" else "${two(m)}:${two(sec)}"
    }
}
