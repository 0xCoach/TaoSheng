package app.resound.ui.assistant

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.resound.AppGraph
import app.resound.mcp.McpClientRow
import app.resound.mcp.McpServer
import app.resound.mcp.McpTools
import app.resound.ui.theme.Rs
import app.resound.ui.theme.RsType
import app.resound.ui.verify.Pill
import app.resound.ui.verify.RsCard
import app.resound.ui.verify.formatStamp
import app.resound.ui.verify.springClick
import app.resound.digest.SecretStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.security.MessageDigest

/** MCP 本机只读接口：开关 + 配对客户端管理（令牌只显示一次，仅存指纹）。 */
@Composable
fun McpScreen(bottomPadding: Dp, onBack: () -> Unit) {
    val c = Rs.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences("assistant", Context.MODE_PRIVATE) }
    var enabled by remember { mutableStateOf(prefs.getBoolean("mcp_enabled", false) && AppGraph.mcpServer.running) }
    val clients by remember { (context.applicationContext as app.resound.ResoundApp).database.mcpClients().observeAll() }
        .collectAsStateWithLifecycle(initialValue = emptyList())
    var adding by remember { mutableStateOf(false) }
    var newToken by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(start = 16.dp, end = 16.dp, top = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("‹", style = RsType.largeTitle, color = c.textSecondary, modifier = Modifier.springClick(onBack).padding(end = 10.dp))
            Text("MCP", style = RsType.largeTitle, color = c.text, modifier = Modifier.weight(1f))
            Pill(if (enabled) "关闭" else "开启", filled = enabled) {
                enabled = !enabled
                prefs.edit().putBoolean("mcp_enabled", enabled).apply()
                if (enabled) AppGraph.mcpServer.start() else AppGraph.mcpServer.stop()
            }
            Spacer(Modifier.width(8.dp))
            Pill("配对客户端", filled = true) { adding = true }
        }
        Spacer(Modifier.height(12.dp))
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = bottomPadding + 16.dp)) {
            item {
                RsCard(padding = PaddingValues(14.dp)) {
                    Text(if (enabled) "监听中：127.0.0.1:${McpServer.PORT}（仅本机）" else "未开启", style = RsType.body.copy(fontWeight = FontWeight.SemiBold), color = c.text)
                    Spacer(Modifier.height(6.dp))
                    Text("只读查询：memory_search / decision_list / project_overview 等 ${McpTools.readOnly.size} 个工具。每个客户端一把令牌，可限定只看某个项目；不写、不执行、不出本机。", style = RsType.subhead, color = c.textSecondary)
                    Spacer(Modifier.height(6.dp))
                    Text("连接：POST http://127.0.0.1:${McpServer.PORT}/mcp，请求头 Authorization: Bearer <令牌>", style = RsType.caption, color = c.textTertiary)
                }
            }
            newToken?.let { token ->
                item {
                    RsCard(padding = PaddingValues(14.dp)) {
                        Text("新客户端令牌——只显示这一次，现在复制", style = RsType.subhead.copy(fontWeight = FontWeight.SemiBold), color = c.warning)
                        Spacer(Modifier.height(6.dp))
                        Text(token, style = RsType.caption, color = c.text)
                        Spacer(Modifier.height(8.dp))
                        Pill("已复制", filled = true) { newToken = null }
                    }
                }
            }
            items(clients, key = { it.id }) { client ->
                ClientCard(client)
            }
            if (clients.isEmpty()) item {
                RsCard { Text("还没有配对的客户端", style = RsType.body, color = c.text) }
            }
        }
    }
    if (adding) AddClientSheet(onToken = { token -> adding = false; newToken = token }, onCancel = { adding = false })
}

@Composable
private fun ClientCard(client: McpClientRow) {
    val c = Rs.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    RsCard(padding = PaddingValues(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(client.name, style = RsType.body.copy(fontWeight = FontWeight.SemiBold), color = c.text)
                Spacer(Modifier.height(4.dp))
                Text(
                    "范围 ${client.projectScope?.let { "项目 #$it" } ?: "全部"} · 令牌 …${client.tokenHint}" +
                        (if (client.lastSeenAt > 0) " · 上次使用 ${formatStamp(client.lastSeenAt)}" else " · 未用过"),
                    style = RsType.caption, color = c.textSecondary)
            }
            Pill(if (client.enabled) "停用" else "启用", filled = client.enabled) {
                scope.launch(Dispatchers.IO) {
                    (context.applicationContext as app.resound.ResoundApp).database.mcpClients().setEnabled(client.id, !client.enabled)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Pill("删除", filled = false) {
            scope.launch(Dispatchers.IO) {
                (context.applicationContext as app.resound.ResoundApp).database.mcpClients().delete(client.id)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddClientSheet(onToken: (String) -> Unit, onCancel: () -> Unit) {
    val c = Rs.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var scopeProject by remember { mutableStateOf("") }
    val projects by (if (AppGraph.isProjectsReady) AppGraph.projects.active() else kotlinx.coroutines.flow.flowOf(emptyList<app.resound.projects.ProjectRow>()))
        .collectAsStateWithLifecycle(initialValue = emptyList())

    ModalBottomSheet(onDismissRequest = onCancel) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 40.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("配对客户端", style = RsType.title, color = c.text)
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.track).padding(horizontal = 12.dp, vertical = 8.dp)) {
                BasicTextField(name, { name = it }, Modifier.fillMaxWidth(), textStyle = RsType.body.copy(color = c.text), singleLine = true,
                    decorationBox = { inner -> if (name.isEmpty()) Text("名字（如 Claude Desktop）", style = RsType.body, color = c.textTertiary); inner() })
            }
            Text("可见范围", style = RsType.subhead.copy(fontWeight = FontWeight.SemiBold), color = c.text)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill("全部记录", filled = scopeProject.isBlank()) { scopeProject = "" }
                projects.take(6).forEach { p -> Pill(p.name, filled = scopeProject == p.id.toString()) { scopeProject = p.id.toString() } }
            }
            Pill("生成令牌", filled = true) {
                if (name.isBlank()) return@Pill
                scope.launch(Dispatchers.IO) {
                    val token = "sbx_" + (1..2).joinToString("") { java.util.UUID.randomUUID().toString().replace("-", "") }
                    val hint = MessageDigest.getInstance("SHA-256").digest(token.toByteArray()).joinToString("") { "%02x".format(it) }.take(8)
                    val app = context.applicationContext as app.resound.ResoundApp
                    val id = app.database.mcpClients().insert(McpClientRow(name = name.trim(), tokenHint = hint,
                        projectScope = scopeProject.toLongOrNull(), createdAt = System.currentTimeMillis()))
                    SecretStore(context, "mcp-secrets", "resound-mcp-aes-v1").write("mcp_$id", token)
                    withContext(Dispatchers.Main) { onToken(token) }
                }
            }
        }
    }
}
