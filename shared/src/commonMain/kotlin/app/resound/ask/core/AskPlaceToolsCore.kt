package app.resound.ask.core
import app.resound.ask.*
import app.resound.context.PlaceAt
import app.resound.net.Arr as JSONArray
import app.resound.net.Obj as JSONObject
import kotlinx.datetime.TimeZone

/** 地点解析在挂起层完成；SQL、截断和 ref 分配继续共用原工具。 */
class AskPlaceToolsCore(
    private val tools: AskToolsCore,
    private val enabled: () -> Boolean,
    private val staysAt: suspend (String, Long, Long) -> List<PlaceAt>,
    private val placesDuring: suspend (Long, Long) -> List<PlaceAt>,
    private val zone: TimeZone,
) {
    suspend fun execute(name: String, args: String, sources: MutableList<AskSource>): String = executeJson(name, JSONObject(args), sources).toString()
    private suspend fun executeJson(name: String, args: JSONObject, sources: MutableList<AskSource>): JSONObject {
        // find_things 原有的 place 表示物品位置，不是足迹地点。
        if (name !in setOf("search_events", "search_lines") || !args.has("place") || args.isNull("place")) {
            val original = JSONObject(args.toString())
            if (name in setOf("search_events", "search_lines")) original.remove("place")
            return tools.execute(name, original, sources)
        }
        val place = (args.opt("place") as? String)?.trim()
        require(place != null && place.length in 1..80)
        val filters = JSONObject(args.toString()).apply { remove("place") }
        tools.query(name, filters) // 足迹关闭/未找到时也不能吞掉日期、关键词等参数错误。
        if (!enabled()) return tools.execute(name, filters, sources)
            .put("notice", "没有开启足迹；以下是按原条件检索的结果，不能据此判断内容发生在该地点。")
        val from = filters.opt("from").takeIf { it is String }?.let { AskTime.timestamp(it as String, false, zone) } ?: Long.MIN_VALUE
        val to = filters.opt("to").takeIf { it is String }?.let { AskTime.timestamp(it as String, true, zone) } ?: Long.MAX_VALUE
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

