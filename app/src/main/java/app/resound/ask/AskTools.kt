package app.resound.ask

import androidx.sqlite.db.SimpleSQLiteQuery
import app.resound.data.CaptureDatabase
import app.resound.context.PlaceAt
import org.json.JSONObject
import java.time.ZoneId

/** 保留原有 SQL 投影和过滤约定；所有模型参数只作为 SQL 绑定值。 */
class AskTools(
    private val db: CaptureDatabase,
    private val spec: JSONObject,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val embedder: () -> app.resound.search.Embedder? = { null },
) {
    data class Query(val sql: String, val args: List<Any>, val kind: String)
    internal val core = app.resound.ask.core.AskToolsCore(object : app.resound.ask.core.AskToolStore {
        override fun read(query: app.resound.ask.core.AskToolsCore.Query) = read(Query(query.sql, query.args, query.kind)).map { it.toString() }
        override fun terms(words: List<String>) = app.resound.search.SearchTokens.terms(words)
        override fun semantic(names: List<String>, args: String, text: String, stays: List<PlaceAt>?) = semantic(names, JSONObject(args), text, stays)
        override fun merge(keyword: List<Long>, semantic: List<Long>) = app.resound.search.Vectors.merge(keyword, semantic)
        override fun findThings(args: String, sources: MutableList<AskSource>) = app.resound.things.FindThingsTool(db, zone).execute(JSONObject(args), sources).toString()
    }, spec.toString(), kotlinx.datetime.TimeZone.of(zone.id))
    fun query(name: String, input: JSONObject, ids: List<Long>? = null, stays: List<PlaceAt>? = null): Query = dateResult {
        core.query(name, input.toString(), ids, stays).let { Query(it.sql, it.args, it.kind) }
    }

    private fun read(q: Query): List<JSONObject> = db.query(SimpleSQLiteQuery(q.sql, q.args.toTypedArray())).use { cursor ->
        buildList {
            while (cursor.moveToNext()) {
                val row = JSONObject()
                cursor.columnNames.forEachIndexed { i, column ->
                    row.put(if (column == "voiceInputJson") "voice_input" else column, when {
                        cursor.isNull(i) -> JSONObject.NULL
                        column == "voiceInputJson" -> app.resound.transcribe.VoiceInputMetadata.read(cursor.getString(i))?.let { app.resound.transcribe.VoiceInputMetadata.json(it.copy(before = it.before?.take(750), after = it.after?.take(750))) } ?: JSONObject.NULL
                        column == "starred" -> cursor.getInt(i) != 0
                        column in setOf("id", "at", "starred_at") -> cursor.getLong(i)
                        else -> cursor.getString(i).take(900)
                    })
                }
                add(row.put("kind", q.kind))
            }
        }
    }

    private fun withoutKeywords(args: JSONObject) = JSONObject(args.toString()).apply { remove("keywords") }

    private fun semanticIds(name: String, args: JSONObject, vector: FloatArray, model: String, stays: List<PlaceAt>?): List<Pair<Long, Double>> {
        val base = query(name, withoutKeywords(args), stays = stays)
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

    private fun semantic(names: List<String>, args: JSONObject, text: String, stays: List<PlaceAt>? = null): Map<String, List<Pair<Long, Double>>> {
        val local = embedder() ?: return emptyMap()
        return try {
            if (!local.ready()) emptyMap() else {
                val vector = app.resound.search.Vectors.normalize(local.embed(text, query = true))
                names.associateWith { semanticIds(it, args, vector, local.model, stays) }
            }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (_: Exception) { emptyMap() } // 模型缺失或无法加载时静默退回关键词。
        finally { if (local is AutoCloseable) local.close() }
    }

    fun execute(name: String, args: JSONObject, sources: MutableList<AskSource>): JSONObject = execute(name, args, sources, null)
    internal fun execute(name: String, args: JSONObject, sources: MutableList<AskSource>, stays: List<PlaceAt>?): JSONObject = dateResult {
        JSONObject(core.execute(name, args.toString(), sources, stays))
    }
    companion object {
        fun timestamp(value: String, end: Boolean, zone: ZoneId): Long = dateResult {
            app.resound.ask.core.AskTime.timestamp(value, end, kotlinx.datetime.TimeZone.of(zone.id))
        }
    }
}

/** 日期异常保留旧安卓入口类型，供既有调用方处理。 */
private inline fun <T> dateResult(block: () -> T): T = try { block() }
catch (e: app.resound.ask.core.AskDateException) { throw java.time.DateTimeException(e.message, e) }
