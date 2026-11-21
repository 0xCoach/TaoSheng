package app.resound.ui.agent

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.resound.AppGraph
import app.resound.agent.AgentBridge
import app.resound.agent.AgentEndpointRow
import app.resound.agent.AgentTaskKind
import app.resound.agent.AgentTaskRow
import app.resound.agent.AgentTaskStatus
import app.resound.ui.inbox.Field
import app.resound.ui.theme.Rs
import app.resound.ui.theme.RsType
import app.resound.ui.verify.Pill
import app.resound.ui.verify.RsCard
import app.resound.ui.verify.formatStamp
import app.resound.ui.verify.springClick
import kotlinx.coroutines.launch

/** Agent 任务：端点管理 + 任务列表（确认/发送/重试/取消/导出/手动收尾）。 */
@Composable
fun AgentScreen(bottomPadding: Dp, onBack: () -> Unit) {
    val c = Rs.colors
    val bridge = AppGraph.agentBridge
    var tab by remember { mutableIntStateOf(0) }
    val tasks by bridge.tasks.collectAsStateWithLifecycle(initialValue = emptyList())
    val endpoints by bridge.endpoints.collectAsStateWithLifecycle(initialValue = emptyList())
    var opened by remember { mutableStateOf<AgentTaskRow?>(null) }
    var editingEndpoint by remember { mutableStateOf<AgentEndpointRow?>(null) }
    var newEndpoint by remember { mutableStateOf(false) }
    var newTask by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(start = 16.dp, end = 16.dp, top = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("‹", style = RsType.largeTitle, color = c.textSecondary, modifier = Modifier.springClick(onBack).padding(end = 10.dp))
            Text("任务", style = RsType.largeTitle, color = c.text, modifier = Modifier.weight(1f))
            Pill("新任务", filled = true) { newTask = true }
        }
        if (endpoints.isEmpty()) {
            Spacer(Modifier.height(10.dp))
            RsCard(padding = PaddingValues(14.dp)) {
                Text("还没配置外部 Agent", style = RsType.body.copy(fontWeight = FontWeight.SemiBold), color = c.text)
                Spacer(Modifier.height(4.dp))
                Text("可以先创建任务、查看并导出 JSON 载荷；配置 Webhook 端点后，确认发送才真正出网。", style = RsType.subhead, color = c.textSecondary)
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("任务", "端点").forEachIndexed { i, label ->
                val on = tab == i
                Box(Modifier.clip(CircleShape).background(if (on) c.accentSoft else c.track).springClick { tab = i }.padding(horizontal = 14.dp, vertical = 7.dp)) {
                    Text(label, style = RsType.subhead, color = if (on) c.accent else c.textSecondary)
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = bottomPadding + 16.dp)) {
            if (tab == 0) {
                if (tasks.isEmpty()) item {
                    RsCard {
                        Text("还没有任务", style = RsType.body, color = c.text)
                        Spacer(Modifier.height(6.dp))
                        Text("在灵感详情里“派任务”，或点右上角手动创建。", style = RsType.subhead, color = c.textSecondary)
                    }
                }
                items(tasks, key = { it.id }) { t -> TaskCard(t) { opened = t } }
            } else {
                item { Pill("新增端点", filled = true) { newEndpoint = true } }
                items(endpoints, key = { it.id }) { e ->
                    RsCard(modifier = Modifier.springClick { editingEndpoint = e }, padding = PaddingValues(14.dp)) {
                        Text(e.name, style = RsType.body.copy(fontWeight = FontWeight.SemiBold), color = c.text)
                        Text(e.url, style = RsType.caption, color = c.textSecondary, maxLines = 1)
                        Text((if (e.enabled) "启用" else "停用") + (if (e.secretName.isNotBlank()) " · 带密钥" else "") + (if (e.statusUrlTemplate.isNotBlank()) " · 可回查" else ""),
                            style = RsType.caption, color = c.textTertiary)
                    }
                }
            }
        }
    }
    opened?.let { TaskSheet(it, bridge) { opened = null } }
    editingEndpoint?.let { EndpointSheet(it, bridge) { editingEndpoint = null } }
    if (newEndpoint) EndpointSheet(null, bridge) { newEndpoint = false }
    if (newTask) NewTaskSheet(bridge) { newTask = false }
}

private fun statusLabel(s: AgentTaskStatus) = s.label

@Composable
private fun TaskCard(t: AgentTaskRow, onClick: () -> Unit) {
    val c = Rs.colors
    val st = AgentTaskStatus.of(t.status)
    RsCard(modifier = Modifier.springClick(onClick), padding = PaddingValues(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(t.title, style = RsType.body.copy(fontWeight = FontWeight.SemiBold), color = c.text, maxLines = 1)
                Text("${AgentTaskKind.of(t.type).label} · ${t.taskId}", style = RsType.caption, color = c.textTertiary, maxLines = 1)
            }
            Spacer(Modifier.width(10.dp))
            Box(Modifier.clip(CircleShape).background(if (st == AgentTaskStatus.Succeeded) c.accentSoft else c.track).padding(horizontal = 9.dp, vertical = 3.dp)) {
                Text(statusLabel(st), style = RsType.caption, color = if (st == AgentTaskStatus.Succeeded) c.accent else c.textSecondary)
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(formatStamp(t.createdAt) + (t.error?.let { " · $it" } ?: ""), style = RsType.caption, color = c.textTertiary, maxLines = 1)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TaskSheet(opened: AgentTaskRow, bridge: AgentBridge, onClose: () -> Unit) {
    val c = Rs.colors
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val task by bridge.observeTask(opened.id).collectAsStateWithLifecycle(initialValue = opened)
    val cur = task ?: return
    val st = AgentTaskStatus.of(cur.status)
    var showPayload by remember { mutableStateOf(false) }
    var resolving by remember { mutableStateOf(false) }
    var resolveNote by remember { mutableStateOf("") }

    ModalBottomSheet(onClose) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 40.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(cur.title, style = RsType.title, color = c.text)
            Text("${AgentTaskKind.of(cur.type).label} · ${cur.taskId}", style = RsType.caption, color = c.textTertiary)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("状态：", style = RsType.subhead, color = c.textSecondary)
                Text(statusLabel(st), style = RsType.subhead, color = c.accent)
                cur.remoteId?.let { Text("  远端：$it", style = RsType.caption, color = c.textTertiary) }
            }
            if (cur.error != null) Text("错误：${cur.error}", style = RsType.subhead, color = c.accent)
            if (cur.resultText.isNotBlank()) RsCard(padding = PaddingValues(14.dp)) {
                Text("结果回执", style = RsType.body.copy(fontWeight = FontWeight.SemiBold), color = c.text)
                Spacer(Modifier.height(4.dp)); Text(cur.resultText.take(1200), style = RsType.subhead, color = c.textSecondary)
            }

            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when (st) {
                    AgentTaskStatus.Pending -> Pill("确认发送", filled = true) {
                        scope.launch { bridge.confirm(cur.id); Toast.makeText(context, "已确认，尝试发送", Toast.LENGTH_SHORT).show() }
                    }
                    AgentTaskStatus.Failed -> Pill("重试", filled = true) { scope.launch { bridge.requeue(cur.id) } }
                    AgentTaskStatus.Running -> {
                        Pill("刷新状态", filled = false) { scope.launch { bridge.refresh(cur.id) } }
                        Pill("手动收尾", filled = false) { resolving = true }
                    }
                    else -> {}
                }
                if (st != AgentTaskStatus.Succeeded && st != AgentTaskStatus.Cancelled && st != AgentTaskStatus.Running)
                    Pill("取消", filled = false) { scope.launch { bridge.cancel(cur.id) } }
                Pill(if (showPayload) "收起 JSON" else "查看 JSON", filled = false) { showPayload = !showPayload }
                Pill("导出 JSON", filled = false) {
                    scope.launch {
                        val json = bridge.payloadOf(cur.id) ?: return@launch
                        clipboard.setText(AnnotatedString(json))
                        runCatching {
                            context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("application/json").putExtra(Intent.EXTRA_TEXT, json), "导出任务 JSON"))
                        }
                        Toast.makeText(context, "已复制到剪贴板", Toast.LENGTH_SHORT).show()
                    }
                }
                if (st == AgentTaskStatus.Cancelled) Pill("重建", filled = false) { scope.launch { bridge.requeue(cur.id) } }
            }
            if (showPayload) RsCard(padding = PaddingValues(14.dp)) {
                Text("发送载荷（已确认的内容，含幂等 Task ID）", style = RsType.caption, color = c.textTertiary)
                Spacer(Modifier.height(4.dp))
                Text(cur.payloadJson, style = RsType.caption, color = c.textSecondary)
            }
            if (cur.receiptJson.isNotBlank()) RsCard(padding = PaddingValues(14.dp)) {
                Text("最近回执", style = RsType.caption, color = c.textTertiary)
                Spacer(Modifier.height(4.dp))
                Text(cur.receiptJson.take(800), style = RsType.caption, color = c.textSecondary)
            }
            if (resolving) {
                Text("只有当你在外部服务里核实过真实结果，才能手动收尾：", style = RsType.subhead, color = c.textSecondary)
                Field("结果摘要（可选）", resolveNote, lines = 3) { resolveNote = it }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Pill("标记成功", filled = true) { scope.launch { bridge.resolveManually(cur.id, true, resolveNote); resolving = false } }
                    Pill("标记失败", filled = false) { scope.launch { bridge.resolveManually(cur.id, false, resolveNote); resolving = false } }
                }
            }
            if (st != AgentTaskStatus.Running) Pill("删除任务", filled = false) { scope.launch { bridge.deleteTask(cur.id); onClose() } }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EndpointSheet(e: AgentEndpointRow?, bridge: AgentBridge, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var name by remember { mutableStateOf(e?.name ?: "") }
    var url by remember { mutableStateOf(e?.url ?: "") }
    var statusUrl by remember { mutableStateOf(e?.statusUrlTemplate ?: "") }
    var headers by remember { mutableStateOf(e?.headersJson ?: "{}") }
    var secret by remember { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf(false) }
    ModalBottomSheet(onClose) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 40.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(if (e == null) "新增端点" else "编辑端点", style = RsType.title, color = Rs.colors.text)
            Field("名称（如：我的 n8n / Dify / 自建服务）", name) { name = it }
            Field("Webhook 地址（https://…）", url) { url = it }
            Field("状态查询地址（可空，{taskId} 会被替换）", statusUrl) { statusUrl = it }
            Field("附加请求头 JSON（可空）", headers) { headers = it }
            Field("密钥（留空不修改；会存进系统安全区，不进数据库）", secret) { secret = it }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill("保存", filled = true) {
                    scope.launch {
                        val r = bridge.saveEndpoint(e?.id, name, url, headers.ifBlank { "{}" },
                            secret.ifBlank { null }, statusUrl, e?.enabled ?: true)
                        Toast.makeText(context, if (r.isSuccess) "已保存" else r.exceptionOrNull()?.message, Toast.LENGTH_SHORT).show()
                        if (r.isSuccess) onClose()
                    }
                }
                if (e != null) Pill(if (e.enabled) "停用" else "启用", filled = false) {
                    scope.launch { bridge.saveEndpoint(e.id, e.name, e.url, e.headersJson, null, e.statusUrlTemplate, !e.enabled) }
                }
                if (e != null) Pill("删除", filled = false) { confirmDelete = true }
            }
            if (confirmDelete) Pill("确认删除端点", filled = true) { scope.launch { bridge.deleteEndpoint(e!!.id); onClose() } }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NewTaskSheet(bridge: AgentBridge, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var kind by remember { mutableStateOf(AgentTaskKind.DeepResearch) }
    var title by remember { mutableStateOf("") }
    var content by remember { mutableStateOf("") }
    var taskContext by remember { mutableStateOf("") }
    ModalBottomSheet(onClose) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 40.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("新任务", style = RsType.title, color = Rs.colors.text)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AgentTaskKind.entries.forEach { k -> Pill(k.label, filled = k == kind) { kind = k } }
            }
            Field("标题", title) { title = it }
            Field("发给 Agent 的正文（确认后才会发送）", content, lines = 6) { content = it }
            Field("附加上下文（可空）", taskContext, lines = 3) { taskContext = it }
            Pill("创建任务", filled = true) {
                if (title.isBlank() || content.isBlank()) return@Pill
                scope.launch {
                    bridge.createTask(kind, title, content, taskContext)
                    Toast.makeText(context, "已创建，待确认发送", Toast.LENGTH_SHORT).show()
                    onClose()
                }
            }
        }
    }
}
