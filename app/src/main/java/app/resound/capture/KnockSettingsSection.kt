package app.resound.capture

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext

/**
 * 敲敲控制设置区（待 Claude 审核接管——挂到录音/手势设置页一行即可：<KnockSettingsSection/>）。
 * 开关默认关；次数 2/3 下；灵敏度 迟钝/标准/灵敏。
 * 如实提示：进程活着才监听；息屏+Doze 可能延迟；录音/暂停中（服务前台）敲敲暂停-恢复可靠。
 */
@Composable
fun KnockSettingsSection() {
    val context = LocalContext.current
    // 通过 ResoundApp 的 store 读写——与 KnockGestureController 同一组键。
    val store = remember { (context.applicationContext as app.resound.ResoundApp).store }
    var enabled by remember { mutableStateOf(store.prefs.getBoolean(KnockGestureController.KEY_ENABLED, false)) }
    var taps by remember { mutableStateOf(store.prefs.getInt(KnockGestureController.KEY_TAPS, 2)) }
    var sensitivity by remember { mutableStateOf(store.prefs.getInt(KnockGestureController.KEY_SENSITIVITY, 1)) }

    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f)) {
                Text("敲敲控制", style = MaterialTheme.typography.titleMedium)
                Text("敲手机 ${taps} 下：录音中暂停 / 暂停中恢复 / 未录启动",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(enabled, {
                enabled = it
                store.prefs.edit().putBoolean(KnockGestureController.KEY_ENABLED, it).apply()
            })
        }
        if (enabled) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(2 to "敲两下", 3 to "敲三下").forEach { (v, label) ->
                    FilterChip(taps == v, { taps = v; store.prefs.edit().putInt(KnockGestureController.KEY_TAPS, v).apply() }, label = { Text(label) })
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(0 to "迟钝", 1 to "标准", 2 to "灵敏").forEach { (v, label) ->
                    FilterChip(sensitivity == v, { sensitivity = v; store.prefs.edit().putInt(KnockGestureController.KEY_SENSITIVITY, v).apply() }, label = { Text(label) })
                }
            }
            Text("息屏或手机深度休眠时敲击可能延迟识别；锁屏放在口袋建议选「迟钝」防误触。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
