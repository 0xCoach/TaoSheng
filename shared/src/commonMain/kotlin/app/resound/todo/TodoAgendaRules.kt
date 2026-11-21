@file:OptIn(kotlin.time.ExperimentalTime::class)
package app.resound.todo

import app.resound.digest.ItemStatus
import app.resound.digest.MemoryItem
import app.resound.digest.hasTime
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.atTime
import kotlinx.datetime.daysUntil
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/**
 * 待办“日历视图”的纯规则（安卓、iOS 共用）：
 * 过期收在最上面；今天起 7 天逐天列；再往后合并成“下周”“本月晚些时候”“12 月”；没日子的归“随时做”。
 * 待确认但有日子的，也放进对应那天（界面画成虚线）。
 */
object TodoAgendaRules {
    const val WINDOW_DAYS = 7

    data class Day(val date: LocalDate, val items: List<MemoryItem>, val pending: List<MemoryItem>) {
        val empty get() = items.isEmpty() && pending.isEmpty()
    }

    /** 一周以后的合并组；key 稳定，界面用来记展开状态 */
    data class Later(val key: String, val title: String, val range: String?, val first: LocalDate, val items: List<MemoryItem>, val pending: List<MemoryItem>)

    data class Agenda(
        val overdue: List<MemoryItem>,
        val days: List<Day>,
        val later: List<Later>,
        val anytime: List<MemoryItem>,
    )

    /** 日历上某天的标记：几件（界面最多画 3 个点）、有没有过期、有没有待确认 */
    data class Mark(val count: Int, val overdue: Boolean, val pending: Boolean)

    fun day(at: Long, zone: TimeZone): LocalDate = Instant.fromEpochMilliseconds(at).toLocalDateTime(zone).date

    /** 只定了哪天的，过了那一天才算过期；定到几点的，过了那个时刻就算 */
    fun isOverdue(item: MemoryItem, now: Long, zone: TimeZone): Boolean {
        val due = item.dueAt ?: return false
        if (item.status != ItemStatus.Accepted) return false
        return if (item.hasTime) due < now else day(due, zone) < day(now, zone)
    }

    fun build(ongoing: List<MemoryItem>, pending: List<MemoryItem>, now: Long, zone: TimeZone): Agenda {
        val today = day(now, zone)
        val overdue = ongoing.filter { isOverdue(it, now, zone) }.sortedBy { it.dueAt }
        val dated = ongoing.filter { it.dueAt != null && it !in overdue }
        val pendingDated = pending.filter { it.dueAt != null && day(it.dueAt!!, zone) >= today }
        fun at(list: List<MemoryItem>, d: LocalDate) = list.filter { day(it.dueAt!!, zone) == d }.sortedBy { it.dueAt }
        val days = (0 until WINDOW_DAYS).map { today.plus(it, DateTimeUnit.DAY) }.map { d -> Day(d, at(dated, d), at(pendingDated, d)) }
        val end = today.plus(WINDOW_DAYS, DateTimeUnit.DAY)
        val beyond = dated.filter { day(it.dueAt!!, zone) >= end }
        val beyondPending = pendingDated.filter { day(it.dueAt!!, zone) >= end }
        val later = (beyond + beyondPending).groupBy { laterKey(day(it.dueAt!!, zone), today) }
            .map { (key, list) ->
                val first = list.minOf { day(it.dueAt!!, zone) }
                val (title, range) = laterTitle(key, today)
                Later(key, title, range, first, list.filter { it.status != ItemStatus.Pending }.sortedBy { it.dueAt },
                    list.filter { it.status == ItemStatus.Pending }.sortedBy { it.dueAt })
            }.sortedBy { it.first }
        val anytime = ongoing.filter { it.dueAt == null }
        return Agenda(overdue, days, later, anytime)
    }

    /** 今天起第 8～14 天算“下周”；再往后按月 */
    fun laterKey(d: LocalDate, today: LocalDate): String {
        val n = today.daysUntil(d)
        return when {
            n < WINDOW_DAYS * 2 -> "next"
            else -> "m-${d.year}-${d.month.ordinal + 1}"
        }
    }

    fun laterTitle(key: String, today: LocalDate): Pair<String, String?> {
        if (key == "next") {
            val a = today.plus(WINDOW_DAYS, DateTimeUnit.DAY)
            val b = today.plus(WINDOW_DAYS * 2 - 1, DateTimeUnit.DAY)
            return "下周" to "${md(a)}—${md(b)}"
        }
        val (y, m) = key.removePrefix("m-").split('-').map { it.toInt() }
        val title = when {
            y == today.year && m == today.month.ordinal + 1 -> "本月晚些时候"
            y == today.year -> "$m 月"
            else -> "${y}年${m}月"
        }
        return title to null
    }

    fun marks(ongoing: List<MemoryItem>, pending: List<MemoryItem>, now: Long, zone: TimeZone): Map<LocalDate, Mark> {
        val all = ongoing.filter { it.dueAt != null }.map { it to false } + pending.filter { it.dueAt != null }.map { it to true }
        return all.groupBy { day(it.first.dueAt!!, zone) }.mapValues { (_, list) ->
            Mark(list.count { !it.second }, list.any { !it.second && isOverdue(it.first, now, zone) }, list.any { it.second })
        }
    }

    /** “全部改到今天”：定到几点且今天那个时刻还没到的保留时刻，否则只改成今天（不定几点） */
    fun moveToToday(item: MemoryItem, now: Long, zone: TimeZone): Pair<Long, Boolean> {
        val today = day(now, zone)
        if (item.hasTime) {
            val time = Instant.fromEpochMilliseconds(item.dueAt!!).toLocalDateTime(zone).time
            val at = today.atTime(time).toInstant(zone).toEpochMilliseconds()
            if (at > now) return at to true
        }
        return today.atStartOfDayIn(zone).toEpochMilliseconds() to false
    }

    /** 中文星期 */
    fun weekday(d: LocalDate): String = "一二三四五六日"[d.dayOfWeek.isoDayNumber - 1].let { "周$it" }

    /** 日期标题右边的小字：11月19日 周三 · 3 天后 */
    fun dayCaption(d: LocalDate, today: LocalDate): String {
        val n = today.daysUntil(d)
        val rel = when (n) { 0 -> null; 1 -> null; 2 -> "后天"; else -> if (n > 0) "$n 天后" else "${-n} 天前" }
        return listOfNotNull(md(d) + " " + weekday(d), rel).joinToString(" · ")
    }

    /** 日期标题左边的大字：今天 / 明天 / 19 */
    fun dayTitle(d: LocalDate, today: LocalDate): String = when (today.daysUntil(d)) {
        0 -> "今天"; 1 -> "明天"; else -> "${d.day}"
    }

    private fun md(d: LocalDate) = "${d.month.ordinal + 1}月${d.day}日"
}

/** 加到系统日历用的日程草稿：标题、备注、全天与否、起止时间 */
data class CalendarDraft(val title: String, val notes: String, val allDay: Boolean, val begin: Long, val end: Long)

object CalendarDraftRules {
    /** 没日子的返回 null（界面先让用户选日期，再用 [forDay]） */
    fun from(item: MemoryItem, zone: TimeZone): CalendarDraft? {
        val due = item.dueAt ?: return null
        return if (item.hasTime) CalendarDraft(item.content, notes(item), false, due, due + 3_600_000L)
        else forDay(item, TodoAgendaRules.day(due, zone), zone)
    }

    fun forDay(item: MemoryItem, date: LocalDate, zone: TimeZone): CalendarDraft {
        val start = date.atStartOfDayIn(zone).toEpochMilliseconds()
        return CalendarDraft(item.content, notes(item), true, start, date.plus(1, DateTimeUnit.DAY).atStartOfDayIn(zone).toEpochMilliseconds())
    }

    fun notes(item: MemoryItem): String = buildList {
        val type = item.kind ?: item.category
        add("来自声声不息 · $type")
        item.who?.takeIf { it.isNotBlank() && it != "我" }?.let { add("和谁：$it") }
        item.dueText?.takeIf { it.isNotBlank() }?.let { add("原定时间：$it") }
    }.joinToString("\n")

}
