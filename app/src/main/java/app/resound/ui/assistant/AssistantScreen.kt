package app.resound.ui.assistant

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.resound.AppGraph
import app.resound.ui.theme.Rs
import app.resound.ui.theme.RsType
import app.resound.ui.verify.RsCard
import app.resound.ui.verify.springClick

/** 助手页的子页；Hub 为枢纽列表 */
enum class AssistantPage { Hub, Ideas, Projects, Analysis, Agent, Decisions, Followups, Collect, Rules, Qa, Mcp }

/**
 * 助手：灵感收件箱、项目长期记忆、AI 智能分析、Agent 任务四个模块的入口。
 * 底栏第 5 页；子页在同一页签内全屏切换，返回回到枢纽。
 */
@Composable
fun AssistantScreen(bottomPadding: androidx.compose.ui.unit.Dp) {
    var page by rememberSaveable { mutableIntStateOf(0) }
    when (AssistantPage.entries[page]) {
        AssistantPage.Hub -> Hub(bottomPadding) { page = it.ordinal }
        AssistantPage.Ideas -> app.resound.ui.inbox.IdeaScreen(bottomPadding) { page = 0 }
        AssistantPage.Projects -> app.resound.ui.projects.ProjectScreen(bottomPadding) { page = 0 }
        AssistantPage.Analysis -> app.resound.ui.analysis.AnalysisScreen(bottomPadding) { page = 0 }
        AssistantPage.Agent -> app.resound.ui.agent.AgentScreen(bottomPadding) { page = 0 }
        AssistantPage.Decisions -> app.resound.ui.assistant.DecisionsScreen(bottomPadding) { page = 0 }
        AssistantPage.Followups -> app.resound.ui.assistant.FollowupsScreen(bottomPadding) { page = 0 }
        AssistantPage.Collect -> app.resound.ui.assistant.CollectScreen(bottomPadding) { page = 0 }
        AssistantPage.Rules -> app.resound.ui.assistant.RulesScreen(bottomPadding) { page = 0 }
        AssistantPage.Qa -> app.resound.ui.assistant.QaScreen(bottomPadding) { page = 0 }
        AssistantPage.Mcp -> app.resound.ui.assistant.McpScreen(bottomPadding) { page = 0 }
    }
}

@Composable
private fun Hub(bottomPadding: androidx.compose.ui.unit.Dp, open: (AssistantPage) -> Unit) {
    val c = Rs.colors
    val ideaCount by (if (AppGraph.isIdeasReady) AppGraph.ideas.count else kotlinx.coroutines.flow.flowOf(0))
        .collectAsStateWithLifecycle(initialValue = 0)
    val projects by (if (AppGraph.isProjectsReady) AppGraph.projects.active() else kotlinx.coroutines.flow.flowOf(emptyList<app.resound.projects.ProjectRow>()))
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val runs by (if (AppGraph.isAnalysisReady) AppGraph.analysis.runs else kotlinx.coroutines.flow.flowOf(emptyList<app.resound.analysis.AnalysisRunRow>()))
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val tasks by (if (AppGraph.isAgentReady) AppGraph.agentBridge.openTasks else kotlinx.coroutines.flow.flowOf(emptyList<app.resound.agent.AgentTaskRow>()))
        .collectAsStateWithLifecycle(initialValue = emptyList())

    Column(
        Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = bottomPadding + 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("助手", style = RsType.largeTitle, color = c.text, modifier = Modifier.padding(start = 4.dp, bottom = 2.dp))
        Text("把每天说的话变成可推进的事：收灵感、挂项目、做分析、派任务。", style = RsType.subhead, color = c.textSecondary, modifier = Modifier.padding(start = 4.dp, bottom = 4.dp))

        EntryCard("灵感", "$ideaCount 条在收件箱", "语音里的点子自动进来；也可手动标记、分类、合并，成熟后转待办或项目", accent = true) { open(AssistantPage.Ideas) }
        EntryCard("项目", "${projects.size} 个进行中", "长期项目的记忆库：关联录音、灵感、待办，记下决策和被否决的原因") { open(AssistantPage.Projects) }
        EntryCard("分析", if (runs.isEmpty()) "还没有分析" else "最近：${runs.first().let { app.resound.analysis.AnalysisType.of(it.type).label }}",
            "对一段录音、一条灵感、一个项目或一整天发起深度分析，结果可追溯来源") { open(AssistantPage.Analysis) }
        EntryCard("任务", "${tasks.size} 个进行中", "把灵感派给外部 AI 执行：深度研究、市场调研、写需求、生成草稿") { open(AssistantPage.Agent) }

        if (AppGraph.isAssistantReady) {
            val openFollowups by AppGraph.followupEngine.observeOpenCount().collectAsStateWithLifecycle(initialValue = 0)
            val activeDecisions by AppGraph.decisionRepo.observeActiveCount().collectAsStateWithLifecycle(initialValue = 0)
            val inboxCount by AppGraph.captureInbox.observeInboxCount().collectAsStateWithLifecycle(initialValue = 0)
            EntryCard("问答", "带出处", "跨项目问你的记录；每条结论标来源编号，没证据明说，不编造回忆") { open(AssistantPage.Qa) }
            EntryCard("决策", "$activeDecisions 条生效", "决定的版本历史：生效、被否决、被替代都留痕，附原话证据和否决原因") { open(AssistantPage.Decisions) }
            EntryCard("跟进", "$openFollowups 条待跟进", "主动发现说过要做但没下文的事；AI 建议单独标注，不当作你的承诺") { open(AssistantPage.Followups) }
            EntryCard("收集箱", "$inboxCount 条待处理", "分享进来的网页、文字、截图统一入库；图片本地识字，可挂灵感、项目或原话") { open(AssistantPage.Collect) }
            EntryCard("规则", "条件→动作", "到点或出事时自动提醒、建待办、跑分析；对外发任务一律先经你确认") { open(AssistantPage.Rules) }
            EntryCard("MCP", "本机只读", "让电脑上的 AI 查询你的记忆：仅本机、令牌认证、可按项目限权") { open(AssistantPage.Mcp) }
        }

        if (!AppGraph.isLlmProvidersReady || AppGraph.llmProviders.saved.value.isEmpty()) {
            RsCard(padding = PaddingValues(16.dp)) {
                Text("还没配置模型服务", style = RsType.body.copy(fontWeight = FontWeight.SemiBold), color = c.text)
                Spacer(Modifier.height(6.dp))
                Text("分析和自动整理会显示“等待配置服务”。手动标记、分类、项目管理、任务创建与导出不受影响。",
                    style = RsType.subhead, color = c.textSecondary)
            }
        }
    }
}

@Composable
private fun EntryCard(title: String, badge: String, note: String, accent: Boolean = false, onClick: () -> Unit) {
    val c = Rs.colors
    RsCard(modifier = Modifier.springClick(onClick), padding = PaddingValues(18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title, style = RsType.title, color = c.text)
                    Spacer(Modifier.size(10.dp))
                    Box(Modifier.clip(CircleShape).background(if (accent) c.accentSoft else c.track).padding(horizontal = 10.dp, vertical = 3.dp)) {
                        Text(badge, style = RsType.caption, color = if (accent) c.accent else c.textSecondary)
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(note, style = RsType.subhead, color = c.textSecondary)
            }
            Text("›", style = RsType.largeTitle, color = c.textTertiary)
        }
    }
}
