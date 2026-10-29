package app.resound.capture

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** 星期属于开始日；先合并重叠/相接区间，再判断边界。 */
object QuietRules {
    private data class Span(val start: Long, val end: Long, val rule: QuietRule)
    private fun spans(rules: List<QuietRule>, first: LocalDate, last: LocalDate, zone: ZoneId): List<Span> {
        val raw = mutableListOf<Span>()
        var date = first
        while (!date.isAfter(last)) {
            for (rule in rules) {
                if (!rule.enabled || date.dayOfWeek !in rule.days) continue
                val start = date.atStartOfDay().plusMinutes(rule.startMinute.toLong()).atZone(zone).toInstant().toEpochMilli()
                val endDate = if (rule.endMinute <= rule.startMinute) date.plusDays(1) else date
                val end = endDate.atStartOfDay().plusMinutes(rule.endMinute.toLong()).atZone(zone).toInstant().toEpochMilli()
                if (end > start) raw += Span(start, end, rule)
            }
            date = date.plusDays(1)
        }
        val merged = mutableListOf<Span>()
        for (span in raw.sortedBy { it.start }) {
            val previous = merged.lastOrNull()
            if (previous != null && span.start <= previous.end) merged[merged.lastIndex] = previous.copy(end = maxOf(previous.end, span.end))
            else merged += span
        }
        return merged
    }
    private fun nearby(rules: List<QuietRule>, now: Long, zone: ZoneId): List<Span> {
        val date = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        return spans(rules, date.minusDays(8), date.plusDays(15), zone).map {
            // 周期内无任何间隙（例如每天 0:00–0:00）意味着持续停录，没有下一次离开边界。
            if (it.start < date.minusDays(7).atStartOfDay(zone).toInstant().toEpochMilli() &&
                it.end > date.plusDays(7).atStartOfDay(zone).toInstant().toEpochMilli()) it.copy(end = Long.MAX_VALUE) else it
        }
    }
    fun windowAt(rules: List<QuietRule>, now: Long, zone: ZoneId): QuietWindow? =
        nearby(rules, now, zone).firstOrNull { now >= it.start && now < it.end }?.let { QuietWindow(it.rule, it.end) }

    fun nextChange(rules: List<QuietRule>, now: Long, zone: ZoneId): Long? =
        nearby(rules, now, zone).firstNotNullOfOrNull {
            when { now < it.start -> it.start; now < it.end -> it.end.takeUnless { end -> end == Long.MAX_VALUE }; else -> null }
        }

    /** 恢复时只把本来应该录音的缺口记为被杀漏录，排除计划停录与手动暂停。 */
    internal fun recordingGaps(rules: List<QuietRule>, from: Long, until: Long, zone: ZoneId, manualUntil: Long, skipUntil: Long): List<Pair<Long, Long>> {
        var cursor = maxOf(from, manualUntil).coerceAtMost(until)
        if (cursor >= until) return emptyList()
        val first = Instant.ofEpochMilli(cursor).atZone(zone).toLocalDate().minusDays(1)
        val last = Instant.ofEpochMilli(until).atZone(zone).toLocalDate()
        val result = mutableListOf<Pair<Long, Long>>()
        for (span in spans(rules, first, last, zone)) {
            val start = if (skipUntil > span.start && skipUntil <= span.end) skipUntil else span.start
            if (span.end <= start || span.end <= cursor) continue
            if (start > cursor) result += cursor to minOf(start, until)
            cursor = maxOf(cursor, span.end).coerceAtMost(until)
            if (cursor >= until) break
        }
        if (cursor < until) result += cursor to until
        return result
    }
}

internal data class CapturePausePlan(val until: Long, val quietName: String?)
internal fun capturePausePlan(now: Long, manualUntil: Long, quiet: QuietWindow?): CapturePausePlan? {
    val quietUntil = quiet?.endsAt ?: 0
    val until = maxOf(manualUntil, quietUntil)
    if (until <= now) return null
    return CapturePausePlan(until, quiet?.rule?.name?.takeIf { quietUntil > manualUntil })
}
