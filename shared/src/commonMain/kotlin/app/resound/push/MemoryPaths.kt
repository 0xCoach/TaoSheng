package app.resound.push

import kotlinx.datetime.*

object MemoryPaths {
    private val weekdays = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
    fun daily(date: LocalDate) = "daily/${date.toString().take(7)}/${date}-${weekdays[date.dayOfWeek.isoDayNumber - 1]}_daily-record.md"
    fun transcript(date: LocalDate) = daily(date).replace("daily/", "transcripts/").replace("_daily-record", "_transcript")
    fun week(date: LocalDate): String {
        val monday = date.minus(date.dayOfWeek.isoDayNumber - 1, DateTimeUnit.DAY)
        val sunday = monday.plus(6, DateTimeUnit.DAY)
        val year = monday.plus(3, DateTimeUnit.DAY).year
        val january4 = LocalDate(year, 1, 4)
        val firstMonday = january4.minus(january4.dayOfWeek.isoDayNumber - 1, DateTimeUnit.DAY)
        val week = firstMonday.daysUntil(monday) / 7 + 1
        fun md(d: LocalDate) = (d.month.ordinal + 1).toString().padStart(2, '0') + d.day.toString().padStart(2, '0')
        return "weekly/$year-W${week.toString().padStart(2, '0')}_${md(monday)}-${md(sunday)}_weekly-review.md"
    }
}
