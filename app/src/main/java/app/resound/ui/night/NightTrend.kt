package app.resound.ui.night

import android.content.Context
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.resound.AppGraph
import app.resound.night.DoctorReport
import app.resound.night.NightConfidence
import app.resound.night.NightPoint
import app.resound.ui.theme.Rs
import app.resound.ui.theme.RsType
import app.resound.ui.topic.ChoicePill
import app.resound.ui.verify.Pill
import app.resound.ui.verify.RsSheet
import app.resound.ui.verify.springClick
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate
import java.time.ZoneId

/**
 * 趋势与周报：范围 7/30/90 天 → 作息图（每晚一根，入睡到起床）→ 鼾声柱 → 试试看的发现 → 周报列表 → 给医生的一页。
 * 可信度“仅供参考”的夜画成淡色。
 */
@Composable
internal fun NightTrendSheet(onDismiss: () -> Unit) {
    RsSheet("睡眠趋势", subtitle = "由手机录音自动统计，仅供参考", onDismiss = onDismiss, fixedHeight = true) { TrendBody() }
}

@Composable
internal fun TrendBody() {
    val c = Rs.colors
    var days by remember { mutableStateOf(30) }
    val today = LocalDate.now()
    val points by remember(days) { AppGraph.nightInsights.trend(today.minusDays(days - 1L).toKotlin(), today.toKotlin()) }
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val findings by AppGraph.nightInsights.findings().collectAsStateWithLifecycle(initialValue = emptyList())
    val reports by AppGraph.nightInsights.weeklyReports().collectAsStateWithLifecycle(initialValue = emptyList())
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(7, 30, 90).forEach { d -> ChoicePill("$d 天", d == days, Modifier.weight(1f)) { days = d } }
    }
    Spacer(Modifier.height(14.dp))
    val valid = points.filter { it.sleepMillis != null && it.confidence != NightConfidence.Rough }
    if (valid.isEmpty()) {
        Text("这段时间还没有完整的夜晚记录", style = RsType.body, color = c.textSecondary, modifier = Modifier.padding(vertical = 20.dp))
    } else {
        Averages(valid)
        Spacer(Modifier.height(16.dp))
        Head("作息", "每根是一晚：从入睡到起床")
        SleepSpans(points, today.minusDays(days - 1L), days)
        Spacer(Modifier.height(16.dp))
        Head("鼾声", "每晚鼾声时长")
        Bars(points, today.minusDays(days - 1L), days) { it.snoreMillis }
    }
    if (findings.isNotEmpty()) {
        Spacer(Modifier.height(18.dp))
        Head("试试看的发现", "样本少时只是线索，不代表一定有关")
        findings.forEach { f ->
            Text(f.text, style = RsType.body, color = c.text, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                .clip(RoundedCornerShape(12.dp)).background(c.fill).padding(12.dp))
        }
    }
    if (reports.isNotEmpty()) {
        Spacer(Modifier.height(18.dp))
        Head("周报")
        reports.forEach { r -> WeekRow(r) }
    }
    Spacer(Modifier.height(18.dp))
    DoctorPage()
    Spacer(Modifier.height(16.dp))
}

@Composable
private fun Averages(valid: List<NightPoint>) {
    val c = Rs.colors
    val sleep = valid.mapNotNull { it.sleepMillis }.average().toLong()
    val zone = ZoneId.systemDefault()
    // 入睡时刻按“晚上 18 点起算的分钟”平均，跨午夜不出错
    val onsets = valid.mapNotNull { it.onset }.map { val t = java.time.Instant.ofEpochMilli(it).atZone(zone); ((t.hour - 18 + 24) % 24) * 60 + t.minute }
    val onset = if (onsets.isEmpty()) null else onsets.average().toInt().let { m -> val h = (m / 60 + 18) % 24; "%d:%02d".format(h, m % 60) }
    val snore = valid.map { it.snoreMillis }.average().toLong()
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("平均睡眠" to hours(sleep), "平均入睡" to (onset ?: "—"), "鼾声每晚" to if (snore > 0) hours(snore) else "很少").forEach { (k, v) ->
            Column(Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).background(c.fill).padding(horizontal = 12.dp, vertical = 10.dp)) {
                Text(k, style = RsType.caption, color = c.textTertiary)
                Text(v, style = RsType.headline, color = c.text)
            }
        }
    }
    Text("共 ${valid.size} 晚有效记录", style = RsType.caption, color = c.textTertiary, modifier = Modifier.padding(top = 6.dp))
}

/** 纵轴 20 点到次日 12 点，每晚一根竖条从入睡到起床 */
@Composable
private fun SleepSpans(points: List<NightPoint>, first: LocalDate, days: Int) {
    val c = Rs.colors
    val good = c.accent; val faded = c.accent.copy(alpha = .3f); val grid = c.separator
    val reveal = remember(days) { Animatable(0f) }
    LaunchedEffect(days) { reveal.animateTo(1f, tween(600)) }
    val zone = ZoneId.systemDefault()
    Row {
        Column(Modifier.height(150.dp), verticalArrangement = Arrangement.SpaceBetween) {
            listOf("20点", "0点", "4点", "8点", "12点").forEach { Text(it, style = RsType.caption, color = c.textTertiary) }
        }
        Spacer(Modifier.padding(start = 6.dp))
        Canvas(Modifier.weight(1f).height(150.dp)) {
            val w = size.width; val h = size.height
            val slot = w / days
            fun y(at: Long): Float {
                val t = java.time.Instant.ofEpochMilli(at).atZone(zone)
                val m = ((t.hour - 20 + 24) % 24) * 60 + t.minute
                return (m / (16f * 60)).coerceIn(0f, 1f) * h
            }
            listOf(0f, .25f, .5f, .75f, 1f).forEach { f -> drawLine(grid, Offset(0f, f * h), Offset(w, f * h), 1f) }
            points.forEach { p ->
                val i = java.time.temporal.ChronoUnit.DAYS.between(first, LocalDate.of(p.date.year, p.date.monthNumber, p.date.dayOfMonth)).toInt()
                val a = p.onset ?: return@forEach; val b = p.wake ?: return@forEach
                if (i !in 0 until days) return@forEach
                val top = y(a); val bottom = top + (y(b) - top) * reveal.value
                val bw = (slot * .62f).coerceAtMost(14.dp.toPx())
                drawRoundRect(if (p.confidence == NightConfidence.Rough) faded else good, Offset(i * slot + (slot - bw) / 2, top), Size(bw, (bottom - top).coerceAtLeast(2f)), CornerRadius(bw / 2))
            }
        }
    }
}

@Composable
private fun Bars(points: List<NightPoint>, first: LocalDate, days: Int, value: (NightPoint) -> Long) {
    val c = Rs.colors
    val color = SnoreColor; val faded = SnoreColor.copy(alpha = .3f); val track = c.fill
    val max = points.maxOfOrNull(value)?.coerceAtLeast(60_000) ?: 60_000
    val reveal = remember(days) { Animatable(0f) }
    LaunchedEffect(days) { reveal.animateTo(1f, tween(600)) }
    Row(verticalAlignment = Alignment.Top) {
        Text(hours(max), style = RsType.caption, color = c.textTertiary)
        Spacer(Modifier.padding(start = 6.dp))
        Canvas(Modifier.weight(1f).height(80.dp)) {
            val w = size.width; val h = size.height; val slot = w / days
            drawRoundRect(track, Offset(0f, h - 1f), Size(w, 1f))
            points.forEach { p ->
                val i = java.time.temporal.ChronoUnit.DAYS.between(first, LocalDate.of(p.date.year, p.date.monthNumber, p.date.dayOfMonth)).toInt()
                if (i !in 0 until days) return@forEach
                val v = value(p); if (v <= 0) return@forEach
                val bh = (v.toFloat() / max * h * reveal.value).coerceAtLeast(2f)
                val bw = (slot * .62f).coerceAtMost(14.dp.toPx())
                drawRoundRect(if (p.confidence == NightConfidence.Rough) faded else color, Offset(i * slot + (slot - bw) / 2, h - bh), Size(bw, bh), CornerRadius(bw / 3))
            }
        }
    }
}

@Composable
private fun WeekRow(r: app.resound.night.WeeklyReport) {
    val c = Rs.colors
    var open by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(12.dp)).background(c.fill)
        .springClick { open = !open }.padding(12.dp).animateContentSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${r.weekStart.monthNumber}月${r.weekStart.dayOfMonth}日这一周 · ${r.nights} 晚", style = RsType.body, color = c.text, modifier = Modifier.weight(1f))
            Text(if (open) "收起" else "展开", style = RsType.caption, color = c.accent)
        }
        r.finding?.let { Text(it, style = RsType.subhead, color = c.textSecondary, modifier = Modifier.padding(top = 4.dp)) }
        if (open) Text(plain(r.markdown), style = RsType.subhead, color = c.text, modifier = Modifier.padding(top = 8.dp))
    }
}

/** 去掉 markdown 标记，界面上直接读 */
private fun plain(md: String) = md.lines().joinToString("\n") { it.trimStart('#', ' ').replace("**", "").replace("|", " ").trimEnd() }
    .replace(Regex("\n-{3,}.*"), "").trim()

/** 给医生的一页：生成文字稿预览，导出 PDF，可连同几段典型录音一起分享 */
@Composable
private fun DoctorPage() {
    val c = Rs.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var report by remember { mutableStateOf<DoctorReport?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    Head("给医生的一页", "最近 30 天的作息、鼾声、咳嗽，附几段确认过的录音")
    if (report == null) Pill(if (busy) "生成中…" else "生成", filled = false) {
        if (busy) return@Pill
        busy = true; error = null
        scope.launch {
            val today = LocalDate.now()
            report = runCatching { AppGraph.nightInsights.doctorReport(today.minusDays(29).toKotlin(), today.toKotlin()) }.onFailure { error = "没生成出来，稍后再试" }.getOrNull()
            busy = false
        }
    }
    report?.let { r ->
        Text(plain(r.markdown), style = RsType.subhead, color = c.text, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.fill).padding(12.dp))
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill("分享 PDF", filled = true) {
                scope.launch {
                    val pdf = withContext(Dispatchers.IO) { runCatching { writePdf(context, r) }.getOrNull() }
                    if (pdf == null) { error = "PDF 没生成出来"; return@launch }
                    share(context, listOf(pdf), "application/pdf", "给医生的一页")
                }
            }
            if (r.clips.isNotEmpty()) Pill("连同 ${r.clips.size} 段录音", filled = false) {
                scope.launch {
                    val files = withContext(Dispatchers.IO) {
                        runCatching { listOf(writePdf(context, r)) + copyClips(context, r) }.getOrNull()
                    }
                    if (files == null) { error = "文件没准备好"; return@launch }
                    share(context, files, "*/*", "给医生的一页")
                }
            }
        }
    }
    error?.let { Text(it, style = RsType.caption, color = c.warning, modifier = Modifier.padding(top = 6.dp)) }
}

private fun shareDir(context: Context) = File(context.cacheDir, "night-share").apply { mkdirs() }

/** 录音在应用私有目录，分享前复制到缓存（一天后由 NightShare.cleanup 清掉） */
private fun copyClips(context: Context, r: DoctorReport): List<File> = r.clips.mapIndexedNotNull { i, ref ->
    val src = File(ref.id).takeIf { it.exists() } ?: return@mapIndexedNotNull null
    File(shareDir(context), "睡眠录音-${i + 1}.${src.extension.ifEmpty { "ogg" }}").also { src.copyTo(it, overwrite = true) }
}

/** A4 纯文字排版：标题加粗，正文按段落换行，满页另起 */
private fun writePdf(context: Context, r: DoctorReport): File {
    val doc = PdfDocument()
    val pageW = 595; val pageH = 842; val margin = 48
    val body = TextPaint().apply { textSize = 11f; isAntiAlias = true; color = android.graphics.Color.rgb(30, 30, 30) }
    val head = TextPaint(body).apply { textSize = 15f; isFakeBoldText = true }
    var pageNo = 1
    var page = doc.startPage(PdfDocument.PageInfo.Builder(pageW, pageH, pageNo).create())
    var y = margin.toFloat()
    for (raw in r.markdown.lines()) {
        val line = raw.trimEnd()
        if (line.isBlank()) { y += 6; continue }
        if (Regex("^\\|?\\s*-{3,}").containsMatchIn(line)) continue
        val isHead = line.startsWith("#")
        val text = line.trimStart('#', ' ').replace("**", "").let { if (it.startsWith("|")) it.trim('|').split("|").joinToString("    ") { s -> s.trim() } else it }
        val layout = StaticLayout.Builder.obtain(text, 0, text.length, if (isHead) head else body, pageW - margin * 2)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL).setLineSpacing(3f, 1f).build()
        if (y + layout.height > pageH - margin) {
            doc.finishPage(page); pageNo++
            page = doc.startPage(PdfDocument.PageInfo.Builder(pageW, pageH, pageNo).create()); y = margin.toFloat()
        }
        page.canvas.save(); page.canvas.translate(margin.toFloat(), y); layout.draw(page.canvas); page.canvas.restore()
        y += layout.height + if (isHead) 8 else 4
    }
    doc.finishPage(page)
    val file = File(shareDir(context), "睡眠记录-${r.from}至${r.to}.pdf")
    file.outputStream().use { doc.writeTo(it) }
    doc.close()
    return file
}
