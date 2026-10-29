package app.resound.push

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.Base64
import java.net.URLEncoder
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

internal class PushFailure(message: String) : IOException(message)
internal data class PushMessage(val title: String, val body: String, val topic: PushTopic?, val deepLink: String?, val at: Long,
    val review: app.resound.review.DailyReview? = null, val memory: JSONObject? = null,
    val memoryDate: String? = null, val memoryWithLines: Boolean = false) {
    fun rendered(format: PushFormat): String = review?.pushBody(format) ?: PushTransport.renderedBody(body, format)
    // 内部重试快照与对外协议分开；快照保留完整文档，不在重试时改变日期或带原话选项。
    fun json() = JSONObject().put("title", title).put("body", body).put("topic", topic?.name ?: JSONObject.NULL)
        .put("deepLink", deepLink ?: JSONObject.NULL).put("at", at).apply {
            review?.let { put("review", it.pushJson()) }; memory?.let { put("memory", it) }
            memoryDate?.let { put("memoryDate", it).put("memoryWithLines", memoryWithLines) }
        }
    fun envelope(format: PushFormat): JSONObject = memory?.let { JSONObject(it.toString()) } ?: json().put("body", rendered(format))
        .put("type", when (topic) {
            PushTopic.DailyReview -> "daily_review"; PushTopic.TodoReminder -> "todo_reminder"
            PushTopic.NewEvent -> "new_event"; PushTopic.SkillCard -> "skill_card"
            PushTopic.DailyMemory -> "daily_memory"; null -> "test"
        }).put("version", 1).put("app", "resound")
    companion object {
        fun from(json: JSONObject) = PushMessage(json.getString("title"), json.getString("body"),
            PushTopic.entries.find { it.name == json.optString("topic") },
            if (json.isNull("deepLink")) null else json.getString("deepLink"), json.getLong("at"),
            json.optJSONObject("review")?.let(::reviewFromPushJson), json.optJSONObject("memory"),
            if (json.isNull("memoryDate")) null else json.getString("memoryDate"), json.optBoolean("memoryWithLines", false))
    }
}

/** 不跟随跳转，不保留原始异常及响应。所有多步骤请求也走同一安全 HTTP 层。 */
internal class PushTransport(client: OkHttpClient = OkHttpClient(), private val smtp: SmtpSender = SmtpSender(),
    private val now: () -> Long = System::currentTimeMillis) {
    private val http = PushHttp(client)
    private val notion = NotionPush(http)
    private val folder = MarkdownFolderPush(http)
    internal fun syncFolder(values: Map<String, String>, state: PushSyncState, files: Map<String, String>, category: Boolean, now: Long, limit: Int = 20): SyncBatch {
        return classified(values) {
            PushFields.validate(PushKind.MarkdownFolder, values)
            FolderSync(FolderRemote(values, http), state).run(files, category, now, limit)
        }
    }
    internal fun deleteNotionDay(values: Map<String, String>, date: String) {
        classified(values) {
            notion.send(values, PushFormat.Markdown, PushMessage("", "", PushTopic.DailyMemory, null, now(),
                memory = JSONObject().put("date", date).put("markdown", "")), archiveOnly = true)
        }
    }
    private fun <T> classified(values: Map<String, String>, block: () -> T): T = try { block() }
    catch (e: kotlinx.coroutines.CancellationException) { throw e }
    catch (e: Exception) { throw PushErrors.failure(e, values) }

    internal fun request(kind: PushKind, format: PushFormat, values: Map<String, String>, message: PushMessage): Request {
        PushFields.validate(kind, values)
        val builder = Request.Builder()
        val markdown = format != PushFormat.Text
        val textFormat = if (markdown) PushFormat.Markdown else PushFormat.Text
        val body: RequestBody = when (kind) {
            PushKind.PushPlus -> {
                builder.url("https://www.pushplus.plus/send")
                JSONObject().put("token", values.getValue("token")).put("title", message.title)
                    .put("content", if (format == PushFormat.Json) message.envelope(format).toString() else message.rendered(format))
                    .put("template", when (format) { PushFormat.Text -> "txt"; PushFormat.Markdown -> "markdown"; PushFormat.Json -> "json" })
                    .apply { values["topic"]?.takeIf(String::isNotBlank)?.let { put("topic", it) } }.body()
            }
            PushKind.WeCom -> {
                builder.url(values.getValue("webhook"))
                val type = if (markdown) "markdown" else "text"
                JSONObject().put("msgtype", type).put(type, JSONObject().put("content", "${message.title}\n${message.rendered(textFormat)}")).body()
            }
            PushKind.ServerChan -> {
                builder.url("https://sctapi.ftqq.com/${values.getValue("sendKey")}.send")
                FormBody.Builder().add("title", message.title).add("desp", message.rendered(textFormat)).build()
            }
            PushKind.Webhook -> {
                builder.url(values.getValue("url"))
                values["authorization"]?.takeIf(String::isNotBlank)?.let { builder.header("Authorization", it) }
                PushFields.headers(values["headers"].orEmpty()).forEach { (key, value) -> builder.header(key, value) }
                val envelope = message.envelope(PushFormat.Json)
                val content = when (format) {
                    PushFormat.Json -> envelope.toString()
                    PushFormat.Markdown -> message.memory?.getString("markdown")
                        ?: "# ${message.title}\n\n${message.rendered(PushFormat.Markdown)}"
                    PushFormat.Text -> renderedBody(message.memory?.getString("markdown")
                        ?: "${message.title}\n${message.rendered(PushFormat.Text)}", PushFormat.Text)
                }
                val date = message.review?.date?.toString() ?: message.memoryDate
                    ?: message.memory?.optString("date")?.takeIf(String::isNotBlank)
                    ?: Instant.ofEpochMilli(now()).atZone(ZoneId.systemDefault()).toLocalDate().toString()
                builder.header("X-Resound-Type", envelope.getString("type"))
                    .header("X-Resound-Date", date)
                    .header("X-Resound-Title", URLEncoder.encode(message.title, "UTF-8"))
                values["signSecret"]?.takeIf(String::isNotBlank)?.let {
                    val timestamp = (now() / 1000).toString()
                    builder.header("X-Resound-Timestamp", timestamp).header("X-Resound-Signature", "sha256=" + hmac(it, "$timestamp.$content").joinToString("") { b -> "%02x".format(b.toInt() and 255) })
                }
                content.toRequestBody(when (format) {
                    PushFormat.Json -> "application/json; charset=utf-8"
                    PushFormat.Markdown -> "text/markdown; charset=utf-8"
                    PushFormat.Text -> "text/plain; charset=utf-8"
                }.toMediaType())
            }
            PushKind.Feishu -> {
                builder.url(values.getValue("webhook"))
                val json = if (!markdown) JSONObject().put("msg_type", "text").put("content", JSONObject().put("text", "${message.title}\n${message.rendered(format)}"))
                else JSONObject().put("msg_type", "interactive").put("card", JSONObject()
                    .put("header", JSONObject().put("title", JSONObject().put("tag", "plain_text").put("content", message.title)))
                    .put("elements", JSONArray().put(JSONObject().put("tag", "markdown").put("content", message.rendered(PushFormat.Markdown)))))
                values["signSecret"]?.takeIf(String::isNotBlank)?.let { secret ->
                    val timestamp = now() / 1000
                    json.put("timestamp", timestamp.toString()).put("sign", feishuSign(timestamp, secret))
                }
                json.body()
            }
            PushKind.DingTalk -> {
                val url = values.getValue("webhook").toHttpUrl().newBuilder()
                values["signSecret"]?.takeIf(String::isNotBlank)?.let { secret ->
                    val timestamp = now()
                    url.setQueryParameter("timestamp", timestamp.toString()).setQueryParameter("sign", dingTalkSign(timestamp, secret))
                }
                builder.url(url.build())
                if (markdown) JSONObject().put("msgtype", "markdown").put("markdown", JSONObject().put("title", message.title).put("text", "## ${message.title}\n\n${message.rendered(PushFormat.Markdown)}")).body()
                else JSONObject().put("msgtype", "text").put("text", JSONObject().put("content", "${message.title}\n${message.rendered(format)}")).body()
            }
            PushKind.Telegram -> {
                val base = PushFields.default(values, "baseUrl", "https://api.telegram.org").trimEnd('/')
                if (message.memory != null) {
                    builder.url("$base/bot${values.getValue("token")}/sendDocument")
                    MultipartBody.Builder().setType(MultipartBody.FORM).addFormDataPart("chat_id", values.getValue("chatId"))
                        .addFormDataPart("caption", truncate(message.body, 1000))
                        .addFormDataPart("document", "记忆-${message.memory.getString("date")}.md", message.memory.getString("markdown").toRequestBody("text/markdown; charset=utf-8".toMediaType())).build()
                } else {
                    builder.url("$base/bot${values.getValue("token")}/sendMessage")
                    // 先限制可见字符，再转义，保证不会截断 HTML 实体或标签。
                    val title = truncate(message.title, 1000)
                    val text = truncate(message.rendered(if (markdown) PushFormat.Markdown else PushFormat.Text), 4000 - title.length - 1)
                    JSONObject().put("chat_id", values.getValue("chatId"))
                        .put("text", if (markdown) "<b>${html(title)}</b>\n${html(text)}" else "$title\n$text")
                        .apply { if (markdown) put("parse_mode", "HTML") }.body()
                }
            }
            PushKind.Email, PushKind.Notion, PushKind.MarkdownFolder -> throw PushFailure("该通道需要多步发送")
        }
        return builder.post(body).build()
    }
    suspend fun send(kind: PushKind, format: PushFormat, values: Map<String, String>, message: PushMessage): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            PushFields.validate(kind, values)
            when (kind) {
                PushKind.Email -> smtp.send(values, format, message)
                PushKind.Notion -> notion.send(values, format, message)
                PushKind.MarkdownFolder -> folder.send(values, format, message)
                else -> {
                    val response = http.context(kind, values).execute(request(kind, format, values, message), readBody = kind != PushKind.Webhook)
                    if (kind != PushKind.Webhook) {
                        val json = response.json()
                        if (kind == PushKind.Telegram) {
                            if (json.opt("ok") != true) throw PushFailure(PushErrors.refused(kind, json, values))
                        } else {
                            val field = if (kind in setOf(PushKind.WeCom, PushKind.DingTalk)) "errcode" else "code"
                            val expected = if (kind == PushKind.PushPlus) 200.0 else 0.0
                            if (json.opt(field) !is Number || json.getDouble(field) != expected) throw PushFailure(PushErrors.refused(kind, json, values))
                        }
                    }
                }
            }
            Result.success(Unit)
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { Result.failure(PushErrors.failure(e, values)) }
    }
    companion object {
        internal fun feishuSign(timestamp: Long, secret: String) = Base64.getEncoder().encodeToString(hmac("$timestamp\n$secret", ""))
        internal fun dingTalkSign(timestamp: Long, secret: String) = Base64.getEncoder().encodeToString(hmac(secret, "$timestamp\n$secret"))
        internal fun renderedBody(body: String, format: PushFormat): String = if (format != PushFormat.Text) body else body
            .replace(Regex("(?m)^\\s{0,3}#{1,6}\\s+"), "")
            .replace(Regex("!?\\[([^\\]]*)\\]\\(([^)]+)\\)"), "$1 ($2)")
            .replace(Regex("(?m)^([ \\t]*)[-*+]\\s+\\[[ xX]\\]\\s*"), "$1")
            .replace(Regex("(?m)^([ \\t]*)[-*+]\\s+"), "$1")
            .replace(Regex("(?m)^\\s*>\\s?"), "")
            .replace("**", "").replace("__", "").replace("`", "")
    }
}
internal fun hmac(secret: String, text: String): ByteArray = Mac.getInstance("HmacSHA256").run {
    init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256")); doFinal(text.toByteArray(Charsets.UTF_8))
}
internal fun String.jsonBody() = toRequestBody("application/json; charset=utf-8".toMediaType())
internal fun JSONObject.body() = toString().jsonBody()
internal fun html(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
internal fun truncate(text: String, limit: Int): String {
    if (text.length <= limit) return text
    val suffix = "\n（已截断）"
    var end = (limit - suffix.length).coerceAtLeast(0)
    if (end > 0 && text[end - 1].isHighSurrogate()) end--
    return text.take(end) + suffix
}
internal data class PushResponse(val code: Int, val text: String, val etag: String?) {
    fun json(): JSONObject = try { JSONObject(text) } catch (_: Exception) { throw PushFailure(PushErrors.INVALID_RESPONSE) }
}
internal class PushHttp(client: OkHttpClient, private val kind: PushKind? = null,
    private val values: Map<String, String> = emptyMap()) {
    fun context(kind: PushKind, values: Map<String, String>) = PushHttp(client, kind, values)
    private val client = client.newBuilder().followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
        .callTimeout(10, TimeUnit.SECONDS).connectTimeout(10, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).writeTimeout(10, TimeUnit.SECONDS).build()
    fun execute(request: Request, allowed: Set<Int> = emptySet(), readBody: Boolean = true): PushResponse = try {
        executeRaw(request, allowed, readBody)
    } catch (e: kotlinx.coroutines.CancellationException) { throw e }
    catch (e: Exception) { throw PushErrors.failure(e, values, request.url.toString()) }

    private fun executeRaw(request: Request, allowed: Set<Int>, readBody: Boolean): PushResponse = client.newCall(request).execute().use { response ->
        if (!response.isSuccessful && response.code !in allowed) {
            val conclusion = PushErrors.status(response.code, kind == PushKind.MarkdownFolder)
            // 非成功响应也可能包含服务原因；只读有限字节，绝不回显原响应。
            val json = runCatching { JSONObject(response.peekBody(65536).string()) }.getOrNull()
            val reason = if (kind != null && kind !in setOf(PushKind.Webhook, PushKind.MarkdownFolder) && json != null)
                PushErrors.refused(kind, json, values) else null
            throw PushFailure(conclusion + (reason?.let { "；$it" } ?: ""))
        }
        val body = if (readBody) response.body else null
        val text = if (body == null) "" else {
            if (body.contentLength() > 65536) throw PushFailure("推送服务响应过大")
            val output = java.io.ByteArrayOutputStream()
            val input = body.byteStream()
            val buffer = ByteArray(4096)
            while (output.size() < 65536) {
                val n = input.read(buffer, 0, minOf(buffer.size, 65536 - output.size()))
                if (n < 0) break
                output.write(buffer, 0, n)
            }
            if (output.size() == 65536) throw PushFailure("推送服务响应过大")
            output.toString("UTF-8")
        }
        PushResponse(response.code, text, response.header("ETag"))
    }
}
