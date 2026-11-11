package app.resound.devices.plugins.d3200

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.resound.ResoundApp
import app.resound.audio.InputChoice
import app.resound.devices.ManagedDevices
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager
import java.text.DateFormat
import java.util.Date

@Composable internal fun D3200OptionsEntry(address: String) {
    val context = LocalContext.current
    val settings = (context.applicationContext as ResoundApp).d3200
    LaunchedEffect(address) { settings.watch(address) }
    val states by settings.details.collectAsState()
    val state = states[address] ?: D3200Details()
    Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        Text("${state.connectionLabel} · ${if (state.connected) "设备" else "上次设备"}${state.info.recordLabel}")
        Text("${if (state.connected) "" else "上次："}录音豆 ${state.info.battery?.let { "$it%" } ?: "未知"} · 充电盒 ${state.info.boxBattery?.let { "$it%" } ?: "未知"}")
        TextButton(onClick = { context.startActivity(Intent(context, D3200SettingsActivity::class.java).putExtra("address", address)) }) {
            Text("录音豆状态、控制与灯光")
        }
    }
}

@Composable private fun DeviceReadings(state: D3200Details) {
    val info = state.info
    Text(state.connectionLabel, style = MaterialTheme.typography.titleMedium)
    if (state.updatedAt > 0) Text("${if (state.connected) "读数更新" else "上次读数（当前未连接）"}：${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM).format(Date(state.updatedAt))}")
    fun power(percent: Int?, charging: Boolean?) = "${percent?.let { "约 $it%" } ?: "电量未知"} · ${when (charging) { true -> "充电中"; false -> "未充电"; null -> "充电状态未知" }}"
    Text("录音豆：${power(info.battery, info.charging)}",
        color = if (info.battery?.let { it <= 20 } == true) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
    Text("充电盒：${power(info.boxBattery, info.boxCharging)}")
    Text("${if (state.connected) "设备录音" else "上次设备录音"}：${info.recordLabel}")
    Text("存储：${info.storage}")
    if (info.totalKb != null && info.freeKb != null && info.freeKb * 100 <= info.totalKb * 5)
        Text("设备剩余空间不足 5%，请先确认录音已备份。", color = MaterialTheme.colorScheme.error)
    Text("主固件：${info.firmware.ifBlank { "未知" }} · 充电盒固件：${info.boxFirmware.ifBlank { "未知" }}")
    if (state.error != null) Text(state.error, color = MaterialTheme.colorScheme.error)
    if (state.message.isNotBlank()) Text(state.message)
}

class D3200SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val address = intent.getStringExtra("address")?.takeIf(android.bluetooth.BluetoothAdapter::checkBluetoothAddress)
            ?: run { finish(); return }
        val app = application as ResoundApp; val settings = app.d3200
        settings.watch(address)
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = true; isAppearanceLightNavigationBars = true
        }
        setContent { MaterialTheme {
            val states by settings.details.collectAsState()
            val state = states[address] ?: D3200Details()
            var mode by remember { mutableStateOf(settings.mode(address)) }
            var autoStart by remember { mutableStateOf(settings.autoStart) }
            var autoSync by remember { mutableStateOf(settings.autoSync) }
            var error by remember { mutableStateOf("") }
            var pendingControl by remember { mutableStateOf<String?>(null) }
            fun execute(action: String) {
                error = ""
                when (action) { "refresh" -> settings.refresh(address); "start" -> settings.record(address, true); "pause" -> settings.record(address, false) }
            }
            val devicePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
                val action = pendingControl; pendingControl = null
                if (granted && action != null) execute(action) else error = "请允许附近设备权限后重试"
            }
            fun deviceAction(action: String) {
                if (Build.VERSION.SDK_INT < 31 || ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) execute(action)
                else { pendingControl = action; devicePermission.launch(Manifest.permission.BLUETOOTH_CONNECT) }
            }
            val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
                if (grants.values.all { it }) {
                    val row = app.cardDevices.devices.value.firstOrNull { it.address == address }
                    if (row != null) {
                        settings.requestSync(address)
                        app.inputs.choose(InputChoice.Device(ManagedDevices.deviceKey(address), row.name))
                        app.controller.start()
                    } else error = "设备已取消配对，请重新搜索"
                }
                else error = "请允许麦克风、通知和附近设备权限后重试"
            }
            Surface(Modifier.fillMaxSize()) {
                Column(Modifier.windowInsetsPadding(WindowInsets.safeDrawing).verticalScroll(rememberScrollState()).padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("录音豆状态与控制", style = MaterialTheme.typography.headlineSmall)
                    Text("搜索、配对和音源选择在涛声的“专用录音设备”和“录音输入”中完成。")
                    DeviceReadings(state)
                    val available = !state.busy && !state.connecting && !state.backfilling && pendingControl == null
                    OutlinedButton(onClick = { deviceAction("refresh") }, enabled = available) { Text(if (state.busy) "正在操作…" else "刷新设备信息") }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(onClick = { deviceAction("start") }, enabled = available && !(state.connected && state.info.recording == 1)) { Text("开始设备录音") }
                        OutlinedButton(onClick = { deviceAction("pause") }, enabled = available && !(state.connected && state.info.recording != 1)) { Text("暂停设备录音") }
                    }
                    Text("未收音时也可刷新或控制，操作完成会释放临时连接。手动暂停后不会自动重启设备录音，点击开始可解除。暂停不等于文件已保存；补传仍需按设备键停止并保存。")
                    HorizontalDivider()
                    Text("灯光", style = MaterialTheme.typography.titleLarge)
                    Text(state.light)
                    listOf("none" to "停止控灯", "off" to "常灭", "on" to "暖橙常亮").forEach { (key, label) ->
                        OutlinedButton(onClick = { runCatching { settings.setMode(address, key); mode = key }.onFailure { error = it.message ?: "设置失败" } },
                            modifier = Modifier.fillMaxWidth()) { Text("${if (mode == key) "✓ " else ""}$label") }
                    }
                    Text("灯控已在 04.92 固件验证；常灭依赖连接，离线新录音、上电及重连前可能亮灯。停止控灯保留共用音频通道。")
                    HorizontalDivider()
                    Text("离线录音补传", style = MaterialTheme.typography.titleLarge)
                    Text(state.sync)
                    Row {
                        Switch(autoSync, { autoSync = it; settings.autoSync = it })
                        Text("连接后补传尚未导入的已保存录音", Modifier.padding(start = 12.dp))
                    }
                    Row {
                        Switch(autoStart, { autoStart = it; settings.autoStart = it })
                        Text("补传完成后，若设备停止则自动开始新录音", Modifier.padding(start = 12.dp))
                    }
                    Button(onClick = {
                        val needed = listOf(Manifest.permission.RECORD_AUDIO) +
                            if (Build.VERSION.SDK_INT >= 31) listOf(Manifest.permission.BLUETOOTH_CONNECT) else emptyList()
                        permissions.launch((needed + if (Build.VERSION.SDK_INT >= 33) listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList()).toTypedArray())
                    }, modifier = Modifier.fillMaxWidth()) { Text("立即检查离线录音") }
                    Text("回来后请先按设备键停止并保存，补传期间保持停止录音。完成后录音出现在涛声时间线，再按当前配置转写和整理；设备原文件保留。切回手机音源或停止涛声收音可以暂停补传。")
                    Text("较长录音的蓝牙补传可能需要较久，期间手机仍可收音。关闭自动补传会暂缓当前任务、保留进度，再按原设置恢复设备录音；重新打开后，下一次保存录音并连接时继续补传。")
                    if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = { finish() }) { Text("返回涛声") }
                }
            }
        } }
    }
}
