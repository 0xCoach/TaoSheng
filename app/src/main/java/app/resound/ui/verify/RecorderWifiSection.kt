/*
 * [INPUT]: 录音卡连接状态、Wi-Fi 快传状态及 Android 按需权限。
 * [OUTPUT]: 快传入口、连接进度、取消与权限/系统开关引导。
 * [POS]: 录音卡管理页的网络操作区；不直接调用厂商 SDK。
 * [PROTOCOL]: 变更时更新此头部，然后检查 CLAUDE.md
 */
package app.resound.ui.verify

import android.app.Activity
import android.content.Intent
import android.location.LocationManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityCompat
import app.resound.sonicloud.*
import app.resound.ui.theme.Rs
import app.resound.ui.theme.RsType

@Composable
internal fun RecorderWifiSection(recorder: SonicloudRecorder, state: RecorderStatus) {
    val wifi by recorder.wifiStatus.collectAsState()
    val context = LocalContext.current
    var rationale by rememberSaveable { mutableStateOf(false) }
    var denied by rememberSaveable { mutableStateOf(false) }
    var canAskAgain by rememberSaveable { mutableStateOf(false) }
    var settingsAction by rememberSaveable { mutableStateOf<String?>(null) }
    val permissionText = if (Build.VERSION.SDK_INT >= 33)
        "连接录音卡热点需要附近设备权限，仅用于传输录音文件。"
    else "此版本 Android 连接录音卡热点需要精确位置权限和系统位置开关；应用不会读取或保存位置。"

    fun connect() {
        when {
            context.getSystemService(WifiManager::class.java)?.isWifiEnabled != true ->
                settingsAction = Settings.ACTION_WIFI_SETTINGS
            Build.VERSION.SDK_INT <= 32 && context.getSystemService(LocationManager::class.java)?.isLocationEnabled != true ->
                settingsAction = Settings.ACTION_LOCATION_SOURCE_SETTINGS
            else -> recorder.connectWifi()
        }
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (RecorderPermissions.canWifi(context)) connect()
        else {
            val activity = context as? Activity
            canAskAgain = activity != null && RecorderPermissions.wifi().any {
                ActivityCompat.shouldShowRequestPermissionRationale(activity, it)
            }
            denied = true
        }
    }
    fun request() {
        if (RecorderPermissions.canWifi(context)) { connect(); return }
        val activity = context as? Activity
        if (activity != null && RecorderPermissions.wifi().any {
            ActivityCompat.shouldShowRequestPermissionRationale(activity, it)
        }) rationale = true
        else launcher.launch(RecorderPermissions.wifi())
    }

    RecorderWifiActions(state, wifi, ::request, recorder::disconnectWifi)

    if (rationale) AlertDialog(
        onDismissRequest = { rationale = false },
        title = { Text("Wi-Fi 快传权限") }, text = { Text(permissionText) },
        confirmButton = { TextButton(onClick = {
            rationale = false; launcher.launch(RecorderPermissions.wifi())
        }) { Text("继续") } },
        dismissButton = { TextButton(onClick = { rationale = false }) { Text("取消") } },
    )
    if (denied) AlertDialog(
        onDismissRequest = { denied = false },
        title = { Text("暂不能使用 Wi-Fi 快传") },
        text = { Text(permissionText + " 蓝牙下载仍可使用。" +
            if (canAskAgain) "可稍后点击快传入口重试。" else "如需开启，可在应用设置中允许权限。") },
        confirmButton = {
            if (!canAskAgain) TextButton(onClick = {
                denied = false
                context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
            }) { Text("应用设置") }
            else TextButton(onClick = { denied = false }) { Text("知道了") }
        },
        dismissButton = { if (!canAskAgain) TextButton(onClick = { denied = false }) { Text("取消") } },
    )
    settingsAction?.let { action ->
        AlertDialog(
            onDismissRequest = { settingsAction = null },
            title = { Text(if (action == Settings.ACTION_WIFI_SETTINGS) "请开启手机 Wi-Fi" else "请开启系统位置开关") },
            text = { Text("开启后返回此页，再点击“开启 Wi-Fi 快传”。") },
            confirmButton = { TextButton(onClick = {
                settingsAction = null; context.startActivity(Intent(action))
            }) { Text("前往设置") } },
            dismissButton = { TextButton(onClick = { settingsAction = null }) { Text("取消") } },
        )
    }
}

@Composable
internal fun RecorderWifiActions(
    state: RecorderStatus,
    wifi: RecorderWifiStatus,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
) {
    Text(when (wifi.phase) {
        RecorderWifiPhase.Off -> "传输方式：蓝牙"
        RecorderWifiPhase.Opening -> "Wi-Fi 快传：正在开启录音卡热点…"
        RecorderWifiPhase.Joining -> "Wi-Fi 快传：请在系统弹窗中允许连接 ${wifi.ssid.orEmpty()}"
        RecorderWifiPhase.Connecting -> "Wi-Fi 快传：正在建立音频传输连接…"
        RecorderWifiPhase.Ready -> "传输方式：Wi-Fi 快传 · ${wifi.ssid.orEmpty()}"
    }, style = RsType.subhead, color = Rs.colors.text)
    if (wifi.phase == RecorderWifiPhase.Off) {
        ActionButton("开启 Wi-Fi 快传", ActionKind.Secondary,
            enabled = state.connected && state.recording == RecorderRecording.Stopped && state.downloading == null,
            onClick = onConnect)
        Text("先停止聆听和卡内录音，再开启快传；连接成功后选择文件下载。",
            style = RsType.caption, color = Rs.colors.textSecondary)
    } else {
        ActionButton(
            if (state.downloading != null) "停止快传（保留断点）"
            else if (wifi.phase == RecorderWifiPhase.Ready) "关闭 Wi-Fi 快传" else "取消 Wi-Fi 连接",
            ActionKind.Secondary, onClick = onDisconnect)
        Text("下载完成后自动关闭快传。连接期间网络可能短暂中断。",
            style = RsType.caption, color = Rs.colors.textSecondary)
    }
    wifi.error?.let { Text(it, style = RsType.caption, color = Rs.colors.warning) }
}
