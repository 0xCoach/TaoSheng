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

@Composable internal fun D3200OptionsEntry(address: String) {
    val context = LocalContext.current
    TextButton(onClick = { context.startActivity(Intent(context, D3200SettingsActivity::class.java).putExtra("address", address)) }) {
        Text("录音豆灯光与补传选项")
    }
}

class D3200SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val address = intent.getStringExtra("address")?.takeIf(android.bluetooth.BluetoothAdapter::checkBluetoothAddress)
            ?: run { finish(); return }
        val app = application as ResoundApp; val settings = app.d3200
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
            val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
                if (grants.values.all { it }) { settings.requestSync(address); app.controller.start() }
                else error = "请允许麦克风、通知和附近设备权限后重试"
            }
            Surface(Modifier.fillMaxSize()) {
                Column(Modifier.windowInsetsPadding(WindowInsets.safeDrawing).verticalScroll(rememberScrollState()).padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("录音豆灯光与补传", style = MaterialTheme.typography.headlineSmall)
                    Text("搜索、配对和音源选择在涛声的“专用录音设备”和“录音输入”中完成。")
                    Text("${if (state.firmware.isBlank()) "连接后读取固件" else "主固件 ${state.firmware}"}\n${state.light}")
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
