package app.resound.things

import app.resound.ask.AskSource
import app.resound.data.CaptureDatabase
import kotlinx.coroutines.runBlocking
import androidx.room.withTransaction
import org.json.JSONArray
import org.json.JSONObject
import java.time.*
import java.time.format.DateTimeFormatter

/** AskTools 本来就是同步的本地查询边界，在其 IO 调用线程读取一个一致的快照。 */
internal class FindThingsTool(private val db: CaptureDatabase, private val zone: ZoneId) {
    fun execute(args: JSONObject, sources: MutableList<AskSource>): JSONObject {
        require(args.keys().asSequence().all { it in setOf("query", "place") })
        fun arg(key: String) = if (!args.has(key) || args.isNull(key)) "" else args.getString(key).trim().also { require(it.length <= 100) }
        val query = arg("query"); val place = arg("place")
        val (ts, rs) = runBlocking { db.withTransaction { db.things().allThings() to db.things().allRecords() } }
        val names = ts.associate { it.id to it.name }
        val known = things(ts, rs)
        fun matches(value: String?, filter: String) = filter.isEmpty() || value?.contains(filter, ignoreCase = true) == true
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
        val attached = mutableSetOf<Long>()
        var truncated = false
        for (t in known) {
            val pending = rs.filter { it.status == "Pending" && (it.thingId == t.id || it.sameAsId == t.id) }
            if (!(listOf(t.name, t.latest.place, t.lastPlace, t.latest.who).plus(t.aliases).plus(pending.flatMap { listOf(it.name, it.place, it.who) }).any { matches(it, query) } &&
                    (listOf(t.latest.place, t.lastPlace) + pending.map { it.place }).any { matches(it, place) })) continue
            if (results.length() >= 40) { truncated = true; break }
            attached.addAll(pending.map { it.id })
            val history = rs.filter { it.thingId == t.id && it.status == "Accepted" }.take(5)
            results.put(JSONObject().put("id", t.id).put("name", t.name).put("aliases", JSONArray(t.aliases))
                .put("current", recordJson(history.first())).put("lastPlace", t.lastPlace ?: JSONObject.NULL)
                .put("lastPlaceAt", t.lastPlaceAt ?: JSONObject.NULL).put("records", JSONArray(history.map(::recordJson)))
                .put("pending", JSONArray(pending.take(40).map { recordJson(it).put("sameAsUncertain", it.sameAsId != null) }))
                .put("pendingTruncated", pending.size > 40))
        }
        // 没有已确认物品的新发现也必须能查；不把它的 Pending 位置当作 current。
        for (r in rs.filter { it.status == "Pending" && it.id !in attached }) {
            if (!listOf(r.name, r.place, r.who).any { matches(it, query) } || !matches(r.place, place)) continue
            if (results.length() >= 40) { truncated = true; break }
            results.put(JSONObject().put("id", r.thingId ?: JSONObject.NULL).put("name", r.name).put("status", "未确认")
                .put("current", JSONObject.NULL).put("sameAsUncertain", r.sameAsId != null).put("records", JSONArray().put(recordJson(r))))
        }
        return JSONObject().put("results", results).put("truncated", truncated)
    }
}
