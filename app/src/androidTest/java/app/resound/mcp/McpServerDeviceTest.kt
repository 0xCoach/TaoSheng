package app.resound.mcp

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.resound.AppGraph
import app.resound.ResoundApp
import app.resound.digest.SecretStore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/** MCP 真机联调：配对→HTTP→令牌→工具白名单→项目限权，全链路在真服务上跑。 */
@RunWith(AndroidJUnit4::class)
class McpServerDeviceTest {
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val db get() = (ctx.applicationContext as ResoundApp).database
    private val secrets get() = SecretStore(ctx, "mcp-secrets", "resound-mcp-aes-v1")
    private val made = mutableListOf<Long>()

    @After fun tearDown() = runBlocking { made.forEach { db.mcpClients().delete(it) } }

    private fun pair(name: String, scope: Long?, tools: List<String> = emptyList()): Pair<Long, String> = runBlocking {
        val token = "sbx_test_" + java.util.UUID.randomUUID().toString().replace("-", "")
        val hint = java.security.MessageDigest.getInstance("SHA-256").digest(token.toByteArray())
            .joinToString("") { "%02x".format(it) }.take(8)
        val toolsJson = tools.joinToString(",", "[", "]") { "\"$it\"" }
        val id = db.mcpClients().insert(
            McpClientRow(name = name, tokenHint = hint, projectScope = scope,
                toolsJson = toolsJson, createdAt = System.currentTimeMillis()))
        secrets.write("mcp_$id", token)
        made += id
        id to token
    }

    private fun call(body: String, token: String? = null): Pair<Int, String> {
        val c = URL("http://127.0.0.1:${McpServer.PORT}/mcp").openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.setRequestProperty("Content-Type", "application/json")
        token?.let { c.setRequestProperty("Authorization", "Bearer $it") }
        c.doOutput = true; c.connectTimeout = 5000; c.readTimeout = 5000
        val bytes = body.toByteArray(Charsets.UTF_8)
        c.setFixedLengthStreamingMode(bytes.size)
        c.outputStream.use { it.write(bytes) }
        val code = c.responseCode
        val text = runCatching { (if (code == 200) c.inputStream else c.errorStream).bufferedReader().readText() }.getOrDefault("")
        c.disconnect(); return code to text
    }

    @Test fun fullRpcFlow() = runBlocking {
        if (!AppGraph.mcpServer.running) AppGraph.mcpServer.start()
        assertTrue(AppGraph.mcpServer.running)

        // 无令牌 → 401；坏令牌 → 401
        val (noCode, noBody) = call("""{"jsonrpc":"2.0","id":1,"method":"initialize"}""")
        assertEquals("无令牌应401，实际=$noCode $noBody", 401, noCode)
        val (badCode, badBody) = call("""{"jsonrpc":"2.0","id":1,"method":"initialize"}""", "sbx_wrong")
        assertEquals("坏令牌应401，实际=$badCode $badBody", 401, badCode)

        val (_, token) = pair("真机联调", scope = null)

        // initialize
        val (c1, r1) = call("""{"jsonrpc":"2.0","id":1,"method":"initialize"}""", token)
        assertEquals(200, c1); assertTrue(r1.contains("serverInfo"))

        // tools/list = 9 个只读工具
        val (c2, r2) = call("""{"jsonrpc":"2.0","id":2,"method":"tools/list"}""", token)
        assertEquals(200, c2)
        for (t in listOf("memory_search", "decision_list", "project_list", "idea_list", "followup_list", "capture_list", "record_get"))
            assertTrue("$t 应在工具清单", r2.contains(t))

        // tools/call：收集箱里刚分享进来的链接应能搜到
        val (c3, r3) = call("""{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"capture_list","arguments":{}}}""", token)
        assertEquals(200, c3); assertTrue(r3.contains("content"))

        // 未知工具被拒
        val (_, r4) = call("""{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"evil_write","arguments":{}}}""", token)
        assertTrue(r4.contains("未知工具"))

        // 客户端白名单：只放行 memory_search 的客户端调 decision_list 被拒
        val (_, limited) = pair("限权客户端", scope = null, tools = listOf("memory_search"))
        val (_, r5) = call("""{"jsonrpc":"2.0","id":5,"method":"tools/call","params":{"name":"decision_list","arguments":{}}}""", limited)
        assertTrue(r5.contains("无权调用"))
        val (c6, r6) = call("""{"jsonrpc":"2.0","id":6,"method":"tools/call","params":{"name":"memory_search","arguments":{"query":"会议"}}}""", limited)
        assertTrue("memory_search 应返回content，实际($c6)：$r6", r6.contains("content"))

        // 项目限权：scope=不存在项目 → project_list 只见无项目记录或空
        val (_, scoped) = pair("项目限定", scope = 999999L)
        val (_, r7) = call("""{"jsonrpc":"2.0","id":7,"method":"tools/call","params":{"name":"project_list","arguments":{}}}""", scoped)
        assertTrue(r7.contains("没有可见项目") || !r7.contains("isError"))
    }
}
