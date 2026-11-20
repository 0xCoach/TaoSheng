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
import app.resound.analysis.AnalysisRunRow
import app.resound.analysis.AnalysisStatus
import app.resound.analysis.AnalysisTarget
import app.resound.analysis.AnalysisType
import app.resound.decision.DecisionChains
import app.resound.decision.DecisionConfidence
import app.resound.decision.DecisionStatus
import app.resound.projects.ActionRow
import app.resound.projects.ActivityEntry
import app.resound.projects.ProjectActions
import app.resound.projects.ProjectMarkKind
import app.resound.projects.ProjectMarkRow
import app.resound.projects.ProjectRefKind
import app.resound.projects.ProjectRepository
import app.resound.projects.ProjectRow
import app.resound.projects.ProjectSummary
import app.resound.qa.QaAnswer
import app.resound.ui.inbox.Field
import app.resound.ui.theme.Rs
import app.resound.ui.theme.RsType
import app.resound.ui.verify.Pill
import app.resound.ui.verify.RsCard
import app.resound.ui.verify.formatStamp
import app.resound.ui.verify.springClick
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** 项目长期记忆：项目列表 + 详情（概览/时间线/行动/决定/报告/问答）。 */
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

/** 项目详情内的六个分区。 */
private enum class ProjectTab(val label: String) {
    Overview("概览"), Timeline("时间线"), Actions("行动"), Decisions("决定"), Reports("报告"), Qa("问答");
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
    val decisions by (if (AppGraph.isAssistantReady) AppGraph.decisionRepo.observeForProject(cur.id)
        else flowOf(emptyList<app.resound.decision.DecisionRow>())).collectAsStateWithLifecycle(initialValue = emptyList())
    val runs by (if (AppGraph.isAnalysisReady) AppGraph.analysis.runsForProject(cur.id)
        else flowOf(emptyList<AnalysisRunRow>())).collectAsStateWithLifecycle(initialValue = emptyList())
    var summary by remember { mutableStateOf<ProjectSummary?>(null) }
    var activity by remember { mutableStateOf<List<ActivityEntry>?>(null) }
    var actions by remember { mutableStateOf<ProjectActions?>(null) }
    var activityLimit by remember { mutableIntStateOf(60) }
    var reloadTick by remember { mutableIntStateOf(0) }
    var tab by remember { mutableIntStateOf(0) }
    var addingMark by remember { mutableStateOf<ProjectMarkKind?>(null) }
    var editing by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var openedRun by remember { mutableStateOf<AnalysisRunRow?>(null) }
    LaunchedEffect(cur.id, marks.size, links.size, decisions.size, activityLimit, reloadTick) {
        summary = repo.summary(cur.id); actions = repo.actions(cur.id)
        activity = repo.activity(cur.id, activityLimit)
    }

    ModalBottomSheet(onClose) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 40.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(cur.name, style = RsType.title, color = c.text, modifier = Modifier.weight(1f))
                if (cur.status == "Archived") Box(Modifier.clip(CircleShape).background(c.track).padding(horizontal = 10.dp, vertical = 4.dp)) {
                    Text("已归档", style = RsType.caption, color = c.textSecondary)
                }
            }
            if (cur.note.isNotBlank()) Text(cur.note, style = RsType.subhead, color = c.textSecondary)

            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ProjectTab.entries.forEachIndexed { i, t ->
                    val on = tab == i
                    Box(Modifier.clip(CircleShape).background(if (on) c.accentSoft else c.track)
                        .springClick { tab = i }.padding(horizontal = 14.dp, vertical = 7.dp)) {
                        Text(t.label, style = RsType.subhead, color = if (on) c.accent else c.textSecondary)
                    }
                }
            }

            when (ProjectTab.entries[tab]) {
                ProjectTab.Overview -> OverviewTab(cur, summary, activity, decisions, runs, marks, links,
                    onAddMark = { addingMark = it }, onOpenReports = { tab = ProjectTab.Reports.ordinal },
                    onEdit = { editing = true }, onToggleArchive = {
                        scope.launch { if (cur.status == "Archived") repo.unarchive(cur.id) else repo.archive(cur.id) }
                    }, onDelete = { confirmDelete = true })
                ProjectTab.Timeline -> TimelineTab(activity, hasMore = activity?.size == activityLimit,
                    onMore = { activityLimit += 60 })
                ProjectTab.Actions -> ActionsTab(actions, onDone = { itemId ->
                    scope.launch {
                        if (AppGraph.isMemoryReady) AppGraph.memory.setItemStatus(itemId, app.resound.digest.ItemStatus.Done)
                        reloadTick++
                    }
                })
                ProjectTab.Decisions -> DecisionsTab(decisions, onReaffirm = { id ->
                    scope.launch { AppGraph.decisionRepo.reaffirm(id); reloadTick++; Toast.makeText(context, "已确认仍有效", Toast.LENGTH_SHORT).show() }
                }, onChanged = { reloadTick++ })
                ProjectTab.Reports -> ReportsTab(cur, runs, onOpen = { openedRun = it })
                ProjectTab.Qa -> ProjectQaTab(cur.id)
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

    addingMark?.let { kind -> MarkSheet(kind, repo, cur.id) { addingMark = null; reloadTick++ } }
    if (editing) ProjectEditSheet(cur, repo) { editing = false }
    openedRun?.let { ReportSheet(it) { openedRun = null } }
}

// ---------- 概览 ----------

@Composable
private fun OverviewTab(
    cur: ProjectRow, summary: ProjectSummary?, activity: List<ActivityEntry>?,
    decisions: List<app.resound.decision.DecisionRow>, runs: List<AnalysisRunRow>,
    marks: List<ProjectMarkRow>, links: List<app.resound.projects.ProjectLinkRow>,
    onAddMark: (ProjectMarkKind) -> Unit, onOpenReports: () -> Unit,
    onEdit: () -> Unit, onToggleArchive: () -> Unit, onDelete: () -> Unit,
) {
    val c = Rs.colors
    val scope = rememberCoroutineScope()

    summary?.let { s ->
        RsCard(padding = PaddingValues(14.dp)) {
            Text("本阶段进展", style = RsType.body.copy(fontWeight = FontWeight.SemiBold), color = c.text)
            Spacer(Modifier.height(6.dp))
            Text(if (s.status.isBlank()) "还没有状态记录，点下方“记一笔”写一条" else s.status, style = RsType.subhead, color = c.textSecondary)
            Spacer(Modifier.height(8.dp))
            Text("决策 ${s.decisions} · 否决 ${s.rejected} · 关联 ${s.links}", style = RsType.caption, color = c.textTertiary)
            if (s.nextActions.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text("下一步：", style = RsType.caption, color = c.textTertiary)
                s.nextActions.forEach { Text("· ${it.title}", style = RsType.subhead, color = c.text) }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                IndicatorChip("有来源依据"); IndicatorChip("跨会议整理"); IndicatorChip("动态更新")
            }
        }
    }

    // 待确认：同主题多条生效决定 + AI 低置信度推断——不会自动改状态，列给用户看
    val conflicts = DecisionChains.conflicts(decisions)
    val tentative = decisions.count { it.status == DecisionStatus.Active.name && it.confidence == DecisionConfidence.Tentative.name }
    if (conflicts.isNotEmpty() || tentative > 0) RsCard(padding = PaddingValues(14.dp)) {
        Text("待确认", style = RsType.body.copy(fontWeight = FontWeight.SemiBold), color = c.warning)
        Spacer(Modifier.height(6.dp))
        conflicts.forEach { g -> Text("· 同主题有 ${g.size} 条生效决定并存：${g.first().title}", style = RsType.subhead, color = c.text) }
        if (tentative > 0) Text("· $tentative 条决定是“随口”置信度，未必是正式决定", style = RsType.subhead, color = c.text)
    }

    // 最近变化：时间线前三条
    val recent = activity?.take(3).orEmpty()
    if (recent.isNotEmpty()) RsCard(padding = PaddingValues(14.dp)) {
        Text("最近变化", style = RsType.body.copy(fontWeight = FontWeight.SemiBold), color = c.text)
        recent.forEach { e ->
            Spacer(Modifier.height(6.dp))
            Text("${e.kind.label} · ${formatStamp(e.at)}", style = RsType.caption, color = c.textTertiary)
            Text(e.title, style = RsType.subhead, color = c.text, maxLines = 1)
        }
    }

    // 最近阶段报告预览：点开报告区
    runs.firstOrNull()?.let { r ->
        RsCard(modifier = Modifier.springClick(onOpenReports), padding = PaddingValues(14.dp)) {
            Text("最近报告", style = RsType.body.copy(fontWeight = FontWeight.SemiBold), color = c.text)
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                RunStatusChip(r.status)
                Spacer(Modifier.width(8.dp))
                Text("${AnalysisType.of(r.type).label} · ${formatStamp(r.createdAt)}", style = RsType.subhead, color = c.textSecondary, modifier = Modifier.weight(1f))
                Text("查看 ›", style = RsType.subhead, color = c.accent)
            }
        }
    }

    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ProjectMarkKind.entries.forEach { k -> Pill("+ ${k.label}", filled = false) { onAddMark(k) } }
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
        val titles = activity.orEmpty().associateBy { it.key }
        links.take(10).forEach { l ->
            val kind = ProjectRefKind.of(l.refKind)
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                val resolved = titles["${l.refKind.lowercase()}:${l.refId}"]?.title
                Text(resolved ?: ("${kind?.label ?: l.refKind} #${l.refId}") + (l.note?.let { " · $it" } ?: ""),
                    style = RsType.subhead, color = c.textSecondary, modifier = Modifier.weight(1f), maxLines = 1)
                Text("移除", style = RsType.caption, color = c.accent, modifier = Modifier.springClick { scope.launch { AppGraph.projects.unlink(l.id) } })
            }
        }
    }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Pill("编辑", filled = false, onClick = onEdit)
        Pill(if (cur.status == "Archived") "恢复" else "归档", filled = false, onClick = onToggleArchive)
        Pill("删除", filled = false, onClick = onDelete)
    }
}

@Composable
private fun IndicatorChip(text: String) {
    val c = Rs.colors
    Box(Modifier.clip(CircleShape).background(c.accentSoft).padding(horizontal = 10.dp, vertical = 4.dp)) {
        Text(text, style = RsType.caption, color = c.accent)
    }
}

// ---------- 时间线 ----------

@Composable
private fun TimelineTab(activity: List<ActivityEntry>?, hasMore: Boolean, onMore: () -> Unit) {
    val c = Rs.colors
    when {
        activity == null -> RsCard(padding = PaddingValues(14.dp)) { Text("加载中…", style = RsType.subhead, color = c.textTertiary) }
        activity.isEmpty() -> EmptyCard("还没有项目动态", "把会议、灵感、待办挂到项目，或记一笔状态/决定，这里会按真实发生时间排成一条线。")
        else -> {
            activity.forEach { e -> ActivityCard(e) }
            if (hasMore) Pill("加载更早", filled = false, onClick = onMore)
        }
    }
}

@Composable
private fun ActivityCard(e: ActivityEntry) {
    val c = Rs.colors
    RsCard(padding = PaddingValues(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.clip(CircleShape).background(c.accentSoft).padding(horizontal = 8.dp, vertical = 2.dp)) {
                Text(e.kind.label, style = RsType.caption, color = c.accent)
            }
            e.status?.let {
                Spacer(Modifier.width(6.dp))
                Box(Modifier.clip(CircleShape).background(c.track).padding(horizontal = 8.dp, vertical = 2.dp)) {
                    Text(it, style = RsType.caption, color = c.textSecondary)
                }
            }
            Spacer(Modifier.weight(1f))
            Text(formatStamp(e.at), style = RsType.caption, color = c.textTertiary)
        }
        Spacer(Modifier.height(6.dp))
        Text(e.title, style = RsType.subhead.copy(fontWeight = FontWeight.Medium), color = c.text)
        if (e.detail.isNotBlank()) Text(e.detail, style = RsType.caption, color = c.textSecondary, modifier = Modifier.padding(top = 2.dp))
        if (e.sources.size > 1) Text("${e.sources.size} 个来源", style = RsType.caption, color = c.textTertiary, modifier = Modifier.padding(top = 4.dp))
    }
}

// ---------- 行动与承诺 ----------

@Composable
private fun ActionsTab(actions: ProjectActions?, onDone: (Long) -> Unit) {
    val c = Rs.colors
    when {
        actions == null -> RsCard(padding = PaddingValues(14.dp)) { Text("加载中…", style = RsType.subhead, color = c.textTertiary) }
        actions.pending.isEmpty() && actions.open.isEmpty() && actions.done.isEmpty() ->
            EmptyCard("还没有项目行动", "把会议挂到项目后，会上安排的待办和承诺会自动出现在这里。")
        else -> {
            if (actions.stalled.isNotEmpty()) RsCard(padding = PaddingValues(14.dp)) {
                Text("可能卡住了 ${actions.stalled.size} 项", style = RsType.body.copy(fontWeight = FontWeight.SemiBold), color = c.warning)
                actions.stalled.forEach { r ->
                    Spacer(Modifier.height(6.dp))
                    Text("· ${r.title}" + (if (r.remindCount >= 3) "（提醒 ${r.remindCount} 次未闭环）" else if (r.overdue) "（已超期）" else ""),
                        style = RsType.subhead, color = c.text)
                }
            }
            if (actions.pending.isNotEmpty()) ActionGroup("待确认", actions.pending, c, onDone)
            if (actions.open.isNotEmpty()) ActionGroup("进行中与承诺", actions.open, c, onDone)
            if (actions.done.isNotEmpty()) ActionGroup("已完成", actions.done.take(10), c, onDone)
        }
    }
}

@Composable
private fun ActionGroup(title: String, rows: List<ActionRow>, c: app.resound.ui.theme.RsColors, onDone: (Long) -> Unit) {
    Text(title, style = RsType.subhead.copy(fontWeight = FontWeight.SemiBold), color = c.text)
    rows.forEach { r ->
        RsCard(padding = PaddingValues(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(r.title, style = RsType.subhead.copy(fontWeight = FontWeight.Medium), color = c.text)
                    val meta = buildList {
                        if (r.detail.isNotBlank()) add(r.detail)
                        r.followupKind?.let { add("跟进：${app.resound.followup.FollowupKind.entries.firstOrNull { k -> k.name == it }?.label ?: it}·${app.resound.followup.FollowupStatus.of(r.followupStatus).label}") }
                        if (r.overdue) add("已超期")
                    }.joinToString(" · ")
                    if (meta.isNotBlank()) Text(meta, style = RsType.caption, color = if (r.overdue) c.warning else c.textSecondary, modifier = Modifier.padding(top = 2.dp))
                    Text(formatStamp(r.doneAt ?: r.at), style = RsType.caption, color = c.textTertiary, modifier = Modifier.padding(top = 2.dp))
                }
                // 只有提取项能就地完成；独立跟进回到“跟进”页处理
                if (r.status == "Accepted" && r.key.startsWith("item:")) {
                    Text("完成", style = RsType.subhead.copy(fontWeight = FontWeight.Medium), color = c.accent,
                        modifier = Modifier.clip(CircleShape).background(c.accentSoft)
                            .springClick { onDone(r.key.removePrefix("item:").toLong()) }.padding(horizontal = 12.dp, vertical = 6.dp))
                }
            }
        }
    }
}

// ---------- 决策演变 ----------

@Composable
private fun DecisionsTab(
    decisions: List<app.resound.decision.DecisionRow>,
    onReaffirm: (Long) -> Unit, onChanged: () -> Unit,
) {
    val c = Rs.colors
    val scope = rememberCoroutineScope()
    var rejecting by remember { mutableStateOf<app.resound.decision.DecisionRow?>(null) }
    var withdrawing by remember { mutableStateOf<app.resound.decision.DecisionRow?>(null) }
    if (decisions.isEmpty()) {
        EmptyCard("还没有项目决定", "把会议或待办挂到项目后，整理出的“决定/约定”会自动归到这里；也可以在“决策”页手动指定项目。")
        return
    }
    val chains = DecisionChains.build(decisions)
    val conflicts = DecisionChains.conflicts(decisions)
    if (conflicts.isNotEmpty()) RsCard(padding = PaddingValues(14.dp)) {
        Text("待确认冲突 ${conflicts.size} 组", style = RsType.body.copy(fontWeight = FontWeight.SemiBold), color = c.warning)
        Spacer(Modifier.height(4.dp))
        Text("同主题有多条生效决定并存。选择哪条有效，请逐条确认或否决——系统不会自动替你废止。", style = RsType.caption, color = c.textSecondary)
    }
    chains.forEach { chain ->
        val d = chain.current
        RsCard(padding = PaddingValues(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.clip(CircleShape).background(if (d.status == DecisionStatus.Active.name) c.accentSoft else c.track)
                    .padding(horizontal = 8.dp, vertical = 2.dp)) {
                    Text(DecisionStatus.of(d.status).label, style = RsType.caption,
                        color = if (d.status == DecisionStatus.Active.name) c.accent else c.textSecondary)
                }
                Spacer(Modifier.width(6.dp))
                Box(Modifier.clip(CircleShape).background(c.track).padding(horizontal = 8.dp, vertical = 2.dp)) {
                    Text(DecisionConfidence.of(d.confidence).label, style = RsType.caption, color = c.textSecondary)
                }
                Spacer(Modifier.weight(1f))
                Text(formatStamp(d.decidedAt), style = RsType.caption, color = c.textTertiary)
            }
            Spacer(Modifier.height(6.dp))
            Text(d.title, style = RsType.subhead.copy(fontWeight = FontWeight.Medium), color = c.text)
            if (d.detail.isNotBlank()) Text(d.detail, style = RsType.caption, color = c.textSecondary, modifier = Modifier.padding(top = 2.dp))
            d.sourceText?.let { Text("原话：“${it.take(100)}”", style = RsType.caption, color = c.textTertiary, modifier = Modifier.padding(top = 4.dp)) }
            val srcLabel = when {
                d.sourceEventId != null -> "来自会议"
                d.sourceItemId != null -> "来自提取项"
                d.sourceLineId != null -> "来自原话"
                d.sourceIdeaId != null -> "来自灵感"
                else -> null
            }
            if (srcLabel != null) Text(srcLabel, style = RsType.caption, color = c.textTertiary, modifier = Modifier.padding(top = 2.dp))
            chain.history.forEach { h ->
                Spacer(Modifier.height(8.dp))
                Row {
                    Box(Modifier.width(2.dp).height(14.dp).background(c.separator).padding(top = 4.dp))
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text("${DecisionStatus.of(h.status).label} · ${formatStamp(h.decidedAt)}", style = RsType.caption, color = c.textTertiary)
                        Text(h.title, style = RsType.caption, color = c.textSecondary)
                        h.rejectedReason?.let { Text("否决原因：$it", style = RsType.caption, color = c.warning) }
                        h.supersededBy?.let { Text("被当前决定替代", style = RsType.caption, color = c.textTertiary) }
                    }
                }
            }
            if (d.status == DecisionStatus.Active.name) Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill("确认仍有效", filled = false) { onReaffirm(d.id) }
                Pill("否决", filled = false) { rejecting = d }
                Pill("撤回", filled = false) { withdrawing = d }
            }
        }
    }
    rejecting?.let { d -> DecisionReasonSheet("否决这条决定？", d.title, onDismiss = { rejecting = null }) { reason ->
        scope.launch { AppGraph.decisionRepo.reject(d.id, reason); onChanged() }; rejecting = null
    } }
    withdrawing?.let { d -> DecisionReasonSheet("撤回这条决定？", d.title, onDismiss = { withdrawing = null }) { reason ->
        scope.launch { AppGraph.decisionRepo.withdraw(d.id, reason); onChanged() }; withdrawing = null
    } }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DecisionReasonSheet(title: String, target: String, onDismiss: () -> Unit, onConfirm: (String?) -> Unit) {
    var reason by remember { mutableStateOf("") }
    ModalBottomSheet(onDismiss) {
        Column(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 40.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(title, style = RsType.title, color = Rs.colors.text)
            Text(target, style = RsType.subhead, color = Rs.colors.textSecondary)
            Field("原因（可空，写入修订记录）", reason) { reason = it }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill("确认", filled = true) { onConfirm(reason.ifBlank { null }) }
                Pill("取消", filled = false, onClick = onDismiss)
            }
        }
    }
}

// ---------- 阶段报告 ----------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReportsTab(cur: ProjectRow, runs: List<AnalysisRunRow>, onOpen: (AnalysisRunRow) -> Unit) {
    val c = Rs.colors
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var confirmWindow by remember { mutableStateOf<Pair<String, Long?>?>(null) }
    val ready = AppGraph.isAnalysisReady && AppGraph.analysis.llmReady()

    RsCard(padding = PaddingValues(14.dp)) {
        Text("生成阶段报告", style = RsType.body.copy(fontWeight = FontWeight.SemiBold), color = c.text)
        Spacer(Modifier.height(4.dp))
        Text(if (ready) "汇总本项目的会议、决定、灵感、待办与承诺，报告附来源依据。"
        else "未配置模型服务：仍可查看已有报告与时间线，生成会标记为失败。", style = RsType.caption, color = c.textSecondary)
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill("本周回顾", filled = true) { confirmWindow = "本周（近 7 天）" to (System.currentTimeMillis() - 7L * 86400_000) }
            Pill("本月回顾", filled = false) { confirmWindow = "本月（近 30 天）" to (System.currentTimeMillis() - 30L * 86400_000) }
            Pill("阶段总结", filled = false) { confirmWindow = "全部记录" to null }
        }
    }

    if (runs.isEmpty()) EmptyCard("还没有报告", "点上方生成本周/本月/阶段报告；生成过的报告保存在这里，离线随时重看。")
    else runs.forEach { r ->
        RsCard(modifier = Modifier.springClick { onOpen(r) }, padding = PaddingValues(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RunStatusChip(r.status)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text("${AnalysisType.of(r.type).label} · ${r.templateName}", style = RsType.subhead.copy(fontWeight = FontWeight.Medium), color = c.text)
                    Text("${formatStamp(r.createdAt)}${if (r.model.isNotBlank()) " · ${r.model}" else ""}" +
                        (r.error?.let { " · $it" } ?: ""), style = RsType.caption, color = c.textSecondary)
                }
                Text("›", style = RsType.title, color = c.textTertiary)
            }
        }
    }

    confirmWindow?.let { (label, windowStart) ->
        ModalBottomSheet({ confirmWindow = null }) {
            Column(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 40.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("生成$label 阶段报告？", style = RsType.title, color = c.text)
                Text("将调用模型服务汇总 $label 的项目材料（会消耗用量）。生成失败会如实标记，不会算作成功。",
                    style = RsType.subhead, color = c.textSecondary)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Pill("生成", filled = true) {
                        scope.launch {
                            AppGraph.analysis.start(AnalysisTarget.Project, cur.id, AnalysisType.PhaseReport,
                                projectId = cur.id, windowStart = windowStart)
                            confirmWindow = null
                            Toast.makeText(context, "报告生成中，稍后回来查看", Toast.LENGTH_SHORT).show()
                        }
                    }
                    Pill("取消", filled = false) { confirmWindow = null }
                }
            }
        }
    }
}

@Composable
private fun RunStatusChip(status: String) {
    val c = Rs.colors
    val st = AnalysisStatus.of(status)
    val (label, warn) = when (st) {
        AnalysisStatus.Succeeded -> "已完成" to false
        AnalysisStatus.Failed -> "失败" to true
        AnalysisStatus.Running -> "生成中…" to false
        AnalysisStatus.Pending -> "排队中" to false
    }
    Box(Modifier.clip(CircleShape).background(if (warn) c.track else c.accentSoft).padding(horizontal = 8.dp, vertical = 2.dp)) {
        Text(label, style = RsType.caption, color = if (warn) c.warning else c.accent)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReportSheet(run: AnalysisRunRow, onClose: () -> Unit) {
    val c = Rs.colors
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    // 跟着数据库走：生成中的报告完成后就地刷新，不另起调用
    val live by AppGraph.analysis.observeRun(run.id).collectAsStateWithLifecycle(initialValue = run)
    val r = live ?: run
    val sources = remember(r.sourcesJson) {
        runCatching {
            (app.resound.platform.SharedJson.parseToJsonElement(r.sourcesJson) as? kotlinx.serialization.json.JsonArray)
                ?.mapNotNull { el -> el.jsonObject.let { it["kind"]?.jsonPrimitive?.content to (it["id"]?.jsonPrimitive?.longOrNull ?: 0L) } }
                .orEmpty()
        }.getOrDefault(emptyList())
    }
    ModalBottomSheet(onClose) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 40.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${AnalysisType.of(r.type).label} · ${r.templateName}", style = RsType.title, color = c.text, modifier = Modifier.weight(1f))
                RunStatusChip(r.status)
            }
            Text("${formatStamp(r.createdAt)}${if (r.model.isNotBlank()) " · 模型 ${r.model}" else ""}", style = RsType.caption, color = c.textTertiary)
            r.error?.let { Text("失败原因：$it", style = RsType.caption, color = c.warning) }
            if (r.resultText.isNotBlank()) RsCard(padding = PaddingValues(16.dp)) {
                Text(remember(r.resultText) { app.resound.analysis.AnalysisContract.displayText(r.resultText) },
                    style = RsType.body, color = c.text)
            } else Text(if (r.status == AnalysisStatus.Running.name || r.status == AnalysisStatus.Pending.name) "生成中，稍后自动显示…" else "（无结果）",
                style = RsType.subhead, color = c.textTertiary)
            if (sources.isNotEmpty()) Text("来源依据 ${sources.size} 条：${sources.groupBy { it.first }.entries.joinToString("、") { "${it.key}×${it.value.size}" }}",
                style = RsType.caption, color = c.textTertiary)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill("重新生成", filled = false) {
                    scope.launch {
                        AppGraph.analysis.rerun(r.id)
                        Toast.makeText(context, "已重新发起生成（会再次调用模型）", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }
}

// ---------- 项目问答 ----------

@Composable
private fun ProjectQaTab(projectId: Long) {
    val c = Rs.colors
    val scope = rememberCoroutineScope()
    var question by remember { mutableStateOf("") }
    var answer by remember { mutableStateOf<QaAnswer?>(null) }
    var busy by remember { mutableStateOf(false) }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).background(c.track).padding(horizontal = 14.dp, vertical = 10.dp)) {
            BasicTextField(question, { question = it }, Modifier.fillMaxWidth(),
                textStyle = RsType.body.copy(color = c.text), singleLine = true,
                decorationBox = { inner ->
                    if (question.isEmpty()) Text("问这个项目：上次会议安排的事做完了吗", style = RsType.body, color = c.textTertiary)
                    inner()
                })
        }
        Spacer(Modifier.width(8.dp))
        Pill(if (busy) "…" else "问", filled = true) {
            if (!busy && question.isNotBlank()) scope.launch {
                busy = true
                answer = AppGraph.groundedQA.ask(question, projectId)
                busy = false
            }
        }
    }
    Text("只在「本项目」的记录里找答案，结论带出处编号。", style = RsType.caption, color = c.textTertiary)
    answer?.let { a ->
        RsCard(padding = PaddingValues(16.dp)) {
            when {
                a.needsService -> QaBadge("等待配置服务", warn = true)
                a.noEvidence -> QaBadge("这个项目里没找到相关内容", warn = true)
                !a.grounded -> QaBadge("含补充内容（非全部来自记录）", warn = true)
                else -> QaBadge("全部出自本项目记录")
            }
            if (a.invalidCitations > 0) { Spacer(Modifier.height(6.dp)); QaBadge("已剔除 ${a.invalidCitations} 处无效出处", warn = true) }
            if (!a.noEvidence && a.evidenceLevel == 1) { Spacer(Modifier.height(6.dp)); QaBadge("证据单薄，仅供参考", warn = true) }
            Spacer(Modifier.height(8.dp))
            Text(a.text, style = RsType.body, color = c.text)
        }
        if (a.sources.isNotEmpty()) {
            Text("来源（[#n] 对应这里）", style = RsType.subhead.copy(fontWeight = FontWeight.SemiBold), color = c.text)
            a.sources.forEach { s ->
                RsCard(padding = PaddingValues(12.dp)) {
                    Text("[#${s.no}] ${s.kind} · ${formatStamp(s.at)}", style = RsType.caption, color = c.textTertiary)
                    Spacer(Modifier.height(4.dp))
                    Text("${s.title}：${s.quote}", style = RsType.subhead, color = c.textSecondary, maxLines = 3)
                }
            }
        }
    } ?: RsCard(padding = PaddingValues(16.dp)) {
        Text("试试：「这个项目推进到什么程度了」「哪些承诺还没完成」「这个方案之前否决过吗」。", style = RsType.subhead, color = c.textSecondary)
    }
}

@Composable
private fun QaBadge(text: String, warn: Boolean = false) {
    val c = Rs.colors
    Box(Modifier.clip(CircleShape).background(if (warn) c.track else c.accentSoft).padding(horizontal = 10.dp, vertical = 4.dp)) {
        Text(text, style = RsType.caption, color = if (warn) c.warning else c.accent)
    }
}

@Composable
private fun EmptyCard(title: String, note: String) {
    val c = Rs.colors
    RsCard(padding = PaddingValues(16.dp)) {
        Text(title, style = RsType.body.copy(fontWeight = FontWeight.SemiBold), color = c.text)
        Spacer(Modifier.height(6.dp))
        Text(note, style = RsType.subhead, color = c.textSecondary)
    }
}

// ---------- 原有的记一笔/新建/编辑 ----------

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
