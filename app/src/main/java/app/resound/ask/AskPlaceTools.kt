package app.resound.ask

import app.resound.context.PlaceAt
import app.resound.data.CaptureDatabase
import app.resound.location.LocationRepository
import app.resound.location.Track
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId

/** 地点解析在挂起层完成；SQL、截断和 ref 分配继续共用原工具。 */
class AskPlaceTools(
    private val tools: AskTools,
    private val enabled: () -> Boolean,
    private val staysAt: suspend (String, Long, Long) -> List<PlaceAt>,
    private val placesDuring: suspend (Long, Long) -> List<PlaceAt>,
    private val zone: ZoneId = ZoneId.systemDefault(),
) {
    suspend fun execute(name: String, args: JSONObject, sources: MutableList<AskSource>): JSONObject {
        // find_things 原有的 place 表示物品位置，不是足迹地点。
        if (name !in setOf("search_events", "search_lines") || !args.has("place") || args.isNull("place")) {
            val original = JSONObject(args.toString())
            if (name in setOf("search_events", "search_lines")) original.remove("place")
            return tools.execute(name, original, sources)
        }
        val place = (args.get("place") as? String)?.trim()
        require(place != null && place.length in 1..80)
        val filters = JSONObject(args.toString()).apply { remove("place") }
        tools.query(name, filters) // 足迹关闭/未找到时也不能吞掉日期、关键词等参数错误。
        if (!enabled()) return tools.execute(name, filters, sources)
            .put("notice", "没有开启足迹；以下是按原条件检索的结果，不能据此判断内容发生在该地点。")
        val from = filters.opt("from").takeIf { it is String }?.let { AskTools.timestamp(it as String, false, zone) } ?: Long.MIN_VALUE
        val to = filters.opt("to").takeIf { it is String }?.let { AskTools.timestamp(it as String, true, zone) } ?: Long.MAX_VALUE
        val stays = staysAt(place, from, to).mapNotNull {
            val start = maxOf(from, it.start)
            val end = minOf(to, it.end)
            if (start < end) it.copy(start = start, end = end) else null
        }
        if (stays.isEmpty()) {
            val names = placesDuring(from, to).filter { it.start < to && it.end > from }
                .map { it.name }.distinct().take(10)
            return JSONObject().put("results", JSONArray()).put("truncated", false)
                .put("notice", "没找到：足迹里没有叫 $place 的地方（所查时间范围内）。" +
                    if (names.isEmpty()) "这段时间没有可用的停留地点。" else "这段时间去过：${names.joinToString("、")}。")
                .put("places", JSONArray(names))
        }
        return tools.execute(name, filters, sources, stays)
    }
}

/**
 * 卡 130 尚未合并时的 staysAt 临时实现。只读 LocationRepository 和 PlaceResolver.cached，绝不反查地名。
 * 合并后将 AskPlaceTools 的 staysAt 参数换为 AppGraph.situation::staysAt；候选地名仍从本地足迹读取。
 */
class CachedAskPlaces(
    private val location: LocationRepository,
    private val database: CaptureDatabase,
    private val zone: ZoneId = ZoneId.systemDefault(),
) {
    fun enabled() = location.settings.enabled.value

    suspend fun staysAt(placeName: String, from: Long, to: Long): List<PlaceAt> =
        placesDuring(from, to).filter {
            if (placeName == "家") it.home else it.name.contains(placeName, ignoreCase = true)
        }

    suspend fun placesDuring(from: Long, to: Long): List<PlaceAt> {
        if (!enabled() || from >= to) return emptyList()
        val bounds = database.query("SELECT MIN(at), MAX(at) FROM location_points", emptyArray()).use {
            if (!it.moveToFirst() || it.isNull(0)) return emptyList()
            it.getLong(0) to it.getLong(1)
        }
        val start = maxOf(from, bounds.first)
        val last = minOf(to - 1, bounds.second)
        if (start > last) return emptyList()
        var date = Instant.ofEpochMilli(start).atZone(zone).toLocalDate()
        val lastDate = Instant.ofEpochMilli(last).atZone(zone).toLocalDate()
        return buildList {
            while (date <= lastDate) {
                // 先算整日停留，再按查询区间取交集，避免窄时间窗口不足十分钟而丢掉真实停留。
                for (stay in Track.day(location.day(date).first()).stays) {
                    if (stay.start >= to || stay.end <= from) continue
                    val place = location.places.cached(stay.lat, stay.lng)
                    val name = place?.name?.takeIf { it.isNotBlank() }
                        ?: place?.address?.takeIf { it.isNotBlank() } ?: "某地"
                    add(PlaceAt(name, place?.home == true, stay.start, stay.end))
                }
                date = date.plusDays(1)
            }
        }
    }
}
