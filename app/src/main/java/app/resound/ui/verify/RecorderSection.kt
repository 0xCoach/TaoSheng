/*
 * [INPUT]: 录音卡控制、连接电量及文件状态。
 * [OUTPUT]: 紧凑设备卡片、连接与文件管理入口；输入源选择统一由 InputSection 提供。
 * [POS]: 录音输入页的设备入口；复用既有卡片、按钮和导入确认流程。
 * [PROTOCOL]: 变更时更新此头部，然后检查 CLAUDE.md
 */
package app.resound.ui.verify

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import app.resound.AppGraph
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import app.resound.sonicloud.*
import app.resound.ui.theme.Rs
import app.resound.ui.theme.RsType

@Composable
internal fun RecorderDeviceCard(state: RecorderStatus, onManage: () -> Unit) {
    val connection = if (state.connected) "已连接" + (state.battery?.let { " · 电量 $it%" } ?: "") else "未连接"
    RsCard(padding = PaddingValues(horizontal = 18.dp, vertical = 12.dp)) {
        Text(buildAnnotatedString {
            append(state.device?.name ?: "Sonicloud 录音卡")
            withStyle(RsType.caption.toSpanStyle().copy(color = Rs.colors.textSecondary)) {
                append("（$connection）")
            }
        }, style = RsType.headline, color = Rs.colors.text)
        Spacer(Modifier.height(8.dp))
        Pill("管理录音卡", true, onClick = onManage)
    }
}

@Composable
internal fun RecorderSection() {
    if (!AppGraph.isRecorderReady) return
    val recorder = AppGraph.recorder
    val state by recorder.status.collectAsState()
    val context = LocalContext.current
    var open by rememberSaveable { mutableStateOf(false) }
    var explanation by remember { mutableStateOf(false) }
    var denied by remember { mutableStateOf(false) }
    var pendingAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted.values.all { it } && RecorderPermissions.canScan(context)) pendingAction?.invoke()
        else denied = true
        pendingAction = null
    }
    fun request(action: () -> Unit) {
        if (RecorderPermissions.canScan(context)) { action(); return }
        pendingAction = action
        val activity = context as? Activity
        if (activity != null && RecorderPermissions.scanning().any { ActivityCompat.shouldShowRequestPermissionRationale(activity, it) })
            explanation = true
        else launcher.launch(RecorderPermissions.scanning())
    }
    Section("录音卡") { RecorderDeviceCard(state) { open = true } }
    if (explanation) AlertDialog(onDismissRequest = { explanation = false; pendingAction = null },
        title = { Text("连接录音卡") }, text = { Text("需要附近设备权限来发现和连接录音卡；旧版 Android 的蓝牙扫描还需要定位权限。") },
        confirmButton = { TextButton(onClick = { explanation = false; launcher.launch(RecorderPermissions.scanning()) }) { Text("继续") } },
        dismissButton = { TextButton(onClick = { explanation = false; pendingAction = null }) { Text("取消") } })
    if (denied) AlertDialog(onDismissRequest = { denied = false }, title = { Text("未获得蓝牙权限") },
        text = { Text("可以稍后重试；若已禁止再次询问，请在系统设置中允许附近设备权限。") },
        confirmButton = { TextButton(onClick = { denied = false; context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))) }) { Text("系统设置") } },
        dismissButton = { TextButton(onClick = { denied = false }) { Text("关闭") } })
    if (open) RsSheet("录音卡", "普通款 A · 蓝牙控制与文件导入", onDismiss = {
        open = false; recorder.stopScan()
    }) {
        ActionButton(if (state.scanning) "停止搜索" else "搜索附近录音卡", ActionKind.Secondary) {
            if (state.scanning) recorder.stopScan() else request { recorder.scan() }
        }
        state.devices.forEach { device ->
            ActionButton("${device.name} · ${device.address.takeLast(5)}", ActionKind.Secondary) {
                request { recorder.connect(device) }
            }
        }
        state.device?.let { device ->
            Text(device.name, style = RsType.headline, color = Rs.colors.text)
            Text(if (state.connected) "已连接 · ${when (state.recording) {
                RecorderRecording.Recording -> "卡正在录音"
                RecorderRecording.Paused -> "卡已暂停"
                RecorderRecording.Stopped -> "卡已停止"
                RecorderRecording.Unknown -> "读取状态中"
            }}" else "未连接", style = RsType.caption, color = Rs.colors.textSecondary)
            if (!state.connected) ActionButton("连接这张卡", ActionKind.Secondary) { request { recorder.connect(device) } }
            if (state.connected) {
                if (state.recording == RecorderRecording.Recording || state.recording == RecorderRecording.Paused)
                    ActionButton("停止并保存卡录音", ActionKind.Secondary) { recorder.stopRecording() }
                ActionButton("断开连接", ActionKind.Secondary) { recorder.disconnect() }
                ActionButton(if (state.listing) "正在读取文件…" else "刷新卡内录音", ActionKind.Secondary,
                    enabled = !state.listing && state.downloading == null) { recorder.refreshFiles() }
            }
        }
        state.error?.let { Text(it, style = RsType.caption, color = Rs.colors.warning) }
        state.downloading?.let {
            Text("蓝牙下载：$it · ${state.percent}% · 剩余 ${state.queued} 个", style = RsType.subhead, color = Rs.colors.text)
            LinearProgressIndicator(progress = { state.percent / 100f }, modifier = Modifier.fillMaxWidth())
            ActionButton("取消下载（保留断点）", ActionKind.Secondary) { recorder.cancelDownload() }
        }
        RecorderFilesSection(state, recorder::download)
    }
}
