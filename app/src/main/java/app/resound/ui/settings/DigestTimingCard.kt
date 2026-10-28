package app.resound.ui.settings

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimeInput
import androidx.compose.material3.TimePickerDefaults
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import app.resound.AppGraph
import app.resound.digest.DigestMode
import app.resound.digest.DigestTiming
import app.resound.ui.verify.Pill
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.resound.digest.AndroidDigestSettings
import app.resound.ui.theme.Rs
import app.resound.ui.theme.RsType
import app.resound.ui.verify.RsCard
import app.resound.ui.verify.springClick

/**
 * 整理时机：按间隔或按时间点（二选一）、停多久算一段结束、一直在说时最多等多久先整理。只管整理，转写在「原话与转写」里设置。
 * [timing] 为空（底层还没装配）时只显示按间隔。
 */
@Composable
fun DigestTimingCard(settings: AndroidDigestSettings, timing: DigestTiming? = if (AppGraph.isDigestTimingReady) AppGraph.digestTiming else null) {
    val c = Rs.colors
    val interval by settings.intervalMinutes.collectAsState()
    val pause by settings.pauseMinutes.collectAsState()
    val maxWait by settings.maxWaitMinutes.collectAsState()
    RsCard(padding = PaddingValues(horizontal = 20.dp, vertical = 18.dp)) {
        Text("什么时候整理", style = RsType.body, color = c.text)
        Spacer(Modifier.height(4.dp))
        Text("把转写好的原话整理成一件件事。转写在「原话与转写」里单独设置。", style = RsType.caption, color = c.textSecondary)

        Spacer(Modifier.height(16.dp))
        val mode = timing?.mode?.collectAsState()?.value ?: DigestMode.Interval
        if (timing != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MinuteChip("按间隔", mode == DigestMode.Interval) { timing.setMode(DigestMode.Interval) }
                MinuteChip("按时间点", mode == DigestMode.TimePoints) { timing.setMode(DigestMode.TimePoints) }
            }
            Spacer(Modifier.height(14.dp))
        }
        AnimatedContent(mode, transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(150)) using SizeTransform(clip = false) },
            label = "digestMode") { m ->
            Column {
                if (m == DigestMode.Interval || timing == null) {
                    Text("多久整理一次", style = RsType.subhead, color = c.text)
                    Spacer(Modifier.height(8.dp))
                    app.resound.ui.transcript.IntervalChoice(interval) { settings.setIntervalMinutes(it) }
                    Spacer(Modifier.height(6.dp))
                    Text("整理要等转写完成；转写间隔比整理长时，以转写为准。", style = RsType.caption, color = c.textTertiary)
                } else TimePoints(timing)
            }
        }

        Box(Modifier.padding(vertical = 16.dp).fillMaxWidth().height(0.5.dp).background(c.separator))
        Text("停多久算说完了", style = RsType.subhead, color = c.text)
        Spacer(Modifier.height(8.dp))
        MinuteChoices(listOf(5, 10, 20), pause, settings::setPauseMinutes)
        Spacer(Modifier.height(6.dp))
        Text("两句话之间停了 $pause 分钟以上，就算前面那段对话结束。", style = RsType.caption, color = c.textTertiary)

        Box(Modifier.padding(vertical = 16.dp).fillMaxWidth().height(0.5.dp).background(c.separator))

        Text("一直在说时，最多等多久", style = RsType.subhead, color = c.text)
        Spacer(Modifier.height(8.dp))
        MinuteChoices(listOf(30, 60, 120), maxWait, settings::setMaxWaitMinutes)
        Spacer(Modifier.height(6.dp))
        Text("对话超过 ${label(maxWait)} 还没结束，就在前面找个停顿切开，先整理前半截。",
            style = RsType.caption, color = c.textTertiary)
        Spacer(Modifier.height(10.dp))
        Text("想马上看结果，在“今天”页点“现在整理”。每日回顾、每周回顾生成前会先整理一次。",
            style = RsType.caption, color = c.textTertiary)
    }
}

/** 每天到点整理的几个时刻：胶囊列出，点 × 删掉，“添加”弹出时间输入 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TimePoints(timing: DigestTiming) {
    val c = Rs.colors
    val points by timing.timePoints.collectAsState()
    var adding by remember { mutableStateOf(false) }
    // 从系统设置回来时重查准点权限
    var exact by remember { mutableStateOf(timing.canScheduleExact()) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { exact = timing.canScheduleExact() }
    val context = LocalContext.current

    Text("每天几点整理", style = RsType.subhead, color = c.text)
    Spacer(Modifier.height(8.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        points.forEach { m -> key(m) { PointChip(clock(m)) { timing.removeTimePoint(m) } } }
        if (points.size < DigestTiming.MAX_TIME_POINTS) MinuteChip("＋ 添加", false) { adding = true }
    }
    Spacer(Modifier.height(6.dp))
    Text(if (points.isEmpty()) "还没设时间点，不会自动整理。适合把整理放在模型便宜的时段。"
        else "每天到点整理，错过了不补，等下一个时间点。适合把整理放在模型便宜的时段。",
        style = RsType.caption, color = if (points.isEmpty()) c.warning else c.textTertiary)
    if (!exact && Build.VERSION.SDK_INT >= 31) {
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("打开“闹钟与提醒”权限，才能准点整理", style = RsType.caption, color = c.warning, modifier = Modifier.weight(1f))
            Pill("去打开", filled = false) {
                runCatching { context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}"))) }
            }
        }
    }
    if (adding) AddPointDialog(onDismiss = { adding = false }) { timing.addTimePoint(it); adding = false }
}

private fun clock(minutes: Int) = "%02d:%02d".format(minutes / 60, minutes % 60)

@Composable
private fun PointChip(text: String, onRemove: () -> Unit) {
    val c = Rs.colors
    Row(Modifier.clip(CircleShape).background(c.accentSoft).padding(start = 16.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = RsType.subhead.copy(fontWeight = FontWeight.Medium), color = c.accent)
        Spacer(Modifier.width(4.dp))
        Box(Modifier.size(28.dp).clip(CircleShape).springClick(onRemove), contentAlignment = Alignment.Center) {
            Text("×", style = RsType.body, color = c.accent)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddPointDialog(onDismiss: () -> Unit, onAdd: (Int) -> Unit) {
    val c = Rs.colors
    val state = rememberTimePickerState(0, 30, is24Hour = true)
    AlertDialog(onDismissRequest = onDismiss, containerColor = c.background,
        title = { Text("添加整理时间", style = RsType.body, color = c.text) },
        confirmButton = { TextButton({ onAdd(state.hour * 60 + state.minute) }) { Text("添加", color = c.accent) } },
        dismissButton = { TextButton(onDismiss) { Text("取消", color = c.textSecondary) } },
        text = {
            TimeInput(state, colors = TimePickerDefaults.colors(
                timeSelectorSelectedContainerColor = c.accentSoft, timeSelectorSelectedContentColor = c.accent,
                timeSelectorUnselectedContainerColor = c.card, timeSelectorUnselectedContentColor = c.text,
            ))
        })
}

private fun label(minutes: Int) = if (minutes % 60 == 0) "${minutes / 60} 小时" else "$minutes 分钟"

@Composable
private fun MinuteChoices(options: List<Int>, selected: Int, onSelect: (Int) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { minutes -> MinuteChip(label(minutes), minutes == selected) { onSelect(minutes) } }
    }
}

@Composable
private fun MinuteChip(text: String, on: Boolean, onClick: () -> Unit) {
    val c = Rs.colors
    // 卡片本身是白底，未选中用页面底色才看得出是可点的胶囊
    val bg by animateColorAsState(if (on) c.accent else c.background, spring(stiffness = Spring.StiffnessMediumLow), label = "minuteChip")
    val fg by animateColorAsState(if (on) Color.White else c.textSecondary, label = "minuteChipFg")
    Text(text, style = RsType.subhead.copy(fontWeight = FontWeight.Medium), color = fg,
        modifier = Modifier.springClick(onClick).clip(CircleShape).background(bg).padding(horizontal = 16.dp, vertical = 8.dp))
}
