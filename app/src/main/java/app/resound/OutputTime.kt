package app.resound

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** 文件、导出与推送共用；长期保存的时间始终包含年份。 */
internal class OutputTime(
    private val zone: ZoneId = ZoneId.systemDefault(),
    @Suppress("UNUSED_PARAMETER") today: LocalDate = LocalDate.now(zone),
) {
    private val clock = DateTimeFormatter.ofPattern("HH:mm", Locale.CHINA)
    private fun date(day: LocalDate) = day.format(DateTimeFormatter.ISO_LOCAL_DATE)
    private fun eventAt(at: Long): String {
        val t = Instant.ofEpochMilli(at).atZone(zone)
        return "${date(t.toLocalDate())} 周${"一二三四五六日"[t.dayOfWeek.value - 1]} ${t.format(clock)}"
    }
    fun moment(at: Long): String {
        val t = Instant.ofEpochMilli(at).atZone(zone)
        return "${date(t.toLocalDate())} ${t.format(clock)}"
    }
    fun event(start: Long, end: Long): String {
        val a = Instant.ofEpochMilli(start).atZone(zone)
        val b = Instant.ofEpochMilli(end).atZone(zone)
        return eventAt(start) + if (a.toLocalDate() == b.toLocalDate()) "–${b.format(clock)}" else "–${eventAt(end)}"
    }
}
