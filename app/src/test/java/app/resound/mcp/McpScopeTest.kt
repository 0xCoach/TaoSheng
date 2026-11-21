package app.resound.mcp

import android.app.Application
import app.resound.collect.CaptureItemRow
import app.resound.data.CaptureDatabase
import app.resound.data.open
import app.resound.decision.DecisionRow
import app.resound.decision.DecisionStatus
import app.resound.followup.FollowupRow
import app.resound.inbox.IdeaRow
import app.resound.projects.ProjectRow
import app.resound.search.RecordSearch
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.io.File
import java.net.Socket
import java.util.UUID

/**
 * MCP 项目范围回归：每个只读入口的权限必须在数据返回前生效。
 * 覆盖：无 scope 客户端 / 限定 scope 客户端 / 直接 ID 猜测 / 跨项目关联 / 无效与停用令牌。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class McpScopeTest {
    private lateinit var db: CaptureDatabase
    private lateinit var server: McpServer
    private var port = 0
    private val tokens = mutableMapOf<Long, String>()
    private val projects = listOf(
        ProjectRow(id = 1, name = "阿尔法项目", note = "甲方合同", createdAt = 1, updatedAt = 1),
        ProjectRow(id = 2, name = "贝塔项目", note = "竞品调研", createdAt = 1, updatedAt = 1),
    )
    private var scopedClientId = 0L
    private var globalClientId = 0L
    private var foreignIdeaId = 0L
    private var ownIdeaId = 0L
    private var globalIdeaId = 0L
    private var foreignDecisionId = 0L

    @Before fun setUp() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        db = CaptureDatabase.open(context, File(context.cacheDir, "mcp-scope-${UUID.randomUUID()}.db").path)
        // 数据：贝塔项目(2)的灵感/决策/收集项 = 限定阿尔法(1)的客户端不可见
        foreignIdeaId = db.ideas().insert(IdeaRow(title = "贝塔机密方案", content = "竞品定价策略", projectId = 2, createdAt = 1, updatedAt = 1))
        ownIdeaId = db.ideas().insert(IdeaRow(title = "阿尔法排期", content = "下周签合同", projectId = 1, createdAt = 1, updatedAt = 1))
        globalIdeaId = db.ideas().insert(IdeaRow(title = "通用灵感", content = "没有归属", projectId = null, createdAt = 1, updatedAt = 1))
        foreignDecisionId = db.decisions().insert(DecisionRow(title = "贝塔定价决定", detail = "按竞品九折", projectId = 2, decidedAt = 1, createdAt = 1, updatedAt = 1))
        db.decisions().insert(DecisionRow(title = "阿尔法排期决定", detail = "周五前", projectId = 1, decidedAt = 1, createdAt = 1, updatedAt = 1))
        db.decisions().insert(DecisionRow(title = "全局决定", detail = "谁都能看", projectId = null, decidedAt = 1, createdAt = 1, updatedAt = 1))
        db.captureItems().insert(CaptureItemRow(kind = "Url", body = "https://beta-secret", title = "贝塔链接", projectId = 2, createdAt = 1, updatedAt = 1))
        db.captureItems().insert(CaptureItemRow(kind = "Text", body = "alpha-note", title = "阿尔法便签", projectId = 1, createdAt = 1, updatedAt = 1))
        // 跟进项无 projectId，靠 refKind/refId 归因
        db.followups().insert(FollowupRow(kind = "Todo", refKind = "idea", refId = foreignIdeaId, title = "跟进贝塔定价", createdAt = 1, updatedAt = 1))
        db.followups().insert(FollowupRow(kind = "Todo", refKind = "idea", refId = ownIdeaId, title = "跟进阿尔法合同", createdAt = 1, updatedAt = 1))
        db.followups().insert(FollowupRow(kind = "Todo", title = "无归属跟进", createdAt = 1, updatedAt = 1))
        globalClientId = db.mcpClients().insert(McpClientRow(name = "全局", projectScope = null, createdAt = 1))
        scopedClientId = db.mcpClients().insert(McpClientRow(name = "限定阿尔法", projectScope = 1, createdAt = 1))
        tokens[globalClientId] = "tok-global-${UUID.randomUUID()}"
        tokens[scopedClientId] = "tok-scoped-${UUID.randomUUID()}"
        server = McpServer(db, RecordSearch(db), { tokens[it] }, { projects })
        server.start(0)
        port = server.boundPort ?: error("MCP 未绑定端口")
        Unit
    }

    @After fun tearDown() { server.stop(); db.close() }

    private fun post(token: String?, body: String): String {
        Socket("127.0.0.1", port).use { s ->
            val req = buildString {
                append("POST /mcp HTTP/1.1\r\nHost: localhost\r\nContent-Type: application/json\r\n")
                if (token != null) append("Authorization: Bearer $token\r\n")
                val bytes = body.toByteArray(Charsets.UTF_8)
                append("Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").append(body)
            }
            s.soTimeout = 15_000
            // 必须按 UTF-8 写字节：请求头 Content-Length 按 UTF-8 字节数计算（ISO_8859_1 会把汉字缩成 '?' 导致服务端等不到字节）
            s.getOutputStream().write(req.toByteArray(Charsets.UTF_8))
            return s.getInputStream().bufferedReader(Charsets.UTF_8).readText()
        }
    }

    private fun call(token: String, name: String, args: String = "{}"): String {
        val resp = post(token, """{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"$name","arguments":$args}}""")
        assertTrue("HTTP 状态非 200：$resp", resp.startsWith("HTTP/1.1 200"))
        val content = JSONObject(resp.substringAfter("\r\n\r\n")).getJSONObject("result")
        return if (content.has("content")) content.getJSONArray("content").getJSONObject(0).getString("text")
            else content.toString()
    }

    @Test fun missingOrInvalidTokenRejected() {
        assertTrue(post(null, "{}").startsWith("HTTP/1.1 401"))
        assertTrue(post("tok-nonexistent", """{"method":"tools/list"}""").startsWith("HTTP/1.1 401"))
    }

    @Test fun disabledClientTokenRejected() = runBlocking {
        db.mcpClients().setEnabled(scopedClientId, false)
        assertTrue(post(tokens[scopedClientId], """{"method":"tools/list"}""").startsWith("HTTP/1.1 401"))
    }

    @Test fun recordGetCrossProjectIdGuessFails() {
        // 直接 ID 查询不能绕过 inScope：贝塔(2)的灵感对阿尔法(1)客户端返回"不存在"
        assertEquals("不存在", call(tokens[scopedClientId]!!, "record_get", """{"kind":"idea","id":"$foreignIdeaId"}"""))
        assertEquals("不存在", call(tokens[scopedClientId]!!, "record_get", """{"kind":"decision","id":"$foreignDecisionId"}"""))
        // 本项目与全局记录正常可读
        assertTrue(call(tokens[scopedClientId]!!, "record_get", """{"kind":"idea","id":"$ownIdeaId"}""").contains("阿尔法排期"))
        assertTrue(call(tokens[scopedClientId]!!, "record_get", """{"kind":"idea","id":"$globalIdeaId"}""").contains("通用灵感"))
        // 全局客户端不受限
        assertTrue(call(tokens[globalClientId]!!, "record_get", """{"kind":"idea","id":"$foreignIdeaId"}""").contains("贝塔机密方案"))
    }

    @Test fun ideaListAndSearchRespectScope() {
        val list = call(tokens[scopedClientId]!!, "idea_list")
        assertTrue(list.contains("阿尔法排期")); assertTrue(list.contains("通用灵感")); assertFalse(list.contains("贝塔机密方案"))
        val hits = call(tokens[scopedClientId]!!, "memory_search", """{"query":"定价"}""")
        assertFalse(hits.contains("贝塔机密方案")); assertFalse(hits.contains("竞品定价策略"))
        // 全局客户端能搜到
        assertTrue(call(tokens[globalClientId]!!, "memory_search", """{"query":"定价"}""").contains("贝塔机密方案"))
    }

    @Test fun decisionListShowsOwnAndGlobalNotForeign() {
        val list = call(tokens[scopedClientId]!!, "decision_list")
        assertTrue(list.contains("阿尔法排期决定")); assertTrue(list.contains("全局决定")); assertFalse(list.contains("贝塔定价决定"))
    }

    @Test fun captureListRespectsScope() {
        val list = call(tokens[scopedClientId]!!, "capture_list")
        assertTrue(list.contains("阿尔法便签")); assertFalse(list.contains("贝塔链接"))
    }

    @Test fun followupListHidesForeignRefs() {
        val list = call(tokens[scopedClientId]!!, "followup_list")
        assertTrue(list.contains("跟进阿尔法合同")); assertTrue(list.contains("无归属跟进"))
        assertFalse(list.contains("跟进贝塔定价"))
    }

    @Test fun projectListAndMarksRespectScope() {
        val list = call(tokens[scopedClientId]!!, "project_list")
        assertTrue(list.contains("阿尔法项目")); assertFalse(list.contains("贝塔项目"))
        // 限定客户端 project_marks 恒走自身 scope，传别人 projectId 也拿不到
        val marks = call(tokens[scopedClientId]!!, "project_marks", """{"projectId":"2"}""")
        assertFalse(marks.contains("贝塔定价决定"))
    }

    @Test fun toolWhitelistEnforced() = runBlocking {
        val limitedId = db.mcpClients().insert(McpClientRow(name = "只给搜索", projectScope = 1,
            toolsJson = """["memory_search"]""", createdAt = 1))
        tokens[limitedId] = "tok-limited-${UUID.randomUUID()}"
        assertTrue(call(tokens[limitedId]!!, "memory_search", """{"query":"x"}""").isNotEmpty())
        assertTrue(call(tokens[limitedId]!!, "record_get", """{"kind":"idea","id":"$ownIdeaId"}""").contains("无权调用"))
    }

    @Test fun noWriteToolsExposed() {
        val resp = post(tokens[globalClientId], """{"jsonrpc":"2.0","id":1,"method":"tools/list"}""")
        val tools = JSONObject(resp.substringAfter("\r\n\r\n")).getJSONObject("result").getJSONArray("tools")
        val names = (0 until tools.length()).map { tools.getJSONObject(it).getString("name") }
        assertEquals(McpTools.readOnly.map { it.name }.toSet(), names.toSet())
        listOf("record_update", "record_delete", "agent_dispatch", "settings_set").forEach {
            assertFalse(it in names)
        }
    }
}
