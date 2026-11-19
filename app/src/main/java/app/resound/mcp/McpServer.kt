package app.resound.mcp

import android.util.Log
import app.resound.data.CaptureDatabase
import app.resound.inbox.IdeaRules
import app.resound.search.RecordSearch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.InetSocketAddress
import java.net.ServerSocket
import kotlin.concurrent.thread

/**
 * 只读本地 MCP 服务：绑 127.0.0.1，HTTP POST /mcp，Bearer 令牌认证。
 * tools/list + tools/call：memory_search / decision_list / project_overview（只读，写工具不外放）。
 * 配对客户端走 mcp_clients 表，token 存 SecretStore；project_scope 空 = 全部可见，否则限定项目。
 */
class McpServer(
    private val db: CaptureDatabase,
    private val search: RecordSearch,
    /** 取配对令牌：key 形如 "mcp_<rowId>"，存 SecretStore */
    private val tokenStore: (clientRowId: Long) -> String?,
    private val projects: suspend () -> List<app.resound.projects.ProjectRow>,
) {
    private var socket: ServerSocket? = null
    private var scope: CoroutineScope? = null
    @Volatile var running = false; private set
    private val json = Json { ignoreUnknownKeys = true }

    fun start(port: Int = 7391) {
        if (running) return
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        this.scope = scope
        try {
            socket = ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress("127.0.0.1", port))
            }
        } catch (e: Exception) { Log.w(TAG, "MCP 端口绑定失败: ${e.message}"); return }
        running = true
        thread(name = "mcp-accept", isDaemon = true) {
            while (running) {
                val conn = try { socket?.accept() } catch (_: Exception) { null } ?: break
                thread(name = "mcp-io", isDaemon = true) { serve(conn) }
            }
        }
    }

    fun stop() { running = false; try { socket?.close() } catch (_: Exception) {}; socket = null; scope?.cancel(); scope = null }

    private fun serve(conn: java.net.Socket) {
        conn.use { s ->
            try {
                // ISO-8859-1：1 字节 = 1 字符，Content-Length 字节数可直接按字符读；body 再按 UTF-8 解码
                val head = s.getInputStream().bufferedReader(Charsets.ISO_8859_1).let { r ->
                    val sb = StringBuilder()
                    while (true) { val line = r.readLine() ?: break; if (line.isEmpty()) break; sb.append(line).append('\n'); if (sb.length > 8192) break }
                    sb.toString() to r
                }
                val lines = head.first.split("\n").filter { it.isNotBlank() }
                val requestLine = lines.firstOrNull() ?: return@use
                val headers = lines.drop(1).mapNotNull { val i = it.indexOf(':'); if (i > 0) it.substring(0, i).trim().lowercase() to it.substring(i + 1).trim() else null }.toMap()
                val len = headers["content-length"]?.toIntOrNull()?.coerceIn(0, 256 * 1024) ?: 0
                val buf = CharArray(len); var off = 0
                while (off < len) { val n = head.second.read(buf, off, len - off); if (n < 0) break; off += n }
                val body = String(String(buf, 0, off).toByteArray(Charsets.ISO_8859_1), Charsets.UTF_8)
                val reply = handle(requestLine, headers, body)
                s.getOutputStream().write(reply.toByteArray(Charsets.UTF_8))
            } catch (e: Exception) { Log.w(TAG, "MCP 连接处理失败: ${e.message}") }
        }
    }

    private fun handle(requestLine: String, headers: Map<String, String>, body: String): String {
        val out = StringBuilder("HTTP/1.1 ")
        fun respond(code: Int, text: String) {
            val bytes = text.toByteArray(Charsets.UTF_8)
            out.append(if (code == 200) "200 OK" else if (code == 401) "401 Unauthorized" else "400 Bad Request")
                .append("\r\nContent-Type: application/json; charset=utf-8\r\nContent-Length: ").append(bytes.size)
                .append("\r\nConnection: close\r\n\r\n").append(text)
        }
        // 只接本机 POST /mcp
        if (!requestLine.startsWith("POST /mcp")) return out.append("404 Not Found\r\nContent-Length: 0\r\n\r\n").toString()
        val auth = headers["authorization"] ?: return respond(401, rpcError(null, -32001, "缺少 Bearer 令牌")).also { }.let { out.toString() }
        val token = auth.removePrefix("Bearer ").trim()
        val client = runCatching { kotlinx.coroutines.runBlocking { db.mcpClients().enabled() } }.getOrNull()?.firstOrNull {
            tokenStore(it.id) == token
        } ?: return respond(401, rpcError(null, -32001, "令牌无效")).let { out.toString() }
        kotlinx.coroutines.runBlocking { db.mcpClients().touch(client.id, System.currentTimeMillis()) }
        val req = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return respond(400, rpcError(null, -32700, "JSON 解析失败")).let { out.toString() }
        val id = req["id"] ?: JsonPrimitive(null as String?)
        val method = req["method"]?.jsonPrimitive?.content ?: ""
        val result = kotlinx.coroutines.runBlocking {
            when (method) {
                "initialize" -> buildJsonObject { put("protocolVersion", "2024-11-05"); put("capabilities", buildJsonObject { put("tools", buildJsonObject {}) }); put("serverInfo", buildJsonObject { put("name", "声声不息记忆"); put("version", "1") }) }
                "tools/list" -> toolList()
                "tools/call" -> callTool(req["params"]?.jsonObject, client)
                else -> buildJsonObject { put("error", "unsupported method: $method") }
            }
        }
        return respond(200, buildJsonObject { put("jsonrpc", "2.0"); put("id", id); put("result", result) }.toString()).let { out.toString() }
    }

    private fun toolList(): JsonObject = buildJsonObject {
        put("tools", buildJsonArray {
            McpTools.readOnly.forEach { tool ->
                add(buildJsonObject {
                    put("name", tool.name)
                    put("description", tool.description)
                    put("inputSchema", toolSchema(tool.name))
                })
            }
        })
    }

    private fun toolSchema(name: String): JsonObject = when (name) {
        "memory_search", "transcript_search" -> schema("query" to "搜索词")
        "decision_list" -> schema("status" to "Active/Rejected/Superseded/Withdrawn，可选")
        "project_marks" -> schema("projectId" to "项目编号（客户端已限定时可省）")
        "idea_list" -> schema("query" to "搜索词，可选", "status" to "状态，可选")
        "record_get" -> schema("kind" to "idea/decision", "id" to "记录编号")
        else -> schema()
    }

    private fun schema(vararg props: Pair<String, String>) = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject { props.forEach { (k, v) -> put(k, buildJsonObject { put("type", "string"); put("description", v) }) } })
    }

    private suspend fun callTool(params: JsonObject?, client: McpClientRow): JsonObject {
        val name = params?.get("name")?.jsonPrimitive?.content ?: return err("缺少 name")
        // 客户端白名单：toolsJson 非空时只允许列出的工具名
        val allowed = runCatching {
            json.parseToJsonElement(client.toolsJson).let { el ->
                if (el is kotlinx.serialization.json.JsonArray) el.map { it.jsonPrimitive.content }.toSet() else emptySet()
            }
        }.getOrDefault(emptySet())
        if (allowed.isNotEmpty() && name !in allowed) return err("该客户端无权调用：$name")
        if (!McpTools.exists(name) && name != "project_overview") return err("未知工具：$name")
        val args = params["arguments"]?.jsonObject
        // projectScope：null = 可查全局；否则仅限该项目及无项目归属的全局记录（原话/事件）
        val scopeId = client.projectScope
        fun inScope(projectId: Long?) = scopeId == null || projectId == null || projectId == scopeId
        fun arg(k: String) = args?.get(k)?.jsonPrimitive?.content
        val text = when (name) {
            "memory_search", "transcript_search" -> {
                val q = arg("query")?.take(60) ?: return err("缺少 query")
                val hits = search.search(q).take(10).map { "[${it.kind}#${it.id}] ${it.at} ${it.speaker ?: ""} ${it.text.take(160)}" }
                val ideas = if (name == "transcript_search") emptyList() else db.ideas().search(IdeaRules.likePattern(q)).first().filter { inScope(it.projectId) }.take(5)
                    .map { "[idea#${it.id}] ${it.title}：${it.content.take(120)}" }
                val decs = if (name == "transcript_search") emptyList() else db.decisions().search(IdeaRules.likePattern(q)).first().filter { inScope(it.projectId) }.take(5)
                    .map { "[decision#${it.id}] (${it.status}) ${it.title}：${it.detail.take(120)}" }
                (hits + ideas + decs).ifEmpty { listOf("没有匹配的记录") }.joinToString("\n")
            }
            "decision_list" -> {
                // observeHistory 只含非 Active：生效决策必须合并进来，否则 AI 看不到当前决定
                val list = if (scopeId == null)
                    (db.decisions().observeActive().first() + db.decisions().observeHistory().first()).take(50)
                else db.decisions().observeForProject(scopeId).first()
                list.filter { inScope(it.projectId) && (arg("status")?.let { s -> it.status == s } ?: true) }
                    .joinToString("\n") { "[#${it.id}] (${it.status}/${it.confidence}) ${it.title} — ${it.detail.take(140)}" }
                    .ifEmpty { "没有匹配的决策" }
            }
            "project_list", "project_overview" -> projects().filter { inScope(it.id) }
                .joinToString("\n") { "[#${it.id}] ${it.name} (${it.status}) ${it.note.take(140)}" }
                .ifEmpty { "没有可见项目" }
            "project_marks" -> {
                val pid = scopeId ?: arg("projectId")?.toLongOrNull() ?: return err("缺少 projectId 或客户端无限定项目")
                db.decisions().observeForProject(pid).first()
                    .joinToString("\n") { "[#${it.id}] (${it.status}) ${it.title} — ${it.detail.take(140)}" }
                    .ifEmpty { "该项目暂无决策记录" }
            }
            "idea_list" -> {
                db.ideas().search(IdeaRules.likePattern(arg("query") ?: "")).first()
                    .filter { inScope(it.projectId) && (arg("status")?.let { s -> it.status == s } ?: true) }.take(30)
                    .joinToString("\n") { "[idea#${it.id}] (${it.status}) ${it.title}" }
                    .ifEmpty { "没有匹配的灵感" }
            }
            "followup_list" -> db.followups().observeOpen().first().take(30)
                .joinToString("\n") { "[followup#${it.id}] (${it.kind}) ${it.title} 下次检查=${it.nextCheckAt}" }
                .ifEmpty { "没有待跟进事项" }
            "capture_list" -> db.captureItems().observeAll().first().take(30)
                .filter { inScope(it.projectId) }
                .joinToString("\n") { "[capture#${it.id}] (${it.kind}) ${it.title.take(140)}" }
                .ifEmpty { "收集箱为空" }
            "record_get" -> {
                val kind = arg("kind") ?: return err("缺少 kind")
                val rid = arg("id")?.toLongOrNull() ?: return err("缺少 id")
                when (kind) {
                    "idea" -> db.ideas().idea(rid)?.takeIf { inScope(it.projectId) }?.let { "${it.title}\n${it.content}" } ?: "不存在"
                    "decision" -> db.decisions().decision(rid)?.takeIf { inScope(it.projectId) }?.let { "(${it.status}) ${it.title}\n${it.detail}" } ?: "不存在"
                    else -> "不支持的 kind：$kind（支持 idea/decision）"
                }
            }
            else -> return err("未知工具：$name")
        }
        return buildJsonObject { put("content", buildJsonArray { add(buildJsonObject { put("type", "text"); put("text", text) }) }) }
    }

    private fun err(msg: String) = buildJsonObject { put("isError", true); put("content", buildJsonArray { add(buildJsonObject { put("type", "text"); put("text", msg) }) }) }
    private fun rpcError(id: JsonObject?, code: Int, msg: String) = buildJsonObject {
        put("jsonrpc", "2.0"); put("id", JsonPrimitive(null as String?))
        put("error", buildJsonObject { put("code", code); put("message", msg) })
    }.toString()

    companion object { const val TAG = "McpServer"; const val PORT = 7391 }
}
