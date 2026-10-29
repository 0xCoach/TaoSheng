package app.resound

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** 文件、导出与推送共用；当前年份按展示时区计算。 */
internal class OutputTime(
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val today: LocalDate = LocalDate.now(zone),
) {
    private val clock = DateTimeFormatter.ofPattern("HH:mm", Locale.CHINA)
    private fun date(day: LocalDate) =
        (if (day.year == today.year) "" else "${day.year}年") + "${day.monthValue}月${day.dayOfMonth}日"
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
