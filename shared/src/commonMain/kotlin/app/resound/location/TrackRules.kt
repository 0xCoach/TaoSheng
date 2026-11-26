package app.resound.location

import kotlin.math.*

/** 一段停留：同一处（半径内）待够一定时间。 */
data class Stay(val start: Long, val end: Long, val lat: Double, val lng: Double, val points: Int, val accuracy: Float) {
    val minutes: Long get() = (end - start) / 60_000
}

data class DayTrack(val points: List<LocationPoint>, val stays: List<Stay>, val meters: Double)

class TrackRules(private val radians: (Double) -> Double) {
    companion object {
    const val STAY_RADIUS_M = 150.0
    const val STAY_MIN_MS = 10 * 60_000L
    /** 精度差于此值的点不参与计算（室内偶尔会飘到几公里外）。 */
    const val MAX_ACCURACY_M = 200f
    /** 两点相距小于此值视为原地抖动，不计入移动距离。 */
    private const val JITTER_M = 30.0

    }

    fun meters(aLat: Double, aLng: Double, bLat: Double, bLng: Double): Double {
        val r = 6_371_000.0
        val dLat = radians(bLat - aLat)
        val dLng = radians(bLng - aLng)
        val h = sin(dLat / 2).pow(2) + cos(radians(aLat)) * cos(radians(bLat)) * sin(dLng / 2).pow(2)
        return 2 * r * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }

    fun day(all: List<LocationPoint>): DayTrack {
        val points = all.filter { it.accuracy <= MAX_ACCURACY_M }.sortedBy { it.at }
        return DayTrack(points, stays(points), distance(points))
    }

    /** 每个候选停留只保存中心、时间边界和最低误差，无需保留簇内的点列表。 */
    fun stays(points: List<LocationPoint>): List<Stay> {
        val pending = points.iterator()
        if (!pending.hasNext()) return emptyList()
        val first = pending.next()
        return buildList {
            var start = first.at
            var end = first.at
            var lat = first.lat
            var lng = first.lng
            var accuracy = first.accuracy
            var count = 1

            fun emit() {
                if (count > 1 && end - start >= STAY_MIN_MS) {
                    add(Stay(start, end, lat, lng, count, accuracy))
                }
            }

            while (pending.hasNext()) {
                val location = pending.next()
                if (meters(lat, lng, location.lat, location.lng) <= STAY_RADIUS_M) {
                    count += 1
                    end = location.at
                    lat += (location.lat - lat) / count
                    lng += (location.lng - lng) / count
                    accuracy = minOf(accuracy, location.accuracy)
                } else {
                    emit()
                    start = location.at
                    end = location.at
                    lat = location.lat
                    lng = location.lng
                    accuracy = location.accuracy
                    count = 1
                }
            }
            emit()
        }
    }

    fun distance(points: List<LocationPoint>): Double {
        val pending = points.iterator()
        if (!pending.hasNext()) return 0.0
        var anchor = pending.next()
        var traveled = 0.0
        while (pending.hasNext()) {
            val next = pending.next()
            val length = meters(anchor.lat, anchor.lng, next.lat, next.lng)
            if (length >= JITTER_M) {
                anchor = next
                traveled += length
            }
        }
        return traveled
    }
}
