package app.resound.screentime

/**
 * 屏幕时间界面用的纯规则：按小时分钟数、某段时间“当时在用”、时间线合并、近几天平均。
 * 区间一律左闭右开、纪元毫秒。平台无关，iOS 可直接复用。
 */
object ScreenTimeView {
    /** 某件事“当时在用”的 App：重叠时长最长的那个。 */
    data class InUse(val pkg: String, val name: String, val minutes: Int)

    /** 时间线一行：同一个 App 挨着的几段合成一段；[minutes] 只算实际用的时长。 */
    data class TimelineRow(val pkg: String, val name: String, val from: Long, val until: Long, val minutes: Int)

    /** 至少用了这么久才算“当时在用”。 */
    const val IN_USE_MIN_MILLIS = 2 * 60_000L

    /** 同一个 App 相隔不超过这么久就在时间线上合成一段。 */
    const val TIMELINE_JOIN_MILLIS = 2 * 60_000L

    /** 时间线上短于这个时长的段不单独列出。 */
    const val TIMELINE_MIN_MILLIS = 60_000L

    /**
     * [bounds] 是 25 个整点时刻（当天 0 点到次日 0 点），返回每小时用手机的分钟数（四舍五入，最多 60）。
     * 由调用方按时区算整点，夏令时那天也能对上。
     */
    fun minutesByHour(sessions: List<NamedAppSession>, bounds: List<Long>): List<Int> {
        require(bounds.size == 25) { "bounds 需要 25 个整点" }
        val millis = LongArray(24)
        for (s in sessions) for (h in 0 until 24) {
            val overlap = minOf(s.until, bounds[h + 1]) - maxOf(s.from, bounds[h])
            if (overlap > 0) millis[h] += overlap
        }
        return millis.map { ((it + 30_000L) / 60_000L).toInt().coerceAtMost(60) }
    }

    /**
     * [from, until) 这件事发生时用得最久的 App。重叠不到 [IN_USE_MIN_MILLIS]、
     * 或不到这件事时长的五分之一时不算（只是瞄了一眼手机）。
     */
    fun inUse(sessions: List<NamedAppSession>, from: Long, until: Long): InUse? {
        if (until <= from) return null
        val byPkg = HashMap<String, Long>()
        val names = HashMap<String, String>()
        for (s in sessions) {
            val overlap = minOf(s.until, until) - maxOf(s.from, from)
            if (overlap <= 0) continue
            byPkg[s.pkg] = (byPkg[s.pkg] ?: 0L) + overlap
            names.getOrPut(s.pkg) { s.name }
        }
        val best = byPkg.entries.sortedWith(compareByDescending<Map.Entry<String, Long>> { it.value }.thenBy { it.key }).firstOrNull() ?: return null
        val enough = maxOf(IN_USE_MIN_MILLIS, (until - from) / 5)
        if (best.value < enough) return null
        return InUse(best.key, names.getValue(best.key), ((best.value + 30_000L) / 60_000L).toInt())
    }

    /** [from, until) 里各 App 用了几分钟：时长降序，同时长按包名；不足半分钟的不列。 */
    fun appsDuring(sessions: List<NamedAppSession>, from: Long, until: Long): List<AppMinutes> {
        val byPkg = LinkedHashMap<String, Pair<String, Long>>()
        for (s in sessions) {
            val overlap = minOf(s.until, until) - maxOf(s.from, from)
            if (overlap <= 0) continue
            val old = byPkg[s.pkg]
            byPkg[s.pkg] = (old?.first ?: s.name) to ((old?.second ?: 0L) + overlap)
        }
        return byPkg.map { (pkg, v) -> AppMinutes(pkg, v.first, ((v.second + 30_000L) / 60_000L).toInt()) }
            .filter { it.minutes > 0 }
            .sortedWith(compareByDescending<AppMinutes> { it.minutes }.thenBy { it.pkg })
    }

    /**
     * 时间线：按开始时间排好，同一个 App 相隔不超过 [TIMELINE_JOIN_MILLIS] 的合成一段，
     * 合完仍短于 [TIMELINE_MIN_MILLIS] 的不列。新的在前。
     */
    fun timeline(sessions: List<NamedAppSession>): List<TimelineRow> {
        val out = mutableListOf<TimelineRow>()
        var used = 0L
        for (s in sessions.sortedBy { it.from }) {
            val last = out.lastOrNull()
            val len = s.until - s.from
            if (last != null && last.pkg == s.pkg && s.from - last.until <= TIMELINE_JOIN_MILLIS) {
                used += len
                out[out.lastIndex] = last.copy(until = maxOf(last.until, s.until), minutes = minutes(used))
            } else {
                used = len
                out += TimelineRow(s.pkg, s.name, s.from, s.until, minutes(used))
            }
        }
        return out.filter { it.until - it.from >= TIMELINE_MIN_MILLIS && it.minutes > 0 }.reversed()
    }

    /** 有数据的那几天的平均分钟数；一天都没有时为 null（刚打开屏幕时间的头几天不拿 0 去比）。 */
    fun averageMinutes(dayMinutes: List<Int>): Int? {
        val days = dayMinutes.filter { it > 0 }
        if (days.isEmpty()) return null
        return (days.sum().toDouble() / days.size + 0.5).toInt()
    }

    /** “比近 7 天平均多 18 分”这一类对比；差不到 1 分钟时说“和近 7 天平均差不多”。 */
    fun compare(minutes: Int, average: Int?, label: String): Compare? {
        average ?: return null
        val diff = minutes - average
        return Compare(diff, if (diff == 0) "和${label}差不多" else "比${label}" + (if (diff > 0) "多" else "少"))
    }

    data class Compare(val diffMinutes: Int, val text: String)

    private fun minutes(ms: Long) = ((ms + 30_000L) / 60_000L).toInt()
}
