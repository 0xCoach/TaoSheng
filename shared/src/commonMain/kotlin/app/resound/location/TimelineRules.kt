package app.resound.location

/** 同一处（半径内）的多段停留合成一个地点；home=夜里待得最久的那处。 */
data class PlaceGroup(val index: Int, val lat: Double, val lng: Double, val stays: List<Stay>, val home: Boolean) {
    val millis: Long get() = stays.sumOf { it.end - it.start }
}

enum class SpanKind { Stay, Move, Gap }

/** 时间轴上的一段：停留（属于某个地点）、路上、没有记录。 */
data class Span(val start: Long, val end: Long, val kind: SpanKind, val group: Int? = null)

data class DayTimeline(val spans: List<Span>, val groups: List<PlaceGroup>) {
    val homeMillis: Long get() = groups.filter { it.home }.sumOf { it.millis }
    /** 有记录、但不在家的时间（含路上）。 */
    val awayMillis: Long get() = spans.filter { it.kind != SpanKind.Gap }.sumOf { it.end - it.start } - homeMillis
    fun groupOf(stay: Stay): PlaceGroup? = groups.firstOrNull { stay in it.stays }
}

class TimelineRules(private val meters: (Double, Double, Double, Double) -> Double) {
    companion object {
    /** 相邻两点隔得比这久，就当这段没有记录（收音停了、手机关机等）。 */
    const val GAP_MS = 20 * 60_000L
    private const val HOUR = 3_600_000L

    }

    private data class VisitedPlace(
        val lat: Double, val lng: Double, val stays: List<Stay>, val duration: Long, val nightDuration: Long,
    )

    fun groups(stays: List<Stay>, dayStart: Long, isHome: (Double, Double) -> Boolean = { _, _ -> false }): List<PlaceGroup> {
        val byLocation = ArrayList<MutableList<Stay>>()
        for (stay in stays) {
            val group = byLocation.firstOrNull { candidates ->
                val first = candidates[0]
                meters(first.lat, first.lng, stay.lat, stay.lng) <= TrackRules.STAY_RADIUS_M
            }
            if (group == null) byLocation.add(arrayListOf(stay)) else group.add(stay)
        }
        val places = byLocation.map { group ->
            var latitude = 0.0
            var longitude = 0.0
            var duration = 0L
            var night = 0L
            for (stay in group) {
                latitude += stay.lat
                longitude += stay.lng
                duration += stay.end - stay.start
                night += overlap(stay.start, stay.end, dayStart, dayStart + 6 * HOUR) +
                    overlap(stay.start, stay.end, dayStart + 22 * HOUR, dayStart + 24 * HOUR)
            }
            VisitedPlace(latitude / group.size, longitude / group.size, group, duration, night)
        }
        val home = places.firstOrNull { isHome(it.lat, it.lng) }
            ?: places.maxByOrNull { it.nightDuration }?.takeIf { it.nightDuration >= HOUR }
        return places.sortedByDescending { it.duration }.mapIndexed { index, place ->
            PlaceGroup(index, place.lat, place.lng, place.stays, place === home)
        }
    }

    /** points 已按时间排好序。to 一般是当天结束，今天则传“现在”。 */
    fun build(points: List<LocationPoint>, stays: List<Stay>, from: Long, to: Long, isHome: (Double, Double) -> Boolean = { _, _ -> false }): DayTimeline {
        val places = groups(stays, from, isHome)
        if (to <= from) return DayTimeline(emptyList(), places)
        if (points.isEmpty()) return DayTimeline(listOf(Span(from, to, SpanKind.Gap)), places)
        val spans = ArrayList<Span>()
        fun append(start: Long, end: Long, kind: SpanKind, group: Int? = null) {
            val clippedFrom = maxOf(from, start)
            val clippedUntil = minOf(to, end)
            if (clippedUntil <= clippedFrom) return
            val previous = spans.lastOrNull()
            if (previous != null && previous.end == clippedFrom && previous.kind == kind && previous.group == group) {
                spans[spans.lastIndex] = previous.copy(end = clippedUntil)
            } else {
                spans.add(Span(clippedFrom, clippedUntil, kind, group))
            }
        }
        fun movement(start: Long, end: Long) = append(start, end, if (end - start > GAP_MS) SpanKind.Gap else SpanKind.Move)
        fun between(start: Long, end: Long) {
            var previous = start
            for (point in points) {
                if (point.at in (start + 1) until end) {
                    movement(previous, point.at)
                    previous = point.at
                }
            }
            movement(previous, end)
        }
        append(from, points.first().at, SpanKind.Gap)
        var cursor = points.first().at
        for (stay in stays) {
            between(cursor, stay.start)
            val group = places.first { stay in it.stays }
            append(stay.start, stay.end, SpanKind.Stay, group.index)
            cursor = stay.end
        }
        val last = points.last().at
        between(cursor, last)
        movement(last, to)
        return DayTimeline(spans, places)
    }

    private fun overlap(a: Long, b: Long, c: Long, d: Long) = maxOf(0L, minOf(b, d) - maxOf(a, c))
}
