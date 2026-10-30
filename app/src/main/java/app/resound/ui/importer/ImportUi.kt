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
        // 卡 071：这次用哪种方式转写（只对这次导入生效，不改设置）
        val chooser = if (app.resound.AppGraph.isRedoReady) app.resound.AppGraph.redo.asr else null
        var asr by remember { mutableStateOf(chooser?.lastChoice?.value) }
        if (chooser != null && asr != null) {
            Spacer(Modifier.height(4.dp))
            Text("用哪个转写", style = RsType.caption.copy(fontWeight = FontWeight.Medium), color = c.textSecondary)
            app.resound.ui.redo.AsrChoice(chooser, pending.sumOf { it.info.durationMillis }, asr!!) { asr = it }
        }
        // 卡 077：这段录音是什么——决定怎么区分说话人、生成什么纪要；按线索预选，否则用上次的
        val kinds = if (app.resound.AppGraph.isImportKindsReady) app.resound.AppGraph.importKinds else null
        var kind by remember { mutableStateOf<app.resound.importer.ImportKind?>(null) }
        var reason by remember { mutableStateOf<app.resound.importer.KindReason?>(null) }
        var merge by remember { mutableStateOf(true) }
        androidx.compose.runtime.LaunchedEffect(pending.map { it.id }) {
            if (kinds != null && kind == null && pending.isNotEmpty()) {
                // 任一文件有通话线索就按通话预选，否则按第一个文件的建议
                val all = pending.map { runCatching { kinds.suggest(it.id) }.getOrNull() }
                val pick = all.firstOrNull { it?.reason == app.resound.importer.KindReason.CallFolder || it?.reason == app.resound.importer.KindReason.DualChannel } ?: all.firstOrNull()
                kind = pick?.kind ?: app.resound.importer.ImportKind.Meeting; reason = pick?.reason
            }
        }
        if (kinds != null) {
            Spacer(Modifier.height(4.dp))
            Text("这段录音是", style = RsType.caption.copy(fontWeight = FontWeight.Medium), color = c.textSecondary)
            KindChoice(kind) { kind = it; reason = null }
            Text(kind?.let { k -> k.hint + (reason?.let { r -> " · " + reasonText(r) } ?: "") } ?: "正在判断…", style = RsType.caption, color = c.textTertiary)
            // 多个文件：前后接得上（相差 5 分钟内）的合成一条
            if (pending.size > 1) {
                val groups = mergeGroups(pending.map { (starts[it.id] ?: 0L) to it.info.durationMillis })
                if (groups < pending.size) Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(if (groups == 1) "这 ${pending.size} 个文件将合成一条" else "将合成 $groups 条", style = RsType.body, color = c.text)
                        Text("前后相差 5 分钟内的接起来，一份纪要；关掉则每个文件各一条", style = RsType.caption, color = c.textTertiary)
                    }
                    app.resound.ui.skills.Toggle(merge) { merge = it }
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        ActionButton(if (pending.size == 1) "开始导入" else "全部开始导入", ActionKind.Primary, enabled = kinds == null || kind != null) {
            asr?.let { chooser?.remember(it) }
            val k = kind
            if (kinds != null && k != null) {
                if (pending.size == 1) kinds.start(pending[0].id, starts[pending[0].id] ?: System.currentTimeMillis(), asr, k)
                else kinds.startBatch(pending.map { it.id }, pending.map { starts[it.id] ?: System.currentTimeMillis() }, asr, k, merge)
            } else pending.forEach { p -> importer.start(p.id, starts[p.id] ?: System.currentTimeMillis(), asr) }
        }
    }
}

/** 四种类型的胶囊：会议 / 培训 / 通话·访谈 / 普通 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun KindChoice(selected: app.resound.importer.ImportKind?, onSelect: (app.resound.importer.ImportKind) -> Unit) {
    val c = Rs.colors
    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        app.resound.importer.ImportKind.entries.forEach { k ->
            val on = k == selected
            val bg by animateColorAsState(if (on) c.accent else c.card, spring(stiffness = Spring.StiffnessMediumLow), label = "kind")
            Text(k.title, style = RsType.subhead.copy(fontWeight = FontWeight.Medium), color = if (on) Color.White else c.textSecondary,
                modifier = Modifier.clip(CircleShape).background(bg).springClick { onSelect(k) }.padding(horizontal = 14.dp, vertical = 8.dp))
        }
    }
}

private fun reasonText(r: app.resound.importer.KindReason) = when (r) {
    app.resound.importer.KindReason.CallFolder -> "看起来是通话录音"
    app.resound.importer.KindReason.DualChannel -> "左右声道分开，按声道分人更快更准"
    app.resound.importer.KindReason.LastUsed -> "上次选的"
    app.resound.importer.KindReason.Default -> ""
}.let { it }

/** 按开始时间排好后，前后相差 5 分钟内的算一组，返回组数（只用于提示，实际合并由底层按录制时间做） */
private fun mergeGroups(files: List<Pair<Long, Long>>): Int {
    val sorted = files.sortedBy { it.first }
    var groups = 0; var end = Long.MIN_VALUE
    for ((start, dur) in sorted) { if (end == Long.MIN_VALUE || start - end > 5 * 60_000L) groups++; end = maxOf(end, start + dur) }
    return groups
}

/** 改开始时间：日期（今天/昨天/前天/大前天）+ 时分滚轮（24 小时制） */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StartEditor(start: Long, onChange: (Long) -> Unit) {
    val c = Rs.colors
    val zone = ZoneId.systemDefault()
    val at = Instant.ofEpochMilli(start).atZone(zone)
    val today = LocalDate.now()
    var time by remember { mutableStateOf(LocalTime.of(at.hour, at.minute)) }
    var day by remember { mutableStateOf(at.toLocalDate()) }
    fun emit() = onChange(day.atTime(time).atZone(zone).toInstant().toEpochMilli())
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
        app.resound.ui.verify.WheelTimePicker(at.hour, at.minute, { h, m -> time = LocalTime.of(h, m) })
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
