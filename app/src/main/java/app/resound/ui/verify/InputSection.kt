/*
 * [INPUT]: 系统输入列表、录音卡连接及传输状态、持久化来源选择。
 * [OUTPUT]: 统一输入源切换；仅连接中的录音卡可见，忙碌时禁止选入。
 * [POS]: 来源选择的唯一录音卡入口；隐藏失联选项但保留等待卡的选择语义。
 * [PROTOCOL]: 变更时更新此头部，然后检查 CLAUDE.md
 */
package app.resound.ui.verify

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.resound.AppGraph
import app.resound.sonicloud.*
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import app.resound.audio.InputChoice
import app.resound.audio.InputDevice
import app.resound.audio.InputDevices
import app.resound.audio.InputKind
import app.resound.ui.theme.Rs
import app.resound.ui.theme.RsType

/** 录音与后台 → 录音输入：手机麦克风（默认）/ 自动用外接 / 指定某个系统认得的设备 */
@Composable
fun InputSection(inputs: InputDevices) {
    val c = Rs.colors
    val choice by inputs.choice.collectAsState()
    val devices by inputs.devices.collectAsState()
    val status by inputs.status.collectAsState()
    val recorder = if (AppGraph.isRecorderReady) AppGraph.recorder else null
    val recorderState = recorder?.status?.collectAsState()?.value
    val external = devices.filter { it.kind != InputKind.Phone && it.kind != InputKind.Recorder }
    // 安卓 12 起用蓝牙耳机录音要“附近设备”权限；选的时候再要，拒绝了后台会退回手机麦克风
    val context = androidx.compose.ui.platform.LocalContext.current
    var pending by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<InputChoice?>(null) }
    val askBluetooth = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { pending?.let(inputs::choose); pending = null }
    fun isBluetooth(k: InputKind) = k == InputKind.BluetoothCall || k == InputKind.BluetoothLe
    fun pick(next: InputChoice) {
        val needs = android.os.Build.VERSION.SDK_INT >= 31 && when (next) {
            is InputChoice.Device -> devices.firstOrNull { it.key == next.key }?.let { isBluetooth(it.kind) } == true
            InputChoice.AutoExternal -> external.any { isBluetooth(it.kind) }
            InputChoice.Phone, is InputChoice.Recorder -> false
        } && androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.BLUETOOTH_CONNECT) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        if (needs) { pending = next; askBluetooth.launch(android.Manifest.permission.BLUETOOTH_CONNECT) } else inputs.choose(next)
    }
    RecorderSection()
    Section("录音输入") {
        RsCard(padding = PaddingValues(vertical = 4.dp)) {
            ChoiceRow("手机麦克风", "默认", "手", c.textSecondary, choice == InputChoice.Phone) { pick(InputChoice.Phone) }
            Divider()
            ChoiceRow("自动", "接上外接麦克风就用它，拔掉换回手机", "自", c.accent, choice == InputChoice.AutoExternal) { pick(InputChoice.AutoExternal) }
            if (recorderState != null) RecorderInputRow(recorderState, choice, ::pick)
            external.forEach { d ->
                Divider()
                ChoiceRow(d.name, if (d.connected) d.kind.title else "${d.kind.title} · 没接着",
                    kindGlyph(d.kind), kindColor(d.kind), (choice as? InputChoice.Device)?.key == d.key, dim = !d.connected) {
                    pick(InputChoice.Device(d.key, d.name))
                }
            }
        }
        Column(Modifier.padding(start = 4.dp, end = 4.dp, top = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val selectedRecorder = choice as? InputChoice.Recorder
                val waiting = selectedRecorder != null && (recorderState?.connected != true || recorderState.device?.key != selectedRecorder.key)
                Box(Modifier.size(7.dp).clip(CircleShape).background(if (waiting) c.warning else c.good))
                Text(if (selectedRecorder != null) "  已选择：${selectedRecorder.name}" + if (waiting) "（未连接）" else ""
                    else "  正在用：${status.using.name}", style = RsType.subhead, color = c.text)
            }
            status.note?.let { Text(it, style = RsType.caption, color = c.warning) }
            if (external.isEmpty() && recorderState?.connected != true) Text("插上 Type-C 麦克风或领夹麦接收器，或连接蓝牙耳机、录音卡后，会出现在这里。", style = RsType.caption, color = c.textTertiary)
            if (status.using.kind == InputKind.BluetoothCall)
                Text("蓝牙耳机走的是通话通道：音质一般，开着时听歌也会变成通话音质。", style = RsType.caption, color = c.textTertiary)
            if (status.using.kind != InputKind.Phone)
                Text("换了麦克风，头几天“认出我”可能不准，在原话里多标几句“这是我”就好。", style = RsType.caption, color = c.textTertiary)
        }
    }
}

@Composable
internal fun RecorderInputRow(
    state: RecorderStatus,
    choice: InputChoice,
    onChoose: (InputChoice) -> Unit,
) {
    val device = state.device?.takeIf { state.connected } ?: return
    val busy = when {
        state.downloading != null -> "正在下载，完成后可切换"
        else -> null
    }
    Divider()
    ChoiceRow(device.name, busy ?: "录音卡 · 已连接", "卡", kindColor(InputKind.Recorder),
        (choice as? InputChoice.Recorder)?.key == device.key, dim = busy != null, enabled = busy == null) {
        onChoose(InputChoice.Recorder(device.key, device.name))
    }
}

@Composable
private fun Divider() = Box(Modifier.padding(start = 64.dp).fillMaxWidth().height(0.5.dp).background(Rs.colors.separator))

@Composable
private fun ChoiceRow(title: String, sub: String, glyph: String, tint: Color, selected: Boolean, dim: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    val c = Rs.colors
    val ring by animateColorAsState(if (selected) c.accent else c.separator, label = "ring")
    Row(Modifier.fillMaxWidth().semantics { if (!enabled) disabled() }.springClick { if (enabled) onClick() }.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(36.dp).clip(CircleShape).background(tint.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
            Text(glyph, style = RsType.subhead.copy(fontWeight = FontWeight.SemiBold), color = tint)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = RsType.body.copy(fontWeight = FontWeight.SemiBold), color = if (dim) c.textTertiary else c.text)
            Text(sub, style = RsType.caption, color = c.textTertiary)
        }
        Box(Modifier.size(22.dp).clip(CircleShape).border(2.dp, ring, CircleShape), contentAlignment = Alignment.Center) {
            if (selected) Box(Modifier.size(12.dp).clip(CircleShape).background(c.accent))
        }
    }
}

private fun kindGlyph(k: InputKind) = when (k) {
    InputKind.Phone -> "手"; InputKind.Usb -> "麦"; InputKind.Wired -> "线"
    InputKind.BluetoothLe, InputKind.BluetoothCall -> "耳"; InputKind.Other -> "其"; InputKind.Recorder -> "卡"
}

private fun kindColor(k: InputKind): Color = when (k) {
    InputKind.Usb -> Color(0xFF3F8F6F)
    InputKind.BluetoothLe, InputKind.BluetoothCall -> Color(0xFF4F86C6)
    InputKind.Wired -> Color(0xFF6E5FA6)
    else -> Color(0xFFB0A89E)
}

