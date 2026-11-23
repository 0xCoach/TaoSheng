package app.resound.ui.verify

import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.resound.audio.AutoInput
import app.resound.audio.InputChoice
import app.resound.audio.InputDevice
import app.resound.audio.InputDevices
import app.resound.audio.InputKind
import app.resound.ui.theme.Rs
import app.resound.ui.theme.RsType

/**
 * 录音与后台 → 录音输入。卡 223：只有一个“自动”——列表从上往下，用第一个开着又接着的；
 * 蓝牙按设备逐个开关（默认关），↑ 和上一行换位置；手机麦克风永远在最后。另可选“只用手机麦克风”或只用某台专用录音设备。
 */
@Composable
fun InputSection(inputs: InputDevices) {
    val c = Rs.colors
    val choice by inputs.choice.collectAsStateWithLifecycle()
    val devices by inputs.devices.collectAsStateWithLifecycle()
    val status by inputs.status.collectAsStateWithLifecycle()
    val external = devices.filter { it.kind != InputKind.Phone }
    val cards = external.filter { it.kind == InputKind.CardDevice }
    // 安卓 12 起用蓝牙耳机录音要“附近设备”权限；打开蓝牙那一行时再要，拒绝了后台会退回手机麦克风
    val context = androidx.compose.ui.platform.LocalContext.current
    var pending by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<InputChoice?>(null) }
    val askBluetooth = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { pending?.let(inputs::choose); pending = null }
    fun isBluetooth(k: InputKind) = k == InputKind.BluetoothCall || k == InputKind.BluetoothLe
    fun bluetoothDenied() = android.os.Build.VERSION.SDK_INT >= 31 &&
        androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.BLUETOOTH_CONNECT) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
    fun pick(next: InputChoice, needsBluetooth: Boolean = false) {
        if (needsBluetooth && bluetoothDenied()) { pending = next; askBluetooth.launch(android.Manifest.permission.BLUETOOTH_CONNECT) } else inputs.choose(next)
    }
    val auto = choice as? InputChoice.Auto
    Section("录音输入") {
        RsCard(padding = PaddingValues(vertical = 4.dp)) {
            ChoiceRow("自动", "接上就用，拔掉换回手机", "自", c.accent, auto != null) {
                if (auto == null) pick(InputChoice.Auto().learn(external))
            }
            Divider()
            ChoiceRow("只用手机麦克风", "不管接了什么都用手机", "手", c.textSecondary, choice == InputChoice.Phone) { pick(InputChoice.Phone) }
            cards.forEach { d ->
                Divider()
                ChoiceRow("只用 ${d.name}", if (d.connected) d.kind.title else "${d.kind.title} · 没接着",
                    kindGlyph(d.kind), kindColor(d.kind), (choice as? InputChoice.Device)?.key == d.key, dim = !d.connected) {
                    pick(InputChoice.Device(d.key, d.name))
                }
            }
        }
        if (auto != null) {
            Text("自动时从上往下，用第一个开着又接着的：", style = RsType.caption, color = c.textSecondary,
                modifier = Modifier.padding(start = 4.dp, top = 12.dp, bottom = 6.dp))
            RsCard(padding = PaddingValues(vertical = 4.dp)) {
                auto.order.forEachIndexed { index, entry ->
                    val connected = external.any { it.connected && auto.matches(entry, it) }
                    val using = auto.matches(entry, status.using)
                    if (index > 0) Divider()
                    AutoRow(index + 1, entry, connected, using, canMoveUp = index > 0,
                        onToggle = { on -> pick(auto.toggle(entry.key, on), needsBluetooth = on && isBluetooth(entry.kind)) },
                        onMoveUp = { pick(auto.moveUp(entry.key)) })
                }
                Divider()
                AutoRow(auto.order.size + 1, AutoInput("mic", "手机麦克风", InputKind.Phone, true), true,
                    status.using.kind == InputKind.Phone, canMoveUp = false, onToggle = null, onMoveUp = {})
            }
        }
        Column(Modifier.padding(start = 4.dp, end = 4.dp, top = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(7.dp).clip(CircleShape).background(c.good))
                Text("  正在用：${status.using.name}", style = RsType.subhead, color = c.text)
            }
            status.note?.let { Text(it, style = RsType.caption, color = c.warning) }
            if (auto != null && auto.order.any { it.enabled && isBluetooth(it.kind) } && bluetoothDenied())
                Text("没给“附近设备”权限，蓝牙麦克风用不上；把蓝牙那一行关掉再打开，可以重新授权。", style = RsType.caption, color = c.warning)
            if (auto != null && auto.order.any { isBluetooth(it.kind) })
                Text("蓝牙麦克风走通话通道，音质一般；开着时听歌也会变成通话音质，所以新连上的默认不自动用，要用就打开它。", style = RsType.caption, color = c.textTertiary)
            if (external.isEmpty()) Text("插上 Type-C 麦克风或领夹麦接收器、连上蓝牙耳机后，会出现在这里。", style = RsType.caption, color = c.textTertiary)
            if (status.using.kind == InputKind.BluetoothCall && auto == null)
                Text("蓝牙耳机走的是通话通道：音质一般，开着时听歌也会变成通话音质。", style = RsType.caption, color = c.textTertiary)
            if (status.using.kind != InputKind.Phone)
                Text("换了麦克风，头几天“认出我”可能不准，在原话里多标几句“这是我”就好。", style = RsType.caption, color = c.textTertiary)
        }
        // 卡 193：蓝牙麦录音要占通话通道，放视频音乐会没声；连过蓝牙麦才显示这两项
        if (external.any { isBluetooth(it.kind) }) {
            val yieldSettings = androidx.compose.runtime.remember { app.resound.audio.BluetoothYieldSettings(context) }
            val opts by yieldSettings.changes.collectAsStateWithLifecycle(initialValue = yieldSettings.read())
            Spacer(Modifier.height(12.dp))
            RsCard(padding = PaddingValues(horizontal = 18.dp, vertical = 14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).padding(end = 12.dp)) {
                        Text("用蓝牙麦时，放视频音乐自动让出", style = RsType.body, color = c.text)
                        Text(if (opts.enabled) "放视频、音乐时先把蓝牙麦让出来，声音照常从手机或耳机出；停下约 3 秒后自动接回"
                            else "一直占着蓝牙麦：这期间看视频、听音乐可能没声音", style = RsType.caption, color = c.textSecondary)
                    }
                    RsToggle(opts.enabled) { yieldSettings.setEnabled(it) }
                }
                if (opts.enabled) {
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f).padding(end = 12.dp)) {
                            Text("让出期间用手机麦克风继续录", style = RsType.body, color = c.text)
                            Text(if (opts.continueOnPhone) "不漏记；手机外放的声音照常会被标出来，不当成你说的话"
                                else "让出期间暂停录音，接回蓝牙麦后继续", style = RsType.caption, color = c.textSecondary)
                        }
                        RsToggle(opts.continueOnPhone) { yieldSettings.setContinueOnPhone(it) }
                    }
                }
            }
        }
    }
}

/** 自动列表一行：序号、名字、状态（正在用 / 没接着）、开关、↑。手机麦克风那行没有开关 */
@Composable
private fun AutoRow(number: Int, entry: AutoInput, connected: Boolean, using: Boolean, canMoveUp: Boolean,
                    onToggle: ((Boolean) -> Unit)?, onMoveUp: () -> Unit) {
    val c = Rs.colors
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(36.dp).clip(CircleShape).background(kindColor(entry.kind).copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
            Text("$number", style = RsType.subhead.copy(fontWeight = FontWeight.SemiBold), color = kindColor(entry.kind))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            val isType = entry.key.startsWith(InputChoice.Auto.TYPE)
            Text(entry.name, style = RsType.body.copy(fontWeight = FontWeight.SemiBold), color = if (entry.enabled) c.text else c.textTertiary)
            Text(when {
                using -> "✓ 正在用"
                onToggle == null -> "上面都没接时用"
                isType -> if (connected) "接着" else "接上就用"
                connected -> "${entry.kind.title} · 接着"
                else -> "${entry.kind.title} · 没接着"
            }, style = RsType.caption, color = if (using) c.good else c.textTertiary)
        }
        if (canMoveUp) Text("↑", style = RsType.headline, color = c.accent,
            modifier = Modifier.clip(CircleShape).springClick(onMoveUp).padding(horizontal = 12.dp, vertical = 4.dp))
        if (onToggle != null) RsToggle(entry.enabled, onToggle)
    }
}

@Composable
private fun Divider() = Box(Modifier.padding(start = 64.dp).fillMaxWidth().height(0.5.dp).background(Rs.colors.separator))

@Composable
private fun ChoiceRow(title: String, sub: String, glyph: String, tint: Color, selected: Boolean, dim: Boolean = false, onClick: () -> Unit) {
    val c = Rs.colors
    val ring by animateColorAsState(if (selected) c.selected else c.separator, label = "ring")
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
    InputKind.BluetoothLe, InputKind.BluetoothCall -> "耳"; InputKind.CardDevice -> "设"; InputKind.Other -> "其"
}

private fun kindColor(k: InputKind): Color = when (k) {
    InputKind.Usb -> Color(0xFF3F8F6F)
    InputKind.BluetoothLe, InputKind.BluetoothCall -> Color(0xFF4F86C6)
    InputKind.Wired -> Color(0xFF6E5FA6)
    else -> Color(0xFFB0A89E)
}

