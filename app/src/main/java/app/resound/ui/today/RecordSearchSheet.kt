package app.resound.ui.today

import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.resound.AppGraph
import app.resound.search.RecordSearch
import app.resound.search.RecordSearchRules
import app.resound.search.SearchHit
import app.resound.ui.ShellNav
import app.resound.ui.settings.SettingsPage
import app.resound.ui.theme.Rs
import app.resound.ui.theme.RsType
import app.resound.ui.transcript.PlayerNav
import app.resound.ui.verify.RsSheet
import app.resound.ui.verify.SheetInput
import app.resound.ui.verify.springClick
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** 首页日期旁的放大镜：和日夜切换按钮同一套样式 */
@Composable
internal fun SearchButton(onClick: () -> Unit) {
    val c = Rs.colors
    Box(Modifier.size(40.dp).springClick(onClick).clip(CircleShape).background(c.accentSoft), contentAlignment = Alignment.Center) {
        MagnifierIcon(c.accent, Modifier.size(18.dp))
    }
}

@Composable
private fun MagnifierIcon(ink: Color, modifier: Modifier) {
    Canvas(modifier) {
        val stroke = size.minDimension * 0.13f
        val r = size.minDimension * 0.33f
        val center = Offset(r + stroke, r + stroke)
        drawCircle(ink, r, center, style = Stroke(stroke))
        val d = r * 0.72f
        drawLine(ink, Offset(center.x + d, center.y + d), Offset(size.width - stroke / 2, size.height - stroke / 2), stroke, StrokeCap.Round)
    }
}

/** 搜原话和整理出的事件（议题 #34）。按天分组、最新的在前，点原话跳到那一刻，点事件打开事件。 */
@Composable
fun RecordSearchSheet(search: RecordSearch, onDismiss: () -> Unit) {
    val c = Rs.colors
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var hits by remember { mutableStateOf<List<SearchHit>?>(emptyList()) }
    LaunchedEffect(query) {
        if (query.isBlank()) { hits = emptyList(); return@LaunchedEffect }
        delay(250) // 打字停一下再查，避免每个字都查一次
        hits = null
        hits = runCatching { search.search(query) }.getOrDefault(emptyList())
    }
    val words = remember(query) { RecordSearchRules.words(query) }

    RsSheet("搜索记录", onDismiss = onDismiss, fixedHeight = true) {
        SheetInput(query, "搜原话或事件，空格分开多个词") { query = it }
        val list = hits
        when {
            query.isBlank() -> Hint("例如：合同、老王 周五、报销")
            list == null -> Hint("正在查…")
            list.isEmpty() -> Hint("没有找到“${query.trim()}”")
            else -> {
                val zone = ZoneId.systemDefault()
                list.groupBy { Instant.ofEpochMilli(it.at).atZone(zone).toLocalDate() }.forEach { (day, items) ->
                    Text(dayLabel(day), style = RsType.subhead.copy(fontWeight = FontWeight.Medium), color = c.textSecondary,
                        modifier = Modifier.padding(start = 4.dp, top = 8.dp))
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(c.card)) {
                        items.forEachIndexed { i, hit ->
                            HitRow(hit, words, zone) {
                                when (hit.kind) {
                                    SearchHit.Kind.Line -> {
                                        PlayerNav.transcriptFocus = day to (hit.segmentId ?: 0L)
                                        onDismiss(); ShellNav.openSettings(SettingsPage.Transcript)
                                    }
                                    SearchHit.Kind.Event -> scope.launch {
                                        val event = if (AppGraph.isMemoryReady) runCatching { AppGraph.memory.eventById(hit.id) }.getOrNull() else null
                                        if (event != null) { onDismiss(); PlayerNav.openEvent = event }
                                    }
                                }
                            }
                            if (i != items.lastIndex) Box(Modifier.fillMaxWidth().padding(start = 64.dp).height(0.5.dp).background(c.separator))
                        }
                    }
                }
                if (list.size >= RecordSearchRules.LIMIT)
                    Hint("只显示最近 ${RecordSearchRules.LIMIT} 条，多加一个词可以缩小范围")
            }
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, style = RsType.subhead, color = Rs.colors.textTertiary, modifier = Modifier.padding(start = 4.dp, top = 8.dp))
}

@Composable
internal fun HitRow(hit: SearchHit, words: List<String>, zone: ZoneId, onClick: () -> Unit) {
    val c = Rs.colors
    Row(Modifier.fillMaxWidth().springClick(onClick).padding(PaddingValues(horizontal = 16.dp, vertical = 12.dp)),
        verticalAlignment = Alignment.Top) {
        Text(Instant.ofEpochMilli(hit.at).atZone(zone).toLocalTime().format(DateTimeFormatter.ofPattern("HH:mm")),
            style = RsType.subhead, color = c.textTertiary, modifier = Modifier.width(48.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val tag = if (hit.kind == SearchHit.Kind.Event) "事件" else hit.speaker
            if (tag != null) Text(tag, style = RsType.caption.copy(fontWeight = FontWeight.Medium),
                color = if (hit.kind == SearchHit.Kind.Event) c.accent else c.textSecondary)
            Text(highlight(hit.text, words, c.accent), style = RsType.body, color = c.text, maxLines = 3)
        }
    }
}

/** 关键词用主题色加粗，不分大小写 */
internal fun highlight(text: String, words: List<String>, color: Color): AnnotatedString = buildAnnotatedString {
    append(text)
    for (word in words) {
        var from = 0
        while (true) {
            val at = text.indexOf(word, from, ignoreCase = true)
            if (at < 0) break
            addStyle(SpanStyle(color = color, fontWeight = FontWeight.SemiBold), at, at + word.length)
            from = at + word.length
        }
    }
}

private fun dayLabel(day: LocalDate): String {
    val today = LocalDate.now()
    val prefix = when (day) { today -> "今天 · "; today.minusDays(1) -> "昨天 · "; else -> "" }
    val pattern = if (day.year == today.year) "M月d日 EEEE" else "yyyy年M月d日 EEEE"
    return prefix + day.format(DateTimeFormatter.ofPattern(pattern, Locale.CHINA))
}
