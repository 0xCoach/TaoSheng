package app.resound.ui.items

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.resound.ui.theme.Rs
import app.resound.ui.theme.RsType
import kotlin.math.roundToInt

/** 刻度上的一件事：列表项 key + 预览文字（“16:27 找昨天开发的宏脚本”） */
data class RailEntry(val key: Any, val label: String)

/** 一个小时：几点、这个小时的事（按显示顺序） */
data class HourSlot(val hour: Int, val entries: List<RailEntry>)

private val MIN_SLOT = 24.dp

/**
 * 右侧时间刻度，定位和滑动合在一条上：
 * - 点一个小时：跳到这个小时的第一件事；
 * - 按住上下拖：在事件之间连续滑动，左边气泡预览当前这件事，对应的小时亮起；
 * - 橙色小滑块随列表滚动，标出现在看到哪。
 * 每个小时占的长度按事件数分配（不少于 24dp，保证好点）。放右侧是因为小米全面屏手势里，左边缘内划是“返回”。
 */
@Composable
fun HourIndex(slots: List<HourSlot>, currentKey: Any?, modifier: Modifier, onJump: (Any) -> Unit) {
    val c = Rs.colors
    val haptic = LocalHapticFeedback.current
    val jump by rememberUpdatedState(onJump)
    val flat = remember(slots) { slots.flatMap { it.entries } }
    val starts = remember(slots) { slots.runningFold(0) { acc, s -> acc + s.entries.size } }
    var touching by remember { mutableIntStateOf(-1) } // 按住时指着的事件序号

    BoxWithConstraints(modifier.fillMaxHeight().width(30.dp)) {
        val density = LocalDensity.current
        val minPx = with(density) { MIN_SLOT.toPx() }
        // 刻度长度跟着内容走：事件少时不拉满整屏，免得一大截空白
        val heightPx = minOf(constraints.maxHeight.toFloat(), maxOf(slots.size * minPx, flat.size * minPx * 0.8f))
        // 各小时的高度：按事件数分，过小的抬到最小值，其余按比例收缩
        val heights = remember(slots, heightPx) {
            val total = flat.size.coerceAtLeast(1).toFloat()
            val raw = slots.map { it.entries.size / total * heightPx }
            val small = raw.count { it < minPx }
            val rest = (heightPx - small * minPx).coerceAtLeast(0f)
            val bigSum = raw.filter { it >= minPx }.sum().coerceAtLeast(1f)
            raw.map { if (it < minPx) minPx else it / bigSum * rest }
        }
        val tops = remember(heights) { heights.runningFold(0f) { a, h -> a + h } }

        fun slotAt(y: Float) = tops.indexOfLast { it <= y }.coerceIn(0, slots.lastIndex)
        fun entryAt(y: Float, snap: Boolean): Int {
            val s = slotAt(y)
            val n = slots[s].entries.size
            val frac = ((y - tops[s]) / heights[s]).coerceIn(0f, 0.999f)
            return starts[s] + if (snap) 0 else (frac * n).toInt()
        }
        fun yOf(index: Int): Float {
            val s = (starts.indexOfLast { it <= index }).coerceIn(0, slots.lastIndex)
            val n = slots[s].entries.size.coerceAtLeast(1)
            return tops[s] + heights[s] * ((index - starts[s] + 0.5f) / n)
        }
        val currentIndex = if (touching >= 0) touching else flat.indexOfFirst { it.key == currentKey }
        val activeSlot = if (currentIndex >= 0) (starts.indexOfLast { it <= currentIndex }).coerceIn(0, slots.lastIndex) else -1

        Box(
            Modifier.height(with(density) { heightPx.toDp() }).width(30.dp).clip(RoundedCornerShape(15.dp))
                .background(if (touching >= 0) c.card else c.card.copy(alpha = 0.72f))
                .pointerInput(slots, heightPx) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        down.consume()
                        // 按下：跳到这个小时的第一件事
                        var i = entryAt(down.position.y, snap = true)
                        touching = i; haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); jump(flat[i].key)
                        var lastSlot = slotAt(down.position.y)
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            if (change.positionChange().y == 0f) continue
                            change.consume()
                            // 拖动：在事件之间连续滑；跨小时轻震一下
                            val j = entryAt(change.position.y, snap = false)
                            val s = slotAt(change.position.y)
                            if (s != lastSlot) { lastSlot = s; haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
                            if (j != i) { i = j; touching = j; jump(flat[j].key) }
                        }
                        touching = -1
                    }
                },
        ) {
            Column(Modifier.fillMaxSize()) {
                slots.forEachIndexed { s, slot ->
                    val on = s == activeSlot
                    val fg by animateColorAsState(if (on) Color.White else c.textSecondary, label = "hourFg")
                    val bg by animateColorAsState(if (on) c.accent else c.accent.copy(alpha = 0f), label = "hourBg")
                    Box(Modifier.height(with(density) { heights[s].toDp() }).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                        Text("${slot.hour}", style = RsType.caption.copy(fontSize = 11.sp, fontWeight = FontWeight.SemiBold), color = fg,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(top = 3.dp).clip(CircleShape).background(bg).widthIn(min = 22.dp).padding(vertical = 2.dp))
                    }
                }
            }
            // 当前位置的小滑块：贴在刻度左缘
            if (currentIndex >= 0) {
                val y by animateDpAsState(with(density) { yOf(currentIndex).toDp() } - 9.dp, label = "thumb")
                Box(Modifier.offset(y = y).padding(start = 2.dp).width(3.dp).height(18.dp).clip(CircleShape).background(c.accent))
            }
        }

        // 按住时的预览气泡：跟着手指所在的事件
        if (touching >= 0) {
            val y = yOf(touching)
            Text(
                flat[touching].label, style = RsType.subhead.copy(fontWeight = FontWeight.SemiBold), color = Color.White,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.align(Alignment.TopEnd).offset { IntOffset(-(38.dp).roundToPx(), (y - 18.dp.toPx()).roundToInt()) }
                    .wrapContentWidth(Alignment.End, unbounded = true).widthIn(max = 260.dp).shadow(8.dp, RoundedCornerShape(14.dp)).clip(RoundedCornerShape(14.dp)).background(c.accent)
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }
    }
}
