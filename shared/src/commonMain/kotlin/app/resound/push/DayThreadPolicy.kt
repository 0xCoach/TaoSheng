package app.resound.push

import kotlinx.datetime.*

/** 定稿日包含后续 3 个当地日期；首次补最近 7 天，绝不生成当天。 */
object DayThreadPolicy {
    fun dates(today: LocalDate, hour: Int, first: Boolean): List<LocalDate> =
        if (hour < 4) emptyList() else (1..if (first) 7 else 3).map { today.minus(it, DateTimeUnit.DAY) }
    fun mutable(date: LocalDate, today: LocalDate) = date < today && date >= today.minus(3, DateTimeUnit.DAY)
    fun eligible(kind: PushKind, s: PushSubscription) = kind in setOf(PushKind.MarkdownFolder, PushKind.Notion, PushKind.Webhook) && s.records.isNotEmpty()
}
