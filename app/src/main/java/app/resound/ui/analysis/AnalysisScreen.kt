package app.resound.ui.analysis

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
import androidx.compose.runtime.LaunchedEffect
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
import app.resound.analysis.AnalysisRepository
import app.resound.analysis.AnalysisRunRow
import app.resound.analysis.AnalysisStatus
import app.resound.analysis.AnalysisTarget
import app.resound.analysis.AnalysisTemplateRow
import app.resound.analysis.AnalysisType
import app.resound.ui.inbox.Field
import app.resound.ui.theme.Rs
import app.resound.ui.theme.RsType
import app.resound.ui.verify.Pill
import app.resound.ui.verify.RsCard
import app.resound.ui.verify.formatStamp
import app.resound.ui.verify.springClick
import kotlinx.coroutines.launch

/** AI 智能分析：运行历史 + 发起新分析 + 结果详情 + 模板管理。 */
@Composable
fun AnalysisScreen(bottomPadding: Dp, onBack: () -> Unit) {
    val c = Rs.colors
    val repo = AppGraph.analysis
    var tab by remember { mutableIntStateOf(0) }
    val runs by repo.runs.collectAsStateWithLifecycle(initialValue = emptyList())
    val templates by repo.templates.collectAsStateWithLifecycle(initialValue = emptyList())
    var opened by remember { mutableStateOf<AnalysisRunRow?>(null) }
    var creating by remember { mutableStateOf(false) }
    var editingTemplate by remember { mutableStateOf<AnalysisTemplateRow?>(null) }
    var newTemplate by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(start = 16.dp, end = 16.dp, top = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("‹", style = RsType.largeTitle, color = c.textSecondary, modifier = Modifier.springClick(onBack).padding(end = 10.dp))
            Text("分析", style = RsType.largeTitle, color = c.text, modifier = Modifier.weight(1f))
            Pill("新分析", filled = true) { creating = true }
        }
        if (!repo.llmReady()) {
            Spacer(Modifier.height(10.dp))
            RsCard(padding = PaddingValues(14.dp)) {
                Text("等待配置服务", style = RsType.body.copy(fontWeight = FontWeight.SemiBold), color = c.accent)
                Spacer(Modifier.height(4.dp))
                Text("到 设置 → 模型与整理时间 配置 API 后才能自动分析；现在仍可组装材料、导出给别的 AI、把结果粘回来。", style = RsType.subhead, color = c.textSecondary)
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("运行", "模板").forEachIndexed { i, label ->
                val on = tab == i
                Box(Modifier.clip(CircleShape).background(if (on) c.accentSoft else c.track).springClick { tab = i }.padding(horizontal = 14.dp, vertical = 7.dp)) {
                    Text(label, style = RsType.subhead, color = if (on) c.accent else c.textSecondary)
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = bottomPadding + 16.dp)) {
            if (tab == 0) {
                if (runs.isEmpty()) item {
                    RsCard {
                        Text("还没有分析记录", style = RsType.body, color = c.text)
                        Spacer(Modifier.height(6.dp))
                        Text("从“新分析”挑一个对象开始，也可以在灵感、项目详情里直接发起。", style = RsType.subhead, color = c.textSecondary)
                    }
                }
                items(runs, key = { it.id }) { run -> RunCard(run) { opened = run } }
            } else {
                item { Pill("新建模板", filled = true) { newTemplate = true } }
                items(templates, key = { it.id }) { t ->
                    RsCard(padding = PaddingValues(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(t.name, style = RsType.body.copy(fontWeight = FontWeight.SemiBold), color = c.text)
                                Text(AnalysisType.of(t.type).label + if (t.builtin) " · 内置" else " · 我的", style = RsType.caption, color = c.textTertiary)
                            }
                            Pill("编辑", filled = false) { editingTemplate = t }
                        }
                    }
                }
            }
        }
    }
    opened?.let { RunSheet(it, repo) { opened = null } }
    if (creating) NewAnalysisSheet(repo) { creating = false }
    editingTemplate?.let { TemplateSheet(it, repo) { editingTemplate = null } }
    if (newTemplate) TemplateSheet(null, repo) { newTemplate = false }
}

@Composable
private fun RunCard(run: AnalysisRunRow, onClick: () -> Unit) {
    val c = Rs.colors
    val st = AnalysisStatus.of(run.status)
    RsCard(modifier = Modifier.springClick(onClick), padding = PaddingValues(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("${AnalysisType.of(run.type).label} · ${AnalysisTarget.of(run.targetKind)?.label ?: run.targetKind} #${run.targetId}",
                    style = RsType.body.copy(fontWeight = FontWeight.SemiBold), color = c.text)
                Text(
                    when (st) {
                        AnalysisStatus.Succeeded -> app.resound.analysis.AnalysisContract.displayText(run.resultText).take(80)
                        AnalysisStatus.Failed -> run.error ?: "失败"
                        else -> "进行中…"
                    }, style = RsType.caption, color = c.textSecondary, maxLines = 2)
            }
            Spacer(Modifier.width(10.dp))
            Box(Modifier.clip(CircleShape).background(if (st == AnalysisStatus.Failed) c.track else c.accentSoft).padding(horizontal = 9.dp, vertical = 3.dp)) {
                Text(when (st) { AnalysisStatus.Succeeded -> "完成"; AnalysisStatus.Failed -> "失败"; AnalysisStatus.Running -> "运行中"; else -> "排队" },
                    style = RsType.caption, color = if (st == AnalysisStatus.Failed) c.textSecondary else c.accent)
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(formatStamp(run.createdAt), style = RsType.caption, color = c.textTertiary)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RunSheet(opened: AnalysisRunRow, repo: AnalysisRepository, onClose: () -> Unit) {
    val c = Rs.colors
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val run by repo.observeRun(opened.id).collectAsStateWithLifecycle(initialValue = opened)
    val cur = run ?: return
    var showPrompt by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    var importText by remember { mutableStateOf("") }

    ModalBottomSheet(onClose) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 40.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("${AnalysisType.of(cur.type).label} · ${AnalysisTarget.of(cur.targetKind)?.label ?: cur.targetKind} #${cur.targetId}",
                style = RsType.title, color = c.text)
            Text("${formatStamp(cur.createdAt)} · 模板：${cur.templateName} · 模型：${cur.model.ifBlank { "—" }}", style = RsType.caption, color = c.textTertiary)

            if (cur.status == AnalysisStatus.Succeeded.name) RsCard(padding = PaddingValues(14.dp)) {
                Text("结果", style = RsType.body.copy(fontWeight = FontWeight.SemiBold), color = c.text)
                Spacer(Modifier.height(6.dp))
                Text(app.resound.analysis.AnalysisContract.displayText(cur.resultText), style = RsType.subhead, color = c.textSecondary)
            } else if (cur.status == AnalysisStatus.Failed.name) RsCard(padding = PaddingValues(14.dp)) {
                Text(if (cur.error?.contains("未配置模型服务") == true) "等待配置服务" else "分析失败", style = RsType.body.copy(fontWeight = FontWeight.SemiBold), color = c.accent)
                Spacer(Modifier.height(6.dp))
                Text(cur.error ?: "", style = RsType.subhead, color = c.textSecondary)
            } else Text("分析进行中…", style = RsType.subhead, color = c.textSecondary)

            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill(if (showPrompt) "收起材料" else "查看材料", filled = false) { showPrompt = !showPrompt }
                Pill("复制材料", filled = false) {
                    scope.launch { clipboard.setText(AnnotatedString(repo.exportPrompt(cur.id))); Toast.makeText(context, "已复制提示词+材料", Toast.LENGTH_SHORT).show() }
                }
                if (cur.status == AnalysisStatus.Succeeded.name) Pill("复制结果", filled = false) {
                    scope.launch { clipboard.setText(AnnotatedString(repo.exportResult(cur.id))); Toast.makeText(context, "已复制结果", Toast.LENGTH_SHORT).show() }
                }
                Pill("重跑", filled = false) { scope.launch { repo.rerun(cur.id) } }
                Pill("导入结果", filled = false) { importing = true }
                Pill("删除", filled = false) { scope.launch { repo.deleteRun(cur.id); onClose() } }
            }
            if (showPrompt) RsCard(padding = PaddingValues(14.dp)) {
                Text("发出的提示词（含材料）", style = RsType.caption, color = c.textTertiary)
                Spacer(Modifier.height(4.dp))
                Text(cur.promptText.take(3000), style = RsType.caption, color = c.textSecondary)
            }
            if (importing) {
                Field("把外部 AI 的回答粘贴到这里", importText, lines = 6) { importText = it }
                Pill("存为结果", filled = true) {
                    if (importText.isBlank()) return@Pill
                    scope.launch { repo.importResult(cur.id, importText); importing = false }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NewAnalysisSheet(repo: AnalysisRepository, onClose: () -> Unit) {
    val c = Rs.colors
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var target by remember { mutableStateOf(AnalysisTarget.Event) }
    var targetIdText by remember { mutableStateOf("") }
    var type by remember { mutableStateOf(AnalysisType.Summary) }
    var attachProject by remember { mutableStateOf<Long?>(null) }
    ModalBottomSheet(onClose) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 40.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("新分析", style = RsType.title, color = c.text)
            Text("分析对象", style = RsType.subhead, color = c.textSecondary)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AnalysisTarget.entries.forEach { t -> Pill(t.label, filled = t == target) { target = t } }
            }
            if (target != AnalysisTarget.Day) Field("对象编号（事件/录音/原话/灵感/项目 id，详情页可见）", targetIdText) { targetIdText = it }
            else Text("将分析今天 0 点起的全天记录", style = RsType.caption, color = c.textTertiary)
            Text("分析类型", style = RsType.subhead, color = c.textSecondary)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AnalysisType.entries.forEach { t -> Pill(t.label, filled = t == type) { type = t } }
            }
            Pill("开始", filled = true) {
                scope.launch {
                    val id = if (target == AnalysisTarget.Day) todayStart() else targetIdText.trim().toLongOrNull()
                    if (id == null) { Toast.makeText(context, "请填对象编号", Toast.LENGTH_SHORT).show(); return@launch }
                    repo.start(target, id, type, projectId = attachProject)
                    Toast.makeText(context, "已发起分析", Toast.LENGTH_SHORT).show()
                    onClose()
                }
            }
        }
    }
}

private fun todayStart(): Long {
    val zone = java.util.TimeZone.getDefault()
    val now = System.currentTimeMillis()
    return now - ((now + zone.getOffset(now)) % 86_400_000L)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TemplateSheet(t: AnalysisTemplateRow?, repo: AnalysisRepository, onClose: () -> Unit) {
    val c = Rs.colors
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var name by remember { mutableStateOf(t?.name ?: "") }
    var type by remember { mutableStateOf(AnalysisType.of(t?.type)) }
    var system by remember { mutableStateOf(t?.systemPrompt ?: app.resound.analysis.AnalysisRepository.DEFAULT_SYSTEM) }
    var user by remember { mutableStateOf(t?.userTemplate ?: "请分析：\n\n{{content}}") }
    ModalBottomSheet(onClose) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 40.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(if (t == null) "新建模板" else "编辑模板", style = RsType.title, color = c.text)
            Field("模板名", name) { name = it }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AnalysisType.entries.forEach { ty -> Pill(ty.label, filled = ty == type) { type = ty } }
            }
            Field("系统提示词", system, lines = 3) { system = it }
            Field("用户模板（必须包含 {{content}}）", user, lines = 5) { user = it }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill("保存", filled = true) {
                    scope.launch {
                        val r = repo.saveTemplate(t?.id, type, name, system, user)
                        Toast.makeText(context, if (r.isSuccess) "已保存" else r.exceptionOrNull()?.message, Toast.LENGTH_SHORT).show()
                        if (r.isSuccess) onClose()
                    }
                }
                if (t != null && !t.builtin) Pill("删除", filled = false) { scope.launch { repo.deleteTemplate(t.id); onClose() } }
            }
        }
    }
}
