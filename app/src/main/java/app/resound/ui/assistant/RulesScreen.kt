package app.resound.ui.assistant

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
import app.resound.rules.ActionKind
import app.resound.rules.RuleRunRow
import app.resound.rules.TriggerKind
import app.resound.rules.WorkflowRuleRow
import app.resound.ui.theme.Rs
import app.resound.ui.theme.RsType
import app.resound.ui.verify.Pill
import app.resound.ui.verify.RsCard
import app.resound.ui.verify.formatStamp
import app.resound.ui.verify.springClick
import kotlinx.coroutines.launch

/** 自动化规则：手动/定时/事件 → 通知/待办/分析/派任务；派任务只建待确认任务。 */
@Composable
fun RulesScreen(bottomPadding: Dp, onBack: () -> Unit) {
    val c = Rs.colors
    val engine = AppGraph.workflowEngine
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val rules by remember { engine.observeRules() }.collectAsStateWithLifecycle(initialValue = emptyList())
    val runs by remember { engine.observeRuns() }.collectAsStateWithLifecycle(initialValue = emptyList())
    var creating by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(start = 16.dp, end = 16.dp, top = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("‹", style = RsType.largeTitle, color = c.textSecondary, modifier = Modifier.springClick(onBack).padding(end = 10.dp))
            Text("规则", style = RsType.largeTitle, color = c.text, modifier = Modifier.weight(1f))
            Pill("新建", filled = true) { creating = true }
        }
        Spacer(Modifier.height(12.dp))
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = bottomPadding + 16.dp)) {
            if (rules.isEmpty()) item {
                RsCard {
                    Text("还没有规则", style = RsType.body, color = c.text)
                    Spacer(Modifier.height(6.dp))
                    Text("例：每天 9 点提醒我昨天的跟进；新灵感进来时建一条待办。对外发任务只生成待确认任务，要你在“任务”页确认才出网。", style = RsType.subhead, color = c.textSecondary)
                }
            }
            items(rules, key = { it.id }) { r -> RuleCard(r, engine) }
            if (runs.isNotEmpty()) {
                item { Text("最近触发", style = RsType.subhead.copy(fontWeight = FontWeight.SemiBold), color = c.text, modifier = Modifier.padding(top = 8.dp)) }
                items(runs.take(15), key = { "run${it.id}" }) { run -> RunRow(run) }
            }
        }
    }
    if (creating) RuleCreateSheet(engine) { creating = false }
}

private fun describe(r: WorkflowRuleRow): String {
    val trig = when (TriggerKind.of(r.triggerKind)) {
        TriggerKind.Manual -> "手动"
        TriggerKind.Time -> "定时"
        TriggerKind.Event -> "事件"
    }
    val act = when (ActionKind.of(r.actionKind)) {
        ActionKind.Notify -> "通知"
        ActionKind.Todo -> "建待办"
        ActionKind.Analysis -> "跑分析"
        ActionKind.AgentTask -> "派任务（待确认）"
    }
    return "$trig → $act"
}

@Composable
private fun RuleCard(r: WorkflowRuleRow, engine: app.resound.rules.WorkflowEngine) {
    val c = Rs.colors
    val scope = rememberCoroutineScope()
    RsCard(padding = PaddingValues(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(r.name, style = RsType.body.copy(fontWeight = FontWeight.SemiBold), color = c.text)
                Spacer(Modifier.height(4.dp))
                Text("${describe(r)} · 已触发 ${r.fireCount} 次", style = RsType.caption, color = c.textSecondary)
            }
            Pill(if (r.enabled) "停用" else "启用", filled = r.enabled) { scope.launch { engine.setEnabled(r.id, !r.enabled) } }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Pill("立即运行", filled = false) { scope.launch { engine.runNow(r.id) } }
            Pill("删除", filled = false) { scope.launch { engine.delete(r.id) } }
        }
    }
}

@Composable
private fun RunRow(run: RuleRunRow) {
    val c = Rs.colors
    val statusLabel = when (run.status) {
        "Ok" -> "成功"
        "NeedsConfirm" -> "待你确认"
        "Failed" -> "失败"
        else -> run.status
    }
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text("${formatStamp(run.at)} · ${run.trigger} · $statusLabel${if (run.detail.isNotBlank()) " · ${run.detail}" else ""}",
            style = RsType.caption, color = if (run.status == "Failed") c.warning else c.textSecondary)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RuleCreateSheet(engine: app.resound.rules.WorkflowEngine, onClose: () -> Unit) {
    val c = Rs.colors
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var name by remember { mutableStateOf("") }
    var trigger by remember { mutableStateOf(TriggerKind.Manual) }
    var action by remember { mutableStateOf(ActionKind.Notify) }
    var timeText by remember { mutableStateOf("09:00") }
    var eventKey by remember { mutableStateOf("idea_created") }
    var body by remember { mutableStateOf("") }

    ModalBottomSheet(onDismissRequest = onClose) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 40.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("新建规则", style = RsType.title, color = c.text)
            Field("规则名", name) { name = it }
            Text("触发", style = RsType.subhead.copy(fontWeight = FontWeight.SemiBold), color = c.text)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TriggerKind.entries.forEach { t -> Pill(when (t) { TriggerKind.Manual -> "手动"; TriggerKind.Time -> "定时"; TriggerKind.Event -> "事件" }, filled = t == trigger) { trigger = t } }
            }
            if (trigger == TriggerKind.Time) Field("每天时间（HH:mm）", timeText) { timeText = it }
            if (trigger == TriggerKind.Event) {
                Text("事件：idea_created（新灵感）/ capture_arrived（收集箱新条目）/ followup_due（跟进提醒）", style = RsType.caption, color = c.textSecondary)
                Field("事件名", eventKey) { eventKey = it }
            }
            Text("动作", style = RsType.subhead.copy(fontWeight = FontWeight.SemiBold), color = c.text)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionKind.entries.forEach { a -> Pill(when (a) { ActionKind.Notify -> "通知"; ActionKind.Todo -> "建待办"; ActionKind.Analysis -> "跑分析"; ActionKind.AgentTask -> "派任务" }, filled = a == action) { action = a } }
            }
            if (action == ActionKind.AgentTask) Text("派任务只创建“待确认”任务：到“任务”页确认后才发出。", style = RsType.caption, color = c.textSecondary)
            Field(if (action == ActionKind.Notify) "通知正文" else "标题/内容", body) { body = it }
            Pill("保存", filled = true) {
                val (h, m) = timeText.split(":").let { (it.getOrNull(0)?.toIntOrNull() ?: 9) to (it.getOrNull(1)?.toIntOrNull() ?: 0) }
                val trigJson = when (trigger) {
                    TriggerKind.Manual -> "{}"
                    TriggerKind.Time -> """{"hour":$h,"minute":$m}"""
                    TriggerKind.Event -> """{"event":"$eventKey"}"""
                }
                val esc = body.replace("\\", "\\\\").replace("\"", "\\\"")
                val actJson = when (action) {
                    ActionKind.Notify -> """{"title":"${name.replace("\"", "\\\"")}","body":"$esc"}"""
                    ActionKind.Todo -> """{"title":"$esc"}"""
                    ActionKind.Analysis -> """{"targetKind":"Day","type":"Summary"}"""
                    ActionKind.AgentTask -> """{"kind":"DeepResearch","title":"$esc"}"""
                }
                scope.launch {
                    engine.create(name.ifBlank { describe(WorkflowRuleRow(name = "", triggerKind = trigger.name, actionKind = action.name, createdAt = 0)) }, trigger, trigJson, action, actJson)
                    Toast.makeText(context, "已保存", Toast.LENGTH_SHORT).show()
                    onClose()
                }
            }
        }
    }
}

@Composable
private fun Field(label: String, value: String, onChange: (String) -> Unit) {
    val c = Rs.colors
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.track).padding(horizontal = 12.dp, vertical = 8.dp)) {
        BasicTextField(value, onChange, Modifier.fillMaxWidth(),
            textStyle = RsType.body.copy(color = c.text),
            decorationBox = { inner -> if (value.isEmpty()) Text(label, style = RsType.body, color = c.textTertiary); inner() })
    }
}
