package app.resound.ui.assistant

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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.resound.AppGraph
import app.resound.collect.CaptureItemRow
import app.resound.collect.CaptureKind
import app.resound.collect.CollectStatus
import app.resound.collect.ExtractState
import app.resound.ui.theme.Rs
import app.resound.ui.theme.RsType
import app.resound.ui.verify.Pill
import app.resound.ui.verify.RsCard
import app.resound.ui.verify.formatStamp
import app.resound.ui.verify.springClick
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private fun kindOf(r: CaptureItemRow) = CaptureKind.entries.firstOrNull { it.name == r.kind }

/** 统一收集箱：分享进来的链接/文字/图片；OCR 结果如实展示（没识别就写没识别）。 */
@Composable
fun CollectScreen(bottomPadding: Dp, onBack: () -> Unit) {
    val c = Rs.colors
    val inbox = AppGraph.captureInbox
    val scope = rememberCoroutineScope()
    var showAll by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val rows by remember(showAll, query) {
        if (query.isNotBlank()) inbox.search(query) else if (showAll) inbox.observeAll() else inbox.observeInbox()
    }.collectAsStateWithLifecycle(initialValue = emptyList())
    var opened by remember { mutableStateOf<CaptureItemRow?>(null) }

    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(start = 16.dp, end = 16.dp, top = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("‹", style = RsType.largeTitle, color = c.textSecondary, modifier = Modifier.springClick(onBack).padding(end = 10.dp))
            Text("收集箱", style = RsType.largeTitle, color = c.text, modifier = Modifier.weight(1f))
            Pill(if (showAll) "待处理" else "全部", filled = false) { showAll = !showAll }
        }
        Spacer(Modifier.height(12.dp))
        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.track).padding(horizontal = 14.dp, vertical = 10.dp)) {
            BasicTextField(query, { query = it }, Modifier.fillMaxWidth(),
                textStyle = RsType.body.copy(color = c.text), singleLine = true,
                decorationBox = { inner ->
                    if (query.isEmpty()) Text("搜索内容、备注、识字结果", style = RsType.body, color = c.textTertiary)
                    inner()
                })
        }
        Spacer(Modifier.height(12.dp))
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = bottomPadding + 16.dp)) {
            if (rows.isEmpty()) item {
                RsCard {
                    Text("收集箱是空的", style = RsType.body, color = c.text)
                    Spacer(Modifier.height(6.dp))
                    Text("在浏览器、小红书、X 等 App 里点“分享 → 声声不息”，链接、文字和截图就会进到这里；截图文字在本机识别，不上传。", style = RsType.subhead, color = c.textSecondary)
                }
            }
            items(rows, key = { it.id }) { r -> CollectCard(r) { opened = r } }
        }
    }
    opened?.let { r -> CollectSheet(r, inbox) { opened = null } }
}

@Composable
private fun CollectCard(r: CaptureItemRow, onClick: () -> Unit) {
    val c = Rs.colors
    RsCard(modifier = Modifier.springClick(onClick), padding = PaddingValues(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(if (r.title.isNotBlank()) r.title else r.body.take(60), style = RsType.body.copy(fontWeight = FontWeight.SemiBold), color = c.text, maxLines = 2)
                Spacer(Modifier.height(4.dp))
                Text(
                    if (r.kind == CaptureKind.Image.name) r.ocrText?.take(90) ?: "本机原图（点开回看）" else r.body.take(90),
                    style = RsType.caption, color = c.textTertiary, maxLines = 2)
                r.note?.let { Spacer(Modifier.height(4.dp)); Text("备注：$it", style = RsType.caption, color = c.textSecondary) }
            }
            Spacer(Modifier.width(10.dp))
            Column(horizontalAlignment = Alignment.End) {
                Box(Modifier.clip(CircleShape).background(if (r.status == CollectStatus.Inbox.name) c.accentSoft else c.track).padding(horizontal = 10.dp, vertical = 3.dp)) {
                    Text(kindOf(r)?.label ?: r.kind, style = RsType.caption, color = if (r.status == CollectStatus.Inbox.name) c.accent else c.textSecondary)
                }
                Spacer(Modifier.height(4.dp))
                Text(formatStamp(r.createdAt), style = RsType.caption, color = c.textTertiary)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CollectSheet(r: CaptureItemRow, inbox: app.resound.collect.CaptureInbox, onClose: () -> Unit) {
    val c = Rs.colors
    val scope = rememberCoroutineScope()
    var note by remember { mutableStateOf(r.note ?: "") }
    val projects by (if (AppGraph.isProjectsReady) AppGraph.projects.active() else kotlinx.coroutines.flow.flowOf(emptyList<app.resound.projects.ProjectRow>()))
        .collectAsStateWithLifecycle(initialValue = emptyList())

    ModalBottomSheet(onDismissRequest = onClose) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 40.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("${kindOf(r)?.label ?: r.kind} · ${ExtractState.of(r.extracted).label} · ${formatStamp(r.createdAt)}${r.sourceApp?.let { " · 来自 $it" } ?: ""}", style = RsType.caption, color = c.textTertiary)
            if (r.kind == CaptureKind.Image.name) {
                InboxImage(r.localPath)
                Text(
                    r.ocrText?.let { "本地识字结果：\n$it" } ?: "本地识字：没有识别出文字（或尚未识别）",
                    style = RsType.subhead, color = c.textSecondary)
            } else {
                RsCard(padding = PaddingValues(12.dp)) { Text(r.body, style = RsType.body, color = c.text) }
            }
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.track).padding(horizontal = 12.dp, vertical = 8.dp)) {
                BasicTextField(note, { note = it }, Modifier.fillMaxWidth(),
                    textStyle = RsType.body.copy(color = c.text),
                    decorationBox = { inner -> if (note.isEmpty()) Text("补一句：这东西要我研究什么？", style = RsType.body, color = c.textTertiary); inner() })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Pill("存备注", filled = false) { scope.launch { inbox.setNote(r.id, note.ifBlank { null }); onClose() } }
                Pill("转灵感", filled = true) {
                    scope.launch {
                        val ideaId = AppGraph.ideas.create(
                            title = if (r.title.isNotBlank()) r.title else r.body.take(40),
                            content = listOfNotNull(r.body, r.ocrText, note.ifBlank { null }).joinToString("\n\n"))
                        inbox.linkIdea(r.id, ideaId)
                        onClose()
                    }
                }
            }
            if (projects.isNotEmpty()) {
                Text("挂到项目", style = RsType.subhead.copy(fontWeight = FontWeight.SemiBold), color = c.text)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    projects.take(10).forEach { p ->
                        Pill(p.name, filled = false) { scope.launch { inbox.linkProject(r.id, p.id); onClose() } }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (r.kind == CaptureKind.Image.name && r.ocrText == null) {
                    Pill("重试识字", filled = false) { scope.launch { inbox.retryOcr(r.id); onClose() } }
                }
                Pill("归档", filled = false) { scope.launch { inbox.archive(r.id); onClose() } }
                Pill("删除", filled = false) { scope.launch { inbox.delete(r.id); onClose() } }
            }
        }
    }
}

/** 收集箱图片：私有目录里的本机原图，解码失败如实显示。 */
@Composable
private fun InboxImage(path: String?) {
    val c = Rs.colors
    val bitmap by androidx.compose.runtime.produceState<android.graphics.Bitmap?>(null, path) {
        value = withContext(kotlinx.coroutines.Dispatchers.IO) {
            path?.let { runCatching { android.graphics.BitmapFactory.decodeFile(it) }.getOrNull() }
        }
    }
    if (bitmap != null) {
        androidx.compose.foundation.Image(
            bitmap = bitmap!!.asImageBitmap(), contentDescription = "原图",
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)),
            contentScale = androidx.compose.ui.layout.ContentScale.FillWidth)
    } else {
        Text(if (path == null) "没有原图" else "原图读取失败", style = RsType.subhead, color = c.textSecondary)
    }
}
