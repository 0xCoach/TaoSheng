@file:OptIn(kotlin.time.ExperimentalTime::class)

package app.resound.platform

import java.time.LocalDate as JavaDate
import java.time.ZoneId
import kotlinx.datetime.*
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** 同一输入对照旧 Java 日期：跨日、纽约夏令时开始/结束、星期与存储文本。 */
@RunWith(Parameterized::class)
class DateParity177Test(private val zoneId: String, private val text: String) {
    @Test fun dayBoundsWeekdayAndStoredTextMatchJava() {
        val old = JavaDate.parse(text)
        val date = LocalDate.parse(text)
        val zone = TimeZone.of(zoneId)
        val javaZone = ZoneId.of(zoneId)
        // 当天起点、次日起点、节奏统计的七天起点均用同一次取得的时区。
        for (offset in listOf(-7, 0, 1)) {
            assertEquals(old.plusDays(offset.toLong()).atStartOfDay(javaZone).toInstant().toEpochMilli(),
                date.plus(offset, DateTimeUnit.DAY).atStartOfDayIn(zone).toEpochMilliseconds())
        }
        val oldMonth = java.time.YearMonth.from(old)
        val month = YearMonth(date.year, date.month)
        for (offset in listOf(0, 1)) {
            assertEquals(oldMonth.plusMonths(offset.toLong()).atDay(1).atStartOfDay(javaZone).toInstant().toEpochMilli(),
                month.plus(offset, DateTimeUnit.MONTH).firstDay.atStartOfDayIn(zone).toEpochMilliseconds())
        }
        assertEquals(oldMonth.toString(), month.toString())
        assertEquals(old.toString(), date.toString())
        assertEquals(old.dayOfWeek.value, date.dayOfWeek.isoDayNumber)
        assertEquals(old.dayOfWeek.name, date.dayOfWeek.name)
        assertEquals(java.time.DayOfWeek.entries.map { it.value }, DayOfWeek.entries.map { it.isoDayNumber })
    }

    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0} {1}") fun cases(): List<Array<String>> =
            listOf("Asia/Shanghai", "America/New_York", "UTC").flatMap { zone ->
                listOf("2024-02-29", "2026-01-01", "2026-03-08", "2026-11-01", "2026-11-13", "2026-12-31").map { arrayOf(zone, it) }
            }
    }
}
