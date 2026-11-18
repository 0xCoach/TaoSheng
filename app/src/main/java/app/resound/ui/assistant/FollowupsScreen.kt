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
import app.resound.followup.FollowupKind
import app.resound.followup.FollowupRow
import app.resound.followup.FollowupStatus
import app.resound.ui.theme.Rs
import app.resound.ui.theme.RsType
import app.resound.ui.verify.Pill
import app.resound.ui.verify.RsCard
import app.resound.ui.verify.formatStamp
import app.resound.ui.verify.springClick
import kotlinx.coroutines.launch

/** 主动跟进：承诺/落后待办/搁置灵感/系统建议——建议明示不是承诺。 */
@Composable
fun FollowupsScreen(bottomPadding: Dp, onBack: () -> Unit) {
    val c = Rs.colors
    val engine = AppGraph.followupEngine
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var closed by remember { mutableStateOf(false) }
    val rows by remember(closed) { if (closed) engine.observeClosed() else engine.observeOpen() }
        .collectAsStateWithLifecycle(initialValue = emptyList())
    var opened by remember { mutableStateOf<FollowupRow?>(null) }
    var scanning by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(start = 16.dp, end = 16.dp, top = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("‹", style = RsType.largeTitle, color = c.textSecondary, modifier = Modifier.springClick(onBack).padding(end = 10.dp))
            Text("跟进", style = RsType.largeTitle, color = c.text, modifier = Modifier.weight(1f))
            Pill(if (scanning) "扫描中…" else "现在扫描", filled = false) {
                if (!scanning) scope.launch {
                    scanning = true
                    val n = engine.scan()
                    scanning = false
                    Toast.makeText(context, "本轮新发现 $n 条待跟进", Toast.LENGTH_SHORT).show()
                }
            }
            Spacer(Modifier.width(8.dp))
            Pill(if (closed) "待跟进" else "已了结", filled = false) { closed = !closed }
        }
        Spacer(Modifier.height(12.dp))
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = bottomPadding + 16.dp)) {
            if (rows.isEmpty()) item {
                RsCard {
                    Text(if (closed) "没有已了结的跟进" else "没有待跟进事项", style = RsType.body, color = c.text)
                    Spacer(Modifier.height(6.dp))
                    Text("系统每天扫描：接受后超时未做的待办、放了很久没推进的灵感、登记过的承诺；系统建议会单独标注，不会当作你的承诺。", style = RsType.subhead, color = c.textSecondary)
                }
            }
            items(rows, key = { it.id }) { f -> FollowupCard(f) { opened = f } }
        }
    }
    opened?.let { f -> FollowupSheet(f, engine) { opened = null } }
}

private fun kindOf(f: FollowupRow) = FollowupKind.entries.firstOrNull { it.name == f.kind }

@Composable
private fun FollowupCard(f: FollowupRow, onClick: () -> Unit) {
    val c = Rs.colors
    val kind = kindOf(f)
    val suggestion = kind == FollowupKind.Suggestion
    RsCard(modifier = Modifier.springClick(onClick), padding = PaddingValues(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(f.title, style = RsType.body.copy(fontWeight = FontWeight.SemiBold), color = c.text)
                if (f.detail.isNotBlank()) { Spacer(Modifier.height(4.dp)); Text(f.detail, style = RsType.subhead, color = c.textSecondary, maxLines = 2) }
                if (suggestion) { Spacer(Modifier.height(4.dp)); Text("系统建议——不是你的承诺", style = RsType.caption, color = c.textTertiary) }
            }
            Spacer(Modifier.width(10.dp))
            Column(horizontalAlignment = Alignment.End) {
                Box(Modifier.clip(CircleShape).background(if (suggestion) c.track else c.accentSoft).padding(horizontal = 10.dp, vertical = 3.dp)) {
                    Text(kind?.label ?: f.kind, style = RsType.caption, color = if (suggestion) c.textSecondary else c.accent)
                }
                Spacer(Modifier.height(4.dp))
                Text(formatStamp(f.createdAt), style = RsType.caption, color = c.textTertiary)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FollowupSheet(f: FollowupRow, engine: app.resound.followup.FollowupEngine, onClose: () -> Unit) {
    val c = Rs.colors
    val scope = rememberCoroutineScope()
    ModalBottomSheet(onDismissRequest = onClose) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 40.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(f.title, style = RsType.title, color = c.text)
            if (f.detail.isNotBlank()) Text(f.detail, style = RsType.body, color = c.textSecondary)
            Text("${kindOf(f)?.label ?: f.kind} · 登记于 ${formatStamp(f.createdAt)} · 已提醒 ${f.remindCount} 次", style = RsType.caption, color = c.textTertiary)
            f.sourceText?.let {
                RsCard(padding = PaddingValues(12.dp)) {
                    Text("原话", style = RsType.caption, color = c.textTertiary)
                    Spacer(Modifier.height(4.dp))
                    Text(it, style = RsType.subhead, color = c.textSecondary)
                }
            }
            if (f.status == FollowupStatus.Open.name) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Pill("完成", filled = true) { scope.launch { engine.markDone(f.id); onClose() } }
                    Pill("稍后再提", filled = false) { scope.launch { engine.snooze(f.id); onClose() } }
                    Pill("不用跟", filled = false) { scope.launch { engine.dismiss(f.id); onClose() } }
                }
            } else {
                Pill("删除记录", filled = false) { scope.launch { engine.delete(f.id); onClose() } }
            }
        }
    }
}
