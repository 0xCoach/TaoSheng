@file:OptIn(kotlin.time.ExperimentalTime::class)
package app.resound.skills.core
import kotlinx.datetime.*
import kotlin.time.Instant
fun SKILL_ZONE(at: Long): TimeZone = app.resound.platform.HistoricalZones.history().zoneAt(at)
fun SKILL_ZONE(date: LocalDate): TimeZone = TimeZone.of(app.resound.platform.HistoricalZones.history().zonesOn(date).first().zoneId)
fun LocalDate.toEpochDay(): Long = toEpochDays().toLong()
fun LocalDate.plusDays(days: Long) = plus(days.toInt(), DateTimeUnit.DAY)
fun LocalDate.monday() = minus(dayOfWeek.isoDayNumber - 1, DateTimeUnit.DAY)
fun LocalDate.startMillis(zone: TimeZone = SKILL_ZONE(this)) = atStartOfDayIn(zone).toEpochMilliseconds()
fun LocalDate.endMillis() = app.resound.platform.HistoricalZones.history().zonesOn(this).last().until
fun LocalDate.atMillis(time: LocalTime, zone: TimeZone? = null): Long {
    if (zone != null) return atTime(time).toInstant(zone).toEpochMilliseconds()
    val spans = app.resound.platform.HistoricalZones.history().zonesOn(this)
    return spans.firstNotNullOfOrNull { span ->
        atTime(time).toInstant(TimeZone.of(span.zoneId)).toEpochMilliseconds().takeIf { it >= span.from && it < span.until }
    } ?: atTime(time).toInstant(SKILL_ZONE(this)).toEpochMilliseconds()
}
