package app.resound.things

import app.resound.ask.AskSource
import app.resound.data.CaptureDatabase
import app.resound.data.writeTransaction
import org.json.JSONArray
import org.json.JSONObject
import java.time.*
import java.time.format.DateTimeFormatter

/** 问问工具通过挂起事务读取物品的一致快照。 */
internal class FindThingsTool(private val db: CaptureDatabase, private val zone: ZoneId) {
    suspend fun execute(args: JSONObject, sources: MutableList<AskSource>): JSONObject {
        require(args.keys().asSequence().all { it in setOf("query", "place") })
        fun arg(key: String) = if (!args.has(key) || args.isNull(key)) "" else args.getString(key).trim().also { require(it.length <= 100) }
        val query = arg("query"); val place = arg("place")
        val (ts, rs) = db.writeTransaction { db.things().allThings() to db.things().allRecords() }
        val names = ts.associate { it.id to it.name }
        fun recordJson(row: ThingRecordRow): JSONObject {
            val r = row.model(names)
            val j = JSONObject().put("id", r.id).put("action", r.action.title).put("place", r.place ?: JSONObject.NULL)
                .put("who", r.who ?: JSONObject.NULL).put("status", if (r.status == RecordStatus.Pending) "未确认" else "已确认")
                .put("at", r.at).put("time", Instant.ofEpochMilli(r.at).atZone(zone).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")))
                .put("sourceLineId", r.sourceLineId ?: JSONObject.NULL).put("sourceText", r.sourceText ?: JSONObject.NULL)
            r.sourceLineId?.let { id ->
                val source = AskSource("line", id, r.at, r.name, r.sourceText.orEmpty())
                val existing = sources.indexOfFirst { it.kind == "line" && it.id == id }
                j.put("ref", if (existing >= 0) existing + 1 else (sources.size + 1).also { sources += source })
            }
            return j
        }
        val results = JSONArray()
        val selection = FindThingRules.select(ts.map { it.shared() }, rs.map { it.shared() }, query, place)
        for (match in selection.matches) {
            val t = match.thing
            if (t != null) {
                val history = match.history.map { it.room() }
                val pending = match.pending.map { it.room() }
                results.put(JSONObject().put("id", t.id).put("name", t.name).put("aliases", JSONArray(t.aliases))
                    .put("current", recordJson(history.first())).put("lastPlace", t.lastPlace ?: JSONObject.NULL)
                    .put("lastPlaceAt", t.lastPlaceAt ?: JSONObject.NULL).put("records", JSONArray(history.map(::recordJson)))
                    .put("pending", JSONArray(pending.take(40).map { recordJson(it).put("sameAsUncertain", it.sameAsId != null) }))
                    .put("pendingTruncated", pending.size > 40))
            } else {
                val r = match.history.single().room()
                results.put(JSONObject().put("id", r.thingId ?: JSONObject.NULL).put("name", r.name).put("status", "未确认")
                    .put("current", JSONObject.NULL).put("sameAsUncertain", r.sameAsId != null).put("records", JSONArray().put(recordJson(r))))
            }
        }
        return JSONObject().put("results", results).put("truncated", selection.truncated)
    }
}
