package app.resound.ask

import org.json.JSONArray
import org.json.JSONObject

object AskCitations {
    private val marker = Regex("\\[\\^(\\d+)\\]")
    data class Parsed(val text: String, val sources: List<AskSource>, val invalid: Boolean)
    /** 合约没有 ref 字段：按正文首次引用顺序重编号，正文 [^n] 始终对应 sources[n-1]。 */
    fun parse(text: String, available: List<AskSource>): Parsed {
        val selected = mutableListOf<AskSource>(); var invalid = false
        val normalized = marker.replace(text) { m ->
            val source = m.groupValues[1].toIntOrNull()?.let { available.getOrNull(it - 1) }
            if (source == null) { invalid = true; "" }
            else {
                var index = selected.indexOf(source)
                if (index < 0) { selected += source; index = selected.lastIndex }
                "[^${index + 1}]"
            }
        }
        return Parsed(normalized, selected, invalid)
    }
    fun encode(sources: List<AskSource>): String = JSONArray(sources.map {
        JSONObject().put("kind", it.kind).put("id", it.id).put("at", it.at).put("title", it.title).put("quote", it.quote)
    }).toString()
    fun decode(json: String): List<AskSource> = runCatching {
        val array = JSONArray(json)
        (0 until array.length()).map { i -> array.getJSONObject(i).let {
            AskSource(it.getString("kind"), it.getLong("id"), it.getLong("at"), it.getString("title"), it.getString("quote"))
        } }
    }.getOrDefault(emptyList())
    fun withoutMarkers(text: String): String = marker.replace(text, "")
}
