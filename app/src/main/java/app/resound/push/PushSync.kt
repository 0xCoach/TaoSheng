package app.resound.push

import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

internal fun sha256(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
internal fun canonical(value: Any?): String = when (value) {
    is JSONObject -> value.keys().asSequence().toList().sorted().joinToString(",", "{", "}") { JSONObject.quote(it) + ":" + canonical(value.get(it)) }
    is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { canonical(value.get(it)) }
    null, JSONObject.NULL -> "null"
    is String -> JSONObject.quote(value)
    else -> value.toString()
}

/** 保存本通道的指纹与已发布模板；模板用于摘要即时刷新时保留尚未触发更新的记录。绝不存凭据。 */
internal class PushSyncState(private val file: File) {
    val json: JSONObject = if (file.exists()) JSONObject(file.readText()) else JSONObject()
    val files: JSONObject = json.optJSONObject("files") ?: JSONObject().also { json.put("files", it) }
    val records: JSONObject = json.optJSONObject("records") ?: JSONObject().also { json.put("records", it) }
    val templates: JSONObject = json.optJSONObject("templates") ?: JSONObject().also { json.put("templates", it) }
    fun save() {
        file.parentFile!!.mkdirs()
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.outputStream().use { out -> out.write(json.toString().toByteArray(Charsets.UTF_8)); out.fd.sync() }
        check(tmp.renameTo(file)) { "同步状态保存失败" }
    }
    fun since(subscription: PushSubscription, today: LocalDate, rebuild: Boolean = false): LocalDate? {
        if (rebuild || !json.has("since")) {
            json.put("since", subscription.backfillDays?.let { today.minusDays((it.toLong() - 1).coerceAtLeast(0)).toString() } ?: JSONObject.NULL)
            save()
        }
        return if (json.isNull("since")) null else LocalDate.parse(json.getString("since"))
    }
}

internal class FolderRemote(private val values: Map<String, String>, private val http: PushHttp) {
    private val root = PushFields.default(values, "url", "https://dav.jianguoyun.com/dav/").toHttpUrl()
    private val folder = values.getValue("folder").split('/')
    private val directories = mutableSetOf<String>()
    private fun url(path: String) = root.newBuilder().apply {
        if (!root.encodedPath.endsWith('/')) addPathSegment("")
        (folder + path.split('/').filter { it.isNotEmpty() }).forEach { addPathSegment(it) }
        if (path.endsWith('/')) addPathSegment("")
    }.build()
    private fun request(path: String) = Request.Builder().url(url(path)).header("Authorization", Credentials.basic(values.getValue("account"), values.getValue("password"), Charsets.UTF_8))
    fun directories(path: String = "") {
        val parents = folder + path.split('/').filter { it.isNotEmpty() }
        parents.indices.forEach { i ->
            val key = parents.take(i + 1).joinToString("/")
            if (key !in directories) {
                val target = root.newBuilder().apply { if (!root.encodedPath.endsWith('/')) addPathSegment(""); parents.take(i + 1).forEach { addPathSegment(it) }; addPathSegment("") }.build()
                http.execute(Request.Builder().url(target).header("Authorization", Credentials.basic(values.getValue("account"), values.getValue("password"), Charsets.UTF_8))
                    .method("MKCOL", ByteArray(0).toRequestBody()).build(), setOf(405), false)
                directories += key
            }
        }
    }
    fun putTemporary(path: String, content: String) {
        directories(path.substringBeforeLast('/', ""))
        http.execute(request(path).put(content.toRequestBody()).build(), readBody = false)
    }
    fun finish(part: String, target: String) {
        val exists = http.execute(request(target).head().build(), setOf(404), false).code != 404
        if (exists) delete(target)
        http.execute(request(part).header("Destination", url(target).toString()).header("Overwrite", "F").method("MOVE", null).build(), readBody = false)
    }
    fun delete(path: String) { http.execute(request(path).delete().build(), setOf(404), false) }
}

internal data class SyncBatch(val updated: Int, val deleted: Int, val more: Boolean) {
    val description get() = "更新 $updated 个文件" + if (deleted > 0) "，删除 $deleted 个文件" else ""
}
internal class FolderSync(private val remote: FolderRemote, private val state: PushSyncState) {
    companion object {
        // 本地指纹表也按白名单校验，避免恢复/损坏状态把删除扩大到用户目录。
        fun owned(path: String): Boolean = path in setOf("README.md", "recent-days-index.md", "starred-highlights.md", "todos-and-appointments.md", "ideas.md", "decisions-and-facts.md") ||
            path.matches(Regex("(?:daily|transcripts)/[0-9]{4}-[0-9]{2}/[0-9]{4}-[0-9]{2}-[0-9]{2}-[A-Za-z]{3}_(?:daily-record|transcript)\\.md")) ||
            path.matches(Regex("weekly/[0-9]{4}-W[0-9]{2}_[0-9]{4}-[0-9]{4}_weekly-review\\.md")) ||
            path.matches(Regex("archive/[0-9]{4}/(?:ideas|decisions-and-facts)\\.md"))
    }
    fun run(files: Map<String, String>, category: Boolean, now: Long, limit: Int = 20): SyncBatch {
        require(files.keys.all(::owned))
        require(limit in 1..20)
        state.json.optString("temporary").takeIf { it.isNotEmpty() }?.let { part ->
            if (owned(part.substringBefore(".part-")) && part.substringAfter(".part-", "").matches(Regex("[a-f0-9-]+"))) remote.delete(part)
            state.json.remove("temporary"); state.save()
        }
        remote.directories()
        if (category) remote.directories("inbox") // my-notes 永不创建、访问或删除。
        var deleted = 0; var updated = 0
        val rebuilding = state.json.optBoolean("rebuilding")
        val stale = state.files.keys().asSequence().toList().filter { owned(it) && (rebuilding || it !in files) }
        stale.take(20).forEach { path -> remote.delete(path); state.files.remove(path); state.templates.remove(path); state.save(); deleted++ }
        if (stale.size > 20) return SyncBatch(0, deleted, true)
        if (rebuilding) { state.json.remove("rebuilding"); state.save() }
        val changed = files.filter { (path, content) -> state.files.optJSONObject(path)?.optString("sourceHash") != sha256(content) }
        changed.entries.take(limit).forEach { (path, template) ->
            val content = template.replace(MemoryFolder.UPDATED, Instant.ofEpochMilli(now).toString())
            val part = "$path.part-${UUID.randomUUID()}"
            state.json.put("temporary", part); state.save()
            remote.putTemporary(part, content)
            remote.finish(part, path)
            state.files.put(path, JSONObject().put("sourceHash", sha256(template)).put("sha256", sha256(content)))
            state.templates.put(path, template)
            state.json.remove("temporary"); state.save(); updated++
        }
        return SyncBatch(updated, deleted, changed.size > limit)
    }
}

internal object RecordDelta {
    fun records(data: PushSnapshot, s: PushSubscription): Map<String, JSONObject> = buildMap {
        data.events.filter { RecordKind.Timeline in s.records || (RecordKind.Starred in s.records && it.starred) }.forEach { e ->
            put("event:${e.id}", JSONObject().put("id", e.id).put("start", e.startAt).put("end", e.endAt).put("title", e.title).put("summary", e.summary)
                .put("points", JSONArray(e.pointsJson)).put("people", JSONArray(e.peopleJson)).put("scene", e.scene).put("important", e.important).put("starred", e.starred).put("starredAt", e.starredAt ?: JSONObject.NULL))
        }
        data.items.forEach { i -> put("item:${i.id}", JSONObject().put("id", i.id).put("eventId", i.eventId).put("category", i.category).put("kind", i.kind ?: JSONObject.NULL)
            .put("content", i.content).put("status", i.status).put("at", i.createdAt).put("due", i.dueAt ?: i.dueText ?: JSONObject.NULL)
            .put("doneAt", i.doneAt ?: JSONObject.NULL).put("who", i.who ?: JSONObject.NULL).put("repeats", data.repeats[i.id] ?: 1)) }
        data.cards.forEach { c -> put("card:${c.id}", JSONObject().put("id", c.id).put("date", LocalDate.ofEpochDay(c.date)).put("title", c.title)
            .put("skill", c.skillName).put("summary", c.summary).put("fields", JSONArray(c.fieldsJson)).put("values", JSONObject(c.valuesJson)).put("at", c.at)) }
        data.reviews.forEach { r -> put("review:${r.id}", JSONObject().put("id", r.id).put("type", r.type).put("startDate", r.startDate).put("endDate", r.endDate)
            .put("brief", r.brief).put("sections", JSONArray(r.sectionsJson))) }
        data.lines.forEach { l -> put("line:${l.id}", JSONObject().put("id", l.id).put("at", l.startAt).put("end", l.endAt)
            .put("eventId", l.eventId ?: JSONObject.NULL).put("speaker", l.speakerName ?: l.speakerTag ?: "某人").put("text", l.text)) }
    }
    fun changes(current: Map<String, JSONObject>, old: JSONObject): JSONObject {
        val changes = JSONArray()
        fun entry(op: String, key: String, data: JSONObject) = JSONObject().put("op", op).put("kind", key.substringBefore(':')).put("id", key.substringAfter(':').toLong()).put("data", data)
        current.toSortedMap().forEach { (key, data) -> if (old.optString(key) != sha256(canonical(data))) changes.put(entry("upsert", key, data)) }
        old.keys().asSequence().toList().sorted().filter { it !in current }.forEach { changes.put(entry("delete", it, JSONObject())) }
        return JSONObject().put("type", "records_delta").put("version", 1).put("changes", changes)
    }
    fun acknowledge(current: Map<String, JSONObject>, state: PushSyncState) {
        state.records.keys().asSequence().toList().forEach { state.records.remove(it) }
        current.forEach { (key, data) -> state.records.put(key, sha256(canonical(data))) }; state.save()
    }
}
