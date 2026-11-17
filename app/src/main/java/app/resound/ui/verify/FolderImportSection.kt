package app.resound.ui.verify

import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.resound.importer.CallFolderGuide
import app.resound.importer.CallRecordingFolders
import app.resound.importer.FolderHistory
import app.resound.importer.FolderImports
import app.resound.importer.FolderPreview
import app.resound.importer.ImportKind
import app.resound.importer.WatchedFolder
import app.resound.ui.items.Choice
import app.resound.ui.theme.Rs
import app.resound.ui.theme.RsType

/*
 * 卡 117 / 199：自动导入文件夹。一个入口管所有来源：通话录音、录音笔·录音机 App、其他文件夹。
 * 添加流程：选来源 →（通话录音先看本机品牌的设置步骤）→ 系统选择器（通话录音直接打开到推荐位置）
 * → 预览“找到 N 个录音” → 选类型和“已有的录音怎么处理” → 开始。可以加多个文件夹。
 */

private enum class Source(val title: String, val hint: String, val kind: ImportKind) {
    Calls("通话录音", "手机自带的自动通话录音，挂断后自动转写成通话记录", ImportKind.Call),
    Recorder("录音笔、录音机 App", "录音笔 App 或手机录音机把录音存在哪，就选哪", ImportKind.Meeting),
    Other("其他文件夹", "任意放录音的文件夹，自己选按什么整理", ImportKind.Normal),
}

/** 打开系统选择器时要落到的位置：有本机存在的推荐文件夹就用它，否则第一个推荐位置，再不然就让系统自己决定 */
private fun initialUri(guide: CallFolderGuide?): android.net.Uri? {
    val folders = guide?.folders.orEmpty()
    if (folders.isEmpty()) return null
    val root = android.os.Environment.getExternalStorageDirectory()
    val pick = folders.firstOrNull { runCatching { java.io.File(root, it).isDirectory }.getOrDefault(false) } ?: folders.first()
    return android.provider.DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:$pick")
}

private fun duration(ms: Long): String {
    val m = ms / 60_000
    return when { m < 1 -> "不到 1 分钟"; m < 60 -> "约 $m 分钟"; m % 60 == 0L -> "约 ${m / 60} 小时"; else -> "约 ${m / 60} 小时 ${m % 60} 分" }
}

private fun day(ms: Long): String = java.time.Instant.ofEpochMilli(ms).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
    .let { d -> if (d.year == java.time.LocalDate.now().year) "${d.monthValue} 月 ${d.dayOfMonth} 日" else "${d.year} 年 ${d.monthValue} 月" }

@Composable
fun FolderImportSection(imports: FolderImports) {
    val c = Rs.colors
    val state by imports.state.collectAsStateWithLifecycle()
    val recordings = if (app.resound.AppGraph.isCallRecordingReady) app.resound.AppGraph.folderRecordings else null
    val backfill = recordings?.backfill?.collectAsStateWithLifecycle()?.value
    val guide = remember { CallRecordingFolders.guide(android.os.Build.MANUFACTURER.orEmpty(), android.os.Build.BRAND.orEmpty()) }
    var choosing by remember { mutableStateOf(false) }
    var callGuide by remember { mutableStateOf(false) }
    var source by remember { mutableStateOf(Source.Other) }
    var picked by remember { mutableStateOf<android.net.Uri?>(null) }
    var editing by remember { mutableStateOf<WatchedFolder?>(null) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> if (uri != null) picked = uri }
    fun launch(s: Source) { source = s; runCatching { pick.launch(if (s == Source.Calls) initialUri(guide) else null) } }

    Section("自动导入文件夹", trailing = if (state.folders.isEmpty()) null else ({
        Text(if (state.running) "查看中…" else "立即查看", style = RsType.subhead, color = c.accent,
            modifier = Modifier.springClick { if (!state.running) imports.scanNow() })
    })) {
        RsCard(Modifier.animateContentSize(), padding = PaddingValues(vertical = 4.dp)) {
            state.folders.forEachIndexed { i, f ->
                if (i > 0) Box(Modifier.padding(start = 64.dp).fillMaxWidth().height(0.5.dp).background(c.separator))
                FolderRow(f, onToggle = { imports.setEnabled(f.id, it) }) { editing = f }
            }
            if (state.folders.isNotEmpty()) Box(Modifier.padding(start = 64.dp).fillMaxWidth().height(0.5.dp).background(c.separator))
            Row(Modifier.fillMaxWidth().springClick { choosing = true }.padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(36.dp).clip(CircleShape).background(c.accentSoft), contentAlignment = Alignment.Center) {
                    Text("+", style = RsType.headline, color = c.accent)
                }
                Spacer(Modifier.width(12.dp))
                Text("添加文件夹", style = RsType.body.copy(fontWeight = FontWeight.SemiBold), color = c.accent)
            }
        }
        Column(Modifier.padding(start = 4.dp, end = 4.dp, top = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (backfill != null && backfill.remaining > 0) Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (backfill.paused) "历史录音暂停转写，还剩 ${backfill.remaining} 个" else "正在转写历史录音，还剩 ${backfill.remaining} 个",
                    style = RsType.subhead, color = c.text, modifier = Modifier.weight(1f))
                Text(if (backfill.paused) "继续" else "暂停", style = RsType.subhead.copy(fontWeight = FontWeight.SemiBold), color = c.accent,
                    modifier = Modifier.springClick { recordings.pauseBackfill(!backfill.paused) }.padding(start = 12.dp, top = 4.dp, bottom = 4.dp))
            }
            state.lastImported?.takeIf { it > 0 }?.let { n ->
                Text("${state.lastImportedAt?.let(::formatStamp) ?: "最近"} 自动导入了 $n 个录音", style = RsType.subhead, color = c.text)
            }
            Text("通话录音、录音笔 App、录音机存在哪，就加哪个文件夹，可以加多个。新录音会自动导入转写；原文件不动、不删，同一个录音只导入一次。",
                style = RsType.caption, color = c.textTertiary)
            Text("大约每 15 分钟看一次，打开涛声时也会看；录音写完一两分钟后才会导入。", style = RsType.caption, color = c.textTertiary)
        }
    }

    if (choosing) SourceSheet(onDismiss = { choosing = false }) { s ->
        choosing = false
        if (s == Source.Calls) callGuide = true else launch(s)
    }
    if (callGuide) CallGuideSheet(guide, onDismiss = { callGuide = false }) { callGuide = false; launch(Source.Calls) }
    picked?.let { uri ->
        AddFolderSheet(uri, source, imports, onDismiss = { picked = null },
            onRepick = { picked = null; launch(source) }) { kind, history -> imports.add(uri, kind, history); picked = null }
    }
    editing?.let { f ->
        val live = state.folders.firstOrNull { it.id == f.id } ?: run { editing = null; return }
        EditFolderSheet(live, imports, onRepick = { editing = null; launch(if (live.kind == ImportKind.Call) Source.Calls else Source.Other) }) { editing = null }
    }
}

@Composable
private fun FolderRow(f: WatchedFolder, onToggle: (Boolean) -> Unit, onClick: () -> Unit) {
    val c = Rs.colors
    val tint = when { !f.accessible -> c.warning; f.kind == ImportKind.Call -> Color(0xFF3370C9); else -> Color(0xFF3F8F6F) }
    Row(Modifier.fillMaxWidth().springClick(onClick).padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(36.dp).clip(CircleShape).background(tint.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
            Text(if (f.kind == ImportKind.Call) "话" else "夹", style = RsType.subhead.copy(fontWeight = FontWeight.SemiBold), color = tint)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(f.name, style = RsType.body.copy(fontWeight = FontWeight.SemiBold), color = if (f.enabled) c.text else c.textTertiary, maxLines = 1)
            val sub = when {
                !f.accessible -> "没有读取权限了，点这里重新选"
                f.lastError != null -> f.lastError
                else -> listOfNotNull("按${f.kind.title.substringBefore(" ")}整理", "已导入 ${f.imported} 个",
                    f.listed.takeIf { it > 0 }?.let { "未转写 $it 个" }, f.lastScanAt?.let { "${formatStamp(it)} 看过" }).joinToString(" · ")
            }
            Text(sub, style = RsType.caption, color = if (!f.accessible || f.lastError != null) c.warning else c.textTertiary, maxLines = 2)
        }
        Spacer(Modifier.width(8.dp))
        app.resound.ui.skills.Toggle(f.enabled && f.accessible) { if (f.accessible) onToggle(it) }
    }
}

/** 第一步：选来源。三个大选项，点了直接往下走 */
@Composable
private fun SourceSheet(onDismiss: () -> Unit, onPick: (Source) -> Unit) {
    val c = Rs.colors
    RsSheet("添加文件夹", "录音从哪来？", onDismiss = onDismiss) {
        Source.entries.forEach { s ->
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.fill).springClick { onPick(s) }.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(s.title, style = RsType.body.copy(fontWeight = FontWeight.SemiBold), color = c.text)
                    Text(s.hint, style = RsType.caption, color = c.textSecondary)
                }
                Text("›", style = RsType.title, color = c.textTertiary, modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
}

/** 通话录音：本机品牌的三步。第 1 步直达系统通话设置，第 3 步打开选择器到推荐位置 */
@Composable
private fun CallGuideSheet(guide: CallFolderGuide, onDismiss: () -> Unit, onPick: () -> Unit) {
    val c = Rs.colors
    val context = LocalContext.current
    var others by remember { mutableStateOf(false) }
    RsSheet("通话录音", "手机自己录，涛声来收。安卓不让其他 App 录通话，但手机自带的电话可以自动录。", onDismiss = onDismiss) {
        GuideStep(1, "打开自动通话录音", guide.brand) {
            guide.steps.forEach { Text("· $it", style = RsType.subhead, color = c.textSecondary) }
            Text("去打开", style = RsType.subhead.copy(fontWeight = FontWeight.SemiBold), color = c.accent,
                modifier = Modifier.padding(top = 4.dp).clip(CircleShape).background(c.accentSoft).springClick {
                    val call = android.content.Intent(android.telecom.TelecomManager.ACTION_SHOW_CALL_SETTINGS).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    val dialer = android.content.Intent(android.content.Intent.ACTION_DIAL).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    runCatching { context.startActivity(call) }.recoverCatching { context.startActivity(dialer) }
                }.padding(horizontal = 14.dp, vertical = 6.dp))
        }
        GuideStep(2, "先打一通测试电话", null) {
            Text("没录过通话的手机，录音文件夹还不存在。打一通电话说几句再挂，让手机先录一个。", style = RsType.subhead, color = c.textSecondary)
        }
        GuideStep(3, "选择通话录音文件夹", null) {
            Text(if (guide.folders.isEmpty()) "在打开的页面里找到通话录音所在的文件夹（常叫“通话录音”“Call”“CallRecord”），点“使用此文件夹”。"
                 else "会直接打开到这款手机存通话录音的位置，点底部的“使用此文件夹”就行。", style = RsType.subhead, color = c.textSecondary)
        }
        guide.note?.let { Text(it, style = RsType.caption, color = c.textTertiary) }
        ActionButton("选择通话录音文件夹", ActionKind.Primary) { onPick() }
        Text(if (others) "收起其他品牌" else "不是这个品牌？看看其他手机怎么开", style = RsType.caption, color = c.accent,
            modifier = Modifier.springClick { others = !others })
        if (others) CallRecordingFolders.all.filter { it.brand != guide.brand }.forEach { g ->
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(g.brand, style = RsType.subhead.copy(fontWeight = FontWeight.SemiBold), color = c.text)
                Text(g.steps.joinToString(" → "), style = RsType.caption, color = c.textSecondary)
            }
        }
        Text("合规提醒：企业用来记录客户沟通时，请按公司规定和当地法规，必要时提前告知对方。", style = RsType.caption, color = c.textTertiary)
    }
}

@Composable
private fun GuideStep(n: Int, title: String, tag: String?, content: @Composable ColumnScope.() -> Unit) {
    val c = Rs.colors
    Row {
        Box(Modifier.size(24.dp).clip(CircleShape).background(c.accentSoft), contentAlignment = Alignment.Center) {
            Text("$n", style = RsType.caption.copy(fontWeight = FontWeight.SemiBold), color = c.accent)
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = RsType.body.copy(fontWeight = FontWeight.SemiBold), color = c.text)
                tag?.let { Text(it, style = RsType.caption, color = c.textTertiary, modifier = Modifier.padding(start = 8.dp)) }
            }
            content()
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun KindChoices(kind: ImportKind, onPick: (ImportKind) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ImportKind.entries.forEach { k -> Choice(k.title, k == kind) { onPick(k) } }
    }
    Text(kind.hint, style = RsType.caption, color = Rs.colors.textTertiary)
}

/** 四个选项，每个带上预览出来的数量和时长 */
@Composable
private fun HistoryChoices(preview: FolderPreview?, selected: FolderHistory, onPick: (FolderHistory) -> Unit) {
    val c = Rs.colors
    FolderHistory.entries.forEach { h ->
        val detail = when (h) {
            FolderHistory.OnlyNew -> "已有的不管，从现在起的新录音才导入"
            FolderHistory.Days7 -> preview?.let { "${it.last7} 个，${duration(it.last7Millis)}，会转写；更早的列出来" } ?: "这 7 天的会转写；更早的列出来"
            FolderHistory.Days30 -> preview?.let { "${it.last30} 个，${duration(it.last30Millis)}，会转写；更早的列出来" } ?: "这 30 天的会转写；更早的列出来"
            FolderHistory.ListAll -> preview?.let { "${it.total} 个，先不转写，点开哪个再转哪个" } ?: "先不转写，点开哪个再转哪个"
        }
        val on = h == selected
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(if (on) c.accentSoft else c.fill)
            .border(if (on) 1.5.dp else 0.dp, if (on) c.accent else Color.Transparent, RoundedCornerShape(14.dp))
            .springClick { onPick(h) }.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(h.title, style = RsType.body.copy(fontWeight = FontWeight.SemiBold), color = if (on) c.accent else c.text)
                Text(detail, style = RsType.caption, color = c.textSecondary)
            }
        }
    }
}

/** 选完文件夹：先预览，再选类型和已有录音怎么处理 */
@Composable
private fun AddFolderSheet(uri: android.net.Uri, source: Source, imports: FolderImports, onDismiss: () -> Unit, onRepick: () -> Unit,
                           onAdd: (ImportKind, FolderHistory) -> Unit) {
    val c = Rs.colors
    var preview by remember(uri) { mutableStateOf<FolderPreview?>(null) }
    var error by remember(uri) { mutableStateOf<String?>(null) }
    var kind by remember(uri) { mutableStateOf(source.kind) }
    var history by remember(uri) { mutableStateOf(FolderHistory.ListAll) }
    LaunchedEffect(uri) {
        runCatching { imports.preview(uri) }
            .onSuccess { p -> preview = p; if (p.looksLikeCalls && source == Source.Other) kind = ImportKind.Call }
            .onFailure { error = it.message ?: "读不了这个文件夹，换一个试试" }
    }
    val p = preview
    val subtitle = when {
        error != null -> error
        p == null -> "正在看文件夹里有什么…"
        p.total == 0 -> "${p.name} · 还没有录音，新录音会自动导入"
        else -> "${p.name} · 找到 ${p.total} 个录音，${duration(p.totalMillis)}" + (p.oldestAt?.let { "，最早是 ${day(it)}" } ?: "")
    }
    RsSheet(if (kind == ImportKind.Call) "添加通话录音" else "添加文件夹", subtitle, onDismiss = onDismiss, fixedHeight = true) {
        if (error != null) { ActionButton("重新选择文件夹", ActionKind.Primary) { onRepick() }; return@RsSheet }
        if (p != null && source == Source.Calls && !p.looksLikeCalls && p.total > 0)
            Text("这里看起来不是通话录音。确定的话照常添加；不确定就重新选一次。", style = RsType.caption, color = c.warning)
        if (p != null && p.looksLikeCalls && kind != ImportKind.Call)
            Text("看起来是通话录音，建议按“通话 / 访谈”整理", style = RsType.caption, color = c.accent,
                modifier = Modifier.springClick { kind = ImportKind.Call })
        Text("按什么整理", style = RsType.caption, color = c.textSecondary)
        KindChoices(kind) { kind = it }
        if (p == null || p.total > 0) {
            Text("已有的录音", style = RsType.caption, color = c.textSecondary, modifier = Modifier.padding(top = 4.dp))
            HistoryChoices(p, history) { history = it }
            if (history == FolderHistory.Days7 || history == FolderHistory.Days30)
                Text("会从最近的往前转，新录音始终优先；随时可以暂停。转写会用掉相应的额度。", style = RsType.caption, color = c.textTertiary)
        }
        ActionButton("开始自动导入", ActionKind.Primary, enabled = p != null) {
            onAdd(kind, if (p != null && p.total == 0) FolderHistory.OnlyNew else history)
        }
        if (p != null) Text("选错了文件夹？重新选", style = RsType.caption, color = c.accent, modifier = Modifier.springClick { onRepick() })
    }
}

@Composable
private fun EditFolderSheet(f: WatchedFolder, imports: FolderImports, onRepick: () -> Unit, onDismiss: () -> Unit) {
    val c = Rs.colors
    var confirm by remember { mutableStateOf(false) }
    RsSheet(f.name, listOfNotNull("已导入 ${f.imported} 个录音", f.listed.takeIf { it > 0 }?.let { "未转写 $it 个" },
        f.lastScanAt?.let { "${formatStamp(it)} 看过" }).joinToString(" · "), onDismiss = onDismiss) {
        if (!f.accessible) {
            Text("涛声没有这个文件夹的读取权限了（可能在系统里撤销了，或文件夹被删除、换了手机）。请重新选一次。", style = RsType.subhead, color = c.warning)
            ActionButton("重新选择文件夹", ActionKind.Primary) { onRepick() }
        }
        Text("按什么整理", style = RsType.caption, color = c.textSecondary)
        KindChoices(f.kind) { imports.setKind(f.id, it) }
        Text("只影响之后导入的录音，已导入的不变。", style = RsType.caption, color = c.textTertiary)
        Text("加入时已有的录音", style = RsType.caption, color = c.textSecondary, modifier = Modifier.padding(top = 4.dp))
        HistoryChoices(null, f.history) { imports.setHistory(f.id, it) }
        Text("只会多转写、多列出；已导入和已列出的不会撤掉。", style = RsType.caption, color = c.textTertiary)
        ActionButton(if (confirm) "确定不再导入？再点一次" else "不再自动导入这个文件夹", ActionKind.Danger) {
            if (!confirm) { confirm = true; return@ActionButton }
            imports.remove(f.id); onDismiss()
        }
        Text("已导入的录音会保留；原文件夹里的文件不受影响。", style = RsType.caption, color = c.textTertiary)
    }
}
