@file:OptIn(kotlin.time.ExperimentalTime::class)
package app.resound.platform

import kotlinx.datetime.*
import kotlinx.serialization.Serializable
import kotlin.time.Instant

@Serializable data class ZoneChange(val from: Long, val zoneId: String)
@Serializable data class ZoneSpan(val from: Long, val until: Long, val zoneId: String)

/** 日界线固定在该日零点的历史时区；日内换区不移动已经开始的一天。 */
class ZoneHistory(val changes: List<ZoneChange>, private val current: String) {
    fun zoneAt(at: Long): TimeZone = TimeZone.of(changes.lastOrNull { it.from <= at }?.zoneId ?: changes.firstOrNull()?.zoneId ?: current)
    fun record(at: Long, zoneId: String): List<ZoneChange> {
        TimeZone.of(zoneId)
        if (changes.lastOrNull()?.zoneId == zoneId) return changes
        return (changes.filter { it.from < at } + ZoneChange(at, zoneId)).takeLast(500)
    }
    fun zonesOn(date: LocalDate): List<ZoneSpan> {
        val candidates = (changes.map { it.zoneId } + current).distinct().map { TimeZone.of(it) }
        val zone = candidates.firstOrNull { z -> zoneAt(date.atStartOfDayIn(z).toEpochMilliseconds()) == z } ?: zoneAt(date.atStartOfDayIn(TimeZone.of(current)).toEpochMilliseconds())
        val start = date.atStartOfDayIn(zone).toEpochMilliseconds()
        val end = date.plus(1, DateTimeUnit.DAY).atStartOfDayIn(zone).toEpochMilliseconds()
        val offsetChanges = mutableListOf<Long>()
        var cursor = start
        while (cursor < end) {
            val next = minOf(cursor + 3_600_000L, end)
            if (offset(cursor) != offset(next - 1)) {
                var lo = cursor; var hi = next - 1
                while (hi - lo > 1) { val mid = lo + (hi - lo) / 2; if (offset(mid) == offset(cursor)) lo = mid else hi = mid }
                offsetChanges += hi
            }
            if (next < end && offset(next - 1) != offset(next)) offsetChanges += next
            cursor = next
        }
        val boundaries = (listOf(start, end) + changes.map { it.from }.filter { it > start && it < end } + offsetChanges).distinct().sorted()
        return boundaries.zipWithNext { a, b -> ZoneSpan(a, b, zoneAt(a).id) }
    }
    fun offset(at: Long): String = zoneAt(at).offsetAt(Instant.fromEpochMilliseconds(at)).toString().let { if (it == "Z") "+00:00" else it }
    fun label(at: Long): String {
        val raw = offset(at)
        val hours = raw.substring(1, 3).toInt()
        val minutes = raw.substring(4, 6)
        return "UTC" + (if (raw == "+00:00") "+" else raw.take(1)) + hours + if (minutes == "00") "" else ":$minutes"
    }
    fun clock(at: Long): String = Instant.fromEpochMilliseconds(at).toLocalDateTime(zoneAt(at)).time.toString().take(5)
    fun iso(at: Long): String {
        val local = Instant.fromEpochMilliseconds(at).toLocalDateTime(zoneAt(at))
        return "${local.date}T${clock(at)}:${local.second.toString().padStart(2, '0')}" + offset(at)
    }
    fun caption(date: LocalDate): String = zonesOn(date).mapIndexed { i, s -> if (i == 0) label(s.from) else "${clock(s.from)} 起为 ${label(s.from)}" }.joinToString("，")
}

/** 平台在启动时注入；规则本身不读取系统时间或偏好。 */
object HistoricalZones {
    var history: () -> ZoneHistory = { ZoneHistory(emptyList(), "UTC") }
}
