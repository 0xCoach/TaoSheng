package app.resound.night

import kotlinx.datetime.LocalDate

/**
 * 今日节奏里的睡眠：把落在这一天里的夜晚（早上醒来的那一夜、晚上刚开始的那一夜）
 * 拆成每小时的睡眠/鼾声分钟数，和一条连续的色带（睡着 / 鼾声 / 醒了）。纯规则，界面只负责画。
 */
object NightDays {
    /** 一小时里至少睡了这么久，这根柱子就画成睡眠柱 */
    const val SLEEP_HOUR_MINUTES = 30

    data class Input(
        /** 这一夜醒来那天 */
        val date: LocalDate,
        val onset: Long,
        /** 还在记录的一夜传当前时间 */
        val wake: Long,
        val events: List<NightEvent>,
        val live: Boolean = false,
    )

    enum class Kind { Sleep, Snore, Awake }
    data class Part(val start: Long, val end: Long, val kind: Kind, val date: LocalDate, val live: Boolean = false)
    data class Hour(val sleepMinutes: Int, val snoreMinutes: Int, val talk: Boolean, val date: LocalDate?) {
        val asleep: Boolean get() = sleepMinutes >= SLEEP_HOUR_MINUTES
    }
    data class Day(val hours: List<Hour>, val parts: List<Part>) {
        /** 点色带/柱子时：这个时刻属于哪一夜（醒来那天）；不在睡眠里为 null */
        fun nightAt(at: Long): LocalDate? = parts.firstOrNull { at >= it.start && at < it.end && it.kind != Kind.Awake }?.date
    }

    /**
     * [hourStarts] 是这一天 25 个整点（含次日 0 点），由平台按时区算好，夏令时那天也对。
     * 被用户点了“不是”的鼾声不算；醒来的那段从睡眠里切开。
     */
    fun build(hourStarts: List<Long>, nights: List<Input>): Day? {
        require(hourStarts.size == 25)
        val dayStart = hourStarts.first(); val dayEnd = hourStarts.last()
        val parts = mutableListOf<Part>()
        for (n in nights) {
            val a = maxOf(n.onset, dayStart); val b = minOf(n.wake, dayEnd)
            if (b <= a) continue
            val awake = n.events.filter { it.kind == NightEventKind.Awake }.map { maxOf(it.start, a) to minOf(it.end, b) }.filter { it.second > it.first }.sortedBy { it.first }
            val snore = n.events.filter { it.kind == NightEventKind.Snore && it.feedback != Feedback.No }
                .map { maxOf(it.start, a) to minOf(it.end, b) }.filter { it.second > it.first }.sortedBy { it.first }
            // 先按醒来切出睡着的几段，再在每段里按鼾声切
            var cursor = a
            val asleep = mutableListOf<Pair<Long, Long>>()
            for ((s, e) in awake) {
                if (s > cursor) asleep += cursor to s
                parts += Part(s, e, Kind.Awake, n.date, n.live)
                cursor = maxOf(cursor, e)
            }
            if (cursor < b) asleep += cursor to b
            for ((s, e) in asleep) {
                var c = s
                for ((ss, se) in snore) {
                    val x = maxOf(ss, s); val y = minOf(se, e)
                    if (y <= x) continue
                    if (x > c) parts += Part(c, x, Kind.Sleep, n.date, n.live)
                    parts += Part(x, y, Kind.Snore, n.date, n.live)
                    c = maxOf(c, y)
                }
                if (c < e) parts += Part(c, e, Kind.Sleep, n.date, n.live)
            }
        }
        if (parts.isEmpty()) return null
        parts.sortBy { it.start }
        val talks = nights.flatMap { n -> n.events.filter { it.kind == NightEventKind.SleepTalk } }
        val hours = (0 until 24).map { h ->
            val hs = hourStarts[h]; val he = hourStarts[h + 1]
            fun overlap(p: Part) = (minOf(p.end, he) - maxOf(p.start, hs)).coerceAtLeast(0)
            val sleep = parts.filter { it.kind != Kind.Awake }.sumOf(::overlap)
            val snoreMs = parts.filter { it.kind == Kind.Snore }.sumOf(::overlap)
            val date = parts.filter { it.kind != Kind.Awake && overlap(it) > 0 }.maxByOrNull(::overlap)?.date
            Hour((sleep / 60_000).toInt(), (snoreMs / 60_000).toInt(), talks.any { it.start in hs until he }, date)
        }
        return Day(hours, parts)
    }
}
