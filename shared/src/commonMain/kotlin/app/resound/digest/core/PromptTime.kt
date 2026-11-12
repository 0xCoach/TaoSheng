@file:OptIn(kotlin.time.ExperimentalTime::class)
package app.resound.digest.core

import kotlinx.datetime.*
import kotlin.time.Instant
import app.resound.net.Obj

internal fun clock(time: LocalTime, seconds: Boolean = false): String =
    "${time.hour.toString().padStart(2, '0')}:${time.minute.toString().padStart(2, '0')}" +
        if (seconds) ":${time.second.toString().padStart(2, '0')}" else ""
internal fun clock(ms: Long, zone: TimeZone, seconds: Boolean = false) = clock(Instant.fromEpochMilliseconds(ms).toLocalDateTime(zone).time, seconds)
/** 保持原 SMART 日期解析：月末溢出日（最多 31）落在当月最后一天，24:00 是次日零点。 */
internal fun parseSmartDateTime(text: String): LocalDateTime {
    require(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2} [0-9]{2}:[0-9]{2}").matches(text))
    val year = text.take(4).toInt(); val month = text.substring(5, 7).toInt(); val day = text.substring(8, 10).toInt()
    require(day in 1..31)
    val first = LocalDate(year, month, 1)
    val last = first.plus(1, DateTimeUnit.MONTH).minus(1, DateTimeUnit.DAY).day
    val date = LocalDate(year, month, minOf(day, last))
    val hour = text.substring(11, 13).toInt(); val minute = text.substring(14, 16).toInt()
    return if (hour == 24 && minute == 0) date.plus(1, DateTimeUnit.DAY).atTime(0, 0) else date.atTime(hour, minute)
}
/** 对齐旧 ZonedDateTime 的秒/小数省略和区域 ID 表示。 */
internal fun zonedText(ms: Long, zone: TimeZone): String {
    val instant = Instant.fromEpochMilliseconds(ms)
    val local = instant.toLocalDateTime(zone)
    val offset = zone.offsetAt(instant).toString()
    return "$local$offset" + if (zone.id == offset) "" else "[${zone.id}]"
}
internal fun takeCodePoints(text: String, count: Int): String {
    var end = 0; var n = 0
    while (end < text.length && n++ < count) {
        val high = text[end++].isHighSurrogate()
        if (high && end < text.length && text[end].isLowSurrogate()) end++
    }
    return text.take(end)
}
internal fun voiceContext(raw: String?): String? = raw?.let { runCatching {
    val j = Obj(it)
    fun optional(key: String) = if (j.isNull(key)) null else j.optString(key).takeIf(String::isNotBlank)
    Obj().put("app", j.getString("app")).put("package_name", j.getString("package_name"))
        .put("title", optional("title") ?: Obj.NULL).put("before", optional("before") ?: Obj.NULL)
        .put("after", optional("after") ?: Obj.NULL).toString()
}.getOrNull() }

object ReviewClock {
    fun cutoff(date: LocalDate, minutes: Int, zone: TimeZone): Instant =
        date.atTime(LocalTime.fromSecondOfDay(minutes.coerceIn(0, 1439) * 60)).toInstant(zone)
    fun targetDate(now: Long, minutes: Int, zone: TimeZone): LocalDate {
        val instant = Instant.fromEpochMilliseconds(now)
        val today = instant.toLocalDateTime(zone).date
        return if (instant < cutoff(today, minutes, zone)) today.minus(1, DateTimeUnit.DAY) else today
    }
}
