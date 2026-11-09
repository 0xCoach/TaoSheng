/*
 * [INPUT]: 完整卡内目录、下载与删除状态；操作回调由 RecorderSection 交给控制器。
 * [OUTPUT]: 日期/星期分组、分页、多选导入及不可撤销删除的二次确认。
 * [POS]: 录音卡管理页的文件区；选择跨页保留，确认固定设备与文件快照。
 * [PROTOCOL]: 变更时更新此头部，然后检查 CLAUDE.md
 */
package app.resound.ui.verify

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
internal fun RecorderFilesSection(
    state: RecorderStatus,
    wifi: RecorderWifiStatus,
    deletion: RecorderDeleteStatus,
    download: (List<RecorderFile>) -> Unit,
    delete: (String, List<RecorderFile>) -> Unit,
    stopDeleting: () -> Unit,
) {
    var selection by remember(state.device?.key) { mutableStateOf(emptySet<String>()) }
    var day by remember(state.device?.key) { mutableStateOf<String?>(null) }
    var page by remember(state.device?.key, day) { mutableIntStateOf(0) }
    var chooseDay by remember { mutableStateOf(false) }
    var confirmDelete by remember(state.device?.key) { mutableStateOf<List<RecorderFile>?>(null) }
    var confirmDownload by remember(state.device?.key) { mutableStateOf<List<RecorderFile>?>(null) }
    val dated = remember(state.files) { recorderDatedFiles(state.files) }
    val days = remember(dated) { dated.map { it.day }.distinct() }
    val shown = remember(dated, day, page) { recorderFilePage(dated, day, page) }
    val selected = state.files.filter { it.name in selection }
    val importable = selected.filterNot { it.imported }
    val idle = state.connected && !state.listing && state.downloading == null && !deletion.busy
    val stopped = idle && state.recording == RecorderRecording.Stopped
    val wifiConnecting = wifi.phase != RecorderWifiPhase.Off && wifi.phase != RecorderWifiPhase.Ready
    LaunchedEffect(state.files) {
        selection = selection.intersect(state.files.map { it.name }.toSet())
    }
    LaunchedEffect(shown.index) { page = shown.index }

    if (deletion.total > 0) {
        Text("已确认清理 ${deletion.completed}/${deletion.total} 个" +
            if (deletion.busy) " · ${if (deletion.verifying) "复核目录" else "删除中"}：${deletion.current}"
            else if (deletion.stopping) " · 已停止" else "",
            style = RsType.caption, color = Rs.colors.textSecondary)
        if (deletion.busy) ActionButton(if (deletion.stopping) "等待当前文件结果…" else "停止后续删除",
            ActionKind.Secondary, enabled = !deletion.stopping, onClick = stopDeleting)
        deletion.error?.let { Text(it, style = RsType.caption, color = Rs.colors.warning) }
    }
    if (state.files.isEmpty()) {
        Text(if (state.listing) "正在读取卡内录音…" else "暂无文件，请连接后刷新目录。",
            style = RsType.caption, color = Rs.colors.textSecondary)
    } else {
        ActionButton(day ?: "全部日期 · ${state.files.size} 个录音", ActionKind.Secondary) { chooseDay = true }
        Text("符合筛选 ${shown.total} 个 · 已选 ${selected.size} 个（含其他页面）",
            style = RsType.caption, color = Rs.colors.textSecondary)
        if (selection.isNotEmpty()) TextButton(onClick = { selection = emptySet() }, enabled = idle) { Text("取消全部选择") }
        val pageNames = shown.rows.map { it.file.name }.toSet()
        val allSelected = pageNames.isNotEmpty() && selection.containsAll(pageNames)
        ActionButton(if (allSelected) "取消选择本页" else "选择本页", ActionKind.Secondary,
            enabled = idle && pageNames.isNotEmpty()) {
            selection = if (allSelected) selection - pageNames else selection + pageNames
        }
        if (shown.pages > 1) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = { page = shown.index - 1 }, enabled = shown.index > 0) { Text("上一页") }
            Text("${shown.index + 1} / ${shown.pages} 页", style = RsType.caption, color = Rs.colors.textSecondary)
            TextButton(onClick = { page = shown.index + 1 }, enabled = shown.index + 1 < shown.pages) { Text("下一页") }
        }
        shown.rows.groupBy { it.day }.forEach { (date, rows) ->
            Text(date, style = RsType.subhead, color = Rs.colors.text)
            rows.forEach { row ->
                val file = row.file
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
        }
        ActionButton("${if (wifi.phase == RecorderWifiPhase.Ready) "Wi-Fi 快传" else "蓝牙下载"}并导入所选 ${importable.size} 个文件",
            ActionKind.Primary, enabled = stopped && !wifiConnecting && importable.isNotEmpty()) {
            if (importable.any { it.capturedLive }) confirmDownload = importable.toList()
            else { download(importable); selection = emptySet() }
        }
        Text("清理会永久删除卡内原件；已交给导入队列不代表已备份。请先确认手机中已保存完整音频。清理前需关闭 Wi-Fi 快传。",
            style = RsType.caption, color = Rs.colors.textSecondary)
        ActionButton("删除卡内所选 ${selected.size} 个文件", ActionKind.Danger,
            enabled = stopped && wifi.phase == RecorderWifiPhase.Off && !deletion.needsReconnect && selected.isNotEmpty()) {
            confirmDelete = selected.toList()
        }
    }
    if (chooseDay) AlertDialog(onDismissRequest = { chooseDay = false }, title = { Text("按日期查找") },
        text = {
            LazyColumn(Modifier.heightIn(max = 360.dp)) {
                item { TextButton(onClick = { day = null; page = 0; chooseDay = false }) { Text("全部日期") } }
                items(days) { date ->
                    TextButton(onClick = { day = date; page = 0; chooseDay = false }) { Text(date) }
                }
            }
        }, confirmButton = { TextButton(onClick = { chooseDay = false }) { Text("关闭") } })
    confirmDownload?.let { files ->
        AlertDialog(onDismissRequest = { confirmDownload = null }, title = { Text("可能有重复内容") },
            text = { Text("所选文件有部分内容曾实时采集。完整导入可能再次转写这些内容。") },
            confirmButton = { TextButton(enabled = stopped && !wifiConnecting, onClick = {
                confirmDownload = null; download(files); selection = emptySet()
            }) { Text("仍然导入") } },
            dismissButton = { TextButton(onClick = { confirmDownload = null }) { Text("取消") } })
    }
    confirmDelete?.let { files ->
        val deviceKey = state.device?.key
        AlertDialog(onDismissRequest = { confirmDelete = null }, title = { Text("永久删除 ${files.size} 个卡内录音？") },
            text = { Column {
                Text("这些卡内原件不可恢复，共 ${files.sumOf { it.size } / 1024} KB。手机中已有的录音和转写不会删除。")
                LazyColumn(Modifier.heightIn(max = 240.dp)) {
                    items(files, key = { it.name }) { Text(it.name, style = RsType.caption) }
                }
            } },
            confirmButton = { TextButton(enabled = stopped && deviceKey != null && wifi.phase == RecorderWifiPhase.Off && !deletion.needsReconnect,
                onClick = { confirmDelete = null; delete(checkNotNull(deviceKey), files); selection = emptySet() }) { Text("永久删除") } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("取消") } })
    }
}
