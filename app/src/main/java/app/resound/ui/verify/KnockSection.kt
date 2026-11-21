package app.resound.ui.verify

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.resound.capture.KnockGestureController
import app.resound.ui.items.Choice
import app.resound.ui.theme.Rs
import app.resound.ui.theme.RsType

/**
 * 敲敲控制：开「敲敲控制」且（服务活着 || App 在前台）时注册加速度计监听；
 * 次数 2/3 下；灵敏度 迟钝/标准/灵敏。
 * 如实提示：进程活着才监听；息屏+Doze 可能延迟；录音/暂停中（服务前台）敲敲暂停-恢复可靠。
 */
@Composable
internal fun KnockSection() {
    val c = Rs.colors
    val context = LocalContext.current
    val store = remember { (context.applicationContext as app.resound.ResoundApp).store }
    var enabled by remember { mutableStateOf(store.prefs.getBoolean(KnockGestureController.KEY_ENABLED, false)) }
    var taps by remember { mutableIntStateOf(store.prefs.getInt(KnockGestureController.KEY_TAPS, 2)) }
    var sensitivity by remember { mutableIntStateOf(store.prefs.getInt(KnockGestureController.KEY_SENSITIVITY, 1)) }

    Section("敲敲控制", trailing = {
        Text(if (enabled) "已开启" else "未开启", style = RsType.caption,
            color = if (enabled) c.accent else c.textTertiary)
    }) {
        RsCard(Modifier.animateContentSize(), padding = PaddingValues(horizontal = 18.dp, vertical = 6.dp)) {
            Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("敲手机 ${taps} 下", style = RsType.body.copy(fontWeight = FontWeight.Medium), color = c.text)
                    Spacer(Modifier.height(2.dp))
                    Text("录音中暂停 / 暂停中恢复 / 未录启动", style = RsType.caption, color = c.textSecondary)
                }
                Spacer(Modifier.width(12.dp))
                RsToggle(enabled) {
                    enabled = it
                    store.prefs.edit().putBoolean(KnockGestureController.KEY_ENABLED, it).apply()
                }
            }
            if (enabled) {
                Box(Modifier.fillMaxWidth().height(0.5.dp).background(c.separator))
                Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("敲几下", style = RsType.caption, color = c.textSecondary, modifier = Modifier.weight(1f))
                    listOf(2 to "敲两下", 3 to "敲三下").forEach { (v, label) ->
                        Choice(label, taps == v) { taps = v; store.prefs.edit().putInt(KnockGestureController.KEY_TAPS, v).apply() }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("灵敏度", style = RsType.caption, color = c.textSecondary, modifier = Modifier.weight(1f))
                    listOf(0 to "迟钝", 1 to "标准", 2 to "灵敏").forEach { (v, label) ->
                        Choice(label, sensitivity == v) { sensitivity = v; store.prefs.edit().putInt(KnockGestureController.KEY_SENSITIVITY, v).apply() }
                    }
                }
                Text("息屏或手机深度休眠时敲击可能延迟识别；锁屏放在口袋建议选「迟钝」防误触。",
                    style = RsType.caption, color = c.textTertiary, modifier = Modifier.padding(bottom = 12.dp))
            }
        }
    }
}
