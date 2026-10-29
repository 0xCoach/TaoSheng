package app.resound.ui.today

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import app.resound.digest.MemoryEvent
import app.resound.digest.MemoryRepository
import app.resound.transcribe.TranscriptLine
import app.resound.ui.items.Choice
import app.resound.ui.theme.Rs
import app.resound.ui.theme.RsType
import app.resound.ui.verify.ActionButton
import app.resound.ui.verify.ActionKind
import app.resound.ui.verify.RsSheet
import app.resound.ui.verify.formatClock
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/** 复制哪些内容；记住上次的选择 */
internal enum class CopyMode(val label: String) { Points("仅要点"), Both("要点 + 原话"), Lines("仅原话") }

/** 剪贴板过大时系统会直接丢掉，留足余量 */
private const val CLIP_LIMIT = 200_000

private fun day(at: Long): LocalDate = Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).toLocalDate()
private val MONTH_DAY = DateTimeFormatter.ofPattern("M月d日", Locale.CHINA)
private val WEEKDAY = DateTimeFormatter.ofPattern("EEE", Locale.CHINA)

/** “10月29日 周三” */
internal fun fullDate(d: LocalDate): String = "${d.format(MONTH_DAY)} ${d.format(WEEKDAY)}"

/** 详情页顶部：今天/昨天/前天，否则“10月27日 周一” */
internal fun relativeDate(at: Long): String = day(at).let { d ->
    when (ChronoUnit.DAYS.between(d, LocalDate.now())) { 0L -> "今天"; 1L -> "昨天"; 2L -> "前天"; else -> fullDate(d) }
}

/** 一件事的复制文本：标题、完整日期时间，再按 [mode] 接要点和/或原话；原话跨过午夜时在换天那句前标出日期 */
internal fun eventCopyText(event: MemoryEvent, lines: List<TranscriptLine>, mode: CopyMode): String = buildString {
    appendLine(event.title)
    val endDay = day(event.endAt)
    appendLine("${fullDate(day(event.startAt))} ${formatClock(event.startAt)}–" +
        (if (endDay != day(event.startAt)) "${fullDate(endDay)} " else "") + "${formatClock(event.endAt)} · ${event.scene}")
    if (mode != CopyMode.Lines) {
        appendLine(); appendLine(event.summary)
        if (event.points.isNotEmpty()) { appendLine(); event.points.forEach { appendLine("· $it") } }
        if (event.items.isNotEmpty()) { appendLine(); event.items.forEach { appendLine("【${it.category}】${it.content}") } }
    }
    if (mode != CopyMode.Points && lines.isNotEmpty()) {
        appendLine(); appendLine("原话：")
        var lastDay = day(event.startAt)
        lines.forEach {
            val d = day(it.startAt)
            if (d != lastDay) { appendLine("—— ${fullDate(d)} ——"); lastDay = d }
            appendLine("${formatClock(it.startAt)} ${it.speakerName ?: it.speakerTag?.toIntOrNull()?.let { t -> "说话人${t + 1}" } ?: ""}：${it.text}")
        }
    }
}.trimEnd()

/** 多件事合成一段：开头“10月29日 · 3 件事”（跨天写成“10月28日–10月29日”），每件事之间分隔线；超长时只放得下的前 N 件 */
internal fun batchCopyText(parts: List<Pair<MemoryEvent, String>>): Pair<String, Int> {
    val days = parts.map { day(it.first.startAt) }
    val first = days.minOrNull() ?: LocalDate.now()
    val last = days.maxOrNull() ?: first
    val range = if (first == last) first.format(MONTH_DAY) else "${first.format(MONTH_DAY)}–${last.format(MONTH_DAY)}"
    val out = StringBuilder()
    var fitted = 0
    for ((_, text) in parts) {
        val piece = (if (fitted > 0) "\n\n————————\n\n" else "") + text
        if (out.length + piece.length > CLIP_LIMIT && fitted > 0) break
        out.append(piece); fitted++
    }
    return "$range · ${parts.size} 件事\n\n$out" to fitted
}

private fun prefs(context: android.content.Context) = context.getSharedPreferences("ui", android.content.Context.MODE_PRIVATE)
internal fun lastCopyMode(context: android.content.Context): CopyMode =
    runCatching { CopyMode.valueOf(prefs(context).getString("copyMode", null) ?: "") }.getOrDefault(CopyMode.Points)

/**
 * 选复制内容的面板：三个胶囊（默认上次的选择），点“复制”读原话、拼文本、放进剪贴板。
 * [events] 一件时就是单条复制；多件按传入顺序（时间先后）拼。
 */
@Composable
internal fun CopySheet(events: List<MemoryEvent>, memory: MemoryRepository, onDone: (String) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var mode by remember { mutableStateOf(lastCopyMode(context)) }
    var busy by remember { mutableStateOf(false) }
    val sorted = remember(events) { events.sortedBy { it.startAt } }
    RsSheet(if (sorted.size == 1) "复制这件事" else "复制 ${sorted.size} 件事", "选择要复制的内容", onDismiss = { if (!busy) onDismiss() }) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CopyMode.entries.forEach { m -> Choice(m.label, m == mode) { mode = m } }
        }
        ActionButton(if (busy) "正在准备…" else "复制", ActionKind.Primary, enabled = !busy) {
            busy = true
            prefs(context).edit().putString("copyMode", mode.name).apply()
            scope.launch {
                val parts = sorted.map { e ->
                    val lines = if (mode == CopyMode.Points) emptyList() else runCatching { memory.eventLines(e.id).first() }.getOrDefault(emptyList())
                    e to eventCopyText(e, lines, mode)
                }
                val (text, fitted) = if (parts.size == 1) parts[0].second to 1 else batchCopyText(parts)
                clipboard.setText(AnnotatedString(text))
                busy = false
                onDone(if (fitted < parts.size) "内容较长，已复制前 $fitted 件" else "已复制")
                onDismiss()
            }
        }
    }
}

