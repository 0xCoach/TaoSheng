package app.resound.ask.core
import app.resound.ask.*
import app.resound.context.PlaceAt
import app.resound.net.Arr as JSONArray
import app.resound.net.Obj as JSONObject
import kotlinx.datetime.TimeZone

/** 数据库、FTS 分词和本地向量推理由平台实现。JSON 行字段沿用旧 SQL 投影。 */
interface AskToolStore {
    suspend fun read(query: AskToolsCore.Query): List<String>
    fun terms(words: List<String>): List<String>
    suspend fun semantic(names: List<String>, args: String, text: String, stays: List<PlaceAt>?): Map<String, List<Pair<Long, Double>>>
    fun merge(keyword: List<Long>, semantic: List<Long>): List<Long>
    suspend fun findThings(args: String, sources: MutableList<AskSource>): String
}
class AskToolsCore(private val store: AskToolStore, spec: String, private val zone: TimeZone) {
    private val spec = JSONObject(spec)
    fun query(name: String, input: String, ids: List<Long>? = null, stays: List<PlaceAt>? = null): Query = query(name, JSONObject(input), ids, stays)
    suspend fun execute(name: String, args: String, sources: MutableList<AskSource>, stays: List<PlaceAt>? = null): String = execute(name, JSONObject(args), sources, stays).toString()
    private suspend fun read(query: Query) = store.read(query).map(::JSONObject)
    data class Query(val sql: String, val args: List<Any>, val kind: String)
    internal fun query(name: String, input: JSONObject, ids: List<Long>? = null, stays: List<PlaceAt>? = null): Query {
        require(name in setOf("search_lines", "search_events", "list_items", "get_day"))
        val s = spec.getJSONObject(name)
        val allowed = when (name) {
            "search_lines" -> setOf("keywords", "from", "to", "speaker")
            "search_events" -> setOf("keywords", "from", "to", "starred_only")
            "list_items" -> setOf("keywords", "category", "status", "from", "to")
            else -> setOf("date")
        }
        require(input.keys().asSequence().all { it in allowed })
        if (name == "search_lines") require(input.has("keywords"))
        val a = if (name == "get_day") {
            val date = input.getString("date"); require(date.length == 10)
            JSONObject().put("from", date).put("to", date)
        } else input
        val where = mutableListOf(s.getString("where")); val args = mutableListOf<Any>()
        if (a.has("starred_only")) require(a.opt("starred_only") is Boolean)
        val starredOnly = a.optBoolean("starred_only", false)
        if (starredOnly) where += "e.starred = 1"
        if (name == "list_items") where += "i.deleted = 0 AND i.mergedInto IS NULL"
        for ((field, op) in listOf("from" to ">=", "to" to "<")) if (a.has(field) && !a.isNull(field)) {
            where += s.getString("time") + op + "?"; args += timestamp(a.getString(field), field == "to", zone)
        }
        if (a.has("from") && !a.isNull("from") && a.has("to") && !a.isNull("to"))
            require(timestamp(a.getString("from"), false, zone) < timestamp(a.getString("to"), true, zone))
        val keywords = if (a.has("keywords")) a.getJSONArray("keywords") else JSONArray()
        require(keywords.length() <= 8)
        val words = (0 until keywords.length()).map { keywords.getString(it).trim().also { word ->
            require(word.isNotBlank() && word.length <= 80)
        } }
        val fts = when (name) { "search_lines" -> "lines_fts"; "list_items" -> "items_fts"; else -> "events_fts" }
        val prefix = when (name) { "search_lines" -> "l"; "list_items" -> "i"; else -> "e" }
        if (stays != null) {
            require(name in setOf("search_lines", "search_events"))
            val valid = stays.filter { it.start < it.end }
            if (valid.isEmpty()) where += "0" else {
                // 只有本地 Long 时间戳进入 VALUES，模型字符串仍全部绑定；避免长时间范围的绑定数/OR 深度上限。
                val spans = valid.joinToString { "(${it.start},${it.end})" }
                where += "EXISTS (SELECT 1 FROM (VALUES $spans) WHERE $prefix.startAt < column2 AND $prefix.endAt > column1)"
            }
        }
        if (words.isNotEmpty()) {
            val terms = store.terms(words)
            if (terms.isEmpty()) where += "0" else {
                where += terms.joinToString(" OR ", "(", ")") { "$prefix.id IN (SELECT rowid FROM $fts WHERE $fts MATCH ?)" }
                args.addAll(terms)
            }
        }
        if (ids != null) {
            if (ids.isEmpty()) where += "0" else {
                where += "$prefix.id IN (${ids.joinToString { "?" }})"; args.addAll(ids)
            }
        }
        for (field in listOf("speaker", "category", "status")) if (a.has(field) && !a.isNull(field)) {
            val value = a.getString(field); require(value.length in 1..80)
            if (field == "category") require(value in app.resound.digest.core.DigestPrompt.CATEGORIES)
            if (field == "status") require(value in app.resound.digest.ItemStatus.entries.map { it.name })
            val columns = s.optJSONArray(field)?.let { c -> (0 until c.length()).map { c.getString(it) } }
                ?: listOf(s.getString(field))
            where += columns.joinToString(" OR ", "(", ")") { "$it=?" }; columns.forEach { args += value }
        }
        val order = if (starredOnly && words.isEmpty()) "e.starredAt" else s.getString("time")
        return Query("SELECT ${s.getString("select")}${if (name == "search_lines") ", l.voiceInputJson" else ""} FROM ${s.getString("table")} WHERE ${where.joinToString(" AND ")} ORDER BY $order DESC, $prefix.id DESC LIMIT 41", args, s.getString("kind"))
    }

    private fun withoutKeywords(args: JSONObject) = JSONObject(args.toString()).apply { remove("keywords") }

    private suspend fun semantic(names: List<String>, args: JSONObject, text: String, stays: List<PlaceAt>? = null) =
        store.semantic(names, args.toString(), text, stays)

    internal suspend fun execute(name: String, args: JSONObject, sources: MutableList<AskSource>): JSONObject =
        execute(name, args, sources, null)

    internal suspend fun execute(name: String, args: JSONObject, sources: MutableList<AskSource>, stays: List<PlaceAt>?): JSONObject {
        if (name == "find_things") return JSONObject(store.findThings(args.toString(), sources))
        val rows: List<JSONObject>
        var truncated = false
        if (name == "semantic_search") {
            require(args.keys().asSequence().all { it in setOf("query", "from", "to") })
            val text = args.getString("query").trim(); require(text.length in 1..1000)
            val filters = JSONObject(args.toString()).apply { remove("query") }
            // 提前校验日期，即使模型没有就绪也不能吞掉错误参数。
            query("search_events", filters)
            val hits = semantic(listOf("search_events", "list_items"), filters, text)
            val ranked = hits.flatMap { (tool, ids) -> ids.map { Triple(tool, it.first, it.second) } }
                .sortedByDescending { it.third }
            val top = ranked.take(10)
            val found = top.groupBy { it.first }.flatMap { (tool, values) ->
                read(query(tool, filters, values.map { it.second })).map { (tool to it.getLong("id")) to it }
            }.toMap()
            rows = top.mapNotNull { found[it.first to it.second] }
            truncated = ranked.size > 10
        } else {
            val q = query(name, args, stays = stays)
            val keyword = read(q)
            val words = args.optJSONArray("keywords")
            if (name in setOf("search_events", "list_items") && words != null && words.length() > 0) {
                val text = (0 until words.length()).joinToString(" ") { words.getString(it) }
                val semantic = semantic(listOf(name), args, text, stays)[name].orEmpty().map { it.first }
                val first = keyword.take(20)
                val ids = store.merge(first.map { it.getLong("id") }, semantic)
                val found = (first + read(query(name, withoutKeywords(args), semantic, stays))).associateBy { it.getLong("id") }
                rows = ids.mapNotNull { found[it] }
                truncated = (keyword.map { it.getLong("id") } + semantic).distinct().size > rows.size
            } else { rows = keyword.take(40); truncated = keyword.size > 40 }
        }
        val results = JSONArray()
        for (row in rows) {
            val source = AskSource(row.getString("kind"), row.getLong("id"), row.getLong("at"), row.getString("title"), row.getString("quote"))
            val existing = sources.indexOf(source)
            row.put("ref", if (existing >= 0) existing + 1 else sources.size + 1)
                .put("time", AskTime.format(source.at, zone))
            results.put(row)
            if (JSONObject().put("results", results).put("truncated", false).toString().length > 6000) {
                results.remove(results.length() - 1); truncated = true; break
            }
            if (existing < 0) sources += source
        }
        return JSONObject().put("results", results).put("truncated", truncated)
    }

    companion object {
        fun timestamp(value: String, end: Boolean, zone: TimeZone) = AskTime.timestamp(value, end, zone)
    }
}
