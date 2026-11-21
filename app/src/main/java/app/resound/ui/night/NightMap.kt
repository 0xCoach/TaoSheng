package app.resound.ui.night

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.resound.night.NightEnvelope
import app.resound.night.NightEvent
import app.resound.night.NightEventKind
import app.resound.ui.theme.Rs
import app.resound.ui.theme.RsType
import java.time.Instant
import java.time.ZoneId

/**
 * 整夜声音地图：上面是响度（每 5 秒一根，按像素列取最大），淡色线是背景；
 * 下面一条彩色事件带（鼾声、咳嗽、梦话、醒来、没有记录），最下面是整点刻度。
 * 点地图上的位置，回调离得最近的、带片段的事件（前后 3 分钟内）。
 */
@Composable
internal fun NightMap(
    from: Long,
    to: Long,
    envelope: NightEnvelope?,
    events: List<NightEvent>,
    selected: Long?,
    modifier: Modifier = Modifier,
    height: Dp = 112.dp,
    showHours: Boolean = true,
    onPick: ((NightEvent) -> Unit)? = null,
    /** 卡 210：开场动画只在换一夜时放；缩放平移不重放 */
    revealKey: Any? = null,
    /** 卡 210：有录音能听的位置在图顶上标小圆点 */
    markers: Boolean = false,
) {
    val c = Rs.colors
    val total = (to - from).coerceAtLeast(60_000)
    val key = revealKey ?: (from to to)
    val reveal = remember(key) { Animatable(0f) }
    LaunchedEffect(key) { reveal.animateTo(1f, tween(700)) }
    val bandKinds = setOf(NightEventKind.Snore, NightEventKind.Cough, NightEventKind.SleepTalk, NightEventKind.Awake, NightEventKind.Gap, NightEventKind.SnoreBreak)
    val bands = events.filter { it.kind in bandKinds && it.feedback != app.resound.night.Feedback.No }
    val loudOnes = events.filter { it.kind == NightEventKind.Loud }
    val colors = (bands + loudOnes).associate { it.id to kindColor(it.kind) }
    val kindColors = NightEventKind.entries.associateWith { kindColor(it) }
    // 响度柱：落在鼾声/咳嗽/梦话里的用那种颜色，其余是中性灰
    val sounding = loudOnes + bands.filter { it.kind == NightEventKind.Snore || it.kind == NightEventKind.Cough || it.kind == NightEventKind.SleepTalk }
    val bar = c.textTertiary.copy(alpha = .35f)
    val bg = c.textTertiary.copy(alpha = .5f)
    val track = c.fill
    val mark = c.text
    Column(modifier) {
        Canvas(
            Modifier.fillMaxWidth().height(height).let { m ->
                if (onPick == null) m else m.pointerInput(events, from, to) {
                    detectTapGestures { p ->
                        val at = from + (p.x / size.width * total).toLong()
                        nearestPlayable(events, at, total)?.let(onPick)
                    }
                }
            },
        ) {
            val w = size.width; val h = size.height
            val plotH = h - 18.dp.toPx()
            val bandY = plotH + 6.dp.toPx(); val bandH = 8.dp.toPx()
            fun x(at: Long) = ((at - from).toFloat() / total * w).coerceIn(0f, w)
            drawRoundRect(track, Offset(0f, bandY), Size(w, bandH), CornerRadius(bandH / 2))
            if (envelope != null && envelope.peakDb.isNotEmpty()) {
                val n = envelope.peakDb.size
                val floor = (envelope.backgroundDb.sorted().let { it.getOrNull(it.size / 2)?.toInt() } ?: -70).coerceAtLeast(-90) - 2
                val ceil = maxOf((envelope.peakDb.maxOrNull()?.toInt() ?: -20), floor + 20)
                fun y(db: Int) = plotH - ((db - floor).toFloat() / (ceil - floor)).coerceIn(0f, 1f) * plotH * reveal.value
                val cols = w.toInt().coerceAtLeast(1)
                val step = 2.dp.toPx()
                var px = 0f
                while (px < cols) {
                    val a = from + (px / w * total).toLong(); val b = from + ((px + step) / w * total).toLong()
                    val i0 = ((a - envelope.start) / envelope.stepMillis).toInt().coerceIn(0, n - 1)
                    val i1 = ((b - envelope.start) / envelope.stepMillis).toInt().coerceIn(i0, n - 1)
                    if (b > envelope.start && a < envelope.start + n * envelope.stepMillis) {
                        var peak = Int.MIN_VALUE; for (i in i0..i1) peak = maxOf(peak, envelope.peakDb[i].toInt())
                        val top = y(peak)
                        val hit = sounding.firstOrNull { a <= it.end + 5_000 && b >= it.start - 5_000 }
                        val color = hit?.let { colors[it.id]?.copy(alpha = .8f) } ?: bar
                        drawRoundRect(color, Offset(px, top), Size(step * .7f, (plotH - top).coerceAtLeast(1.dp.toPx())), CornerRadius(step / 3))
                    }
                    px += step
                }
                // 背景线
                var last: Offset? = null
                val v0 = ((from - envelope.start) / envelope.stepMillis).toInt().coerceIn(0, n - 1)
                val v1 = ((to - envelope.start) / envelope.stepMillis + 1).toInt().coerceIn(v0, n - 1)
                for (i in v0..v1 step maxOf(1, (v1 - v0) / 240)) {
                    val p = Offset(x(envelope.start + i * envelope.stepMillis), y(envelope.backgroundDb[i].toInt()))
                    last?.let { drawLine(bg, it, p, 1.dp.toPx()) }; last = p
                }
            }
            for (e in bands) {
                if (e.end < from || e.start > to) continue
                val color = colors[e.id] ?: continue
                val a = x(e.start); val b = maxOf(x(e.end), a + 3.dp.toPx())
                drawRoundRect(color, Offset(a, bandY), Size(b - a, bandH), CornerRadius(bandH / 2))
                if (e.id == selected) drawRoundRect(mark, Offset(a - 2.dp.toPx(), bandY - 2.dp.toPx()), Size(b - a + 4.dp.toPx(), bandH + 4.dp.toPx()),
                    CornerRadius(bandH), style = Stroke(1.5.dp.toPx()))
            }
            if (markers) events.filter { it.clipId != null && it.audioAvailable && it.feedback != app.resound.night.Feedback.No && it.end >= from && it.start <= to }.forEach { e ->
                val cx = (x(e.start) + x(maxOf(e.end, e.start + 1))) / 2
                drawCircle(kindColors.getValue(e.kind).copy(alpha = if (e.id == selected) 1f else .75f), (if (e.id == selected) 4.5f else 3.5f) * density, Offset(cx, 5.dp.toPx()))
            }
            selected?.let { id -> (bands + events.filter { it.kind == NightEventKind.Loud }).firstOrNull { it.id == id }?.let { e ->
                val mid = (x(e.start) + x(e.end)) / 2
                drawLine(mark.copy(alpha = .6f), Offset(mid, 0f), Offset(mid, plotH), 1.dp.toPx())
            } }
        }
        if (showHours) HourTicks(from, to)
    }
}

/** 卡 210：点图时找离得最近、带录音的事件；放大后容差跟着缩小 */
internal fun nearestPlayable(events: List<NightEvent>, at: Long, span: Long): NightEvent? {
    val tolerance = (span / 40).coerceIn(5_000L, 180_000L)
    return events.filter { it.clipId != null && it.audioAvailable }
        .minByOrNull { if (at in it.start..it.end) 0 else minOf(kotlin.math.abs(it.start - at), kotlin.math.abs(it.end - at)) }
        ?.takeIf { at in (it.start - tolerance)..(it.end + tolerance) }
}

/** 刻度随缩放变：整夜按小时，放大后 30/15/5/1 分钟 */
@Composable
private fun HourTicks(from: Long, to: Long) {
    val c = Rs.colors
    val zone = ZoneId.systemDefault()
    val offset = zone.rules.getOffset(Instant.ofEpochMilli(from)).totalSeconds * 1000L
    val ticks = app.resound.night.NightZoom.ticks(app.resound.night.NightView(from, to), offset)
    val step = app.resound.night.NightZoom.tickStep(to - from)
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth().height(16.dp)) {
        val w = maxWidth
        ticks.forEach { at ->
            val t = Instant.ofEpochMilli(at).atZone(zone)
            val f = (at - from).toFloat() / (to - from)
            Text(if (step >= 3_600_000L) "${t.hour}点" else "%d:%02d".format(t.hour, t.minute), style = RsType.caption, color = c.textTertiary,
                modifier = Modifier.padding(start = (w * f - 12.dp).coerceIn(0.dp, (w - 30.dp).coerceAtLeast(0.dp))))
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun MapLegend(events: List<NightEvent>, sharedRoom: Boolean) {
    val c = Rs.colors
    val kinds = listOf(NightEventKind.Snore, NightEventKind.Cough, NightEventKind.SleepTalk, NightEventKind.SnoreBreak, NightEventKind.Loud, NightEventKind.Awake, NightEventKind.Gap)
        .filter { k -> events.any { it.kind == k && it.feedback != app.resound.night.Feedback.No } }
    if (kinds.isEmpty()) return
    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(4.dp)) {
        kinds.forEach { k ->
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                app.resound.ui.verify.Dot(kindColor(k), 7.dp)
                Text(" " + when (k) {
                    NightEventKind.Snore -> "鼾声"; NightEventKind.Cough -> "咳嗽"; NightEventKind.SleepTalk -> "梦话"
                    NightEventKind.SnoreBreak -> "中断"; NightEventKind.Loud -> "最响"; NightEventKind.Awake -> "醒了"; else -> "没记录"
                }, style = RsType.caption, color = c.textSecondary)
            }
        }
    }
}

