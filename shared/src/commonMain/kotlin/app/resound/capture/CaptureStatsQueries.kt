@file:OptIn(kotlin.time.ExperimentalTime::class)
package app.resound.capture

import app.resound.data.CaptureDao
import app.resound.platform.ZoneProvider
import kotlinx.coroutines.flow.*
import kotlinx.datetime.*

/** 混合文件约定之外的共用查询和区间统计。 */
class CaptureStatsQueries(private val dao: CaptureDao, private val zoneProvider: ZoneProvider) {
    fun bounds(date: LocalDate): Pair<Long, Long> {
        val zone = TimeZone.of(zoneProvider.zoneId())
        return date.atStartOfDayIn(zone).toEpochMilliseconds() to date.plus(1, DateTimeUnit.DAY).atStartOfDayIn(zone).toEpochMilliseconds()
    }
    fun segments(start: Long, end: Long) = dao.segments(start, end)
    fun watchdogEvents(start: Long, end: Long, knownTypes: Set<String>) = dao.events(start, end).map { rows ->
        rows.filter { it.at in start until end && it.type in knownTypes }
    }
    fun dayStats(a: Long, b: Long): Flow<CaptureDayStats> {
        fun overlap(start: Long, end: Long) = (minOf(b, end) - maxOf(a, start)).coerceAtLeast(0)
        return combine(dao.segments(a, b), dao.events(a, b), dao.heartbeats(a, b), dao.batteries(a, b), dao.appPowerSamples(a, b)) { segments, events, heartbeats, batteries, power ->
            val discharge = batteries.zipWithNext().filter { (x, y) ->
                val xPercent = x.percent; val yPercent = y.percent
                x.charging == false && y.charging == false && xPercent != null && yPercent != null &&
                    yPercent <= xPercent && y.at > x.at && y.at - x.at <= CaptureTuning.BATTERY_MS * 2
            }
            val hours = discharge.sumOf { (x, y) -> y.at - x.at } / 3_600_000.0
            CaptureDayStats(segments.count { it.startAt in a until b }, segments.sumOf { overlap(it.startAt, it.endAt) },
                heartbeats.filter { it.running }.sumOf { overlap(it.intervalStart, it.at) },
                events.count { it.type == "Killed" && it.at in a until b },
                events.count { it.type == "Recovered" && it.detail?.contains("system-sticky") == true && it.at in a until b },
                events.filter { it.type == "Killed" }.sumOf { overlap(it.gapStart ?: it.at, it.gapEnd ?: it.at) },
                if (hours > 0) (discharge.sumOf { (x, y) -> x.percent!! - y.percent!! } / hours).toFloat() else null,
                appPowerPerHour = AppPowerModel.perHour(power, a, b),
                storageBytes = segments.filter { it.startAt in a until b }.sumOf { it.sizeBytes })
        }
    }
}

data class CaptureDayStats(
    val segmentCount: Int, val speechMillis: Long, val runningMillis: Long,
    val killedCount: Int, val recoveredCount: Int, val gapMillis: Long,
    val dischargePerHour: Float?, val storageBytes: Long, val appPowerPerHour: Float?,
)
