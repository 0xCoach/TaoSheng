@file:OptIn(kotlin.time.ExperimentalTime::class)
package app.resound.review
import java.time.*
import kotlinx.datetime.toKotlinLocalDate
import kotlinx.datetime.toJavaLocalDate
import app.resound.digest.sharedZone
import app.resound.digest.core.ReviewClock
internal object ReviewTime {
    fun cutoff(date: LocalDate, minutes: Int, zone: ZoneId): ZonedDateTime = Instant.ofEpochMilli(ReviewClock.cutoff(date.toKotlinLocalDate(), minutes, zone.sharedZone()).toEpochMilliseconds()).atZone(zone)
    fun targetDate(now: ZonedDateTime, minutes: Int): LocalDate = ReviewClock.targetDate(now.toInstant().toEpochMilli(), minutes, now.zone.sharedZone()).toJavaLocalDate()
}
