package app.resound.ui.today

import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.animateContentSize
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.resound.capture.QuietRule
import app.resound.digest.MemoryEvent
import app.resound.digest.RhythmBaseline
import app.resound.transcribe.TranscriptLine
import app.resound.ui.theme.Rs
import app.resound.ui.theme.RsType
import app.resound.ui.verify.RsCard
import app.resound.ui.verify.formatClock
import app.resound.ui.verify.springClick
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 当天节奏：只用数得准的东西——原话句子的时长、事件数、整理里提到的人名、会议场景；不用声纹分“我/别人”。
 * 媒体（电视、视频、外放）不算进说话，只在分布条上用灰色叠在柱子上。
 */
internal data class DayRhythm(
    val speechMinutes: Int,
    val events: Int,
    val people: Int,
    val meetings: Int,
    val meetingMinutes: Int,
    /** 0..23 点每小时的说话秒数 / 媒体秒数 */
    val speechByHour: List<Int>,
    val mediaByHour: List<Int>,
    val firstAt: Long,
    val lastAt: Long,
    /** 实际说话最长的一件事：句子时长之和（不含中间的安静） */
    val longest: Pair<MemoryEvent, Int>?,
    val mediaEvents: Int = 0,
)

private fun overlaps(l: TranscriptLine, e: MemoryEvent) = l.startAt < e.endAt && l.endAt > e.startAt
private fun minutes(ms: Long) = ((ms + 59_999) / 60_000).toInt()
private fun TranscriptLine.ms() = (endAt - startAt).coerceAtLeast(0)

internal fun dayRhythm(events: List<MemoryEvent>, lines: List<TranscriptLine>): DayRhythm? {
    val zone = ZoneId.systemDefault()
    val media = events.filter { it.isMedia() }
    val life = events.filter { !it.isMedia() }
    val (mediaLines, kept) = lines.partition { l -> media.any { overlaps(l, it) } }
    if (kept.isEmpty()) return null
    fun byHour(ls: List<TranscriptLine>) = IntArray(24).also { h -> ls.forEach { h[Instant.ofEpochMilli(it.startAt).atZone(zone).hour] += (it.ms() / 1000).toInt() } }.toList()
    val meetings = life.filter { it.scene == "会议" }
    val longest = life.map { e -> e to kept.filter { overlaps(it, e) }.sumOf { it.ms() } }.maxByOrNull { it.second }
        ?.takeIf { it.second >= 60_000 }?.let { it.first to minutes(it.second) }
    return DayRhythm(
        speechMinutes = minutes(kept.sumOf { it.ms() }),
        events = life.size,
        people = life.flatMap { it.people }.filter { it != "我" && it.isNotBlank() }.toSet().size,
        meetings = meetings.size,
        meetingMinutes = minutes(kept.filter { l -> meetings.any { overlaps(l, it) } }.sumOf { it.ms() }),
        speechByHour = byHour(kept), mediaByHour = byHour(mediaLines),
        firstAt = kept.minOf { it.startAt }, lastAt = kept.maxOf { it.endAt },
        longest = longest,
        mediaEvents = media.size,
    )
}

/** “比平时多 30%”：至少 3 天记录才有平时；差别不到 15% 不显示 */
private fun compare(today: Int, usual: Int?): String? {
    if (usual == null || usual <= 0) return null
    val pct = ((today - usual) * 100f / usual).toInt()
    return when { pct >= 15 -> "↑$pct%"; pct <= -15 -> "↓${-pct}%"; else -> null }
}

/** 停录时段（如睡眠）当天落在哪些小时，分布条上用浅底标出 */
private fun quietHours(rules: List<QuietRule>, date: LocalDate): Map<Int, String> = buildMap {
    rules.filter { it.enabled }.forEach { r ->
        val spans = if (r.endMinute <= r.startMinute) listOf(
            (r.startMinute / 60..23).takeIf { date.dayOfWeek in r.days },
            (0 until (r.endMinute + 59) / 60).takeIf { date.minusDays(1).dayOfWeek in r.days })
        else listOf((r.startMinute / 60 until (r.endMinute + 59) / 60).takeIf { date.dayOfWeek in r.days })
        spans.filterNotNull().forEach { span -> span.forEach { putIfAbsent(it, r.name) } }
    }
}

/**
 * 今天页“今日节奏”：说话 · 件事 · 提到几人 · 开会；数字可点（点了筛选时间线）；柱子可点（跳到那个小时）。
 * 说话不到 5 分钟的日子只显示一行。
 */
@Composable
internal fun RhythmCard(events: List<MemoryEvent>, lines: List<TranscriptLine>, date: LocalDate, pendingSegments: Int,
                        onFilter: (TimelineFilter) -> Unit, onHour: (Int) -> Unit, onOpen: (MemoryEvent) -> Unit) {
    val c = Rs.colors
    val r = remember(events, lines) { dayRhythm(events, lines) } ?: return
    val base by remember(date) { if (app.resound.AppGraph.isRhythmReady) app.resound.AppGraph.rhythm.baseline(date) else kotlinx.coroutines.flow.flowOf(null) }
        .collectAsState(initial = null)
    val rules = if (app.resound.AppGraph.isQuietScheduleReady) app.resound.AppGraph.quietSchedule.rules.collectAsState().value else emptyList()
    val pending = if (pendingSegments > 0 && date == LocalDate.now()) " · 还有 $pendingSegments 段待转写" else ""
    // 点标题收起成一行 / 展开；记住上次的选择
    val prefs = androidx.compose.ui.platform.LocalContext.current.getSharedPreferences("ui", android.content.Context.MODE_PRIVATE)
    var collapsed by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(prefs.getBoolean("rhythmCollapsed", false)) }
    RsCard(Modifier.animateContentSize(androidx.compose.animation.core.spring(stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow)),
        padding = PaddingValues(start = 16.dp, end = 10.dp, top = 10.dp, bottom = 12.dp)) {
        val toggle = { collapsed = !collapsed; prefs.edit().putBoolean("rhythmCollapsed", collapsed).apply() }
        Row(Modifier.fillMaxWidth().springClick(toggle), verticalAlignment = Alignment.CenterVertically) {
            Text("今日节奏" + pending, style = RsType.caption.copy(fontWeight = FontWeight.Medium), color = c.textTertiary)
            // 收起时标题这一行直接带上概要和一条迷你分布，一眼看完
            if (collapsed && r.speechMinutes >= 5) {
                Text("  提到 ${r.people} 人" + (if (r.meetings > 0) " · 开会 ${r.meetings} 次" else ""), style = RsType.subhead.copy(fontWeight = FontWeight.Medium), color = c.text,
                    maxLines = 1, modifier = Modifier.weight(1f))
                MiniBars(r, Modifier.padding(end = 8.dp).size(width = 64.dp, height = 16.dp))
            } else Spacer(Modifier.weight(1f))
            if (r.speechMinutes >= 5) FoldButton(collapsed)
        }
        if (collapsed && r.speechMinutes >= 5) return@RsCard
        if (r.speechMinutes < 5) {
            Spacer(Modifier.height(4.dp))
            Text("今天说话 ${r.speechMinutes} 分钟 · ${r.events} 件事", style = RsType.subhead, color = c.textSecondary)
            return@RsCard
        }
        Spacer(Modifier.height(2.dp))
        Row(Modifier.fillMaxWidth().padding(end = 6.dp)) {
            // 说话时长和件数在上面的录音卡里，这里只放它没有的，同一屏每个数字只出现一次
            Stat("提到", "${r.people} 人", compare(r.people, base?.people), Modifier.weight(1f)) { onFilter(TimelineFilter.All) }
            Stat("开会", if (r.meetings == 0) "没有" else "${r.meetings} 次", compare(r.meetings, base?.meetings), Modifier.weight(1f)) { if (r.meetings > 0) onFilter(TimelineFilter.Meeting) }
            Stat("媒体", if (r.mediaEvents == 0) "没有" else "${r.mediaEvents} 件", null, Modifier.weight(1f)) { if (r.mediaEvents > 0) onFilter(TimelineFilter.Media) }
        }
        Spacer(Modifier.height(4.dp))
        Box(Modifier.padding(end = 6.dp)) { HourBars(r, date, rules, onHour) }
    }
}

@Composable
private fun Stat(label: String, value: String, delta: String?, modifier: Modifier, onClick: () -> Unit) {
    val c = Rs.colors
    // 不裁圆角：圆角会切掉“提”字左下那一点这类贴边的笔画
    Column(modifier.padding(end = 6.dp).springClick(onClick)) {
        Text(value, style = RsType.headline, color = c.text, maxLines = 1,
            autoSize = TextAutoSize.StepBased(minFontSize = 11.sp, maxFontSize = RsType.headline.fontSize))
        // 和平时比的箭头跟在标签后面，不和数字抢宽度
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = RsType.caption, color = c.textTertiary, maxLines = 1)
            delta?.let { Text(" $it", style = RsType.caption.copy(fontSize = 10.sp), color = if (it.startsWith("↑")) c.accent else c.textTertiary, maxLines = 1) }
        }
    }
}

/**
 * 0～24 点的说话分布：每天位置固定，今天和昨天能直接对上。
 * 说话主色（最多的那个小时更深）、媒体灰色叠在上面、停录时段浅底；数字只标 0·6·12·18·24，3·9·15·21 只画淡刻度。
 * 点柱子跳到那个时段；按住显示“15 点 · 说话 12 分钟”，按住左右滑跟着手指看。
 */
@Composable
private fun HourBars(r: DayRhythm, date: LocalDate, rules: List<QuietRule>, onHour: (Int) -> Unit) {
    val c = Rs.colors
    val quiet = remember(rules, date) { quietHours(rules, date) }
    val totals = (0..23).map { r.speechByHour[it] + r.mediaByHour[it] }
    val max = (totals.maxOrNull() ?: 1).coerceAtLeast(1)
    val peak = (0..23).maxByOrNull { r.speechByHour[it] } ?: 0
    var hover by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<Int?>(null) }
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    fun bias(h: Float) = (h / 24f) * 2f - 1f
    Column {
        val width = androidx.compose.runtime.remember { androidx.compose.runtime.mutableIntStateOf(1) }
        fun hourAt(x: Float) = ((x / width.intValue.coerceAtLeast(1)) * 24).toInt().coerceIn(0, 23)
        Box(Modifier.fillMaxWidth().height(36.dp)) {
        Row(Modifier.fillMaxWidth().height(36.dp)
            .onSizeChanged { width.intValue = it.width }
            .pointerInput(r) {
                detectTapGestures(
                    onTap = { o -> val h = hourAt(o.x); if (totals[h] > 0) onHour(h) },
                    onLongPress = { o -> hover = hourAt(o.x); haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress) },
                    onPress = { tryAwaitRelease(); hover = null },
                )
            }
            .pointerInput(r) {
                detectDragGesturesAfterLongPress(onDragEnd = { hover = null }, onDragCancel = { hover = null }) { change, _ ->
                    val h = hourAt(change.position.x); if (h != hover) hover = h
                }
            },
            horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.Bottom) {
            (0..23).forEach { h ->
                val speech = r.speechByHour[h]; val mediaS = r.mediaByHour[h]; val total = speech + mediaS
                Box(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(3.dp))
                    .background(if (h in quiet) c.separator.copy(alpha = 0.35f) else androidx.compose.ui.graphics.Color.Transparent),
                    contentAlignment = Alignment.BottomCenter) {
                    if (total == 0) Box(Modifier.fillMaxWidth().fillMaxHeight(0.06f).clip(RoundedCornerShape(2.dp)).background(c.separator))
                    else Column(Modifier.fillMaxWidth().fillMaxHeight((total / max.toFloat()).coerceIn(0.12f, 1f))) {
                        if (mediaS > 0) Box(Modifier.fillMaxWidth().weight(mediaS.toFloat()).clip(RoundedCornerShape(topStart = 2.dp, topEnd = 2.dp)).background(c.textTertiary.copy(alpha = 0.45f)))
                        if (speech > 0) Box(Modifier.fillMaxWidth().weight(speech.toFloat()).clip(RoundedCornerShape(2.dp))
                            .background(if (h == peak || h == hover) c.accent else c.accent.copy(alpha = 0.35f)))
                    }
                }
            }
        }
        // 看今天时，现在的位置画一条细竖线：右边是还没到的时间，不是漏录
        if (date == LocalDate.now()) {
            val now = java.time.LocalTime.now()
            Box(Modifier.align(androidx.compose.ui.BiasAlignment(bias(now.hour + now.minute / 60f), 0f)).size(width = 1.5.dp, height = 36.dp)
                .background(c.accent.copy(alpha = 0.6f)))
        }
        }
        // 刻度：3·9·15·21 淡竖线，0·6·12·18·24 写数字
        Box(Modifier.fillMaxWidth().height(4.dp)) {
            listOf(3, 9, 15, 21).forEach { h ->
                Box(Modifier.align(androidx.compose.ui.BiasAlignment(bias(h.toFloat()), -1f)).size(width = 1.dp, height = 4.dp).background(c.separator))
            }
        }
        // 平时显示 0·6·12·18·24；按住柱子时这一行换成那个小时的详情，不另占地方
        Box(Modifier.fillMaxWidth().height(20.dp).padding(top = 1.dp)) {
            val h = hover
            if (h == null) listOf(0, 6, 12, 18, 24).forEach { t ->
                Text("$t", style = RsType.caption, color = c.textTertiary, maxLines = 1,
                    modifier = Modifier.align(androidx.compose.ui.BiasAlignment(bias(t.toFloat()), 0f)))
            } else {
                val talk = (r.speechByHour[h] + 59) / 60; val media = (r.mediaByHour[h] + 59) / 60
                Text("$h 点 · " + (if (talk > 0) "说话 $talk 分钟" else "没有说话") + (if (media > 0) " · 媒体 $media 分钟" else "") + (quiet[h]?.let { " · $it" } ?: ""),
                    style = RsType.caption.copy(fontWeight = FontWeight.Medium), color = c.accent, maxLines = 1,
                    modifier = Modifier.align(androidx.compose.ui.BiasAlignment(bias(h + 0.5f).coerceIn(-0.7f, 0.7f), 0f)))
            }
        }
        quiet.values.firstOrNull()?.let { name ->
            Text("浅底为停录时段（$name）", style = RsType.caption, color = c.textTertiary, modifier = Modifier.padding(top = 2.dp))
        }
    }
}


/** 收起/展开：淡色小圆钮，里面一个细箭头，切换时转半圈 */
@Composable
private fun FoldButton(collapsed: Boolean) {
    val c = Rs.colors
    val turn by androidx.compose.animation.core.animateFloatAsState(if (collapsed) 0f else 180f,
        androidx.compose.animation.core.spring(dampingRatio = 0.6f, stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow), label = "fold")
    Box(Modifier.size(28.dp).clip(androidx.compose.foundation.shape.CircleShape).background(c.accentSoft), contentAlignment = Alignment.Center) {
        androidx.compose.foundation.Canvas(Modifier.size(12.dp).graphicsLayer { rotationZ = turn }) {
            val w = size.width; val h = size.height
            val path = androidx.compose.ui.graphics.Path().apply { moveTo(w * 0.15f, h * 0.35f); lineTo(w * 0.5f, h * 0.7f); lineTo(w * 0.85f, h * 0.35f) }
            drawPath(path, c.accent, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.8.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round,
                join = androidx.compose.ui.graphics.StrokeJoin.Round))
        }
    }
}

/** 收起时标题行里的迷你分布：24 根细柱，最多的那根用主色 */
@Composable
private fun MiniBars(r: DayRhythm, modifier: Modifier) {
    val c = Rs.colors
    val totals = (0..23).map { r.speechByHour[it] }
    val max = (totals.maxOrNull() ?: 1).coerceAtLeast(1)
    val peak = totals.indexOf(totals.max())
    androidx.compose.foundation.Canvas(modifier) {
        val gap = 1.dp.toPx(); val w = (size.width - gap * 23) / 24
        totals.forEachIndexed { h, v ->
            val bh = if (v == 0) size.height * 0.08f else size.height * (v / max.toFloat()).coerceIn(0.15f, 1f)
            drawRoundRect(if (h == peak) c.accent else c.accent.copy(alpha = 0.35f),
                topLeft = androidx.compose.ui.geometry.Offset(h * (w + gap), size.height - bh), size = androidx.compose.ui.geometry.Size(w, bh),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(w / 2))
        }
    }
}
