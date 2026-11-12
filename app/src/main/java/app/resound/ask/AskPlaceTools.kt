package app.resound.ask

import app.resound.context.PlaceAt
import app.resound.data.CaptureDatabase
import app.resound.location.LocationRepository
import app.resound.location.Track
import kotlinx.coroutines.flow.first
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
    private val core = app.resound.ask.core.AskPlaceToolsCore(tools.core, enabled, staysAt, placesDuring, kotlinx.datetime.TimeZone.of(zone.id))
    suspend fun execute(name: String, args: JSONObject, sources: MutableList<AskSource>): JSONObject =
        JSONObject(core.execute(name, args.toString(), sources))
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
