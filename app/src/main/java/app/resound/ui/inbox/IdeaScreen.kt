package app.resound.ui.inbox

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
import androidx.compose.foundation.layout.size
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.resound.AppGraph
import app.resound.inbox.IdeaRepository
import app.resound.inbox.IdeaRow
import app.resound.inbox.IdeaStatus
import app.resound.projects.ProjectRow
import app.resound.ui.theme.Rs
import app.resound.ui.theme.RsType
import app.resound.ui.verify.Pill
import app.resound.ui.verify.RsCard
import app.resound.ui.verify.formatStamp
import app.resound.ui.verify.springClick
import kotlinx.coroutines.launch

private val STATUSES = IdeaStatus.entries

/** 灵感收件箱：列表 + 筛选 + 搜索 + 详情操作。无 AI 时全部手动功能可用。 */
@Composable
fun IdeaScreen(bottomPadding: Dp, onBack: () -> Unit) {
    val c = Rs.colors
    val repo = AppGraph.ideas
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var statusFilter by remember { mutableStateOf(IdeaStatus.Research) }
    var query by remember { mutableStateOf("") }
    val ideas by remember(statusFilter, query) {
        if (query.isBlank()) repo.list(statusFilter) else repo.search(query)
    }.collectAsStateWithLifecycle(initialValue = emptyList())
    var opened by remember { mutableStateOf<IdeaRow?>(null) }
    var creating by remember { mutableStateOf(false) }
    var syncing by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(start = 16.dp, end = 16.dp, top = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("‹", style = RsType.largeTitle, color = c.textSecondary, modifier = Modifier.springClick(onBack).padding(end = 10.dp))
            Text("灵感", style = RsType.largeTitle, color = c.text, modifier = Modifier.weight(1f))
            Pill(if (syncing) "同步中…" else "同步提取", filled = false) {
                if (!syncing) scope.launch {
                    syncing = true
                    val n = repo.syncFromDigest { if (AppGraph.isAssistantReady) AppGraph.workflowEngine.emit(it) }
                    syncing = false
                    Toast.makeText(context, if (n > 0) "新收入 $n 条灵感" else "没有新的灵感提取项", Toast.LENGTH_SHORT).show()
                }
            }
            Spacer(Modifier.width(8.dp))
            Pill("新建", filled = true) { creating = true }
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            STATUSES.forEach { s ->
                val on = s == statusFilter
                Box(Modifier.clip(CircleShape).background(if (on) c.accentSoft else c.track).springClick { statusFilter = s }.padding(horizontal = 14.dp, vertical = 7.dp)) {
                    Text(s.label, style = RsType.subhead, color = if (on) c.accent else c.textSecondary)
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.track).padding(horizontal = 14.dp, vertical = 10.dp)) {
            BasicTextField(query, { query = it }, Modifier.fillMaxWidth(),
                textStyle = RsType.body.copy(color = c.text), singleLine = true,
                decorationBox = { inner ->
                    if (query.isEmpty()) Text("搜索标题、内容、标签", style = RsType.body, color = c.textTertiary)
                    inner()
                })
        }
        Spacer(Modifier.height(12.dp))
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = bottomPadding + 16.dp)) {
            if (ideas.isEmpty()) item {
                RsCard {
                    Text(if (query.isNotBlank()) "没有匹配的灵感" else "还没有${statusFilter.label}的灵感", style = RsType.body, color = c.text)
                    Spacer(Modifier.height(6.dp))
                    Text("语音里提到的点子经整理后自动进来；也可以长按一句原话标为灵感，或点右上角手动新建。", style = RsType.subhead, color = c.textSecondary)
                }
            }
            items(ideas, key = { it.id }) { idea -> IdeaCard(idea) { opened = idea } }
        }
    }
    opened?.let { IdeaSheet(it, repo, { opened = null }) }
    if (creating) IdeaCreateSheet(repo) { creating = false }
}

@Composable
private fun IdeaCard(idea: IdeaRow, onClick: () -> Unit) {
    val c = Rs.colors
    RsCard(modifier = Modifier.springClick(onClick), padding = PaddingValues(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(idea.title, style = RsType.body.copy(fontWeight = FontWeight.SemiBold), color = c.text, modifier = Modifier.weight(1f))
            Box(Modifier.clip(CircleShape).background(c.accentSoft).padding(horizontal = 9.dp, vertical = 3.dp)) {
                Text(idea.category, style = RsType.caption, color = c.accent)
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(idea.content, style = RsType.subhead, color = c.textSecondary, maxLines = 3)
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(IdeaStatus.of(idea.status).label, style = RsType.caption, color = c.accent)
            if (idea.projectId != null) { Spacer(Modifier.width(10.dp)); Text("已挂项目", style = RsType.caption, color = c.textTertiary) }
            idea.convertedKind?.let { Spacer(Modifier.width(10.dp)); Text(if (it == "Todo") "已转待办" else "已转项目", style = RsType.caption, color = c.textTertiary) }
            Spacer(Modifier.weight(1f))
            Text(formatStamp(idea.updatedAt), style = RsType.caption, color = c.textTertiary)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun IdeaSheet(opened: IdeaRow, repo: IdeaRepository, onClose: () -> Unit) {
    val c = Rs.colors
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val idea by repo.observe(opened.id).collectAsStateWithLifecycle(initialValue = opened)
    val current = idea ?: opened
    var dups by remember { mutableStateOf<List<IdeaRow>?>(null) }
    var source by remember { mutableStateOf<String?>(null) }
    val projects by (if (AppGraph.isProjectsReady) AppGraph.projects.active() else kotlinx.coroutines.flow.flowOf(emptyList()))
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val entries by repo.entries(opened.id).collectAsStateWithLifecycle(initialValue = emptyList())
    var confirmDelete by remember { mutableStateOf(false) }
    var appendText by remember { mutableStateOf("") }
    var listening by remember { mutableStateOf(false) }
    LaunchedEffect(opened.id) {
        dups = repo.duplicatesOf(opened.id)
        source = repo.sourceText(opened)
    }
    ModalBottomSheet(onClose) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 40.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(current.title, style = RsType.title, color = c.text, modifier = Modifier.weight(1f))
                Box(Modifier.clip(CircleShape).background(c.accentSoft).padding(horizontal = 10.dp, vertical = 4.dp)) {
                    Text(current.category, style = RsType.caption, color = c.accent)
                }
            }
            Text(current.content, style = RsType.body, color = c.text)
            source?.takeIf { it.isNotBlank() }?.let {
                RsCard(padding = PaddingValues(14.dp)) {
                    Text("原话出处", style = RsType.caption, color = c.textTertiary)
                    Spacer(Modifier.height(4.dp))
                    Text(it, style = RsType.subhead, color = c.textSecondary)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("状态：", style = RsType.subhead, color = c.textSecondary, modifier = Modifier.align(Alignment.CenterVertically))
                IdeaStatus.next(IdeaStatus.of(current.status)).take(3).forEach { s ->
                    Pill(s.label, filled = false) {
                        scope.launch { repo.setStatus(current.id, s) }
                    }
                }
            }
            val trail = entries.filter { it.kind != "Seed" }
            if (trail.isNotEmpty()) {
                RsCard(padding = PaddingValues(14.dp)) {
                    Text("发展轨迹", style = RsType.body.copy(fontWeight = FontWeight.SemiBold), color = c.text)
                    trail.forEach { e ->
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.Top) {
                            Box(Modifier.padding(top = 5.dp).size(6.dp).clip(CircleShape).background(
                                if (e.origin == "User") c.accent else c.textTertiary))
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text(e.text, style = RsType.subhead, color = c.textSecondary, maxLines = 3)
                                Text(formatStamp(e.at) + when (e.kind) {
                                    "Note" -> " · 补充"; "Line" -> " · 原话补充"; "Status" -> " · 状态"
                                    "Merged" -> " · 合并"; "Todo" -> " · 转待办"; "Project" -> " · 转项目"
                                    else -> ""
                                } + if (e.origin == "Digest") " · AI整理" else "", style = RsType.caption, color = c.textTertiary)
                            }
                        }
                    }
                }
            }
            RsCard(padding = PaddingValues(14.dp)) {
                Text("继续补充", style = RsType.body.copy(fontWeight = FontWeight.SemiBold), color = c.text)
                Spacer(Modifier.height(8.dp))
                Field("补充这个想法（语音或文字，逐条保留）", appendText, lines = 2) { appendText = it }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Pill("保存补充", filled = true) {
                        if (appendText.isBlank()) return@Pill
                        scope.launch {
                            repo.append(current.id, appendText)
                            appendText = ""
                        }
                    }
                    if (AppGraph.isTodoVoiceReady) {
                        Pill(if (listening) "说完，点这里" else "语音补充", filled = listening) {
                            scope.launch {
                                if (!listening) {
                                    listening = AppGraph.todoVoice.start()
                                    if (!listening) Toast.makeText(context, "麦克风暂时用不了", Toast.LENGTH_SHORT).show()
                                } else {
                                    listening = false
                                    when (val r = AppGraph.todoVoice.finish()) {
                                        is app.resound.digest.VoiceNoteResult.Text -> {
                                            repo.append(current.id, r.text)
                                            Toast.makeText(context, "已记下这次补充", Toast.LENGTH_SHORT).show()
                                        }
                                        is app.resound.digest.VoiceNoteResult.Failed -> Toast.makeText(context, r.message, Toast.LENGTH_SHORT).show()
                                        else -> {}
                                    }
                                }
                            }
                        }
                    }
                }
            }
            dups?.takeIf { it.isNotEmpty() }?.let { list ->
                RsCard(padding = PaddingValues(14.dp)) {
                    Text("相关灵感 ${list.size} 条", style = RsType.body.copy(fontWeight = FontWeight.SemiBold), color = c.text)
                    list.take(3).forEach { d ->
                        Spacer(Modifier.height(6.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(d.title, style = RsType.subhead, color = c.textSecondary, modifier = Modifier.weight(1f), maxLines = 1)
                            Pill("并入本条", filled = false) {
                                scope.launch { repo.merge(d.id, current.id); dups = repo.duplicatesOf(current.id) }
                            }
                            Spacer(Modifier.width(6.dp))
                            Pill("不相关", filled = false) {
                                scope.launch { repo.dismissDuplicate(current.id, d.id); dups = repo.duplicatesOf(current.id) }
                            }
                        }
                    }
                }
            }
            if (AppGraph.isProjectsReady && projects.isNotEmpty()) {
                RsCard(padding = PaddingValues(14.dp)) {
                    Text("挂到项目", style = RsType.body.copy(fontWeight = FontWeight.SemiBold), color = c.text)
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        projects.forEach { p ->
                            val on = current.projectId == p.id
                            Pill(p.name, filled = on) {
                                scope.launch {
                                    repo.setProject(current.id, if (on) null else p.id)
                                    if (!on) AppGraph.projects.link(p.id, app.resound.projects.ProjectRefKind.Idea, current.id)
                                }
                            }
                        }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (current.convertedKind == null) {
                    Pill("转待办", filled = true) {
                        scope.launch { repo.convertToTodo(current.id); Toast.makeText(context, "已转成待办", Toast.LENGTH_SHORT).show() }
                    }
                    if (AppGraph.isProjectsReady && current.projectId == null) Pill("转为新项目", filled = false) {
                        scope.launch { repo.convertToProject(current.id); Toast.makeText(context, "已建项目并关联", Toast.LENGTH_SHORT).show() }
                    }
                    if (AppGraph.isAgentReady) Pill("派任务", filled = false) {
                        scope.launch {
                            AppGraph.agentBridge.createTask(app.resound.agent.AgentTaskKind.DeepResearch,
                                "研究灵感：${current.title}", current.content, sourceKind = "Idea", sourceId = current.id)
                            Toast.makeText(context, "已创建 Agent 任务，到“任务”页确认发送", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                if (AppGraph.isAnalysisReady) Pill("AI 分析", filled = false) {
                    scope.launch {
                        AppGraph.analysis.start(app.resound.analysis.AnalysisTarget.Idea, current.id, app.resound.analysis.AnalysisType.DeepResearch)
                        Toast.makeText(context, "已开始分析，到“分析”页看结果", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill("删除", filled = false) { confirmDelete = true }
            }
            if (confirmDelete) RsCard(padding = PaddingValues(14.dp)) {
                Text("删除这条灵感？关联的录音和原话不受影响。", style = RsType.subhead, color = c.textSecondary)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Pill("确认删除", filled = true) { scope.launch { repo.delete(current.id); onClose() } }
                    Pill("取消", filled = false) { confirmDelete = false }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun IdeaCreateSheet(repo: IdeaRepository, onClose: () -> Unit) {
    val c = Rs.colors
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var title by remember { mutableStateOf("") }
    var content by remember { mutableStateOf("") }
    var tags by remember { mutableStateOf("") }
    ModalBottomSheet(onClose) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 40.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("新建灵感", style = RsType.title, color = c.text)
            Field("标题（可空，取内容前 30 字）", title) { title = it }
            Field("内容", content, lines = 4) { content = it }
            Field("标签（逗号分隔）", tags) { tags = it }
            Pill("保存", filled = true) {
                if (content.isBlank() && title.isBlank()) return@Pill
                scope.launch {
                    repo.create(title.trim(), content.trim(), tags = tags.split(',', '，').map { it.trim() }.filter { it.isNotEmpty() })
                    Toast.makeText(context, "已存进收件箱", Toast.LENGTH_SHORT).show()
                    onClose()
                }
            }
        }
    }
}

@Composable
internal fun Field(hint: String, value: String, lines: Int = 1, onChange: (String) -> Unit) {
    val c = Rs.colors
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.track).padding(horizontal = 14.dp, vertical = 10.dp)) {
        BasicTextField(value, onChange, Modifier.fillMaxWidth().height((lines * 26).dp),
            textStyle = RsType.body.copy(color = c.text),
            decorationBox = { inner -> if (value.isEmpty()) Text(hint, style = RsType.body, color = c.textTertiary); inner() })
    }
}
