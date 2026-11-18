package app.resound.ui.night

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
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
internal fun TrendBody(initialDays: Int = 30) {
    val c = Rs.colors
    var days by remember { mutableStateOf(initialDays) }
    var tag by remember { mutableStateOf<String?>(null) }
    val today = LocalDate.now()
    val first = today.minusDays(days - 1L)
    val points by remember(days) { AppGraph.nightInsights.trend(first.toKotlin(), today.toKotlin()) }
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val previous by remember(days) { AppGraph.nightInsights.trend(first.minusDays(days.toLong()).toKotlin(), first.minusDays(1).toKotlin()) }
        .collectAsStateWithLifecycle(initialValue = emptyList())
    // 7/30 天要把鼾声和醒来画进每晚的条里，逐晚取详情；90 天按周合并不需要
    val dates = points.filter { it.onset != null }.map { it.javaDate() }
    val details by remember(days, dates) {
        if (days > 45 || dates.isEmpty()) flowOf(emptyMap())
        else combine(dates.map { d -> AppGraph.night.night(d.toKotlin()).map { d to it } }) { arr -> arr.mapNotNull { (d, v) -> v?.let { d to it } }.toMap() }
    }.collectAsStateWithLifecycle(initialValue = emptyMap())
    val findings by AppGraph.nightInsights.findings().collectAsStateWithLifecycle(initialValue = emptyList())
    val reports by AppGraph.nightInsights.weeklyReports().collectAsStateWithLifecycle(initialValue = emptyList())
    val valid = points.filter { it.sleepMillis != null && it.confidence != NightConfidence.Rough }
    val prevValid = previous.filter { it.sleepMillis != null && it.confidence != NightConfidence.Rough }

    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(7, 30, 90).forEach { d -> ChoicePill("$d 天", d == days, Modifier.weight(1f)) { days = d; tag = null } }
    }
    Spacer(Modifier.height(14.dp))
    if (valid.isEmpty()) {
        Text("这段时间还没有完整的夜晚记录", style = RsType.body, color = c.textSecondary, modifier = Modifier.padding(vertical = 20.dp))
    } else {
        TrendHero(days, valid, prevValid)
        Spacer(Modifier.height(20.dp))
        Head("作息 · 鼾声", if (days > 45) "每根是一周：淡色是最早到最晚，实心是中间值；下面的圆点越大，鼾声越多" else "每根是一晚，从入睡到起床；深色是鼾声，断开是醒了。点一根看那晚")
        SleepChart(points, details, first, days, tag)
        val tags = points.flatMap { it.tags }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.map { it.key }
        if (tags.isNotEmpty()) {
            Spacer(Modifier.height(18.dp))
            Head("试试看", "点一个标签，图上只亮那几晚")
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                tags.forEach { t ->
                    val on = tag == t
                    Text(t, style = RsType.subhead.copy(fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium),
                        color = if (on) Color.White else c.textSecondary,
                        modifier = Modifier.clip(RoundedCornerShape(50)).background(if (on) SnoreColor else c.fill)
                            .springClick { tag = if (on) null else t }.padding(horizontal = 14.dp, vertical = 8.dp))
                }
            }
        }
        val shownFindings = findings.filter { tag == null || it.tag.name == tag }
        if (shownFindings.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            shownFindings.forEach { FindingCard(it) }
        }
    }
    if (reports.isNotEmpty()) {
        Spacer(Modifier.height(20.dp))
        Head("周报")
        reports.forEach { WeekCard(it) }
    }
    Spacer(Modifier.height(20.dp))
    // 换范围就清掉旧预览，按新范围重新生成
    androidx.compose.runtime.key(days) { DoctorPage(days) }
    Spacer(Modifier.height(16.dp))
}

/** 给医生的一页：生成两页 PDF（图在前、明细在后），预览缩略图，点开看大图；可连同几段确认过的录音一起分享 */
@Composable
private fun DoctorPage(days: Int) {
    val c = Rs.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var report by remember { mutableStateOf<DoctorReport?>(null) }
    var pdf by remember { mutableStateOf<File?>(null) }
    var pages by remember { mutableStateOf<List<Bitmap>>(emptyList()) }
    var open by remember { mutableStateOf<Int?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    Head("给医生的一页", "最近 $days 天的作息、鼾声、咳嗽，附几段确认过的录音")
    if (report == null) Pill(if (busy) "生成中…" else "生成", filled = false) {
        if (busy) return@Pill
        busy = true; error = null
        scope.launch {
            val today = LocalDate.now()
            val r = runCatching { AppGraph.nightInsights.doctorReport(today.minusDays(days - 1L).toKotlin(), today.toKotlin()) }.getOrNull()
            val made = r?.let { withContext(Dispatchers.IO) { runCatching { DoctorPdf.file(shareDir(context), it).let { f -> f to renderPages(f) } }.getOrNull() } }
            if (made == null) error = "没生成出来，稍后再试" else { report = r; pdf = made.first; pages = made.second }
            busy = false
        }
    }
    if (pages.isNotEmpty()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            pages.forEachIndexed { i, page ->
                Image(page.asImageBitmap(), "第 ${i + 1} 页", contentScale = ContentScale.FillWidth,
                    modifier = Modifier.weight(1f).aspectRatio(595f / 842f).clip(RoundedCornerShape(10.dp)).background(Color.White)
                        .border(1.dp, c.textSecondary.copy(alpha = .15f), RoundedCornerShape(10.dp)).springClick { open = i })
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill("分享 PDF", filled = true) { pdf?.let { share(context, listOf(it), "application/pdf", "给医生的一页") } }
            val r = report
            if (r != null && r.clips.isNotEmpty()) Pill("连同 ${r.clips.size} 段录音", filled = false) {
                scope.launch {
                    val files = withContext(Dispatchers.IO) { runCatching { listOfNotNull(pdf) + copyClips(context, r) }.getOrNull() }
                    if (files == null) { error = "文件没准备好"; return@launch }
                    share(context, files, "*/*", "给医生的一页")
                }
            }
        }
    }
    error?.let { Text(it, style = RsType.caption, color = c.warning, modifier = Modifier.padding(top = 6.dp)) }
    open?.let { first ->
        Dialog(onDismissRequest = { open = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Column(Modifier.fillMaxSize().background(Color(0xFFF3F0F8)).verticalScroll(rememberScrollState()).springClick { open = null }.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // 点的那页放前面
                (pages.drop(first) + pages.take(first)).forEach { page ->
                    Image(page.asImageBitmap(), null, contentScale = ContentScale.FillWidth, modifier = Modifier.fillMaxWidth().aspectRatio(595f / 842f).clip(RoundedCornerShape(8.dp)).background(Color.White))
                }
            }
        }
    }
}

/** 把 PDF 每页画成图片做预览（宽 1240 像素，放大看也清楚） */
private fun renderPages(file: File): List<Bitmap> = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
    PdfRenderer(fd).use { renderer ->
        (0 until renderer.pageCount).map { i ->
            renderer.openPage(i).use { page ->
                val w = 1240; val h = w * page.height / page.width
                Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { b -> b.eraseColor(android.graphics.Color.WHITE); page.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY) }
            }
        }
    }
}

private fun shareDir(context: Context) = File(context.cacheDir, "night-share").apply { mkdirs() }

/** 录音在应用私有目录，分享前复制到缓存（一天后由 NightShare.cleanup 清掉） */
private fun copyClips(context: Context, r: DoctorReport): List<File> = r.clips.mapIndexedNotNull { i, ref ->
    val src = File(ref.id).takeIf { it.exists() } ?: return@mapIndexedNotNull null
    File(shareDir(context), "睡眠录音-${i + 1}.${src.extension.ifEmpty { "ogg" }}").also { src.copyTo(it, overwrite = true) }
}

