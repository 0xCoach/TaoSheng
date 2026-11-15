package app.resound.ask

import app.resound.diagnostics.FailureNotes

import app.resound.ask.core.AskDateException
import app.resound.ask.core.AskTime
import app.resound.ask.core.AskToolStore
import app.resound.ask.core.AskToolsCore
import app.resound.search.Embedder
import app.resound.search.SearchTokens
import app.resound.search.Vectors
import app.resound.things.FindThingsTool
import app.resound.transcribe.VoiceInputMetadata

import app.resound.data.*
import app.resound.context.PlaceAt
import org.json.JSONObject
import java.time.ZoneId

/** 保留原有 SQL 投影和过滤约定；所有模型参数只作为 SQL 绑定值。 */
class AskTools(
    private val db: CaptureDatabase,
    private val spec: JSONObject,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val embedder: () -> Embedder? = { null },
) {
    data class Query(val sql: String, val args: List<Any>, val kind: String)
    internal val core = AskToolsCore(object : AskToolStore {
        override suspend fun read(query: AskToolsCore.Query) = read(Query(query.sql, query.args, query.kind)).map { it.toString() }
        override fun terms(words: List<String>) = SearchTokens.terms(words)
        override suspend fun semantic(names: List<String>, args: String, text: String, stays: List<PlaceAt>?) = semantic(names, JSONObject(args), text, stays)
        override fun merge(keyword: List<Long>, semantic: List<Long>) = Vectors.merge(keyword, semantic)
        override suspend fun findThings(args: String, sources: MutableList<AskSource>) = FindThingsTool(db, zone).execute(JSONObject(args), sources).toString()
    }, spec.toString(), kotlinx.datetime.TimeZone.of(zone.id))
    fun query(name: String, input: JSONObject, ids: List<Long>? = null, stays: List<PlaceAt>? = null): Query = dateResult {
        core.query(name, input.toString(), ids, stays).let { Query(it.sql, it.args, it.kind) }
    }

    private suspend fun read(q: Query): List<JSONObject> = db.readSql(q.sql, q.args.toTypedArray()) { cursor ->
        buildList {
            while (cursor.step()) {
                val row = JSONObject()
                cursor.columnNames.forEachIndexed { i, column ->
                    row.put(if (column == "voiceInputJson") "voice_input" else column, when {
                        cursor.isNull(i) -> JSONObject.NULL
                        column == "voiceInputJson" -> VoiceInputMetadata.read(cursor.getText(i))?.let { VoiceInputMetadata.json(it.copy(before = it.before?.take(750),
                            after = it.after?.take(750))) } ?: JSONObject.NULL
                        column == "starred" -> cursor.getInt(i) != 0
                        column in setOf("id", "at", "starred_at") -> cursor.getLong(i)
                        else -> cursor.getText(i).take(900)
                    })
                }
                add(row.put("kind", q.kind))
            }
        }
    }

    private fun withoutKeywords(args: JSONObject) = JSONObject(args.toString()).apply { remove("keywords") }

    private suspend fun semanticIds(name: String, args: JSONObject, vector: FloatArray, model: String, stays: List<PlaceAt>?): List<Pair<Long, Double>> {
        val base = query(name, withoutKeywords(args), stays = stays)
        val kind = base.kind
        val prefix = if (kind == "item") "i" else "e"
        val table = spec.getJSONObject(name).getString("table")
        val from = table + " WHERE " + base.sql.substringAfterLast(" FROM $table WHERE ").substringBeforeLast(" ORDER BY ")
        // 使用与关键词完全相同的时间/类别/状态/删除过滤；向量不进入云端。
        val sql = "SELECT v.refId,v.dim,v.vector FROM embeddings v JOIN $from AND v.kind=? AND v.refId=$prefix.id AND v.model=?"
        return db.readSql(sql, (base.args + listOf(kind, model)).toTypedArray()) { c ->
            buildList {
                while (c.step()) {
                    val values = runCatching { Vectors.decode(c.getBlob(2), c.getLong(1).toInt()) }.onFailure {
                        FailureNotes.record("ask/AskTools.kt:69", it)
                    }.getOrNull() ?: continue
                    val score = Vectors.cosine(vector, values)
                    if (score.isFinite()) add(c.getLong(0) to score)
                }
            }.sortedByDescending { it.second }.take(20)
        }
    }

    private suspend fun semantic(names: List<String>, args: JSONObject, text: String, stays: List<PlaceAt>? = null): Map<String, List<Pair<Long, Double>>> {
        val local = embedder() ?: return emptyMap()
        return try {
            if (!local.ready()) emptyMap() else {
                val vector = Vectors.normalize(local.embed(text, query = true))
                names.associateWith { semanticIds(it, args, vector, local.model, stays) }
            }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (failure190: Exception) {
            FailureNotes.record("ask/AskTools.kt:85", failure190)
            emptyMap() } // 模型缺失或无法加载时静默退回关键词。
        finally { if (local is AutoCloseable) local.close() }
    }

    suspend fun execute(name: String, args: JSONObject, sources: MutableList<AskSource>): JSONObject = execute(name, args, sources, null)
    internal suspend fun execute(name: String, args: JSONObject, sources: MutableList<AskSource>, stays: List<PlaceAt>?): JSONObject = dateResult {
        JSONObject(core.execute(name, args.toString(), sources, stays))
    }
    companion object {
        fun timestamp(value: String, end: Boolean, zone: ZoneId): Long = dateResult {
            AskTime.timestamp(value, end, kotlinx.datetime.TimeZone.of(zone.id))
        }
    }
}

/** 日期异常保留旧安卓入口类型，供既有调用方处理。 */
private inline fun <T> dateResult(block: () -> T): T = try { block() }
catch (e: AskDateException) { throw java.time.DateTimeException(e.message, e) }
