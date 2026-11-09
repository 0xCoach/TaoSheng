/*
 * [INPUT]: 完整卡内目录、下载状态和下载回调。
 * [OUTPUT]: 卡内文件多选及曾实时采集内容的导入确认。
 * [POS]: 录音卡管理页的 BLE 文件入口，沿用现有导入队列。
 * [PROTOCOL]: 变更时更新此头部，然后检查 CLAUDE.md
 */
package app.resound.ui.verify

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.resound.sonicloud.*
import app.resound.ui.theme.Rs
import app.resound.ui.theme.RsType

@Composable
internal fun RecorderFilesSection(state: RecorderStatus, download: (List<RecorderFile>) -> Unit) {
    var selection by remember(state.device?.key) { mutableStateOf(emptySet<String>()) }
    var confirmDownload by remember(state.device?.key) { mutableStateOf<List<RecorderFile>?>(null) }
    val importable = state.files.filter { it.name in selection && !it.imported }
    val idle = state.connected && !state.listing && state.downloading == null
    val stopped = idle && state.recording == RecorderRecording.Stopped
    LaunchedEffect(state.files) { selection = selection.intersect(state.files.map { it.name }.toSet()) }
    if (state.files.isEmpty()) {
        Text(if (state.listing) "正在读取卡内录音…" else "暂无文件，请连接后刷新目录。",
            style = RsType.caption, color = Rs.colors.textSecondary)
    } else {
        state.files.forEach { file ->
            key(file.name) {
                Row(Modifier.fillMaxWidth().toggleable(value = file.name in selection, enabled = idle, role = Role.Checkbox) { checked ->
                    selection = if (checked) selection + file.name else selection - file.name
                }.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Checkbox(checked = file.name in selection, onCheckedChange = null, enabled = idle)
                    Column(Modifier.weight(1f)) {
                        Text(file.name, style = RsType.body, color = Rs.colors.text)
                        Text("${file.seconds / 60} 分 ${file.seconds % 60} 秒 · ${file.size / 1024} KB" +
                            if (file.imported) " · 已交给导入队列" else if (file.capturedLive) " · 曾实时采集" else "",
                            style = RsType.caption, color = Rs.colors.textSecondary)
                    }
                }
            }
        }
        ActionButton("蓝牙下载并导入所选 ${importable.size} 个文件", ActionKind.Primary,
            enabled = stopped && importable.isNotEmpty()) {
            if (importable.any { it.capturedLive }) confirmDownload = importable.toList()
            else { download(importable); selection = emptySet() }
        }
    }
    confirmDownload?.let { files ->
        AlertDialog(onDismissRequest = { confirmDownload = null }, title = { Text("可能有重复内容") },
            text = { Text("所选文件有部分内容曾实时采集。完整导入可能再次转写这些内容。") },
            confirmButton = { TextButton(enabled = stopped, onClick = {
                confirmDownload = null; download(files); selection = emptySet()
            }) { Text("仍然导入") } },
            dismissButton = { TextButton(onClick = { confirmDownload = null }) { Text("取消") } })
    }
}
