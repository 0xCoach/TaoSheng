package app.resound.review

import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/** 日回顾按设置的本地时刻切日，窗口为（上一日截止时刻，当日截止时刻]。 */
internal object ReviewTime {
    fun cutoff(date: LocalDate, minutes: Int, zone: ZoneId): ZonedDateTime =
        date.atTime(LocalTime.ofSecondOfDay(minutes.coerceIn(0, 1439) * 60L)).atZone(zone)

    fun targetDate(now: ZonedDateTime, minutes: Int): LocalDate {
        val today = now.toLocalDate()
        return if (now.isBefore(cutoff(today, minutes, now.zone))) today.minusDays(1) else today
    }
}
