package app.resound.push

import okhttp3.Credentials
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId

internal class NotionPush(private val http: PushHttp) {
    // 按数据库和 token 隔离，避免更换账号后用到另一数据库的属性名。
    private val titles = java.util.concurrent.ConcurrentHashMap<String, String>()
    @Synchronized fun send(values: Map<String, String>, format: PushFormat, message: PushMessage, archiveOnly: Boolean = false) {
        val database = PushFields.databaseId(values.getValue("databaseId"))
        fun call(path: String, method: String = "GET", json: JSONObject? = null): JSONObject {
            val request = Request.Builder().url("https://api.notion.com/v1/$path")
                .header("Authorization", "Bearer ${values.getValue("token")}").header("Notion-Version", "2022-06-28")
                .method(method, json?.body()).build()
            val response = http.execute(request)
            if (response.code != 200) throw PushFailure("推送服务返回格式异常")
            return response.json()
        }
        val key = database + ":" + java.security.MessageDigest.getInstance("SHA-256").digest(values.getValue("token").toByteArray()).joinToString("") { "%02x".format(it) }
        val property = titles[key] ?: run {
            val properties = call("databases/$database").getJSONObject("properties")
            properties.keys().asSequence().firstOrNull { properties.getJSONObject(it).optString("type") == "title" }
                ?.also { titles[key] = it } ?: throw PushFailure("推送服务返回格式异常")
        }
        val daily = message.memory
        val title = if (daily == null) truncate(message.title, 2000) else "${daily.getString("date")} 的记忆文档"
        if (daily != null) {
            var cursor: String? = null
            val oldPages = mutableListOf<String>()
            do {
                val query = JSONObject().put("page_size", 10).put("filter", JSONObject().put("property", property).put("title", JSONObject().put("equals", title)))
                cursor?.let { query.put("start_cursor", it) }
                val result = call("databases/$database/query", "POST", query)
                val rows = result.getJSONArray("results")
                repeat(rows.length()) { oldPages += rows.getJSONObject(it).getString("id") }
                val next = if (result.optBoolean("has_more")) result.optString("next_cursor").takeIf { it.isNotBlank() && it != "null" } else null
                if (result.optBoolean("has_more") && (next == null || next == cursor)) throw PushFailure("推送服务返回格式异常")
                cursor = next
            } while (cursor != null)
            oldPages.forEach { call("pages/$it", "PATCH", JSONObject().put("archived", true)) }
        }
        if (archiveOnly) return
        val blocks = if (daily != null) blocks(daily.getString("markdown"), true) else blocks(message.rendered(if (format == PushFormat.Text) format else PushFormat.Markdown), false).take(90)
        val page = call("pages", "POST", JSONObject().put("parent", JSONObject().put("database_id", database))
            .put("properties", JSONObject().put(property, JSONObject().put("title", rich(title))))
            .put("children", JSONArray(blocks.take(90))))
        if (blocks.size > 90) {
            val id = page.getString("id")
            blocks.drop(90).chunked(90).forEach { call("blocks/$id/children", "PATCH", JSONObject().put("children", JSONArray(it))) }
        }
    }
    companion object {
        private fun rich(text: String) = JSONArray().put(JSONObject().put("type", "text").put("text", JSONObject().put("content", text)))
        internal fun blocks(markdown: String, styled: Boolean): List<JSONObject> = markdown.lines().filter(String::isNotBlank).flatMap { line ->
            var content = line
            val type = if (!styled) "paragraph" else when {
                line.startsWith("### ") -> { content = line.drop(4); "heading_3" }
                line.startsWith("## ") -> { content = line.drop(3); "heading_2" }
                line.startsWith("# ") -> { content = line.drop(2); "heading_1" }
                line.startsWith("- ") || line.startsWith("* ") -> { content = line.drop(2); "bulleted_list_item" }
                line.startsWith("> ") -> { content = line.drop(2); "quote" }
                else -> "paragraph"
            }
            splitText(content, 2000).map { JSONObject().put("object", "block").put("type", type).put(type, JSONObject().put("rich_text", rich(it))) }
        }
        private fun splitText(text: String, limit: Int): List<String> {
            val parts = mutableListOf<String>(); var start = 0
            while (start < text.length) {
                var end = minOf(start + limit, text.length)
                if (end < text.length && text[end - 1].isHighSurrogate()) end--
                parts += text.substring(start, end); start = end
            }
            return parts.ifEmpty { listOf("") }
        }
    }
}
internal class MarkdownFolderPush(private val http: PushHttp) {
    /** 同一进程内串行读改写；外部客户端并发由 ETag/If-Match 防止覆盖。 */
    @Synchronized fun send(values: Map<String, String>, format: PushFormat, message: PushMessage) {
        val daily = message.memory
        val time = Instant.ofEpochMilli(message.at).atZone(ZoneId.systemDefault())
        val date = daily?.getString("date") ?: time.toLocalDate().toString()
        val root = PushFields.default(values, "url", "https://dav.jianguoyun.com/dav/").toHttpUrl()
        val parts = values.getValue("folder").split('/') + if (daily == null) listOf("推送记录") else emptyList()
        fun url(path: List<String>) = root.newBuilder().apply {
            if (!root.encodedPath.endsWith('/')) addPathSegment("")
            path.forEach { addPathSegment(it) }
        }.build()
        fun request(path: List<String>) = Request.Builder().url(url(path)).header("Authorization", Credentials.basic(values.getValue("account"), values.getValue("password"), Charsets.UTF_8))
        val file = parts + "$date.md"
        val old = if (daily == null) http.execute(request(file).get().build(), setOf(404)) else null
        // MKCOL 已存在时标准应答为 405；其它错误不当作成功吞掉。
        parts.indices.forEach { i -> http.execute(request(parts.take(i + 1) + "").method("MKCOL", ByteArray(0).toRequestBody()).build(), setOf(405), false) }
        val content = if (daily != null) daily.getString("markdown") else {
            val prefix = old!!.text.takeIf { old.code != 404 }.orEmpty().trimEnd()
            (if (prefix.isEmpty()) "" else "$prefix\n\n") + "## ${app.resound.OutputTime().moment(message.at)} ${message.title}\n\n${message.rendered(if (format == PushFormat.Text) format else PushFormat.Markdown)}\n"
        }
        val put = request(file)
        if (old?.code == 404) put.header("If-None-Match", "*") else old?.etag?.let { put.header("If-Match", it) }
        http.execute(put.put(content.toRequestBody("text/markdown; charset=utf-8".toMediaType())).build(), readBody = false)
    }
}
