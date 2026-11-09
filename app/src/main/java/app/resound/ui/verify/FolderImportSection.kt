package app.resound.ui.verify

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.resound.importer.FolderImports
import app.resound.importer.ImportKind
import app.resound.importer.WatchedFolder
import app.resound.ui.items.Choice
import app.resound.ui.theme.Rs
import app.resound.ui.theme.RsType

/** 卡 117：自动导入文件夹。选录音笔 App、录音机存录音的文件夹，新录音自动导入转写，原文件不动 */
@Composable
fun FolderImportSection(imports: FolderImports) {
    val c = Rs.colors
    val state by imports.state.collectAsState()
    var adding by remember { mutableStateOf<android.net.Uri?>(null) }
    var editing by remember { mutableStateOf<WatchedFolder?>(null) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> if (uri != null) adding = uri }
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
            Row(Modifier.fillMaxWidth().springClick { runCatching { pick.launch(null) } }.padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(36.dp).clip(CircleShape).background(c.accentSoft), contentAlignment = Alignment.Center) {
                    Text("+", style = RsType.headline, color = c.accent)
                }
                Spacer(Modifier.width(12.dp))
                Text("添加文件夹", style = RsType.body.copy(fontWeight = FontWeight.SemiBold), color = c.accent)
            }
        }
        Column(Modifier.padding(start = 4.dp, end = 4.dp, top = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            state.lastImported?.takeIf { it > 0 }?.let { n ->
                Text("${state.lastImportedAt?.let(::formatStamp) ?: "最近"} 自动导入了 $n 个录音", style = RsType.subhead, color = c.text)
            }
            Text("录音笔 App、录音机把录音存在哪，就选哪个文件夹。新录音会自动导入转写，原文件不动、不删；同一个录音只导入一次。",
                style = RsType.caption, color = c.textTertiary)
            Text("大约每 15 分钟看一次，打开涛声时也会看；录音写完一两分钟后才会导入。", style = RsType.caption, color = c.textTertiary)
        }
    }
    adding?.let { uri -> AddFolderSheet(onDismiss = { adding = null }) { kind, existing -> imports.add(uri, kind, existing); adding = null } }
    editing?.let { f ->
        val live = state.folders.firstOrNull { it.id == f.id } ?: run { editing = null; return }
        EditFolderSheet(live, imports, onRepick = { editing = null; runCatching { pick.launch(null) } }) { editing = null }
    }
}

@Composable
private fun FolderRow(f: WatchedFolder, onToggle: (Boolean) -> Unit, onClick: () -> Unit) {
    val c = Rs.colors
    val tint = if (f.accessible) Color(0xFF3F8F6F) else c.warning
    Row(Modifier.fillMaxWidth().springClick(onClick).padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(36.dp).clip(CircleShape).background(tint.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
            Text("夹", style = RsType.subhead.copy(fontWeight = FontWeight.SemiBold), color = tint)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(f.name, style = RsType.body.copy(fontWeight = FontWeight.SemiBold), color = if (f.enabled) c.text else c.textTertiary, maxLines = 1)
            val sub = when {
                !f.accessible -> "没有读取权限了，点这里重新选"
                f.lastError != null -> f.lastError
                else -> listOfNotNull("按${f.kind.title}整理", "已导入 ${f.imported} 个", f.lastScanAt?.let { "${formatStamp(it)} 看过" }).joinToString(" · ")
            }
            Text(sub, style = RsType.caption, color = if (!f.accessible || f.lastError != null) c.warning else c.textTertiary, maxLines = 2)
        }
        Spacer(Modifier.width(8.dp))
        app.resound.ui.skills.Toggle(f.enabled && f.accessible) { if (f.accessible) onToggle(it) }
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

@Composable
private fun AddFolderSheet(onDismiss: () -> Unit, onAdd: (ImportKind, Boolean) -> Unit) {
    val c = Rs.colors
    var kind by remember { mutableStateOf(ImportKind.Normal) }
    var existing by remember { mutableStateOf(false) }
    RsSheet("添加文件夹", "这个文件夹里的录音按什么整理？之后可以改", onDismiss = onDismiss) {
        KindChoices(kind) { kind = it }
        Text("已有的录音", style = RsType.caption, color = c.textSecondary, modifier = Modifier.padding(top = 4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Choice("只导入以后的新录音", !existing) { existing = false }
            Choice("现有的也导入", existing) { existing = true }
        }
        if (existing) Text("文件夹里的录音多时会排队导入一阵子，转写也会用掉相应的额度。", style = RsType.caption, color = c.warning)
        ActionButton("开始自动导入", ActionKind.Primary) { onAdd(kind, existing) }
    }
}

@Composable
private fun EditFolderSheet(f: WatchedFolder, imports: FolderImports, onRepick: () -> Unit, onDismiss: () -> Unit) {
    val c = Rs.colors
    var confirm by remember { mutableStateOf(false) }
    RsSheet(f.name, "已导入 ${f.imported} 个录音" + (f.lastScanAt?.let { " · ${formatStamp(it)} 看过" } ?: ""), onDismiss = onDismiss) {
        if (!f.accessible) {
            Text("涛声没有这个文件夹的读取权限了（可能在系统里撤销了，或文件夹被删除、换了手机）。请重新选一次。", style = RsType.subhead, color = c.warning)
            ActionButton("重新选择文件夹", ActionKind.Primary) { imports.remove(f.id); onRepick() }
        }
        Text("按什么整理", style = RsType.caption, color = c.textSecondary)
        KindChoices(f.kind) { imports.setKind(f.id, it) }
        Text("只影响之后导入的录音，已导入的不变。", style = RsType.caption, color = c.textTertiary)
        ActionButton(if (confirm) "确定不再导入？再点一次" else "不再自动导入这个文件夹", ActionKind.Danger) {
            if (!confirm) { confirm = true; return@ActionButton }
            imports.remove(f.id); onDismiss()
        }
        Text("已导入的录音会保留；原文件夹里的文件不受影响。", style = RsType.caption, color = c.textTertiary)
    }
}
