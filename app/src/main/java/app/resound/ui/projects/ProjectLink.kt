package app.resound.ui.projects

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.resound.AppGraph
import app.resound.projects.ProjectRefKind
import app.resound.projects.ProjectRow
import app.resound.ui.theme.Rs
import app.resound.ui.theme.RsType
import app.resound.ui.verify.Pill
import app.resound.ui.verify.RsCard
import app.resound.ui.verify.springClick
import kotlinx.coroutines.launch

/**
 * 详情页通用的“挂到项目”区块：显示已挂上的项目（点×移除），可打开选择器加挂。
 * project_links 的唯一写入通道——挂上会议后其待办/约定/决定会自动归入项目。
 */
@Composable
internal fun ProjectLinkSection(kind: ProjectRefKind, refId: Long) {
    if (!AppGraph.isProjectsReady) return
    val c = Rs.colors
    val scope = rememberCoroutineScope()
    var linked by remember { mutableStateOf<List<ProjectRow>?>(null) }
    var picking by remember { mutableStateOf(false) }
    suspend fun refresh() { linked = runCatching { AppGraph.projects.projectsOf(kind, refId) }.getOrDefault(emptyList()) }
    androidx.compose.runtime.LaunchedEffect(kind, refId) { refresh() }

    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("所属项目", style = RsType.section, color = c.textSecondary, modifier = Modifier.weight(1f))
            Text(if (linked.isNullOrEmpty()) "挂到项目 ›" else "管理 ›",
                style = RsType.subhead.copy(fontWeight = FontWeight.Medium), color = c.accent,
                modifier = Modifier.springClick { picking = true }.padding(vertical = 4.dp))
        }
        if (linked.isNullOrEmpty()) {
            Text(if (kind == ProjectRefKind.Event) "还没挂项目。挂上后这场会的待办、约定、决定自动归入。"
            else "还没挂项目。", style = RsType.caption, color = c.textTertiary)
        } else Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            linked!!.forEach { p -> Pill(p.name, filled = true) { picking = true } }
        }
    }
    if (picking) ProjectPickSheet(kind, refId, onChanged = { scope.launch { refresh() } }) { picking = false }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProjectPickSheet(kind: ProjectRefKind, refId: Long, onChanged: () -> Unit, onClose: () -> Unit) {
    val c = Rs.colors
    val scope = rememberCoroutineScope()
    val active by AppGraph.projects.active().collectAsStateWithLifecycle(initialValue = emptyList())
    var linkedIds by remember { mutableStateOf<Set<Long>?>(null) }
    suspend fun refresh() {
        linkedIds = runCatching { AppGraph.projects.projectsOf(kind, refId).map { it.id }.toSet() }.getOrDefault(emptySet())
    }
    androidx.compose.runtime.LaunchedEffect(kind, refId) { refresh() }

    ModalBottomSheet(onClose) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("挂到项目", style = RsType.title, color = c.text)
            if (linkedIds == null) Text("加载中…", style = RsType.subhead, color = c.textTertiary)
            else if (active.isEmpty()) RsCard(padding = PaddingValues(14.dp)) {
                Text("还没有项目。到「助手 → 项目」先建一个。", style = RsType.subhead, color = c.textSecondary)
            }
            else active.forEach { p ->
                val on = linkedIds!!.contains(p.id)
                RsCard(modifier = Modifier.springClick {
                        scope.launch {
                            if (on) AppGraph.projects.unlink(p.id, kind, refId) else AppGraph.projects.link(p.id, kind, refId)
                            refresh(); onChanged()
                        }
                    }, padding = PaddingValues(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(p.name, style = RsType.body.copy(fontWeight = FontWeight.SemiBold), color = c.text)
                            if (p.note.isNotBlank()) Text(p.note, style = RsType.caption, color = c.textSecondary, maxLines = 1)
                        }
                        Text(if (on) "已挂 ✓" else "挂上", style = RsType.subhead, color = if (on) c.accent else c.textSecondary)
                    }
                }
            }
            Pill("完成", filled = true, onClick = onClose)
        }
    }
}
