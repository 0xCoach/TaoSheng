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

    /** 顺序聚类：点落在当前簇中心半径内就并入，否则另起一簇；时长够的簇算停留。 */
    fun stays(points: List<LocationPoint>): List<Stay> {
        val out = ArrayList<Stay>()
        var cluster = ArrayList<LocationPoint>()
        var cLat = 0.0; var cLng = 0.0
        fun flush() {
            if (cluster.size >= 2 && cluster.last().at - cluster.first().at >= STAY_MIN_MS)
                out += Stay(cluster.first().at, cluster.last().at, cLat, cLng, cluster.size, cluster.minOf { it.accuracy })
        }
        for (p in points) {
            if (cluster.isNotEmpty() && meters(cLat, cLng, p.lat, p.lng) <= STAY_RADIUS_M) {
                cluster += p
                cLat += (p.lat - cLat) / cluster.size; cLng += (p.lng - cLng) / cluster.size
            } else {
                flush()
                cluster = arrayListOf(p); cLat = p.lat; cLng = p.lng
            }
        }
        flush()
        return out
    }

    fun distance(points: List<LocationPoint>): Double {
        var total = 0.0
        var last: LocationPoint? = null
        for (p in points) {
            val prev = last
            if (prev == null) { last = p; continue }
            val d = meters(prev.lat, prev.lng, p.lat, p.lng)
            if (d >= JITTER_M) { total += d; last = p }
        }
        return total
    }
}
