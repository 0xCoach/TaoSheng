package app.resound.ui.projects

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.resound.AppGraph
import app.resound.projects.ProjectMarkKind
import app.resound.projects.ProjectMarkRow
import app.resound.projects.ProjectRefKind
import app.resound.projects.ProjectRepository
import app.resound.projects.ProjectRow
import app.resound.projects.ProjectSummary
import app.resound.ui.inbox.Field
import app.resound.ui.theme.Rs
import app.resound.ui.theme.RsType
import app.resound.ui.verify.Pill
import app.resound.ui.verify.RsCard
import app.resound.ui.verify.formatStamp
import app.resound.ui.verify.springClick
import kotlinx.coroutines.launch

/** 项目长期记忆：项目列表 + 详情（状态摘要/记忆/关联/时间线/搜索）。 */
@Composable
fun ProjectScreen(bottomPadding: Dp, onBack: () -> Unit) {
    val c = Rs.colors
    val repo = AppGraph.projects
    var tab by remember { mutableIntStateOf(0) }
    val active by repo.active().collectAsStateWithLifecycle(initialValue = emptyList())
    val archived by repo.archived().collectAsStateWithLifecycle(initialValue = emptyList())
    var opened by remember { mutableStateOf<ProjectRow?>(null) }
    var creating by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var hits by remember { mutableStateOf<List<ProjectRow>?>(null) }
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(start = 16.dp, end = 16.dp, top = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("‹", style = RsType.largeTitle, color = c.textSecondary, modifier = Modifier.springClick(onBack).padding(end = 10.dp))
            Text("项目", style = RsType.largeTitle, color = c.text, modifier = Modifier.weight(1f))
            Pill("新建项目", filled = true) { creating = true }
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("进行中", "已归档").forEachIndexed { i, label ->
                val on = tab == i
                Box(Modifier.clip(CircleShape).background(if (on) c.accentSoft else c.track).springClick { tab = i }.padding(horizontal = 14.dp, vertical = 7.dp)) {
                    Text(label, style = RsType.subhead, color = if (on) c.accent else c.textSecondary)
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.track).padding(horizontal = 14.dp, vertical = 10.dp)) {
            BasicTextField(query, {
                query = it
                hits = if (it.isBlank()) null else null
                if (it.isNotBlank()) scope.launch { hits = repo.searchProjects(it) } else hits = null
            }, Modifier.fillMaxWidth(), textStyle = RsType.body.copy(color = c.text), singleLine = true,
                decorationBox = { inner -> if (query.isEmpty()) Text("搜索项目名、备注", style = RsType.body, color = c.textTertiary); inner() })
        }
        Spacer(Modifier.height(12.dp))
        val list = hits ?: (if (tab == 0) active else archived)
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = bottomPadding + 16.dp)) {
            if (list.isEmpty()) item {
                RsCard {
                    Text(if (tab == 0) "还没有进行中的项目" else "没有已归档的项目", style = RsType.body, color = c.text)
                    Spacer(Modifier.height(6.dp))
                    Text("项目是你长期在做的事：把录音、灵感、待办、分析关联进来，记下决策和被否决的原因。", style = RsType.subhead, color = c.textSecondary)
                }
            }
            items(list, key = { it.id }) { p ->
                RsCard(modifier = Modifier.springClick { opened = p }, padding = PaddingValues(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(p.name, style = RsType.body.copy(fontWeight = FontWeight.SemiBold), color = c.text, modifier = Modifier.weight(1f))
                        Text(formatStamp(p.updatedAt), style = RsType.caption, color = c.textTertiary)
                    }
                    if (p.note.isNotBlank()) { Spacer(Modifier.height(6.dp)); Text(p.note, style = RsType.subhead, color = c.textSecondary, maxLines = 2) }
                }
            }
        }
    }
    opened?.let { ProjectSheet(it, repo) { opened = null } }
    if (creating) ProjectCreateSheet(repo) { creating = false }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProjectSheet(opened: ProjectRow, repo: ProjectRepository, onClose: () -> Unit) {
    val c = Rs.colors
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val project by repo.observe(opened.id).collectAsStateWithLifecycle(initialValue = opened)
    val cur = project ?: return
    val marks by repo.marks(cur.id).collectAsStateWithLifecycle(initialValue = emptyList())
    val links by repo.links(cur.id).collectAsStateWithLifecycle(initialValue = emptyList())
    var summary by remember { mutableStateOf<ProjectSummary?>(null) }
    var timeline by remember { mutableStateOf<List<app.resound.projects.ProjectTimelineEntry>?>(null) }
    var addingMark by remember { mutableStateOf<ProjectMarkKind?>(null) }
    var editing by remember { mutableStateOf(false) }
    var showTimeline by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    LaunchedEffect(cur.id) { summary = repo.summary(cur.id); timeline = repo.timeline(cur.id) }

    ModalBottomSheet(onClose) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 40.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(cur.name, style = RsType.title, color = c.text, modifier = Modifier.weight(1f))
                if (cur.status == "Archived") Box(Modifier.clip(CircleShape).background(c.track).padding(horizontal = 10.dp, vertical = 4.dp)) {
                    Text("已归档", style = RsType.caption, color = c.textSecondary)
                }
            }
            if (cur.note.isNotBlank()) Text(cur.note, style = RsType.subhead, color = c.textSecondary)

            summary?.let { s ->
                RsCard(padding = PaddingValues(14.dp)) {
                    Text("当前状态", style = RsType.body.copy(fontWeight = FontWeight.SemiBold), color = c.text)
                    Spacer(Modifier.height(6.dp))
                    Text(if (s.status.isBlank()) "还没有状态记录，点下方“记一笔”写一条" else s.status, style = RsType.subhead, color = c.textSecondary)
                    Spacer(Modifier.height(8.dp))
                    Text("决策 ${s.decisions} · 否决 ${s.rejected} · 关联 ${s.links}", style = RsType.caption, color = c.textTertiary)
                    if (s.nextActions.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Text("下一步：", style = RsType.caption, color = c.textTertiary)
                        s.nextActions.forEach { Text("· ${it.title}", style = RsType.subhead, color = c.text) }
                    }
                }
            }

            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ProjectMarkKind.entries.forEach { k -> Pill("+ ${k.label}", filled = false) { addingMark = k } }
            }

            if (marks.isNotEmpty()) RsCard(padding = PaddingValues(14.dp)) {
                Text("记忆 ${marks.size} 条", style = RsType.body.copy(fontWeight = FontWeight.SemiBold), color = c.text)
                marks.take(8).forEach { m ->
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.clip(CircleShape).background(c.accentSoft).padding(horizontal = 8.dp, vertical = 2.dp)) {
                            Text(ProjectMarkKind.of(m.kind).label, style = RsType.caption, color = c.accent)
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(m.title, style = RsType.subhead, color = c.text, modifier = Modifier.weight(1f), maxLines = 1)
                        Text(formatStamp(m.at), style = RsType.caption, color = c.textTertiary)
                    }
                    if (m.content.isNotBlank()) Text(m.content, style = RsType.caption, color = c.textSecondary, maxLines = 2, modifier = Modifier.padding(start = 4.dp, top = 2.dp))
                    m.reason?.let { Text("否决原因：$it", style = RsType.caption, color = c.accent, modifier = Modifier.padding(start = 4.dp, top = 2.dp)) }
                }
            }

            if (links.isNotEmpty()) RsCard(padding = PaddingValues(14.dp)) {
                Text("关联 ${links.size} 条", style = RsType.body.copy(fontWeight = FontWeight.SemiBold), color = c.text)
                links.take(10).forEach { l ->
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${ProjectRefKind.of(l.refKind)?.label ?: l.refKind} #${l.refId}" + (l.note?.let { " · $it" } ?: ""),
                            style = RsType.subhead, color = c.textSecondary, modifier = Modifier.weight(1f), maxLines = 1)
                        Text("移除", style = RsType.caption, color = c.accent, modifier = Modifier.springClick { scope.launch { repo.unlink(l.id) } })
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill(if (showTimeline) "收起时间线" else "时间线", filled = false) {
                    if (!showTimeline) scope.launch { timeline = repo.timeline(cur.id) }
                    showTimeline = !showTimeline
                }
                if (AppGraph.isAnalysisReady) Pill("AI 分析", filled = false) {
                    scope.launch {
                        AppGraph.analysis.start(app.resound.analysis.AnalysisTarget.Project, cur.id, app.resound.analysis.AnalysisType.DeepResearch, projectId = cur.id)
                        Toast.makeText(context, "已开始分析", Toast.LENGTH_SHORT).show()
                    }
                }
                Pill("编辑", filled = false) { editing = true }
            }
            if (showTimeline) timeline?.let { list ->
                RsCard(padding = PaddingValues(14.dp)) {
                    Text("时间线", style = RsType.body.copy(fontWeight = FontWeight.SemiBold), color = c.text)
                    list.take(20).forEach { e ->
                        Spacer(Modifier.height(8.dp))
                        Text(formatStamp(e.at), style = RsType.caption, color = c.textTertiary)
                        Text(e.title, style = RsType.subhead, color = c.text)
                        if (e.detail.isNotBlank()) Text(e.detail, style = RsType.caption, color = c.textSecondary)
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (cur.status != "Archived") Pill("归档", filled = false) { scope.launch { repo.archive(cur.id) } }
                else Pill("恢复", filled = false) { scope.launch { repo.unarchive(cur.id) } }
                Pill("删除", filled = false) { confirmDelete = true }
            }
            if (confirmDelete) RsCard(padding = PaddingValues(14.dp)) {
                Text("删除项目？项目记忆一并删除，关联的录音/灵感不受影响。", style = RsType.subhead, color = c.textSecondary)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Pill("确认删除", filled = true) { scope.launch { repo.delete(cur.id); onClose() } }
                    Pill("取消", filled = false) { confirmDelete = false }
                }
            }
        }
    }

    addingMark?.let { kind -> MarkSheet(kind, repo, cur.id) { addingMark = null } }
    if (editing) ProjectEditSheet(cur, repo) { editing = false }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MarkSheet(kind: ProjectMarkKind, repo: ProjectRepository, projectId: Long, onClose: () -> Unit) {
    var title by remember { mutableStateOf("") }
    var content by remember { mutableStateOf("") }
    var reason by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    ModalBottomSheet(onClose) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 40.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("记一笔：${kind.label}", style = RsType.title, color = Rs.colors.text)
            Field("标题", title) { title = it }
            Field("内容（可空）", content, lines = 4) { content = it }
            if (kind == ProjectMarkKind.Rejected) Field("否决原因", reason) { reason = it }
            Pill("保存", filled = true) {
                if (title.isBlank()) return@Pill
                scope.launch { repo.addMark(projectId, kind, title, content, reason.ifBlank { null }); onClose() }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProjectCreateSheet(repo: ProjectRepository, onClose: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    ModalBottomSheet(onClose) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 40.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("新建项目", style = RsType.title, color = Rs.colors.text)
            Field("项目名", name) { name = it }
            Field("备注（可空）", note, lines = 3) { note = it }
            Pill("创建", filled = true) {
                if (name.isBlank()) return@Pill
                scope.launch { repo.create(name, note); onClose() }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProjectEditSheet(p: ProjectRow, repo: ProjectRepository, onClose: () -> Unit) {
    var name by remember { mutableStateOf(p.name) }
    var note by remember { mutableStateOf(p.note) }
    val scope = rememberCoroutineScope()
    ModalBottomSheet(onClose) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 40.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("编辑项目", style = RsType.title, color = Rs.colors.text)
            Field("项目名", name) { name = it }
            Field("备注", note, lines = 3) { note = it }
            Pill("保存", filled = true) {
                if (name.isBlank()) return@Pill
                scope.launch { repo.rename(p.id, name, note); onClose() }
            }
        }
    }
}
