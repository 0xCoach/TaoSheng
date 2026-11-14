package app.resound.ui.verify

import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.resound.devices.DeviceState
import app.resound.devices.DeviceStatus
import app.resound.devices.android.DeviceInputs
import app.resound.devices.driver.*
import app.resound.devices.hub.DisableReason
import app.resound.ui.theme.Rs
import app.resound.ui.theme.RsType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * 卡 144：设备详情。内容全部按驱动声明的能力生成，不出现任何品牌判断。
 * 读数来自上次成功读取的快照（标注读取时间）；控制按钮等设备确认后才更新显示。
 */
@Composable
internal fun DeviceDetailSheet(d: DeviceState, inputs: DeviceInputs?, meta: DriverMeta?, onForget: () -> Unit, onDismiss: () -> Unit) {
    RsSheet(d.name, deviceSubtitle(d, meta), onDismiss = onDismiss, fixedHeight = true) {
        DeviceDetailBody(d, inputs, meta, onForget)
    }
}

/** 详情内容；预览 / 截图时可直接给读数和能力 */
@Composable
internal fun DeviceDetailBody(d: DeviceState, inputs: DeviceInputs?, meta: DriverMeta?, onForget: () -> Unit,
                              previewStatus: DeviceStatusModel? = null, previewInfo: LiveInfo? = null) {
    val c = Rs.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val manualStates by (inputs?.manualSyncStates ?: remember { kotlinx.coroutines.flow.MutableStateFlow(emptyMap<String, app.resound.devices.android.ManualSyncState>()) }).collectAsStateWithLifecycle()
    val manual = manualStates[d.address]
    val cached by (inputs?.lastDeviceStatus ?: remember { kotlinx.coroutines.flow.MutableStateFlow<Map<String, DeviceStatusModel>>(emptyMap()) }).collectAsStateWithLifecycle()
    var live by remember { mutableStateOf(previewInfo) }
    var busy by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var confirmForget by remember { mutableStateOf(false) }
    var pendingOff by remember { mutableStateOf<LightMode?>(null) }
    var disabled by remember { mutableStateOf(inputs?.disabledReason(d.address)) }
    val status = live?.status ?: cached[d.address] ?: previewStatus
    val prefs = remember { context.getSharedPreferences("device-ui", android.content.Context.MODE_PRIVATE) }

    fun run(label: String, block: suspend (DriverConnection) -> Unit) {
        val target = inputs ?: return
        if (busy != null || manual?.running == true) return
        scope.launch {
            busy = label; error = null; notice = null
            try { target.withDevice(d.address) { block(it) } }
            catch (e: CancellationException) { throw e }
            catch (e: DeviceException) { error = e.code.text() }
            catch (_: Exception) { error = "操作没有完成，稍后再试" }
            finally { busy = null }
        }
    }
    fun load() = run("正在读取设备…") { conn ->
        runCatching { if (Capability.Battery in conn.capabilities || Capability.Storage in conn.capabilities) conn.controls.refresh() }
        val light = if (Capability.Light in conn.capabilities || Capability.AndroidOnlyLight in conn.capabilities)
            runCatching { conn.controls.lightModes() }.getOrDefault(emptyList()) else emptyList()
        val settings = runCatching { conn.settings?.schema() }.getOrNull().orEmpty()
        live = LiveInfo(conn.capabilities, light, live?.lightId, settings, conn.status.value)
    }
    // 已经连着（正在收音或补传）时顺手读一次，不额外占用设备；没连着就等用户点“读取”
    LaunchedEffect(d.address, d.status) {
        if (live == null && (d.status == DeviceStatus.Live || d.status == DeviceStatus.Backfilling)) load()
    }
    fun setLight(mode: LightMode) = run("正在设置灯光…") { conn ->
        val actual = conn.controls.setLight(mode.id)
        live = live?.copy(lightId = actual.id)
        notice = "设备已确认：${actual.name.zh}"
    }

    Box(Modifier.fillMaxWidth()) {
        Column(Modifier.animateContentSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (disabled != null) {
                Notice("这台设备的驱动已停用", if (disabled == DisableReason.FaultLimit) "短时间内连续出错，已自动停用，免得反复连接耗电。" else "你停用了它。", c.warning)
                ActionButton("重新启用", ActionKind.Primary) { inputs?.enableDriver(d.address); disabled = null; notice = "已重新启用，下次收音时会重新连接" }
            }
            d.lastError?.takeIf { disabled == null }?.let { Text(it, style = RsType.subhead, color = c.warning) }

            // 读数
            val parts = status?.batteries.orEmpty()
            if (parts.isNotEmpty() || status?.storage != null || status?.recording != null) {
                RsCard(padding = PaddingValues(vertical = 4.dp)) {
                    parts.sortedBy { it.part }.forEachIndexed { i, part ->
                        if (i > 0) Line()
                        Reading(partName(part), batteryText(part), part.readAtMillis,
                            tint = part.percent?.let { if (it <= 15) c.critical else c.text } ?: c.textTertiary)
                    }
                    status?.storage?.let { s ->
                        if (parts.isNotEmpty()) Line()
                        Reading("存储", storageText(s), null, tint = if ((s.freeBytes ?: Long.MAX_VALUE) < 200L shl 20) c.critical else c.text)
                    }
                    status?.recording?.let { r ->
                        if (parts.isNotEmpty() || status.storage != null) Line()
                        Reading("设备录音", when (r) { RecordingState.Recording -> "正在录音"; RecordingState.Paused -> "已暂停"; RecordingState.Stopped -> "已停止" }, null,
                            tint = if (r == RecordingState.Recording) c.good else c.textSecondary)
                    }
                }
                if (live == null && previewStatus == null) Text("显示上次成功读取的状态，不代表当前状态；请连接并读取。", style = RsType.caption, color = c.textSecondary)
                if (parts.any { it.part == 2 }) Text("充电盒读数由录音设备转报，接电或取出后可能延迟更新。", style = RsType.caption, color = c.textTertiary)
            } else if (inputs != null) {
                Text("还没读过这台设备的电量和状态。", style = RsType.subhead, color = c.textTertiary)
            }
            if (inputs != null || previewInfo != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Pill(if (live == null) "连接并读取" else "刷新", filled = live == null) { load() }
                    busy?.let { Text(it, style = RsType.caption, color = c.textTertiary) }
                }
            }
            error?.let { Text(it, style = RsType.subhead, color = c.critical) }
            notice?.let { Text(it, style = RsType.subhead, color = c.good) }

            val info = live
            // 录音控制
            if (info != null && Capability.Record in info.capabilities) {
                Group("设备录音") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Pill("开始", filled = info.status.recording != RecordingState.Recording) {
                            run("正在开始…") { conn -> val s = conn.controls.setRecording(RecordingState.Recording); live = live?.copy(status = live!!.status.copy(recording = s)); notice = "设备已确认开始录音" }
                        }
                        Pill("暂停", filled = info.status.recording == RecordingState.Recording) {
                            run("正在暂停…") { conn -> val s = conn.controls.setRecording(RecordingState.Paused); live = live?.copy(status = live!!.status.copy(recording = s)); notice = "设备已确认暂停" }
                        }
                    }
                    Text("这是设备自己的录音开关；涛声收不收这台设备的声音，在上面“录音输入”里选。", style = RsType.caption, color = c.textTertiary)
                }
            }
            // 灯光
            if (info != null && info.lights.size > 1) {
                Group("指示灯") {
                    Capsules(info.lights.map { it.name.zh }, info.lights.indexOfFirst { it.id == info.lightId }) { i ->
                        val mode = info.lights[i]
                        if (mode.kind == LightKind.Off && !prefs.getBoolean("lightOffWarned", false)) pendingOff = mode else setLight(mode)
                    }
                    AnimatedVisibility(pendingOff != null) {
                        Column(Modifier.clip(RoundedCornerShape(12.dp)).background(c.warning.copy(alpha = 0.1f)).padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("灯灭后，旁人看不出设备在录音。录别人说话前，请遵守当地关于录音告知的规定。", style = RsType.subhead, color = c.text)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Pill("知道了，关灯", filled = true) { prefs.edit().putBoolean("lightOffWarned", true).apply(); pendingOff?.let(::setLight); pendingOff = null }
                                Pill("取消", filled = false) { pendingOff = null }
                            }
                        }
                    }
                    if (Capability.AndroidOnlyLight in info.capabilities)
                        Text("灯光控制只在安卓上可用。", style = RsType.caption, color = c.textTertiary)
                }
            }
            // 设置项（按设备声明自动生成）
            if (info != null && info.settings.isNotEmpty()) {
                Group("设备设置") {
                    RsCard(padding = PaddingValues(vertical = 4.dp)) {
                        info.settings.forEachIndexed { i, item ->
                            if (i > 0) Line()
                            SettingRow(item) { value ->
                                run("正在保存…") { conn ->
                                    val actual = conn.settings?.set(item.key, value) ?: throw DeviceException(ErrorCode.Unsupported)
                                    live = live?.copy(settings = live!!.settings.map { if (it.key == item.key) it.copy(value = actual) else it })
                                }
                            }
                        }
                    }
                }
            }
            // 离线录音
            if ((inputs != null || previewInfo != null) && meta?.tier == DriverTier.Plugin && (info == null || Capability.Files in info.capabilities)) {
                ActionButton(if (manual?.running == true) "正在检查…" else "立即检查离线录音", ActionKind.Secondary, enabled = busy == null && manual?.running != true) {
                    error = null; notice = null
                    inputs?.startSyncDevice(d.address)
                }
                if (manual?.running == true) {
                    Text("离开此页面会继续补传；暂停后可按已保存的进度继续。", style = RsType.caption, color = c.textSecondary)
                    ActionButton(if (manual.pausing) "正在暂停…" else "暂停补传", ActionKind.Secondary, enabled = !manual.pausing) { inputs?.pauseSyncDevice(d.address) }
                } else (manual?.errorCode?.text() ?: manual?.message)?.let { Text(it, style = RsType.caption,
                    color = if (manual?.errorCode != null) c.warning else c.textSecondary) }
            }
            d.backfill?.let { b ->
                Text("补传任务：${b.doneFiles}/${b.files} 项", style = RsType.subhead, color = c.text)
                Text("已接收 ${formatBytes(b.doneBytes)} / ${formatBytes(b.bytes)}；同一文件的多个缺口分别计数。", style = RsType.caption, color = c.textSecondary)
                if (b.bytes > 0) LinearProgressIndicator(progress = { (b.doneBytes.toFloat() / b.bytes).coerceIn(0f, 1f) }, color = c.accent,
                    trackColor = c.accentSoft, modifier = Modifier.fillMaxWidth().clip(CircleShape))
            }
            meta?.let { m ->
                if (m.stability == Stability.Trial) Text(
                    "试用：这款设备由${if (m.tier == DriverTier.Plugin) "社区" else "公开资料"}适配" + (m.maintainer?.let { "，维护人 $it" } ?: "") + "，可能有不稳定的地方，有问题请反馈。",
                    style = RsType.caption, color = c.textTertiary)
            }
            Text("要用它录音，到上面“录音输入”里点它。", style = RsType.caption, color = c.textTertiary)
            ActionButton(if (confirmForget) "确定忘记？再点一次" else "忘记这台设备", ActionKind.Danger, enabled = manual?.running != true) {
                if (!confirmForget) { confirmForget = true; return@ActionButton }
                onForget()
            }
            Text("忘记后不再自动连接；系统蓝牙里的配对不受影响。", style = RsType.caption, color = c.textTertiary)
        }
    }
}

internal data class LiveInfo(
    val capabilities: Set<Capability>,
    val lights: List<LightMode>,
    val lightId: Int?,
    val settings: List<SettingItem>,
    val status: DeviceStatusModel,
)

internal fun deviceSubtitle(d: DeviceState, meta: DriverMeta?): String = buildString {
    append(when (d.status) {
        DeviceStatus.Connecting -> "正在连接"
        DeviceStatus.Live -> "正在收音"
        DeviceStatus.Waiting -> "没连上，靠近手机会自动连"
        DeviceStatus.Backfilling -> "正在补传"
        DeviceStatus.Disconnected -> "未连接"
    })
    meta?.name?.zh?.takeIf { it != d.name }?.let { append(" · ").append(it) }
}

private fun partName(p: BatteryPart) = p.name?.zh ?: when (p.part) { 1 -> "设备电量"; 2 -> "充电盒电量"; else -> "电池 ${p.part}" }

private fun batteryText(p: BatteryPart) = buildString {
    append(p.percent?.let { "$it%" } ?: "未知")
    when (p.charging) { true -> append(" · 充电中"); false -> Unit; null -> Unit }
}

private fun storageText(s: StorageInfo): String {
    fun gb(b: Long) = if (b >= 1L shl 30) String.format(java.util.Locale.US, "%.1f GB", b / (1L shl 30).toDouble()) else "${b shr 20} MB"
    val free = s.freeBytes; val total = s.totalBytes
    return when {
        free != null && total != null -> "剩余 ${gb(free)} / 共 ${gb(total)}"
        free != null -> "剩余 ${gb(free)}"
        else -> "未知"
    }
}

/** 读取时间：刚刚 / x 分钟前 / x 小时前 / x 天前；没有时间不显示 */
internal fun agoText(at: Long?): String? {
    at ?: return null
    val m = (System.currentTimeMillis() - at) / 60_000
    return when { m < 1 -> "刚刚"; m < 60 -> "$m 分钟前"; m < 24 * 60 -> "${m / 60} 小时前"; else -> "${m / 1440} 天前" }
}

/** 设备只报错误码，文字在这里翻译 */
internal fun ErrorCode.text(): String = when (this) {
    ErrorCode.Unsupported -> "这台设备不支持这个操作"
    ErrorCode.BadArgs -> "设备没接受这个设置"
    ErrorCode.NotBound -> "设备还没和这部手机绑定"
    ErrorCode.AlreadyBound -> "设备已经绑定了别的手机，请先在旧手机上解绑或重置设备"
    ErrorCode.BindTimeout -> "绑定超时，请在设备上按键确认后重试"
    ErrorCode.BusyRetry -> "设备正忙，稍后再试"
    ErrorCode.NotFound -> "设备里找不到这段录音"
    ErrorCode.NotConfirmed -> "录音还没确认保存，设备拒绝删除"
    ErrorCode.ChecksumMismatch -> "传输校验没对上，会自动重传"
    ErrorCode.StorageFull -> "设备存储已满"
    ErrorCode.LowBattery -> "设备电量太低"
    ErrorCode.StateConflict -> "设备当前状态不允许这样做（比如还在录音，要先在设备上停止保存）"
    ErrorCode.VendorError -> "设备报告了一个错误"
    ErrorCode.Disconnected -> "设备连接断开了，靠近手机后重试"
    ErrorCode.Timeout -> "设备没有响应，靠近手机后重试"
    ErrorCode.ProtocolError -> "设备回的数据看不懂，可能是固件不兼容"
    ErrorCode.CryptoFailed -> "和设备的加密握手失败"
    ErrorCode.WriteTooLong -> "命令太长，设备收不下"
    ErrorCode.PermissionDenied -> "缺少蓝牙权限"
}

@Composable
private fun Line() = Box(Modifier.padding(start = 16.dp).fillMaxWidth().height(0.5.dp).background(Rs.colors.separator))

@Composable
private fun Reading(label: String, value: String, at: Long?, tint: Color) {
    val c = Rs.colors
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = RsType.body, color = c.textSecondary, modifier = Modifier.weight(1f))
        Column(horizontalAlignment = Alignment.End) {
            Text(value, style = RsType.body.copy(fontWeight = FontWeight.SemiBold), color = tint)
            agoText(at)?.let { Text(it, style = RsType.caption, color = c.textTertiary) }
        }
    }
}

@Composable
private fun Group(title: String, content: @Composable ColumnScope.() -> Unit) =
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = RsType.subhead.copy(fontWeight = FontWeight.SemiBold), color = Rs.colors.text)
        content()
    }

@Composable
private fun Notice(title: String, body: String, tint: Color) =
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(tint.copy(alpha = 0.1f)).padding(12.dp)) {
        Text(title, style = RsType.subhead.copy(fontWeight = FontWeight.SemiBold), color = tint)
        Text(body, style = RsType.caption, color = Rs.colors.textSecondary)
    }

/** 胶囊单选 */
@Composable
internal fun Capsules(names: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        names.forEachIndexed { i, n -> Pill(n, filled = i == selected) { onSelect(i) } }
    }
}

@Composable
private fun SettingRow(item: SettingItem, onChange: (SettingValue) -> Unit) {
    val c = Rs.colors
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 11.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(item.label.zh, style = RsType.body, color = c.text, modifier = Modifier.weight(1f))
            when (item.kind) {
                SettingKind.Switch -> app.resound.ui.skills.Toggle((item.value as? SettingValue.Bool)?.value == true) { onChange(SettingValue.Bool(it)) }
                SettingKind.ReadOnly -> Text(item.value.display(item), style = RsType.body, color = c.textSecondary,
                    modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.End)
                SettingKind.Number -> {
                    val v = (item.value as? SettingValue.Num)?.value
                    val step = item.step ?: 1.0
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Pill("−", filled = false) { v?.let { onChange(SettingValue.Num((it - step).coerceAtLeast(item.min ?: Double.NEGATIVE_INFINITY))) } }
                        Text(item.value.display(item), style = RsType.body.copy(fontWeight = FontWeight.SemiBold), color = c.text)
                        Pill("+", filled = false) { v?.let { onChange(SettingValue.Num((it + step).coerceAtMost(item.max ?: Double.POSITIVE_INFINITY))) } }
                    }
                }
                SettingKind.Choice -> Unit
            }
        }
        if (item.kind == SettingKind.Choice) {
            val current = (item.value as? SettingValue.Choice)?.id
            Capsules(item.options.map { it.label.zh }, item.options.indexOfFirst { it.id == current }) { i -> onChange(SettingValue.Choice(item.options[i].id)) }
        }
    }
}

private fun SettingValue?.display(item: SettingItem): String = when (this) {
    null -> "未知"
    is SettingValue.Bool -> if (value) "开" else "关"
    is SettingValue.Num -> (if (value == Math.floor(value)) value.toLong().toString() else value.toString()) + (item.unit?.let { " $it" } ?: "")
    is SettingValue.Choice -> item.options.firstOrNull { it.id == id }?.label?.zh ?: "未知"
    is SettingValue.Text -> value
}
