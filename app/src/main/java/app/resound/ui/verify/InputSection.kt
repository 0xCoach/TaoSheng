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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
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
    val external = devices.filter { it.kind != InputKind.Phone }
    Section("录音输入") {
        RsCard(padding = PaddingValues(vertical = 4.dp)) {
            ChoiceRow("手机麦克风", "默认", "手", c.textSecondary, choice == InputChoice.Phone) { inputs.choose(InputChoice.Phone) }
            Divider()
            ChoiceRow("自动", "接上外接麦克风就用它，拔掉换回手机", "自", c.accent, choice == InputChoice.AutoExternal) { inputs.choose(InputChoice.AutoExternal) }
            external.forEach { d ->
                Divider()
                ChoiceRow(d.name, if (d.connected) d.kind.title else "${d.kind.title} · 没接着",
                    kindGlyph(d.kind), kindColor(d.kind), (choice as? InputChoice.Device)?.key == d.key, dim = !d.connected) {
                    inputs.choose(InputChoice.Device(d.key, d.name))
                }
            }
        }
        Column(Modifier.padding(start = 4.dp, end = 4.dp, top = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(7.dp).clip(CircleShape).background(c.good))
                Text("  正在用：${status.using.name}", style = RsType.subhead, color = c.text)
            }
            status.note?.let { Text(it, style = RsType.caption, color = c.warning) }
            if (external.isEmpty()) Text("插上 Type-C 麦克风或领夹麦接收器、连上蓝牙耳机后，会出现在这里。", style = RsType.caption, color = c.textTertiary)
            if (status.using.kind == InputKind.BluetoothCall)
                Text("蓝牙耳机走的是通话通道：音质一般，开着时听歌也会变成通话音质。", style = RsType.caption, color = c.textTertiary)
            if (status.using.kind != InputKind.Phone)
                Text("换了麦克风，头几天“认出我”可能不准，在原话里多标几句“这是我”就好。", style = RsType.caption, color = c.textTertiary)
        }
    }
}

@Composable
private fun Divider() = Box(Modifier.padding(start = 64.dp).fillMaxWidth().height(0.5.dp).background(Rs.colors.separator))

@Composable
private fun ChoiceRow(title: String, sub: String, glyph: String, tint: Color, selected: Boolean, dim: Boolean = false, onClick: () -> Unit) {
    val c = Rs.colors
    val ring by animateColorAsState(if (selected) c.accent else c.separator, label = "ring")
    Row(Modifier.fillMaxWidth().springClick(onClick).padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
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
    InputKind.BluetoothLe, InputKind.BluetoothCall -> "耳"; InputKind.Other -> "其"
}

private fun kindColor(k: InputKind): Color = when (k) {
    InputKind.Usb -> Color(0xFF3F8F6F)
    InputKind.BluetoothLe, InputKind.BluetoothCall -> Color(0xFF4F86C6)
    InputKind.Wired -> Color(0xFF6E5FA6)
    else -> Color(0xFFB0A89E)
}

