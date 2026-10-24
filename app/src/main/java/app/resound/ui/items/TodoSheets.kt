package app.resound.ui.items

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimeInput
import androidx.compose.material3.TimePickerDefaults
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.resound.digest.AppointmentReminders
import app.resound.digest.MemoryItem
import app.resound.digest.MemoryRepository
import app.resound.ui.theme.Rs
import app.resound.ui.theme.RsType
import app.resound.ui.verify.ActionButton
import app.resound.ui.verify.ActionKind
import app.resound.ui.verify.RsSheet
import app.resound.ui.verify.SheetInput
import app.resound.ui.verify.springClick
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 小胶囊点选 */
@Composable
private fun Choice(text: String, on: Boolean, onClick: () -> Unit) {
    val c = Rs.colors
    val bg by animateColorAsState(if (on) c.accent else c.card, spring(stiffness = Spring.StiffnessMediumLow), label = "choice")
    Text(text, style = RsType.subhead.copy(fontWeight = FontWeight.Medium), color = if (on) Color.White else c.textSecondary,
        modifier = Modifier.clip(CircleShape).background(bg).springClick(onClick).padding(horizontal = 14.dp, vertical = 8.dp))
}

@Composable
private fun Label(text: String) =
    Text(text, style = RsType.caption, color = Rs.colors.textSecondary, modifier = Modifier.padding(top = 4.dp))

/** 编辑一条待办 / 约定 / 备忘：内容、哪天、几点、和谁 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun EditItemSheet(item: MemoryItem, memory: MemoryRepository, onDismiss: () -> Unit) {
    val c = Rs.colors
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now(zone)
    val start = item.dueAt?.let { Instant.ofEpochMilli(it).atZone(zone) }
    var content by remember { mutableStateOf(item.content) }
    var who by remember { mutableStateOf(item.who?.takeIf { it != "我" }.orEmpty()) }
    var day by remember { mutableStateOf(start?.toLocalDate()) }
    var time by remember { mutableStateOf(start?.takeIf { (item.dueText?.length ?: 0) > 10 }?.toLocalTime()) }
    var pickDay by remember { mutableStateOf(false) }
    var pickTime by remember { mutableStateOf(false) }

    RsSheet("编辑", "改过的内容以后重新整理也不会被覆盖。", onDismiss = onDismiss, fixedHeight = true) {
        Label("内容")
        SheetInput(content, "要做的事") { content = it }
        Label("哪天")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Choice("不定", day == null) { day = null; time = null }
            listOf("今天", "明天", "后天").forEachIndexed { i, n -> today.plusDays(i.toLong()).let { d -> Choice(n, day == d) { day = d } } }
            val custom = day?.takeIf { it.isBefore(today) || it.isAfter(today.plusDays(2)) }
            Choice(custom?.format(DateTimeFormatter.ofPattern("M月d日")) ?: "选日期…", custom != null) { pickDay = true }
        }
        if (day != null) {
            Label("几点")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Choice("不定几点", time == null) { time = null }
                val presets = listOf(9, 12, 14, 18, 20).map { LocalTime.of(it, 0) }
                presets.forEach { t -> Choice(t.format(DateTimeFormatter.ofPattern("H:mm")), time == t) { time = t } }
                val custom = time?.takeIf { it !in presets }
                Choice(custom?.format(DateTimeFormatter.ofPattern("H:mm")) ?: "其他…", custom != null) { pickTime = true }
            }
        }
        Label("和谁（可不填）")
        SheetInput(who, "例如 老王") { who = it }
        ActionButton("保存", ActionKind.Primary, enabled = content.isNotBlank()) {
            val at = day?.let { d -> (time?.let { d.atTime(it) } ?: d.atStartOfDay()).atZone(zone).toInstant().toEpochMilli() }
            memory.updateItem(item.id, content.trim(), at, time != null, who.trim().ifEmpty { null })
            onDismiss()
        }
    }

    if (pickDay) {
        val state = rememberDatePickerState(initialSelectedDateMillis = (day ?: today).atStartOfDay(ZoneId.of("UTC")).toInstant().toEpochMilli())
        DatePickerDialog(onDismissRequest = { pickDay = false }, confirmButton = {
            TextButton({ state.selectedDateMillis?.let { day = Instant.ofEpochMilli(it).atZone(ZoneId.of("UTC")).toLocalDate() }; pickDay = false }) { Text("好", color = c.accent) }
        }, dismissButton = { TextButton({ pickDay = false }) { Text("取消", color = c.textSecondary) } }) { DatePicker(state) }
    }
    if (pickTime) {
        val state = rememberTimePickerState(time?.hour ?: 9, time?.minute ?: 0, is24Hour = true)
        androidx.compose.material3.AlertDialog(onDismissRequest = { pickTime = false }, containerColor = c.background,
            confirmButton = { TextButton({ time = LocalTime.of(state.hour, state.minute); pickTime = false }) { Text("好", color = c.accent) } },
            dismissButton = { TextButton({ pickTime = false }) { Text("取消", color = c.textSecondary) } },
            text = {
                TimeInput(state, colors = TimePickerDefaults.colors(
                    timeSelectorSelectedContainerColor = c.accentSoft, timeSelectorSelectedContentColor = c.accent,
                    timeSelectorUnselectedContainerColor = c.card, timeSelectorUnselectedContentColor = c.text,
                ))
            })
    }
}

/** 约定的提前提醒：头一天晚上 + 开始前多久 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ReminderSheet(memory: MemoryRepository, onDismiss: () -> Unit) {
    val c = Rs.colors
    val r by memory.appointmentReminders.collectAsState()
    fun set(v: AppointmentReminders) = memory.setAppointmentReminders(v)
    RsSheet("约定提醒", "有时间的约定和待办会提前提醒你，和到期提醒走同一个地方（手机通知和推送通道）。", onDismiss = onDismiss) {
        androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("头一天晚上提醒", style = RsType.body, color = c.text)
                Text("第二天有什么安排，前一晚先说一声", style = RsType.caption, color = c.textTertiary)
            }
            Switch(r.eveningBefore, { set(r.copy(eveningBefore = it)) }, colors = SwitchDefaults.colors(checkedTrackColor = c.accent))
        }
        if (r.eveningBefore) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(20, 21, 22).forEach { h -> Choice("$h:00", r.eveningAt == h * 60) { set(r.copy(eveningAt = h * 60)) } }
        }
        Label("开始前")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(null to "不提醒", 15 to "15 分钟", 30 to "30 分钟", 60 to "1 小时", 120 to "2 小时").forEach { (m, n) ->
                Choice(n, r.leadMinutes == m) { set(r.copy(leadMinutes = m)) }
            }
        }
        Text("只定了哪天、没定几点的，只在头一天晚上提醒。", style = RsType.caption, color = c.textTertiary)
    }
}
