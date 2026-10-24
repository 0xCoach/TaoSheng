package app.resound.ask

import androidx.sqlite.db.SimpleSQLiteQuery
import app.resound.data.CaptureDatabase
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle

/** 保留原有 SQL 投影和过滤约定；所有模型参数只作为 SQL 绑定值。 */
class AskTools(
    private val db: CaptureDatabase,
    private val spec: JSONObject,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val embedder: () -> app.resound.search.Embedder? = { null },
) {
    data class Query(val sql: String, val args: List<Any>, val kind: String)
    fun query(name: String, input: JSONObject, ids: List<Long>? = null): Query {
        require(name in setOf("search_lines", "search_events", "list_items", "get_day"))
        val s = spec.getJSONObject(name)
        val allowed = when (name) {
            "search_lines" -> setOf("keywords", "from", "to", "speaker")
            "search_events" -> setOf("keywords", "from", "to")
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
        if (words.isNotEmpty()) {
            val match = app.resound.search.SearchTokens.match(words)
            if (match.isEmpty()) where += "0" else {
                where += "$prefix.id IN (SELECT rowid FROM $fts WHERE $fts MATCH ?)"; args += match
            }
        }
        if (ids != null) {
            if (ids.isEmpty()) where += "0" else {
                where += "$prefix.id IN (${ids.joinToString { "?" }})"; args.addAll(ids)
            }
        }
        for (field in listOf("speaker", "category", "status")) if (a.has(field) && !a.isNull(field)) {
            val value = a.getString(field); require(value.length in 1..80)
            if (field == "category") require(value in app.resound.digest.DigestPrompt.CATEGORIES)
            if (field == "status") require(value in app.resound.digest.ItemStatus.entries.map { it.name })
            val columns = s.optJSONArray(field)?.let { c -> (0 until c.length()).map { c.getString(it) } }
                ?: listOf(s.getString(field))
            where += columns.joinToString(" OR ", "(", ")") { "$it=?" }; columns.forEach { args += value }
        }
        return Query("SELECT ${s.getString("select")} FROM ${s.getString("table")} WHERE ${where.joinToString(" AND ")} ORDER BY ${s.getString("time")} DESC, $prefix.id DESC LIMIT 41", args, s.getString("kind"))
    }

    private fun read(q: Query): List<JSONObject> = db.query(SimpleSQLiteQuery(q.sql, q.args.toTypedArray())).use { cursor ->
        buildList {
            while (cursor.moveToNext()) {
                val row = JSONObject()
                cursor.columnNames.forEachIndexed { i, column ->
                    row.put(column, when {
                        cursor.isNull(i) -> JSONObject.NULL
                        column in setOf("id", "at") -> cursor.getLong(i)
                        else -> cursor.getString(i).take(900)
                    })
                }
                add(row.put("kind", q.kind))
            }
        }
    }

    private fun withoutKeywords(args: JSONObject) = JSONObject(args.toString()).apply { remove("keywords") }

    private fun semanticIds(name: String, args: JSONObject, vector: FloatArray, model: String): List<Pair<Long, Double>> {
        val base = query(name, withoutKeywords(args))
        val kind = base.kind
        val prefix = if (kind == "item") "i" else "e"
        val table = spec.getJSONObject(name).getString("table")
        val from = table + " WHERE " + base.sql.substringAfterLast(" FROM $table WHERE ").substringBeforeLast(" ORDER BY ")
        // 使用与关键词完全相同的时间/类别/状态/删除过滤；向量不进入云端。
        val sql = "SELECT v.refId,v.dim,v.vector FROM embeddings v JOIN $from AND v.kind=? AND v.refId=$prefix.id AND v.model=?"
        return db.query(SimpleSQLiteQuery(sql, (base.args + listOf(kind, model)).toTypedArray())).use { c ->
            buildList {
                while (c.moveToNext()) {
                    val values = runCatching { app.resound.search.Vectors.decode(c.getBlob(2), c.getInt(1)) }.getOrNull() ?: continue
                    val score = app.resound.search.Vectors.cosine(vector, values)
                    if (score.isFinite()) add(c.getLong(0) to score)
                }
            }.sortedByDescending { it.second }.take(20)
        }
    }

    private fun semantic(names: List<String>, args: JSONObject, text: String): Map<String, List<Pair<Long, Double>>> {
        val local = embedder() ?: return emptyMap()
        return try {
            if (!local.ready()) emptyMap() else {
                val vector = app.resound.search.Vectors.normalize(local.embed(text, query = true))
                names.associateWith { semanticIds(it, args, vector, local.model) }
            }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (_: Exception) { emptyMap() } // 模型缺失或无法加载时静默退回关键词。
        finally { if (local is AutoCloseable) local.close() }
    }

    fun execute(name: String, args: JSONObject, sources: MutableList<AskSource>): JSONObject {
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
            val q = query(name, args)
            val keyword = read(q)
            val words = args.optJSONArray("keywords")
            if (name in setOf("search_events", "list_items") && words != null && words.length() > 0) {
                val text = (0 until words.length()).joinToString(" ") { words.getString(it) }
                val semantic = semantic(listOf(name), args, text)[name].orEmpty().map { it.first }
                val first = keyword.take(20)
                val ids = app.resound.search.Vectors.merge(first.map { it.getLong("id") }, semantic)
                val found = (first + read(query(name, withoutKeywords(args), semantic))).associateBy { it.getLong("id") }
                rows = ids.mapNotNull { found[it] }
                truncated = (keyword.map { it.getLong("id") } + semantic).distinct().size > rows.size
            } else { rows = keyword.take(40); truncated = keyword.size > 40 }
        }
        val results = JSONArray()
        for (row in rows) {
            val source = AskSource(row.getString("kind"), row.getLong("id"), row.getLong("at"), row.getString("title"), row.getString("quote"))
            val existing = sources.indexOf(source)
            row.put("ref", if (existing >= 0) existing + 1 else sources.size + 1)
                .put("time", Instant.ofEpochMilli(source.at).atZone(zone).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")))
            results.put(row)
            if (JSONObject().put("results", results).put("truncated", false).toString().length > 6000) {
                results.remove(results.length() - 1); truncated = true; break
            }
            if (existing < 0) sources += source
        }
        return JSONObject().put("results", results).put("truncated", truncated)
    }

    companion object {
        private val minute = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm").withResolverStyle(ResolverStyle.STRICT)
        fun timestamp(value: String, end: Boolean, zone: ZoneId): Long {
            require(value.length == 10 || value.length == 16)
            val local = if (value.length == 10) LocalDate.parse(value).plusDays(if (end) 1 else 0).atStartOfDay()
                else LocalDateTime.parse(value, minute).plusMinutes(if (end) 1 else 0)
            return local.atZone(zone).toInstant().toEpochMilli()
        }
    }
}
