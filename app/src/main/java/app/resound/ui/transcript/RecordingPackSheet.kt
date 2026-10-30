package app.resound.ui.transcript

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.resound.AppGraph
import app.resound.recordings.RecordingPackEstimate
import app.resound.recordings.RecordingPackOptions
import app.resound.recordings.RecordingPackState
import app.resound.recordings.RecordingPacker
import app.resound.recordings.RecordingRange
import app.resound.ui.theme.Rs
import app.resound.ui.theme.RsType
import app.resound.ui.verify.ActionButton
import app.resound.ui.verify.ActionKind
import app.resound.ui.verify.RsSheet
import app.resound.ui.verify.springClick
import app.resound.ui.verify.formatBytes
import app.resound.ui.verify.formatDuration
import java.time.LocalDate

/** 原话页状态卡里的一行：“录音包 · 把手机里的原始录音打包导出 ›” */
@Composable
internal fun RecordingPackRow() {
    if (!AppGraph.isRecordingPackerReady) return
    val packer = AppGraph.recordingPacker
    val current by packer.current.collectAsState()
    var open by remember { mutableStateOf(false) }
    val st = current?.second
    SettingLine("录音包", when (st) {
        is RecordingPackState.Running -> "正在打包 ${(st.fraction * 100).toInt()}%"
        else -> "把手机里的原始录音打包导出"
    }) { open = true }
    if (open) RecordingPackSheet(packer) { open = false }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RecordingPackSheet(packer: RecordingPacker, onDismiss: () -> Unit) {
    val c = Rs.colors
    val context = LocalContext.current
    val days by packer.availableDays.collectAsState()
    val current by packer.current.collectAsState()
    var started by remember { mutableStateOf<String?>(null) }
    val today = LocalDate.now()
    val oldest = days.minOrNull() ?: today
    // 常用范围；只列有录音的
    val presets = listOfNotNull(
        ("今天" to RecordingRange(today, today)).takeIf { today in days },
        ("昨天" to RecordingRange(today.minusDays(1), today.minusDays(1))).takeIf { today.minusDays(1) in days },
        ("最近 3 天" to RecordingRange(today.minusDays(2), today)).takeIf { days.size > 1 },
        ("全部 ${days.size} 天" to RecordingRange(oldest, today)).takeIf { days.size > 3 },
    )
    var customOn by remember { mutableStateOf(false) }
    var custom by remember { mutableStateOf(RecordingRange(today, today, java.time.LocalTime.of(0, 0), java.time.LocalTime.now().withSecond(0).withNano(0))) }
    var presetPick by remember(presets.size) { mutableStateOf(presets.lastOrNull()?.second) }
    val customValid = custom.startMillis() <= custom.endMillis()
    val pick = if (customOn) custom.takeIf { customValid } else presetPick
    var withText by remember { mutableStateOf(true) }
    var skipMedia by remember { mutableStateOf(false) }
    val estimate by produceState<RecordingPackEstimate?>(null, pick, skipMedia) {
        value = pick?.let { runCatching { packer.estimate(it, RecordingPackOptions(withText, skipMedia)) }.getOrNull() }
    }
    val mine = current?.takeIf { started == null || it.first == started }
    RsSheet("导出录音包", "原始录音（OGG）按天分文件夹打成一个压缩包，保存到 下载 › Resound › 录音包", onDismiss = onDismiss, fixedHeight = mine?.second !is RecordingPackState.Running && started == null) {
        when (val st = mine?.second.takeIf { started != null || it is RecordingPackState.Running }) {
            is RecordingPackState.Running -> {
                Text("正在打包 ${st.done}/${st.total} 段", style = RsType.body, color = c.text)
                Box(Modifier.fillMaxWidth().height(6.dp).clip(CircleShape).background(c.accentSoft)) {
                    Box(Modifier.fillMaxWidth(st.fraction.coerceIn(0.02f, 1f)).height(6.dp).clip(CircleShape).background(c.accent))
                }
                Text("可以关掉面板去做别的，打好了会通知你", style = RsType.caption, color = c.textTertiary)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ActionButton("后台继续", ActionKind.Secondary, Modifier.weight(1f)) { onDismiss() }
                    ActionButton("取消", ActionKind.Danger, Modifier.weight(1f)) { mine?.first?.let(packer::cancel) }
                }
            }
            is RecordingPackState.Done -> {
                Text("打好了", style = RsType.title, color = c.text)
                Text("${st.segments} 段 · ${formatBytes(st.bytes)}", style = RsType.body, color = c.textSecondary)
                Text("在 文件管理 › 下载 › Resound › 录音包 › ${st.name}", style = RsType.caption, color = c.textTertiary)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ActionButton("分享", ActionKind.Secondary, Modifier.weight(1f)) {
                        mine?.first?.let(packer::shareUri)?.let { uri ->
                            val send = android.content.Intent(android.content.Intent.ACTION_SEND).setType("application/zip")
                                .putExtra(android.content.Intent.EXTRA_STREAM, uri).addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            context.startActivity(android.content.Intent.createChooser(send, "分享录音包"))
                        }
                    }
                    ActionButton("完成", ActionKind.Primary, Modifier.weight(1f)) { onDismiss() }
                }
            }
            is RecordingPackState.Failed -> {
                Text("没打包成", style = RsType.title, color = c.text)
                Text(st.message, style = RsType.body, color = c.textSecondary)
                ActionButton("知道了", ActionKind.Primary) { started = null }
            }
            RecordingPackState.Cancelled -> {
                Text("已取消", style = RsType.body, color = c.textSecondary)
                ActionButton("知道了", ActionKind.Primary) { started = null }
            }
            null -> {
                if (days.isEmpty()) {
                    Text("手机里没有还保留着的录音", style = RsType.body, color = c.textSecondary)
                    return@RsSheet
                }
                Text("导出哪几天", style = RsType.caption.copy(fontWeight = FontWeight.Medium), color = c.textSecondary)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    presets.forEach { (label, r) -> DayChip(label, !customOn && r == presetPick) { presetPick = r; customOn = false } }
                    DayChip("自定义", customOn) { customOn = true }
                }
                if (customOn) {
                    RangeEnd("从", custom.from, custom.fromTime, oldest, today) { d, t -> custom = custom.copy(from = d, fromTime = t) }
                    RangeEnd("到", custom.to, custom.toTime, oldest, today) { d, t -> custom = custom.copy(to = d, toTime = t) }
                    if (!customValid) Text("开始时间不能晚于结束时间", style = RsType.caption, color = c.warning)
                }
                Text("手机里保留着 ${days.size} 天的录音（${oldest.monthValue}月${oldest.dayOfMonth}日起）；更早的已按原声保留天数清理",
                    style = RsType.caption, color = c.textTertiary)
                OptionRow("附上原话", "每段一个同名文字文件，外加一份原话总表", withText) { withText = it }
                OptionRow("跳过媒体和环境音", "电视、视频的声音和被跳过的片段不导出", skipMedia) { skipMedia = it }
                estimate?.let { e ->
                    Text("${e.days} 天 · ${e.segments} 段 · ${formatBytes(e.bytes)} · 共 ${formatDuration(e.audioMs, seconds = false)}",
                        style = RsType.subhead, color = c.textSecondary,
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.background).padding(14.dp))
                }
                Text("每段按“日期 时刻 事件标题”命名，如 2026-10-30 09.15 项目周会.ogg", style = RsType.caption, color = c.textTertiary)
                ActionButton("开始打包", ActionKind.Primary, enabled = pick != null && (estimate?.segments ?: 0) > 0) {
                    pick?.let { started = packer.start(it, RecordingPackOptions(withText, skipMedia)) }
                }
            }
        }
    }
}

@Composable
private fun DayChip(label: String, on: Boolean, onClick: () -> Unit) {
    val c = Rs.colors
    val bg by animateColorAsState(if (on) c.accent else c.background, spring(stiffness = Spring.StiffnessMediumLow), label = "packDay")
    Text(label, style = RsType.subhead.copy(fontWeight = FontWeight.Medium), color = if (on) Color.White else c.textSecondary,
        modifier = Modifier.clip(CircleShape).background(bg).springClick(onClick).padding(horizontal = 14.dp, vertical = 8.dp))
}

/** 自定义范围的一端：“从  10月30日  09:00”，日期、时刻分别点开系统选择器；日期限在手机还保留录音的这几天 */
@Composable
private fun RangeEnd(label: String, day: LocalDate, time: java.time.LocalTime, min: LocalDate, max: LocalDate,
                     onChange: (LocalDate, java.time.LocalTime) -> Unit) {
    val c = Rs.colors
    val context = LocalContext.current
    val zone = java.time.ZoneId.systemDefault()
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = RsType.subhead, color = c.textSecondary, modifier = Modifier.padding(end = 4.dp))
        PickBox("${day.monthValue}月${day.dayOfMonth}日", Modifier.weight(1.3f)) {
            android.app.DatePickerDialog(context, { _, y, m, d -> onChange(LocalDate.of(y, m + 1, d), time) }, day.year, day.monthValue - 1, day.dayOfMonth).apply {
                datePicker.minDate = min.atStartOfDay(zone).toInstant().toEpochMilli()
                datePicker.maxDate = max.atStartOfDay(zone).toInstant().toEpochMilli()
            }.show()
        }
        PickBox("%02d:%02d".format(time.hour, time.minute), Modifier.weight(1f)) {
            android.app.TimePickerDialog(context, { _, h, m -> onChange(day, java.time.LocalTime.of(h, m)) }, time.hour, time.minute, true).show()
        }
    }
}

@Composable
private fun PickBox(text: String, modifier: Modifier, onClick: () -> Unit) {
    val c = Rs.colors
    Box(modifier.clip(RoundedCornerShape(12.dp)).background(c.background).springClick(onClick).padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
        Text(text, style = RsType.subhead.copy(fontWeight = FontWeight.Medium, fontFeatureSettings = "tnum"), color = c.text)
    }
}
