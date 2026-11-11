package app.resound.ui.verify

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.resound.devices.CardStatus
import app.resound.devices.DeviceCards
import app.resound.devices.DeviceState
import app.resound.devices.DeviceStatus
import app.resound.devices.Devices
import app.resound.devices.FoundDevice
import app.resound.ui.theme.Rs
import app.resound.ui.theme.RsType

/**
 * 0.2.21 专用录音设备：搜索、配对、状态与补传进度、说明卡（支持的设备）开关。
 * 配对后的设备出现在上面的“录音输入”里，由用户指定使用。
 */
@Composable
fun DeviceSection(devices: Devices, cards: DeviceCards, scanError: kotlinx.coroutines.flow.StateFlow<String?>) {
    val c = Rs.colors
    val context = LocalContext.current
    val paired by devices.devices.collectAsState()
    val found by devices.found.collectAsState()
    val error by scanError.collectAsState()
    val installed by cards.cards.collectAsState()
    var scanning by remember { mutableStateOf(false) }
    var detail by remember { mutableStateOf<String?>(null) }
    var showCards by remember { mutableStateOf(false) }
    // 搜索 30 秒后底层自动停；界面按同样时长收起“搜索中”
    LaunchedEffect(scanning) { if (scanning) { kotlinx.coroutines.delay(30_000); scanning = false } }
    DisposableEffect(Unit) { onDispose { devices.stopScan() } }
    val needed = if (android.os.Build.VERSION.SDK_INT >= 31)
        arrayOf(android.Manifest.permission.BLUETOOTH_SCAN, android.Manifest.permission.BLUETOOTH_CONNECT)
    else arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION)
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        if (r.values.all { it }) { devices.startScan(); scanning = true }
    }
    fun scan() {
        val granted = needed.all { androidx.core.content.ContextCompat.checkSelfPermission(context, it) == android.content.pm.PackageManager.PERMISSION_GRANTED }
        if (granted) { devices.startScan(); scanning = true } else ask.launch(needed)
    }
    val enabledCards = installed.count { it.enabled }
    Section("专用录音设备", trailing = {
        Text(if (scanning) "停止" else "搜索", style = RsType.subhead, color = c.accent,
            modifier = Modifier.springClick { if (scanning) { devices.stopScan(); scanning = false } else scan() })
    }) {
        RsCard(Modifier.animateContentSize(), padding = PaddingValues(vertical = 4.dp)) {
            paired.forEachIndexed { i, d ->
                if (i > 0) Divider64()
                PairedRow(d) { detail = d.address }
                DeviceOptionsUi.Content(d)
            }
            if (scanning || found.isNotEmpty()) {
                if (paired.isNotEmpty()) Divider64()
                ScanHeader(scanning, found.isEmpty())
                found.filter { f -> paired.none { it.address == f.address } }.forEach { f ->
                    Divider64()
                    FoundRow(f) { devices.pair(f) }
                }
            }
            if (paired.isEmpty() && !scanning && found.isEmpty()) {
                Text(if (enabledCards == 0) "还没有打开任何设备类型，先在下面“支持的设备”里打开" else "点右上角“搜索”，打开设备并靠近手机",
                    style = RsType.subhead, color = c.textTertiary, modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp))
            }
        }
        Column(Modifier.padding(start = 4.dp, end = 4.dp, top = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            error?.let { Text(it, style = RsType.caption, color = c.warning) }
            Text("配对后，在上面“录音输入”里点它就用它录音；断开时自动换回手机麦克风，连回来后补上断开那段。",
                style = RsType.caption, color = c.textTertiary)
            Text(if (showCards) "支持的设备 ⌄" else "支持的设备（已打开 $enabledCards 种）›", style = RsType.subhead, color = c.accent,
                modifier = Modifier.padding(top = 4.dp).springClick { showCards = !showCards })
        }
        AnimatedVisibility(showCards) {
            RsCard(Modifier.padding(top = 8.dp), padding = PaddingValues(vertical = 4.dp)) {
                installed.forEachIndexed { i, ic ->
                    if (i > 0) Divider64()
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Glyph("型", if (ic.card.status == CardStatus.Verified) Color(0xFF3F8F6F) else c.warning)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(ic.card.name, style = RsType.body.copy(fontWeight = FontWeight.SemiBold), color = c.text)
                                if (ic.card.status == CardStatus.Trial) { Spacer(Modifier.width(6.dp)); Tag("试用", c.warning) }
                            }
                            Text(if (ic.card.status == CardStatus.Trial) "还没在真机上验证过，有问题请反馈" else "已在真机上验证",
                                style = RsType.caption, color = c.textTertiary)
                        }
                        app.resound.ui.skills.Toggle(ic.enabled) { cards.setEnabled(ic.card.id, it) }
                    }
                }
                if (installed.isEmpty()) Text("暂时没有可用的设备类型", style = RsType.subhead, color = c.textTertiary,
                    modifier = Modifier.padding(16.dp))
            }
        }
    }
    detail?.let { address ->
        val d = paired.firstOrNull { it.address == address } ?: run { detail = null; return }
        DeviceSheet(d, onForget = { devices.forget(d.address); detail = null }) { detail = null }
    }
}

@Composable
private fun Divider64() = Box(Modifier.padding(start = 64.dp).fillMaxWidth().height(0.5.dp).background(Rs.colors.separator))

@Composable
private fun Glyph(text: String, tint: Color) =
    Box(Modifier.size(36.dp).clip(CircleShape).background(tint.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
        Text(text, style = RsType.subhead.copy(fontWeight = FontWeight.SemiBold), color = tint)
    }

@Composable
private fun Tag(text: String, tint: Color) = Text(text, style = RsType.caption, color = tint,
    modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(tint.copy(alpha = 0.13f)).padding(horizontal = 6.dp, vertical = 1.dp))

private fun DeviceState.statusLine(): String = when (status) {
    DeviceStatus.Connecting -> "正在连接"
    DeviceStatus.Live -> "正在收音"
    DeviceStatus.Waiting -> "没连上，靠近手机会自动连"
    DeviceStatus.Backfilling -> backfill?.let { "正在补传 ${it.doneFiles}/${it.files}" } ?: "正在补传"
    DeviceStatus.Disconnected -> "未连接"
}

@Composable
private fun DeviceStatus.tint(): Color = when (this) {
    DeviceStatus.Live -> Rs.colors.good
    DeviceStatus.Backfilling, DeviceStatus.Connecting -> Rs.colors.accent
    DeviceStatus.Waiting -> Rs.colors.warning
    DeviceStatus.Disconnected -> Rs.colors.textTertiary
}

@Composable
private fun PairedRow(d: DeviceState, onClick: () -> Unit) {
    val c = Rs.colors
    Row(Modifier.fillMaxWidth().springClick(onClick).padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Glyph("设", d.status.tint())
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(d.name, style = RsType.body.copy(fontWeight = FontWeight.SemiBold), color = c.text, maxLines = 1)
            Text(d.lastError ?: d.statusLine(), style = RsType.caption, color = if (d.lastError != null) c.warning else c.textTertiary, maxLines = 2)
            d.backfill?.takeIf { it.bytes > 0 }?.let { b ->
                LinearProgressIndicator(progress = { (b.doneBytes.toFloat() / b.bytes).coerceIn(0f, 1f) }, color = c.accent, trackColor = c.accentSoft,
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp).height(4.dp).clip(CircleShape))
            }
        }
        d.battery?.let { Text("$it%", style = RsType.subhead, color = if (it <= 15) c.critical else c.textSecondary) }
    }
}

@Composable
private fun ScanHeader(scanning: Boolean, empty: Boolean) {
    val c = Rs.colors
    val pulse by rememberInfiniteTransition(label = "scan").animateFloat(0.3f, 1f, infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "a")
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).alpha(if (scanning) pulse else 1f).clip(CircleShape).background(if (scanning) c.accent else c.textTertiary))
        Spacer(Modifier.width(10.dp))
        Text(when { scanning && empty -> "正在搜索附近的设备…"; scanning -> "正在搜索，找到这些："; else -> "找到这些：" },
            style = RsType.subhead, color = c.textSecondary)
    }
}

@Composable
private fun FoundRow(f: FoundDevice, onPair: () -> Unit) {
    val c = Rs.colors
    var pairing by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Glyph("新", c.accent)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(f.name, style = RsType.body.copy(fontWeight = FontWeight.SemiBold), color = c.text, maxLines = 1)
                if (f.trial) { Spacer(Modifier.width(6.dp)); Tag("试用", c.warning) }
            }
            f.rssi?.let { Text(if (it > -60) "就在旁边" else if (it > -80) "附近" else "有点远", style = RsType.caption, color = c.textTertiary) }
        }
        Pill(if (pairing) "配对中" else "配对", filled = !pairing) { if (!pairing) { pairing = true; onPair() } }
    }
}

@Composable
private fun DeviceSheet(d: DeviceState, onForget: () -> Unit, onDismiss: () -> Unit) {
    val c = Rs.colors
    var confirm by remember { mutableStateOf(false) }
    RsSheet(d.name, d.statusLine() + (d.battery?.let { " · 电量 $it%" } ?: ""), onDismiss = onDismiss) {
        d.lastError?.let { Text(it, style = RsType.subhead, color = c.warning) }
        d.backfill?.let { b ->
            Text("正在补传断开期间的录音：${b.doneFiles}/${b.files} 个文件", style = RsType.subhead, color = c.text)
            if (b.bytes > 0) LinearProgressIndicator(progress = { (b.doneBytes.toFloat() / b.bytes).coerceIn(0f, 1f) }, color = c.accent,
                trackColor = c.accentSoft, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(CircleShape))
            Text("补完、确认存好后才会删设备里的文件。", style = RsType.caption, color = c.textTertiary)
        }
        Text("要用它录音，到上面“录音输入”里点它。", style = RsType.caption, color = c.textTertiary)
        ActionButton(if (confirm) "确定忘记？再点一次" else "忘记这台设备", ActionKind.Danger) {
            if (!confirm) { confirm = true; return@ActionButton }
            onForget()
        }
        Text("忘记后不再自动连接；系统蓝牙里的配对不受影响。", style = RsType.caption, color = c.textTertiary)
    }
}
