package app.resound.ui.assistant

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
import app.resound.decision.DecisionRow
import app.resound.decision.DecisionStatus
import app.resound.ui.theme.Rs
import app.resound.ui.theme.RsType
import app.resound.ui.verify.Pill
import app.resound.ui.verify.RsCard
import app.resound.ui.verify.formatStamp
import app.resound.ui.verify.springClick
import kotlinx.coroutines.launch

/** 决策记忆：生效决定 + 历史（被否决/替代/撤回）；人工编辑、否决、撤回都留痕可溯源。 */
@Composable
fun DecisionsScreen(bottomPadding: Dp, onBack: () -> Unit) {
    val c = Rs.colors
    val repo = AppGraph.decisionRepo
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var history by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val rows by remember(history, query) {
        if (query.isNotBlank()) repo.search(query) else if (history) repo.observeHistory() else repo.observeActive()
    }.collectAsStateWithLifecycle(initialValue = emptyList())
    var opened by remember { mutableStateOf<DecisionRow?>(null) }
    var syncing by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(start = 16.dp, end = 16.dp, top = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("‹", style = RsType.largeTitle, color = c.textSecondary, modifier = Modifier.springClick(onBack).padding(end = 10.dp))
            Text("决策", style = RsType.largeTitle, color = c.text, modifier = Modifier.weight(1f))
            Pill(if (syncing) "同步中…" else "同步提取", filled = false) {
                if (!syncing) scope.launch {
                    syncing = true
                    val n = repo.syncFromDigest()
                    syncing = false
                    Toast.makeText(context, if (n > 0) "新导入 $n 条决定" else "没有新的决定提取项", Toast.LENGTH_SHORT).show()
                }
            }
            Spacer(Modifier.width(8.dp))
            Pill(if (history) "生效中" else "历史", filled = false) { history = !history }
        }
        Spacer(Modifier.height(12.dp))
        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.track).padding(horizontal = 14.dp, vertical = 10.dp)) {
            BasicTextField(query, { query = it }, Modifier.fillMaxWidth(),
                textStyle = RsType.body.copy(color = c.text), singleLine = true,
                decorationBox = { inner ->
                    if (query.isEmpty()) Text("搜索决定", style = RsType.body, color = c.textTertiary)
                    inner()
                })
        }
        Spacer(Modifier.height(12.dp))
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = bottomPadding + 16.dp)) {
            if (rows.isEmpty()) item {
                RsCard {
                    Text(if (history) "没有历史决定" else "还没有生效中的决定", style = RsType.body, color = c.text)
                    Spacer(Modifier.height(6.dp))
                    Text("整理提取的“决定/约定”会自动入库；被否决或替代的旧决定保留在历史里，不再当作当前决定。", style = RsType.subhead, color = c.textSecondary)
                }
            }
            items(rows, key = { it.id }) { d -> DecisionCard(d, history) { opened = d } }
        }
    }
    opened?.let { d -> DecisionSheet(d, repo) { opened = null } }
}

@Composable
private fun DecisionCard(d: DecisionRow, history: Boolean, onClick: () -> Unit) {
    val c = Rs.colors
    val status = DecisionStatus.of(d.status)
    RsCard(modifier = Modifier.springClick(onClick), padding = PaddingValues(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(d.title, style = RsType.body.copy(fontWeight = FontWeight.SemiBold), color = c.text)
                if (d.detail.isNotBlank()) { Spacer(Modifier.height(4.dp)); Text(d.detail, style = RsType.subhead, color = c.textSecondary, maxLines = 2) }
            }
            Spacer(Modifier.width(10.dp))
            Column(horizontalAlignment = Alignment.End) {
                Box(Modifier.clip(CircleShape).background(if (status == DecisionStatus.Active) c.accentSoft else c.track).padding(horizontal = 10.dp, vertical = 3.dp)) {
                    Text(status.label, style = RsType.caption, color = if (status == DecisionStatus.Active) c.accent else c.textSecondary)
                }
                Spacer(Modifier.height(4.dp))
                Text(formatStamp(d.decidedAt), style = RsType.caption, color = c.textTertiary)
            }
        }
        if (history && d.status == DecisionStatus.Rejected.name && !d.rejectedReason.isNullOrBlank()) {
            Spacer(Modifier.height(6.dp))
            Text("否决原因：${d.rejectedReason}", style = RsType.caption, color = c.textSecondary)
        }
        if (d.supersededBy != null) {
            Spacer(Modifier.height(6.dp))
            Text("已被决定 #${d.supersededBy} 替代", style = RsType.caption, color = c.textSecondary)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DecisionSheet(d: DecisionRow, repo: app.resound.decision.DecisionRepository, onClose: () -> Unit) {
    val c = Rs.colors
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var reason by remember { mutableStateOf("") }
    val evidence by remember(d.id) { kotlinx.coroutines.flow.flow { emit(repo.evidence(d)) } }.collectAsStateWithLifecycle(initialValue = null)
    val revisions by remember(d.id) { repo.revisionsOf(d.id) }.collectAsStateWithLifecycle(initialValue = emptyList())

    androidx.compose.material3.ModalBottomSheet(onDismissRequest = onClose) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 40.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(d.title, style = RsType.title, color = c.text)
            if (d.detail.isNotBlank()) Text(d.detail, style = RsType.body, color = c.textSecondary)
            Text("决定时间 ${formatStamp(d.decidedAt)} · 置信度 ${app.resound.decision.DecisionConfidence.of(d.confidence).label}", style = RsType.caption, color = c.textTertiary)
            evidence?.let {
                RsCard(padding = PaddingValues(12.dp)) {
                    Text("原话证据", style = RsType.caption, color = c.textTertiary)
                    Spacer(Modifier.height(4.dp))
                    Text(it, style = RsType.subhead, color = c.textSecondary)
                }
            }
            if (revisions.isNotEmpty()) {
                Text("修订记录", style = RsType.subhead.copy(fontWeight = FontWeight.SemiBold), color = c.text)
                revisions.take(8).forEach { r ->
                    Text("${formatStamp(r.at)} ${app.resound.revise.RevisionOp.of(r.op).label}${r.reason?.let { " · $it" } ?: ""}", style = RsType.caption, color = c.textSecondary)
                }
            }
            if (d.status == DecisionStatus.Active.name) {
                Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.track).padding(horizontal = 12.dp, vertical = 8.dp)) {
                    BasicTextField(reason, { reason = it }, Modifier.fillMaxWidth(),
                        textStyle = RsType.body.copy(color = c.text), singleLine = true,
                        decorationBox = { inner -> if (reason.isEmpty()) Text("原因（否决/撤回时记录）", style = RsType.body, color = c.textTertiary); inner() })
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Pill("否决", filled = false) {
                        scope.launch {
                            if (repo.reject(d.id, reason.ifBlank { null })) { Toast.makeText(context, "已否决并留痕", Toast.LENGTH_SHORT).show(); onClose() }
                        }
                    }
                    Pill("撤回", filled = false) {
                        scope.launch {
                            if (repo.withdraw(d.id, reason.ifBlank { null })) { Toast.makeText(context, "已撤回并留痕", Toast.LENGTH_SHORT).show(); onClose() }
                        }
                    }
                    Pill("删除", filled = false) {
                        scope.launch { repo.delete(d.id); onClose() }
                    }
                }
            }
        }
    }
}
