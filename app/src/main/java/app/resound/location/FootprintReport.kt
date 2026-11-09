package app.resound.location

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

    fun dur(ms: Long): String {
        val m = ms / 60_000
        return when { m < 60 -> "$m 分钟"; m % 60 == 0L -> "${m / 60} 小时"; else -> "${m / 60} 小时 ${m % 60} 分" }
    }

    /**
     * 纯函数，便于测试。to 是统计截止（今天传“现在”，往日传次日零点）。
     * place 按停留坐标取地名（手动名优先），取不到为 null 时只写坐标。
     */
    fun render(date: LocalDate, zone: ZoneId, points: List<LocationPoint>, to: Long,
               isHome: (Double, Double) -> Boolean, place: (Double, Double) -> Place?): FootprintReport? {
        val track = Track.day(points)
        if (track.points.isEmpty()) return null
        val from = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val tl = Timeline.build(track.points, track.stays, from, to, isHome)
        fun clock(at: Long) = Instant.ofEpochMilli(at).atZone(zone).format(hm)
        val weekday = date.format(DateTimeFormatter.ofPattern("M月d日 EEE", Locale.CHINA))
        val title = "今日足迹 · $weekday"
        val stays = track.stays.map { s ->
            val g = tl.groupOf(s)
            val p = place(s.lat, s.lng)
            val name = when {
                g?.home == true && (p == null || p.name == "家") -> "家"
                g?.home == true -> "家 · ${p!!.name}"
                p != null -> p.name
                else -> String.format(Locale.US, "%.5f, %.5f", s.lat, s.lng)
            }
            Triple(s, name, p)
        }
        val body = buildString {
            append("在家 ${dur(tl.homeMillis)} · 外出 ${dur(tl.awayMillis)} · 移动 ")
            append(String.format(Locale.US, "%.1f km", track.meters / 1000))
            append("\n")
            if (stays.isEmpty()) append("\n还没有停留够 10 分钟的地方。\n")
            else {
                append("\n")
                stays.forEach { (s, name, p) ->
                    append("${clock(s.start)}–${clock(s.end)} $name（${dur(s.end - s.start)}）")
                    p?.address?.takeIf { it.isNotBlank() && it != p.name }?.let { append("\n  $it") }
                    append("\n")
                }
            }
            val gaps = tl.spans.filter { it.kind == SpanKind.Gap && it.end - it.start >= 60 * 60_000L && it.start > from }
            if (gaps.isNotEmpty()) append("\n没有记录：" + gaps.joinToString("、") { "${clock(it.start)}–${clock(it.end)}" } + "\n")
            append("\n共 ${track.points.size} 个定位点")
        }
        val json = JSONObject()
            .put("date", date.toString()).put("timezone", zone.id)
            .put("homeMinutes", tl.homeMillis / 60_000).put("awayMinutes", tl.awayMillis / 60_000)
            .put("distanceMeters", track.meters.toLong())
            .put("stays", JSONArray(stays.map { (s, name, p) ->
                JSONObject().put("start", Instant.ofEpochMilli(s.start).toString()).put("end", Instant.ofEpochMilli(s.end).toString())
                    .put("minutes", (s.end - s.start) / 60_000).put("name", name).put("address", p?.address ?: JSONObject.NULL)
                    .put("placeSource", p?.source ?: JSONObject.NULL).put("home", tl.groupOf(s)?.home == true)
                    .put("lat", s.lat).put("lng", s.lng).put("accuracyMeters", s.accuracy.toInt())
            }))
            .put("spans", JSONArray(tl.spans.map { sp ->
                JSONObject().put("start", Instant.ofEpochMilli(sp.start).toString()).put("end", Instant.ofEpochMilli(sp.end).toString())
                    .put("kind", when (sp.kind) { SpanKind.Stay -> "stay"; SpanKind.Move -> "move"; SpanKind.Gap -> "no_record" })
            }))
            .put("points", JSONArray(track.points.map { pt ->
                JSONObject().put("at", Instant.ofEpochMilli(pt.at).toString()).put("lat", pt.lat).put("lng", pt.lng).put("accuracy", pt.accuracy.toInt())
            }))
        return FootprintReport(date, title, body, json)
    }

    /** 读库并查地名（会联网查还没缓存的地名）。 */
    suspend fun build(repo: LocationRepository, date: LocalDate, zone: ZoneId = ZoneId.systemDefault(), now: Long = System.currentTimeMillis()): FootprintReport? {
        val points = repo.day(date).first()
        if (points.isEmpty()) return null
        val manual = repo.places.manual.first()
        val stays = Track.day(points).stays
        val names = HashMap<String, Place?>()
        for (s in stays) {
            val key = Places.cell(s.lat, s.lng)
            if (key !in names) names[key] = runCatching { repo.places.resolve(s.lat, s.lng) }.getOrNull()
        }
        val end = minOf(now, date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli())
        return render(date, zone, points, end,
            { la, ln -> Places.nearestManual(manual, la, ln)?.home == true },
            { la, ln -> Places.nearestManual(manual, la, ln)?.toPlace() ?: names[Places.cell(la, ln)] })
    }
}
