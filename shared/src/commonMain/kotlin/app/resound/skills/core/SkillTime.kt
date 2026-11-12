@file:OptIn(kotlin.time.ExperimentalTime::class)
package app.resound.skills.core
import kotlinx.datetime.*
import kotlin.time.Instant
val SKILL_ZONE: TimeZone = TimeZone.of("Asia/Shanghai")
fun LocalDate.toEpochDay(): Long = toEpochDays().toLong()
fun LocalDate.plusDays(days: Long) = plus(days.toInt(), DateTimeUnit.DAY)
fun LocalDate.monday() = minus(dayOfWeek.isoDayNumber - 1, DateTimeUnit.DAY)
fun LocalDate.startMillis(zone: TimeZone = SKILL_ZONE) = atStartOfDayIn(zone).toEpochMilliseconds()
fun LocalDate.atMillis(time: LocalTime, zone: TimeZone = SKILL_ZONE) = atTime(time).toInstant(zone).toEpochMilliseconds()
