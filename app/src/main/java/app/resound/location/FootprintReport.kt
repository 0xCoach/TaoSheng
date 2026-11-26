package app.resound.location

import app.resound.diagnostics.FailureNotes

import app.resound.location.Places.toPlace
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject

/** 一天的足迹推送内容：标题、给人看的正文、给 Agent 的结构化数据。当天没有定位点时为 null。 */
data class FootprintReport(val date: LocalDate, val title: String, val body: String, val json: JSONObject)

object FootprintReports {
    private val hm = DateTimeFormatter.ofPattern("HH:mm")
    private val weekday = DateTimeFormatter.ofPattern("M月d日 EEE", Locale.CHINA)
    private const val MINUTE_MS = 60_000L

    private data class NamedStay(val stay: Stay, val place: Place?, val home: Boolean) {
        val label: String get() = when {
            home && (place == null || place.name == "家") -> "家"
            home -> "家 · ${place?.name}"
            place != null -> place.name
            else -> String.format(Locale.US, "%.5f, %.5f", stay.lat, stay.lng)
        }
    }

    fun dur(ms: Long): String {
        val minutes = ms / MINUTE_MS
        if (minutes < 60) return "$minutes 分钟"
        val hours = "${minutes / 60} 小时"
        val remainder = minutes % 60
        return if (remainder == 0L) hours else "$hours $remainder 分"
    }

    /**
     * 纯函数，便于测试。to 是统计截止（今天传“现在”，往日传次日零点）。
     * place 按停留坐标取地名（手动名优先），取不到为 null 时只写坐标。
     */
    fun render(date: LocalDate, zone: ZoneId, points: List<LocationPoint>, to: Long,
               isHome: (Double, Double) -> Boolean, place: (Double, Double) -> Place?): FootprintReport? =
        renderTrack(date, zone, Track.day(points), to, isHome, place)

    private fun renderTrack(
        date: LocalDate, zone: ZoneId, track: DayTrack, to: Long,
        isHome: (Double, Double) -> Boolean, place: (Double, Double) -> Place?,
    ): FootprintReport? {
        if (track.points.isEmpty()) return null
        val from = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val timeline = Timeline.build(track.points, track.stays, from, to, isHome)
        val stays = track.stays.map { stay ->
            NamedStay(stay, place(stay.lat, stay.lng), timeline.groupOf(stay)?.home == true)
        }
        val title = "今日足迹 · ${date.format(weekday)}"
        val clock = hm.withZone(zone)
        fun range(start: Long, end: Long) = "${clock.format(Instant.ofEpochMilli(start))}–${clock.format(Instant.ofEpochMilli(end))}"
        val missing = timeline.spans.filter { it.kind == SpanKind.Gap && it.start > from && it.end - it.start >= 60 * MINUTE_MS }
        val body = buildString {
            val distance = String.format(Locale.US, "%.1f km", track.meters / 1000)
            appendLine("在家 ${dur(timeline.homeMillis)} · 外出 ${dur(timeline.awayMillis)} · 移动 $distance")
            appendLine()
            if (stays.isEmpty()) appendLine("还没有停留够 10 分钟的地方。")
            for (item in stays) {
                val stay = item.stay
                appendLine("${range(stay.start, stay.end)} ${item.label}（${dur(stay.end - stay.start)}）")
                item.place?.let { location ->
                    if (location.address.isNotBlank() && location.address != location.name) appendLine("  ${location.address}")
                }
            }
            if (missing.isNotEmpty()) {
                appendLine()
                appendLine(missing.joinToString(separator = "、", prefix = "没有记录：") { range(it.start, it.end) })
            }
            appendLine()
            append("共 ${track.points.size} 个定位点")
        }
        return FootprintReport(date, title, body, payload(date, zone, track, timeline, stays))
    }

    private fun payload(date: LocalDate, zone: ZoneId, track: DayTrack, timeline: DayTimeline, stays: List<NamedStay>): JSONObject {
        fun iso(at: Long) = Instant.ofEpochMilli(at).toString()
        val locations = JSONArray()
        for (item in stays) {
            val (stay, place, home) = item
            locations.put(JSONObject().put("start", iso(stay.start)).put("end", iso(stay.end))
                .put("minutes", (stay.end - stay.start) / MINUTE_MS).put("name", item.label)
                .put("address", place?.address ?: JSONObject.NULL).put("placeSource", place?.source ?: JSONObject.NULL)
                .put("home", home).put("lat", stay.lat).put("lng", stay.lng).put("accuracyMeters", stay.accuracy.toInt()))
        }
        val steps = JSONArray()
        for (span in timeline.spans) {
            val type = when (span.kind) {
                SpanKind.Stay -> "stay"
                SpanKind.Move -> "move"
                SpanKind.Gap -> "no_record"
            }
            steps.put(JSONObject().put("start", iso(span.start)).put("end", iso(span.end)).put("kind", type))
        }
        val fixes = JSONArray()
        for (point in track.points) {
            fixes.put(JSONObject().put("at", iso(point.at)).put("lat", point.lat)
                .put("lng", point.lng).put("accuracy", point.accuracy.toInt()))
        }
        return JSONObject()
            .put("date", date.toString()).put("timezone", zone.id)
            .put("homeMinutes", timeline.homeMillis / MINUTE_MS).put("awayMinutes", timeline.awayMillis / MINUTE_MS)
            .put("distanceMeters", track.meters.toLong())
            .put("stays", locations).put("spans", steps).put("points", fixes)
    }

    /** 读库并查地名（会联网查还没缓存的地名）。 */
    suspend fun build(repo: LocationRepository, date: LocalDate, zone: ZoneId = ZoneId.systemDefault(), now: Long = System.currentTimeMillis()): FootprintReport? {
        val points = repo.day(date).first()
        if (points.isEmpty()) return null
        val manual = repo.places.manual.first()
        val track = Track.day(points)
        val names = HashMap<String, Place?>()
        for (s in track.stays) {
            val key = Places.cell(s.lat, s.lng)
            if (key !in names) names[key] = runCatching { repo.places.resolve(s.lat, s.lng) }.onFailure {
                FailureNotes.record("location/FootprintReport.kt:94", it)
            }.getOrNull()
        }
        val end = minOf(now, date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli())
        return renderTrack(date, zone, track, end,
            { la, ln -> Places.nearestManual(manual, la, ln)?.home == true },
            { la, ln -> Places.nearestManual(manual, la, ln)?.toPlace() ?: names[Places.cell(la, ln)] })
    }
}
