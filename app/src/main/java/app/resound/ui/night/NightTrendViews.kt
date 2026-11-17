package app.resound.ui.night

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.resound.AppGraph
import app.resound.night.CompareMetric
import app.resound.night.NightConfidence
import app.resound.night.NightDetail
import app.resound.night.NightEventKind
import app.resound.night.NightPoint
import app.resound.night.TagFinding
import app.resound.night.WeeklyReport
import app.resound.ui.theme.Rs
import app.resound.ui.theme.RsType
import app.resound.ui.verify.springClick
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs

/** 睡着的条：柔和的靛蓝；鼾声用更深的 [SnoreColor] 画在条里面 */
internal val SleepColor = Color(0xFFA9A6F2)

private val zone: ZoneId get() = ZoneId.systemDefault()

/** 从晚上 18 点起算的分钟数，跨午夜不出错 */
internal fun eveningMinutes(at: Long): Int {
    val t = Instant.ofEpochMilli(at).atZone(zone)
    return ((t.hour - 18 + 24) % 24) * 60 + t.minute
}

private fun eveningClock(m: Int) = "%d:%02d".format((m / 60 + 18) % 24, m % 60)

internal fun NightPoint.javaDate(): LocalDate = LocalDate.of(date.year, date.monthNumber, date.dayOfMonth)

private fun median(xs: List<Int>): Int? = xs.sorted().let { if (it.isEmpty()) null else it[it.size / 2] }

/** 顶部大卡：浅紫到晨光橙的渐变，平均睡眠滚动出来，和前一段时间比 */
@Composable
internal fun TrendHero(days: Int, valid: List<NightPoint>, previous: List<NightPoint>) {
    val c = Rs.colors
    val dark = c.background.luminance() < .5f
    val brush = Brush.linearGradient(if (dark) listOf(Color(0xFF29264A), Color(0xFF3B2B24)) else listOf(Color(0xFFECEAFF), Color(0xFFFFEADB)))
    val avg = valid.mapNotNull { it.sleepMillis }.average().toLong()
    val prevAvg = previous.mapNotNull { it.sleepMillis }.takeIf { it.size >= 3 }?.average()?.toLong()
    val onsets = valid.mapNotNull { it.onset }.map(::eveningMinutes)
    val onsetMid = onsets.takeIf { it.isNotEmpty() }?.average()?.toInt()
    // 波动：平均偏离多少分钟，取整到 5
    val spread = onsetMid?.let { m -> (onsets.map { abs(it - m) }.average() / 5).toInt() * 5 }
    val snore = valid.map { it.snoreMillis }.average().toLong()
    val shown = remember(days, avg) { Animatable(0f) }
    LaunchedEffect(days, avg) { shown.snapTo(0f); shown.animateTo(1f, tween(900, easing = FastOutSlowInEasing)) }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(brush).padding(18.dp)) {
        Text("这 $days 天 · ${valid.size} 晚", style = RsType.subhead, color = c.textSecondary)
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text("平均睡了 ", style = RsType.body, color = c.text, modifier = Modifier.padding(bottom = 6.dp))
            Text(hours((avg * shown.value).toLong()), style = RsType.largeTitle.copy(fontWeight = FontWeight.Bold), color = c.text)
        }
        prevAvg?.let { p ->
            val diff = (avg - p) / 60_000
            if (abs(diff) >= 5) {
                Spacer(Modifier.height(6.dp))
                Text("比前 $days 天${if (diff > 0) "多" else "少"} ${abs(diff)} 分", style = RsType.caption.copy(fontWeight = FontWeight.SemiBold),
                    color = if (dark) Color(0xFFD6D3FF) else Color(0xFF5450B8),
                    modifier = Modifier.clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = if (dark) .08f else .6f)).padding(horizontal = 10.dp, vertical = 4.dp))
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            HeroStat("入睡", onsetMid?.let(::eveningClock) ?: "—", spread?.let { if (it <= 5) "很规律" else "前后差不多 $it 分" })
            HeroStat("鼾声每晚", if (snore >= 60_000) hours(snore) else "很少", null)
        }
    }
}

@Composable
private fun HeroStat(label: String, value: String, sub: String?) {
    val c = Rs.colors
    Column {
        Text(label, style = RsType.caption, color = c.textSecondary)
        Text(value, style = RsType.title.copy(fontWeight = FontWeight.SemiBold), color = c.text)
        sub?.let { Text(it, style = RsType.caption, color = c.textSecondary) }
    }
}

/**
 * 作息 · 鼾声：每晚一根胶囊，从入睡到起床；鼾声用深色画在胶囊里，半夜醒来处断开。
 * 背后淡色带是“平时”（入睡、起床的中位数）。90 天按周合并：淡色是这周最早到最晚，实心是中位。
 * 点一根，下面浮出那晚的小卡，再点进详情。[highlight] 不为空时只亮带这个标签的晚上。
 */
@Composable
internal fun SleepChart(points: List<NightPoint>, details: Map<LocalDate, NightDetail>, first: LocalDate, days: Int, highlight: String?) {
    val c = Rs.colors
    val weekly = days > 45
    val slots = if (weekly) (days + 6) / 7 else days
    val valid = points.filter { it.onset != null && it.wake != null }
    if (valid.isEmpty()) return
    val onsets = valid.map { eveningMinutes(it.onset!!) }; val wakes = valid.map { eveningMinutes(it.wake!!) }
    // 纵轴：数据范围外留 1 小时，至少 9 小时，取整点
    var lo = ((onsets.min() - 60) / 60) * 60; var hi = ((wakes.max() + 119) / 60) * 60
    if (hi - lo < 9 * 60) hi = lo + 9 * 60
    lo = lo.coerceAtLeast(0); hi = hi.coerceAtMost(20 * 60)
    val usual = median(onsets)!! to median(wakes)!!
    var selected by remember(days) { mutableStateOf<Int?>(null) }
    val reveal = remember(days) { Animatable(0f) }
    LaunchedEffect(days) { reveal.snapTo(0f); reveal.animateTo(1f, tween(1100, easing = FastOutSlowInEasing)) }
    val dim by animateFloatAsState(if (highlight != null) 1f else 0f, tween(300), label = "tagDim")
    val bg = c.card; val grid = c.separator; val band = SleepColor.copy(alpha = .16f); val ring = c.text
    // 每个格子里的晚上
    val bySlot: Map<Int, List<NightPoint>> = valid.groupBy { p ->
        val i = java.time.temporal.ChronoUnit.DAYS.between(first, p.javaDate()).toInt()
        if (weekly) i / 7 else i
    }.filterKeys { it in 0 until slots }
    val tickHours = (lo / 60..hi / 60).filter { (it * 60) in lo..hi }.let { all -> val step = if (all.size > 7) 3 else 2; all.filter { (it + 18) % step == 0 } }
    Row {
        Box(Modifier.width(34.dp).height(190.dp)) {
            tickHours.forEach { h ->
                val f = (h * 60 - lo).toFloat() / (hi - lo)
                Text("${(h + 18) % 24}点", style = RsType.caption, color = c.textTertiary,
                    modifier = Modifier.padding(top = (190 * f - 8).coerceIn(0f, 172f).dp))
            }
        }
        Canvas(Modifier.weight(1f).height(190.dp).pointerInput(days, points) {
            detectTapGestures { p -> val i = (p.x / (size.width / slots)).toInt(); selected = if (selected == i || i !in bySlot) null else i }
        }) {
            val w = size.width; val h = size.height; val slot = w / slots
            fun y(m: Int) = ((m - lo).toFloat() / (hi - lo)).coerceIn(0f, 1f) * h
            fun yAt(at: Long) = y(eveningMinutes(at))
            tickHours.forEach { hh -> val yy = y(hh * 60); drawLine(grid, Offset(0f, yy), Offset(w, yy), 1f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f))) }
            // 平时
            drawRoundRect(band, Offset(0f, y(usual.first)), Size(w, y(usual.second) - y(usual.first)), CornerRadius(10.dp.toPx()))
            val bw = (slot * .58f).coerceAtMost(16.dp.toPx()).coerceAtLeast(3f)
            bySlot.forEach { (i, nights) ->
                val x = i * slot + (slot - bw) / 2
                // 依次长出来
                val t = ((reveal.value * (slots + 8) - i) / 8f).coerceIn(0f, 1f)
                val tagged = highlight == null || nights.any { highlight in it.tags }
                val alpha = (if (nights.all { it.confidence == NightConfidence.Rough }) .35f else 1f) * (if (tagged) 1f else 1f - .8f * dim)
                if (weekly) {
                    val top = nights.minOf { eveningMinutes(it.onset!!) }; val bottom = nights.maxOf { eveningMinutes(it.wake!!) }
                    val mTop = median(nights.map { eveningMinutes(it.onset!!) })!!; val mBottom = median(nights.map { eveningMinutes(it.wake!!) })!!
                    drawRoundRect(SleepColor.copy(alpha = .28f * alpha), Offset(x, y(top)), Size(bw, (y(bottom) - y(top)) * t), CornerRadius(bw / 2))
                    drawRoundRect(SleepColor.copy(alpha = alpha), Offset(x + bw * .2f, y(mTop)), Size(bw * .6f, (y(mBottom) - y(mTop)) * t), CornerRadius(bw / 2))
                    val snoreAvg = nights.map { it.snoreMillis }.average()
                    if (snoreAvg > 60_000) drawCircle(SnoreColor.copy(alpha = alpha), (bw * .18f + (snoreAvg / 3_600_000).toFloat() * bw * .2f).coerceAtMost(bw * .45f),
                        Offset(x + bw / 2, y(mBottom) + bw * .8f))
                } else {
                    val p = nights.first()
                    val top = yAt(p.onset!!); val bottom = top + (yAt(p.wake!!) - top) * t
                    val rect = RoundRect(x, top, x + bw, bottom.coerceAtLeast(top + 2f), CornerRadius(bw / 2))
                    val shape = Path().apply { addRoundRect(rect) }
                    clipPath(shape) {
                        drawRect(SleepColor.copy(alpha = alpha), Offset(x, top), Size(bw, bottom - top))
                        details[p.javaDate()]?.events?.forEach { e ->
                            if (e.feedback == app.resound.night.Feedback.No) return@forEach
                            val a = yAt(e.start); val b = maxOf(yAt(e.end), a + 2f)
                            when (e.kind) {
                                NightEventKind.Snore -> drawRect(SnoreColor.copy(alpha = alpha), Offset(x, a), Size(bw, b - a))
                                // 醒来：用底色切开
                                NightEventKind.Awake -> drawRect(bg, Offset(x, a), Size(bw, maxOf(b - a, 3f)))
                                else -> Unit
                            }
                        }
                    }
                }
                if (selected == i) {
                    val top = y(nights.minOf { eveningMinutes(it.onset!!) }); val bottom = y(nights.maxOf { eveningMinutes(it.wake!!) })
                    drawRoundRect(ring, Offset(x - 3.dp.toPx(), top - 3.dp.toPx()), Size(bw + 6.dp.toPx(), bottom - top + 6.dp.toPx()),
                        CornerRadius(bw), style = Stroke(1.5.dp.toPx()))
                }
            }
        }
    }
    XLabels(first, days, weekly, slots)
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
        Key(SleepColor, "睡着"); Key(SnoreColor, "鼾声"); Key(SleepColor.copy(alpha = .3f), "平时 ${eveningClock(usual.first)}–${eveningClock(usual.second)}")
    }
    AnimatedVisibility(selected != null, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
        selected?.let { i -> bySlot[i]?.let { SlotCard(it, weekly) } }
    }
}

@Composable
private fun Key(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(10.dp).height(10.dp).clip(RoundedCornerShape(3.dp)).background(color))
        Text(" $label", style = RsType.caption, color = Rs.colors.textSecondary)
    }
}

private val week = listOf("一", "二", "三", "四", "五", "六", "日")

@Composable
private fun XLabels(first: LocalDate, days: Int, weekly: Boolean, slots: Int) {
    val c = Rs.colors
    BoxWithConstraints(Modifier.fillMaxWidth().padding(start = 34.dp).height(18.dp)) {
        val slot = maxWidth / slots
        (0 until slots).forEach { i ->
            val d = first.plusDays(if (weekly) i * 7L else i.toLong())
            val label = when {
                days <= 7 -> week[d.dayOfWeek.value - 1]
                weekly -> if (i == 0 && first.plusDays(14).monthValue != d.monthValue) null
                    else if (i == 0 || d.monthValue != d.minusDays(7).monthValue) "${d.monthValue}月" else null
                else -> if ((days - 1 - i) % 7 == 0) "${d.monthValue}/${d.dayOfMonth}" else null
            } ?: return@forEach
            val weekend = !weekly && days <= 7 && d.dayOfWeek.value >= 6
            Text(label, style = RsType.caption.copy(fontWeight = if (weekend) FontWeight.SemiBold else FontWeight.Normal),
                color = if (weekend) SnoreColor else c.textTertiary, softWrap = false,
                modifier = Modifier.padding(start = (slot * i + slot / 2 - 10.dp).coerceIn(0.dp, maxWidth - 40.dp)))
        }
    }
}

/** 点中一晚（或一周）后浮出的小卡 */
@Composable
private fun SlotCard(nights: List<NightPoint>, weekly: Boolean) {
    val c = Rs.colors
    val p = nights.first()
    val d = p.javaDate()
    Row(Modifier.padding(top = 10.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.fill)
        .let { if (weekly) it else it.springClick { NightUi.openDate = d } }.padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            if (weekly) {
                val avg = nights.mapNotNull { it.sleepMillis }.average().toLong()
                Text("${d.monthValue}月${d.dayOfMonth}日这一周 · ${nights.size} 晚", style = RsType.subhead, color = c.textSecondary)
                Text("平均睡了 ${hours(avg)} · 鼾声每晚 ${hours(nights.map { it.snoreMillis }.average().toLong())}", style = RsType.body, color = c.text)
            } else {
                Text("${d.monthValue}月${d.dayOfMonth}日 周${week[d.dayOfWeek.value - 1]}早上" + if (p.tags.isNotEmpty()) " · ${p.tags.joinToString("、")}" else "",
                    style = RsType.subhead, color = c.textSecondary)
                Text("${p.onset?.let(::clock) ?: "—"}–${p.wake?.let(::clock) ?: "—"} · 睡了 ${p.sleepMillis?.let(::hours) ?: "—"}" +
                    if (p.snoreMillis >= 60_000) " · 鼾声 ${hours(p.snoreMillis)}" else "", style = RsType.body, color = c.text)
            }
        }
        if (!weekly) Text("›", style = RsType.headline, color = c.textTertiary)
    }
}

/** “侧睡的晚上 vs 其他晚上”：两根短条直接比 */
@Composable
internal fun FindingCard(f: TagFinding) {
    val c = Rs.colors
    val count = f.metric == CompareMetric.Awake
    fun fmt(v: Long) = if (count) "$v 次" else hours(v)
    val max = maxOf(f.withMedian, f.withoutMedian, 1).toFloat()
    val grow = remember(f) { Animatable(0f) }
    LaunchedEffect(f) { grow.animateTo(1f, tween(700, easing = FastOutSlowInEasing)) }
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(16.dp)).background(c.fill).padding(14.dp)) {
        Text("${f.metric.title} · 标了“${f.tag.name}”的晚上", style = RsType.subhead.copy(fontWeight = FontWeight.SemiBold), color = c.text)
        Spacer(Modifier.height(10.dp))
        listOf("${f.tag.name}（${f.withNights} 晚）" to f.withMedian, "其他（${f.withoutNights} 晚）" to f.withoutMedian).forEachIndexed { i, (label, v) ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 3.dp)) {
                Text(label, style = RsType.caption, color = c.textSecondary, modifier = Modifier.width(96.dp), maxLines = 1)
                Box(Modifier.weight(1f).height(12.dp)) {
                    Box(Modifier.fillMaxWidth(v / max * grow.value).height(12.dp).clip(RoundedCornerShape(6.dp))
                        .background(if (i == 0) SnoreColor else SleepColor.copy(alpha = .55f)))
                }
                Text(" " + fmt(v), style = RsType.caption.copy(fontWeight = FontWeight.SemiBold), color = c.text, modifier = Modifier.width(72.dp))
            }
        }
        Text("可能有关，样本还少", style = RsType.caption, color = c.textTertiary, modifier = Modifier.padding(top = 6.dp))
    }
}

/** 周报卡：三个大数字 + 7 晚小图 + 一条发现 + 一条建议；展开看全文 */
@Composable
internal fun WeekCard(r: WeeklyReport) {
    val c = Rs.colors
    var open by remember { mutableStateOf(false) }
    val start = r.weekStart
    val points by remember(start) { AppGraph.nightInsights.trend(start, kotlinx.datetime.LocalDate.fromEpochDays(start.toEpochDays() + 6)) }
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val valid = points.filter { it.sleepMillis != null }
    val first = LocalDate.of(start.year, start.monthNumber, start.dayOfMonth)
    Column(Modifier.fillMaxWidth().padding(vertical = 5.dp).clip(RoundedCornerShape(18.dp)).background(c.fill)
        .springClick { open = !open }.padding(16.dp).animateContentSize()) {
        Text("${first.monthValue}月${first.dayOfMonth}日–${first.plusDays(6).monthValue}月${first.plusDays(6).dayOfMonth}日 · ${r.nights} 晚",
            style = RsType.subhead, color = c.textSecondary)
        if (valid.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    WeekStat("睡眠", hours(valid.mapNotNull { it.sleepMillis }.average().toLong()))
                    valid.mapNotNull { it.onset }.map(::eveningMinutes).takeIf { it.isNotEmpty() }?.let { WeekStat("入睡", eveningClock(it.average().toInt())) }
                    WeekStat("鼾声", hours(valid.map { it.snoreMillis }.average().toLong()))
                }
                MiniWeek(points, first)
            }
        }
        r.finding?.let {
            Spacer(Modifier.height(10.dp))
            Text("发现　$it", style = RsType.body, color = c.text)
        }
        r.suggestion?.let {
            Spacer(Modifier.height(4.dp))
            Text("试试　$it", style = RsType.body, color = SnoreColor)
        }
        if (open) Text(plainText(r.markdown), style = RsType.subhead, color = c.textSecondary, modifier = Modifier.padding(top = 10.dp))
        Text(if (open) "收起" else "看全文", style = RsType.caption, color = c.accent, modifier = Modifier.padding(top = 8.dp))
    }
}

@Composable
private fun WeekStat(label: String, value: String) {
    Column {
        Text(label, style = RsType.caption, color = Rs.colors.textTertiary)
        Text(value, style = RsType.headline, color = Rs.colors.text)
    }
}

/** 7 根小柱：每晚睡了多久 */
@Composable
private fun MiniWeek(points: List<NightPoint>, first: LocalDate) {
    val byDay = points.associateBy { java.time.temporal.ChronoUnit.DAYS.between(first, it.javaDate()).toInt() }
    val track = Rs.colors.separator
    Canvas(Modifier.width(70.dp).height(34.dp)) {
        val slot = size.width / 7; val bw = slot * .55f
        (0 until 7).forEach { i ->
            val v = byDay[i]?.sleepMillis
            // 4～9 小时铺满高度，差几十分钟也看得出
            val hh = if (v == null) 3f else ((v / 3_600_000f - 4f) / 5f).coerceIn(.12f, 1f) * size.height
            drawRoundRect(if (v == null) track else SleepColor, Offset(i * slot + (slot - bw) / 2, size.height - hh), Size(bw, hh), CornerRadius(bw / 2))
        }
    }
}

internal fun plainText(md: String) = md.lines().joinToString("\n") { it.trimStart('#', ' ').replace("**", "").replace("|", " ").trimEnd() }
    .replace(Regex("\n-{3,}.*"), "").trim()
