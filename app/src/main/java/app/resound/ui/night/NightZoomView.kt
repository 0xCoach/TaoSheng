package app.resound.ui.night

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.resound.night.Feedback
import app.resound.night.NightEnvelope
import app.resound.night.NightEvent
import app.resound.night.NightEventKind
import app.resound.night.NightView
import app.resound.night.NightZoom
import app.resound.ui.theme.Rs
import app.resound.ui.theme.RsType
import app.resound.ui.verify.springClick
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId

/** 卡 210：声音详情图正在看的时间段。手势时直接跟手，点按钮/列表跳转时平滑过去。 */
@Stable
internal class NightZoomState(val start: Long, val end: Long, private val scope: CoroutineScope) {
    private val total = (end - start).coerceAtLeast(1).toFloat()
    private val a = Animatable(0f)
    private val b = Animatable(1f)
    val view: NightView get() = NightView(start + (total * a.value).toLong(), start + (total * b.value).toLong())
    val zoomed: Boolean get() = b.value - a.value < .995f
    private fun frac(at: Long) = ((at - start) / total).coerceIn(0f, 1f)
    fun snap(v: NightView) { scope.launch { a.snapTo(frac(v.from)); b.snapTo(frac(v.to)) } }
    fun animate(v: NightView) {
        scope.launch { a.animateTo(frac(v.from), tween(480, easing = FastOutSlowInEasing)) }
        scope.launch { b.animateTo(frac(v.to), tween(480, easing = FastOutSlowInEasing)) }
    }
    fun whole() = animate(NightView(start, end))
    /** 把某件事放到眼前：至少 10 分钟，事件本身占三分之一 */
    fun focus(e: NightEvent) = animate(NightZoom.around((e.start + e.end) / 2, maxOf(NightZoom.MIN_SPAN, (e.end - e.start) * 3), start, end))
}

@Composable
internal fun rememberNightZoom(night: Long, start: Long, end: Long): NightZoomState {
    val scope = rememberCoroutineScope()
    return remember(night, start, end) { NightZoomState(start, end, scope) }
}

/**
 * 卡 210：可放大的声音详情图。
 * 上面一行读数/时间段与“最响”；放大后出现整夜总览条和取景框；下面细看（每根柱最细 5 秒）。
 * 双指缩放、双击逐级放大、单指横拖平移、长按滑动看读数、点圆点回听。
 */
@Composable
internal fun NightSoundDetail(
    night: Long,
    zoom: NightZoomState,
    envelope: NightEnvelope?,
    events: List<NightEvent>,
    selected: Long?,
    onPlay: (NightEvent) -> Unit,
) {
    val c = Rs.colors
    val v = zoom.view
    var probe by remember(night) { mutableStateOf<Long?>(null) }
    var note by remember(night) { mutableStateOf<String?>(null) }
    val loudest = remember(envelope, zoom.start, zoom.end) { envelope?.let { NightZoom.loudest(it, zoom.start, zoom.end) } }
    val playable = events.filter { it.clipId != null && it.audioAvailable && it.feedback != Feedback.No }

    // 第一行：按住时是读数，否则是正在看的时间段；右边“整夜”“最响”
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().height(34.dp)) {
        val p = probe
        val text = if (p != null) readout(p, envelope, events)
            else if (zoom.zoomed) "${clockSec(v.from, v.span)}–${clockSec(v.to, v.span)}" else "整夜"
        Text(text, style = RsType.subhead.copy(fontWeight = if (p != null) FontWeight.SemiBold else FontWeight.Normal),
            color = if (p != null) c.text else c.textSecondary, modifier = Modifier.weight(1f), maxLines = 1)
        AnimatedVisibility(zoom.zoomed && p == null, enter = fadeIn(), exit = fadeOut()) {
            Chip("整夜", strong = false) { note = null; zoom.whole() }
        }
        if (loudest != null && p == null) {
            Spacer(Modifier.width(6.dp))
            Chip("最响 ${clock(loudest.first)}", strong = true) {
                zoom.animate(NightZoom.around(loudest.first, NightZoom.MIN_SPAN, zoom.start, zoom.end))
                val hit = playable.firstOrNull { loudest.first in (it.start - 10_000)..(it.end + 10_000) }
                note = if (hit != null) { onPlay(hit); null } else "这一下比背景响 ${loudest.second} 分贝，没存录音"
            }
        }
    }

    // 放大后才出现的整夜总览条：暗掉取景框以外，点哪跳哪、拖动取景框
    AnimatedVisibility(zoom.zoomed, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
        Box(Modifier.fillMaxWidth().padding(bottom = 8.dp)
            .pointerInput(zoom) {
                detectTapGestures { pt ->
                    val at = zoom.start + (pt.x / size.width * (zoom.end - zoom.start)).toLong()
                    zoom.animate(NightZoom.around(at, zoom.view.span, zoom.start, zoom.end))
                }
            }
            .pointerInput(zoom) {
                detectHorizontalDragGestures { _, dx ->
                    zoom.snap(NightZoom.pan(zoom.view, (dx / size.width * (zoom.end - zoom.start)).toLong(), zoom.start, zoom.end))
                }
            }) {
            NightMap(zoom.start, zoom.end, envelope, events, selected, height = 34.dp, showHours = false, revealKey = night)
            val shade = c.elevated.copy(alpha = .7f)
            val edge = c.accent
            Canvas(Modifier.matchParentSize()) {
                val total = (zoom.end - zoom.start).toFloat()
                val l = (v.from - zoom.start) / total * size.width
                val r = (v.to - zoom.start) / total * size.width
                drawRect(shade, Offset.Zero, Size(l, size.height))
                drawRect(shade, Offset(r, 0f), Size(size.width - r, size.height))
                drawRoundRect(edge, Offset(l, 0f), Size((r - l).coerceAtLeast(3.dp.toPx()), size.height), CornerRadius(6.dp.toPx()), style = Stroke(1.5.dp.toPx()))
            }
        }
    }

    // 细看
    Box(Modifier.fillMaxWidth()) {
        fun at(x: Float, width: Int): Long { val w = zoom.view; return w.from + (x / width.coerceAtLeast(1) * w.span).toLong() }
        NightMap(v.from, v.to, envelope, events, selected, height = 140.dp, revealKey = night, markers = true,
            modifier = Modifier
                .pointerInput(zoom) {
                    // 两指：缩放 + 平移，以两指中点为中心
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        do {
                            val event = awaitPointerEvent()
                            if (event.changes.count { it.pressed } >= 2) {
                                val factor = event.calculateZoom()
                                val pan = event.calculatePan()
                                val centroid = event.calculateCentroid(useCurrent = true)
                                if (factor != 1f || pan.x != 0f) {
                                    var next = NightZoom.zoom(zoom.view, centroid.x / size.width, factor, zoom.start, zoom.end)
                                    next = NightZoom.pan(next, (-pan.x / size.width * next.span).toLong(), zoom.start, zoom.end)
                                    zoom.snap(next); note = null
                                    event.changes.forEach { it.consume() }
                                }
                            }
                        } while (event.changes.any { it.pressed })
                    }
                }
                .pointerInput(zoom) {
                    // 单指横拖：放大后平移；整夜时不拦，免得和面板滑动打架
                    detectHorizontalDragGestures { change, dx ->
                        if (!zoom.zoomed) return@detectHorizontalDragGestures
                        change.consume()
                        zoom.snap(NightZoom.pan(zoom.view, (-dx / size.width * zoom.view.span).toLong(), zoom.start, zoom.end))
                    }
                }
                .pointerInput(zoom, events) {
                    detectTapGestures(
                        onDoubleTap = { pt -> note = null; zoom.animate(NightZoom.nextLevel(zoom.view, at(pt.x, size.width), zoom.start, zoom.end)) },
                        onTap = { pt -> nearestPlayable(playable, at(pt.x, size.width), zoom.view.span)?.let { note = null; onPlay(it) } },
                    )
                }
                .pointerInput(zoom) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = { pt -> probe = at(pt.x, size.width) },
                        onDrag = { change, _ -> change.consume(); probe = at(change.position.x.coerceIn(0f, size.width.toFloat()), size.width) },
                        onDragEnd = { probe = null }, onDragCancel = { probe = null },
                    )
                },
        )
        // 读数竖线
        val line = c.text
        Canvas(Modifier.matchParentSize()) {
            val p = probe ?: return@Canvas
            val x = (p - v.from).toFloat() / v.span.coerceAtLeast(1) * size.width
            drawLine(line.copy(alpha = .7f), Offset(x, 0f), Offset(x, size.height - 18.dp.toPx()), 1.5.dp.toPx())
            drawCircle(line, 3.5.dp.toPx(), Offset(x, 4.dp.toPx()))
        }
    }
    note?.let { Text(it, style = RsType.caption, color = c.textTertiary, modifier = Modifier.padding(top = 4.dp)) }
}

/** 03:12:40 · 比背景响 23 分贝 · 鼾声 */
private fun readout(at: Long, envelope: NightEnvelope?, events: List<NightEvent>): String {
    val t = Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault())
    val time = "%02d:%02d:%02d".format(t.hour, t.minute, t.second)
    val db = envelope?.let { NightZoom.aboveBackground(it, at) }
    val kinds = setOf(NightEventKind.Snore, NightEventKind.Cough, NightEventKind.SleepTalk, NightEventKind.SnoreBreak, NightEventKind.Loud, NightEventKind.Awake, NightEventKind.Gap)
    val what = events.firstOrNull { it.kind in kinds && it.feedback != Feedback.No && at in (it.start - 2_000)..(it.end + 2_000) }
    return listOfNotNull(time, db?.let { if (it <= 2) "和背景差不多" else "比背景响 $it 分贝" }, what?.let { kindTitle(it, false) }).joinToString(" · ")
}

/** 放大到 1 小时以内时带秒 */
private fun clockSec(at: Long, span: Long): String {
    if (span > 60 * 60_000L) return clock(at)
    val t = Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault())
    return "%02d:%02d:%02d".format(t.hour, t.minute, t.second)
}

@Composable
private fun Chip(text: String, strong: Boolean, onClick: () -> Unit) {
    val c = Rs.colors
    Text(text, style = RsType.caption.copy(fontWeight = FontWeight.SemiBold), color = if (strong) c.onAccent else c.accent,
        modifier = Modifier.clip(CircleShape).springClick(onClick).background(if (strong) c.accent else c.accentSoft)
            .padding(horizontal = 12.dp, vertical = 7.dp))
}
