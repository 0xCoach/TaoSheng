package app.resound.ui.importer

import app.resound.appDisplayName
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TimeInput
import androidx.compose.material3.TimePickerDefaults
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.resound.AppGraph
import app.resound.importer.AudioImporter
import app.resound.importer.PendingImport
import app.resound.ui.theme.Rs
import app.resound.ui.theme.RsType
import app.resound.ui.verify.ActionButton
import app.resound.ui.verify.ActionKind
import app.resound.ui.verify.Pill
import app.resound.ui.verify.RsCard
import app.resound.ui.verify.RsSheet
import app.resound.ui.verify.springClick
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 挂在最外层：有等确认的导入（别的 App 分享过来、或在回响里选的文件）就弹确认面板 */
@Composable
fun ImportHost() {
    if (!AppGraph.isImporterReady) return
    val importer = AppGraph.importer
    val pending by importer.pending.collectAsState()
    if (pending.isNotEmpty()) ImportSheet(importer, pending)
}

/** 原话与转写页里的“导入录音”按钮：打开系统文件选择器，可多选 */
@Composable
fun ImportButton() {
    if (!AppGraph.isImporterReady) return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            val failed = AppGraph.importer.receive(uris).mapNotNull { it.exceptionOrNull()?.message }
            if (failed.isNotEmpty()) Toast.makeText(context, failed.first(), Toast.LENGTH_SHORT).show()
        }
    }
    Pill("导入录音", filled = false) { pick.launch(arrayOf("audio/*")) }
}

/** 正在导入时的小横条：文件名、进度、排队数 */
@Composable
fun ImportBanner(modifier: Modifier = Modifier) {
    if (!AppGraph.isImporterReady) return
    val c = Rs.colors
    val p by AppGraph.importer.progress.collectAsState()
    AnimatedVisibility(p.running || p.error != null, modifier, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
        RsCard(padding = PaddingValues(horizontal = 18.dp, vertical = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (p.error != null) "导入没成功" else "正在导入 ${p.percent}%", style = RsType.body.copy(fontWeight = FontWeight.SemiBold),
                    color = if (p.error != null) c.critical else c.text)
                Spacer(Modifier.width(8.dp))
                Text(listOfNotNull(p.fileName, if (p.queued > 0) "还有 ${p.queued} 个" else null).joinToString(" · "),
                    style = RsType.caption, color = c.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            }
            if (p.error != null) Text(p.error!!, style = RsType.caption, color = c.critical, modifier = Modifier.padding(top = 4.dp))
            else {
                Spacer(Modifier.height(10.dp))
                Box(Modifier.fillMaxWidth().height(5.dp).clip(CircleShape).background(c.background)) {
                    Box(Modifier.fillMaxWidth(p.percent / 100f).height(5.dp).clip(CircleShape).background(c.accent))
                }
                Text("导入完会马上转写；录音转写完就删除，${appDisplayName}不保留", style = RsType.caption, color = c.textTertiary, modifier = Modifier.padding(top = 8.dp))
            }
        }
    }
}

/** 确认面板：每个文件一行，确认开始时间；一次全部开始 */
@Composable
private fun ImportSheet(importer: AudioImporter, pending: List<PendingImport>) {
    val c = Rs.colors
    // 每个文件选定的开始时间；默认用文件里读到的，读不到用“现在 − 时长”
    val starts = remember { mutableStateMapOf<String, Long>() }
    pending.forEach { p -> if (p.id !in starts) starts[p.id] = p.info.suggestedStartAt ?: (System.currentTimeMillis() - p.info.durationMillis) }
    var editing by remember { mutableStateOf<String?>(null) }

    RsSheet(
        if (pending.size == 1) "导入这段录音？" else "导入 ${pending.size} 段录音？",
        "会转成原话并整理进时间线。录音转写完就删除，${appDisplayName}不保留。",
        onDismiss = { pending.forEach { importer.discard(it.id) } },
    ) {
        pending.forEach { p ->
            val start = starts[p.id] ?: 0L
            RsCard(color = c.background, padding = PaddingValues(horizontal = 16.dp, vertical = 14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(p.info.displayName, style = RsType.body.copy(fontWeight = FontWeight.SemiBold), color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("时长 ${duration(p.info.durationMillis)}", style = RsType.caption, color = c.textTertiary)
                    }
                    Text("不要", style = RsType.caption.copy(fontWeight = FontWeight.Medium), color = c.textSecondary,
                        modifier = Modifier.clip(CircleShape).background(c.card).springClick { importer.discard(p.id); starts.remove(p.id) }
                            .padding(horizontal = 12.dp, vertical = 6.dp))
                }
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("开始于", style = RsType.caption, color = c.textTertiary)
                    Spacer(Modifier.width(8.dp))
                    Text(when_(start), style = RsType.subhead.copy(fontWeight = FontWeight.SemiBold), color = c.accent,
                        modifier = Modifier.clip(CircleShape).background(c.accentSoft).springClick { editing = if (editing == p.id) null else p.id }
                            .padding(horizontal = 12.dp, vertical = 6.dp))
                    if (p.info.suggestedStartAt == null) {
                        Spacer(Modifier.width(8.dp))
                        Text("文件里没有录制时间，请确认", style = RsType.caption, color = c.warning, maxLines = 1)
                    }
                }
                AnimatedVisibility(editing == p.id) { StartEditor(start) { starts[p.id] = it } }
            }
        }
        Spacer(Modifier.height(4.dp))
        ActionButton(if (pending.size == 1) "开始导入" else "全部开始导入", ActionKind.Primary) {
            pending.forEach { p -> importer.start(p.id, starts[p.id] ?: System.currentTimeMillis()) }
        }
    }
}

/** 改开始时间：日期（今天/昨天/前天/大前天）+ 时分输入 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StartEditor(start: Long, onChange: (Long) -> Unit) {
    val c = Rs.colors
    val zone = ZoneId.systemDefault()
    val at = Instant.ofEpochMilli(start).atZone(zone)
    val today = LocalDate.now()
    val time = rememberTimePickerState(at.hour, at.minute, is24Hour = true)
    var day by remember { mutableStateOf(at.toLocalDate()) }
    fun emit() = onChange(day.atTime(LocalTime.of(time.hour, time.minute)).atZone(zone).toInstant().toEpochMilli())
    Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            (0L..3L).forEach { back ->
                val d = today.minusDays(back)
                val on = d == day
                val bg by animateColorAsState(if (on) c.accent else c.card, spring(stiffness = Spring.StiffnessMediumLow), label = "importDay")
                Text(listOf("今天", "昨天", "前天", d.format(DateTimeFormatter.ofPattern("M月d日")))[back.toInt()],
                    style = RsType.caption.copy(fontWeight = FontWeight.Medium), color = if (on) Color.White else c.textSecondary,
                    modifier = Modifier.clip(CircleShape).background(bg).springClick { day = d; emit() }.padding(horizontal = 12.dp, vertical = 7.dp))
            }
        }
        TimeInput(time, colors = TimePickerDefaults.colors(
            timeSelectorSelectedContainerColor = c.accentSoft, timeSelectorSelectedContentColor = c.accent,
            timeSelectorUnselectedContainerColor = c.card, timeSelectorUnselectedContentColor = c.text,
        ))
        Pill("用这个时间", filled = true) { emit() }
    }
}

private fun when_(at: Long): String {
    val t = Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault())
    val days = java.time.temporal.ChronoUnit.DAYS.between(t.toLocalDate(), LocalDate.now())
    val d = when (days) { 0L -> "今天"; 1L -> "昨天"; 2L -> "前天"; else -> t.format(DateTimeFormatter.ofPattern("M月d日")) }
    return "$d ${t.format(DateTimeFormatter.ofPattern("HH:mm"))}"
}

private fun duration(ms: Long): String {
    val s = ms / 1000
    return when { s < 60 -> "${s} 秒"; s < 3600 -> "${s / 60} 分 ${s % 60} 秒"; else -> "${s / 3600} 小时 ${s % 3600 / 60} 分" }
}
