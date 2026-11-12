@file:OptIn(kotlin.time.ExperimentalTime::class)
package app.resound.topic.core
import app.resound.topic.*

import kotlinx.datetime.*
import kotlin.time.Instant

object TopicPolicy {
    const val LOOK_BACK = 120_000L
    const val MAX_DURATION = 4 * 60 * 60_000L
    const val QUIET = 15 * 60_000L
    const val CONFLICT = "与已有专题时段重叠，请调整时间"
    const val GENERATION_ERROR = "专题生成失败，请重试"
    fun activeAt(topic: Topic?, at: Long): Boolean = topic != null && topic.status in setOf(TopicStatus.Running, TopicStatus.Paused) &&
        at < (topic.plannedEndAt ?: ((topic.spans.minOfOrNull { it.startAt } ?: topic.createdAt) + MAX_DURATION))
    fun overridesQuiet(topic: Topic?, at: Long): Boolean = activeAt(topic, at) && topic!!.status == TopicStatus.Running &&
        topic.spans.any { it.endAt == null && it.startAt <= at }
    fun mask(days: Set<DayOfWeek>): Int? = days.fold(0) { m, d -> m or (1 shl (d.isoDayNumber - 1)) }.takeIf { it != 0 }
    fun days(mask: Int?): Set<DayOfWeek> = DayOfWeek.entries.filter { (mask ?: 0) and (1 shl (it.isoDayNumber - 1)) != 0 }.toSet()
    fun words(name: String?): List<String> = name.orEmpty().split(Regex("[\\s\\p{P}\\p{S}]+"))
        .filter { codePointCount(it) >= 2 }.distinct()
    fun next(start: Long, days: Set<DayOfWeek>, after: Long, zone: TimeZone): Long? {
        if (days.isEmpty()) return null
        val local = Instant.fromEpochMilliseconds(start).toLocalDateTime(zone)
        var date = Instant.fromEpochMilliseconds(maxOf(start, after)).toLocalDateTime(zone).date
        repeat(9) {
            val candidate = date.atTime(local.time).toInstant(zone).toEpochMilliseconds()
            if (date.dayOfWeek in days && candidate > start && candidate > after) return candidate
            date = date.plus(1, DateTimeUnit.DAY)
        }
        return null
    }
    fun overlaps(a: Long, b: Long, c: Long, d: Long) = a < d && c < b
    fun conflicts(start: Long, end: Long, repeat: Set<DayOfWeek>, groups: List<TopicGroup>, zone: TimeZone): Boolean {
        fun occurrences(first: Long, until: Long, days: Set<DayOfWeek>, around: Long): List<Pair<Long, Long>> {
            val duration = until - first
            if (days.isEmpty()) return listOf(first to until)
            // 周期冲突以双方较晚的起点为锚，检查完整两周（含跨午夜与夏令时边界）。
            val lower = maxOf(first, around - duration - 8 * 86_400_000L)
            val result = mutableListOf(first to until)
            var cursor = next(first, days, lower - 1, zone)
            val upper = maxOf(first, around) + duration + 15 * 86_400_000L
            while (cursor != null && cursor < upper) {
                result += cursor to (cursor + duration)
                cursor = next(first, days, cursor, zone)
            }
            return result
        }
        return groups.filter { it.row.status != "Cancelled" }.any { group ->
            val otherRepeat = if (group.row.status == "Scheduled") days(group.row.repeatRule) else emptySet()
            val spans = group.spans.map { it.startAt to (it.endAt ?: group.deadline()) }
            spans.any { (a, b) ->
                val anchor = maxOf(start, a)
                val mine = occurrences(start, end, repeat, anchor)
                val theirs = occurrences(a, b, otherRepeat, anchor)
                mine.any { (c, d) -> theirs.any { (e, f) -> overlaps(c, d, e, f) } }
            }
        }
    }
    fun changed(item: app.resound.digest.core.MemoryItemData) = item.deleted || item.status != "Pending" ||
        item.statusChangedAt != null || item.editedAt != null || item.noMerge || item.strongReminder
}

private fun codePointCount(text: String): Int {
 var i = 0; var count = 0
 while (i < text.length) { val high = text[i++].isHighSurrogate(); if (high && i < text.length && text[i].isLowSurrogate()) i++; count++ }
 return count
}
