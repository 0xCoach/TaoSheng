package app.resound.ui.today

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.resound.ui.theme.Rs
import app.resound.ui.theme.RsType
import app.resound.ui.verify.springClick
import kotlin.math.roundToInt

/**
 * 首页时间线的多选：长按进入，点一条勾选/取消，长按另一条 = 选到这条；
 * 滑动停下后在最后一条完整露出的事件旁浮出“选到这儿”，点它把起点到这一条之间全部选上。
 * 起点 = 最近一次勾上的那条，所以中间单独取消的不会被重新勾上（除非它就在新的范围里）。
 */
@Stable
internal class TimelineSelection {
    var active by mutableStateOf(false); private set
    var ids by mutableStateOf<Set<Long>>(emptySet()); private set
    var anchor by mutableStateOf<Long?>(null); private set
    /** 每次勾选变化 +1；“选到这儿”只在这之后又滑动过才出现 */
    var version by mutableIntStateOf(0); private set
    /** 正在显示的事件行在屏幕上的位置（离开屏幕的行会被移除） */
    val bounds = mutableStateMapOf<Long, Rect>()

    fun start(id: Long) { active = true; ids = setOf(id); anchor = id; version++ }
    fun exit() { active = false; ids = emptySet(); anchor = null; version++ }

    fun toggle(id: Long, order: List<Long>) {
        if (id in ids) {
            ids = ids - id
            // 取消的正好是起点：起点退到离它最近的已选那条
            if (anchor == id) anchor = order.indexOf(id).let { at -> ids.minByOrNull { kotlin.math.abs(order.indexOf(it) - at) } }
        } else { ids = ids + id; anchor = id }
        version++
    }

    fun rangeTo(id: Long, order: List<Long>) {
        val from = order.indexOf(anchor ?: id).takeIf { it >= 0 } ?: order.indexOf(id)
        val to = order.indexOf(id)
        if (to < 0) return
        ids = ids + order.subList(minOf(from, to), maxOf(from, to) + 1)
        anchor = id; version++
    }

    /** 把这些都加进已选（全选当前筛选出来的），已选的其他保留 */
    fun addAll(more: List<Long>) { ids = ids + more; anchor = more.lastOrNull() ?: anchor; version++ }
    fun removeAll(less: List<Long>) { ids = ids - less.toSet(); if (anchor in less) anchor = ids.lastOrNull(); version++ }

    fun setAll(order: List<Long>, on: Boolean) { ids = if (on) order.toSet() else emptySet(); anchor = if (on) order.lastOrNull() else null; version++ }

    /** 事件被删掉或换了一天后，去掉已不存在的 id */
    fun retain(order: List<Long>) {
        val keep = ids.filter { it in order }.toSet()
        if (keep != ids) { ids = keep; if (anchor !in order) anchor = keep.lastOrNull() }
    }

    /**
     * “选到这儿”指向哪一条：起点在屏幕上方（往下滑过）取最下面一条完整露出的，起点在下方（往上滑过）取最上面一条；
     * 起点到它之间全都已选上时不提示。
     */
    fun reachTarget(order: List<Long>, visibleTop: Float, visibleBottom: Float): Long? {
        val start = order.indexOf(anchor ?: return null).takeIf { it >= 0 } ?: return null
        val visible = order.withIndex().filter { (_, id) -> bounds[id]?.let { it.top >= visibleTop && it.bottom <= visibleBottom } == true }
        if (visible.isEmpty()) return null
        val target = when {
            visible.last().index > start -> visible.last()
            visible.first().index < start -> visible.first()
            else -> return null
        }
        val span = order.subList(minOf(start, target.index), maxOf(start, target.index) + 1)
        return target.value.takeIf { span.any { it !in ids } }
    }
}

/** 多选时每行左侧的勾选圈 */
@Composable
internal fun SelectCircle(selected: Boolean) {
    val c = Rs.colors
    val bg by animateColorAsState(if (selected) c.accent else Color.Transparent, label = "selBg")
    val pop by animateFloatAsState(if (selected) 1f else 0.92f, spring(dampingRatio = 0.45f, stiffness = Spring.StiffnessMedium), label = "selPop")
    Box(
        Modifier.size(22.dp).scale(pop).clip(CircleShape).background(bg)
            .then(if (selected) Modifier else Modifier.border(1.5.dp, c.textTertiary, CircleShape)),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) androidx.compose.material3.Text("✓", style = RsType.caption.copy(fontWeight = FontWeight.Bold), color = Color.White)
    }
}

/** 记下一行在屏幕上的位置，供“选到这儿”判断；行离开屏幕时移除 */
@Composable
internal fun Modifier.trackRow(selection: TimelineSelection, id: Long): Modifier {
    androidx.compose.runtime.DisposableEffect(id) { onDispose { selection.bounds.remove(id) } }
    return onGloballyPositioned { selection.bounds[id] = it.boundsInRoot() }
}

@Composable
private fun BarText(text: String, color: Color, onClick: () -> Unit) =
    androidx.compose.material3.Text(text, style = RsType.body.copy(fontWeight = FontWeight.Medium), color = color,
        modifier = Modifier.clip(RoundedCornerShape(16.dp)).springClick(onClick).padding(horizontal = 14.dp, vertical = 10.dp))

/** 底部两行面板：上行 ✕ · 已选 N 件 · 全选；下行 重点 / 复制 / 删除 N。都在拇指够得着的地方 */
@Composable
internal fun BoxScope.SelectBottomBar(selection: TimelineSelection, order: List<Long>, allStarred: Boolean, bottomPadding: Dp,
                                      onTop: (Float) -> Unit, onStar: () -> Unit, onCopy: () -> Unit, onDelete: () -> Unit) {
    val c = Rs.colors
    AnimatedVisibility(selection.active, Modifier.align(Alignment.BottomCenter),
        enter = slideInVertically { it } + fadeIn(), exit = slideOutVertically { it } + fadeOut()) {
        val n = selection.ids.size
        // 全选只针对当前筛选出来的；换筛选时已选的保留
        val all = order.isNotEmpty() && order.all { it in selection.ids }
        androidx.compose.foundation.layout.Column(
            Modifier.padding(start = 16.dp, end = 16.dp, bottom = bottomPadding + 12.dp).fillMaxWidth()
                .shadow(if (c.isDark) 0.dp else 16.dp, RoundedCornerShape(26.dp), ambientColor = c.shadow, spotColor = c.shadow)
                .clip(RoundedCornerShape(26.dp)).background(c.card).onGloballyPositioned { onTop(it.boundsInRoot().top) }.padding(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BarText("✕", c.textSecondary) { selection.exit() }
                androidx.compose.material3.Text(if (n == 0) "选择要处理的事" else "已选 $n 件", style = RsType.body.copy(fontWeight = FontWeight.SemiBold),
                    color = c.text, maxLines = 1, modifier = Modifier.weight(1f))
                BarText(if (all) "全不选" else "全选", c.accent) {
                    if (all) selection.removeAll(order) else selection.addAll(order)
                }
            }
            Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                BarChip(if (allStarred) "取消重点" else "★ 重点", c.accentSoft, c.accent, n > 0, Modifier.weight(1f), onStar)
                BarChip("复制", c.background, c.text, n > 0, Modifier.weight(1f), onCopy)
                BarChip(if (n > 0) "删除 $n 件" else "删除", c.critical, Color.White, n > 0, Modifier.weight(1.2f), onDelete)
            }
        }
    }
}

@Composable
private fun BarChip(text: String, bg: Color, fg: Color, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val alpha by animateFloatAsState(if (enabled) 1f else 0.4f, label = "chipAlpha")
    Box(modifier.clip(CircleShape).background(bg.copy(alpha = bg.alpha * alpha)).springClick { if (enabled) onClick() }.padding(vertical = 13.dp),
        contentAlignment = Alignment.Center) {
        androidx.compose.material3.Text(text, style = RsType.subhead.copy(fontWeight = FontWeight.SemiBold), color = fg.copy(alpha = alpha), maxLines = 1)
    }
}

/** “选到这儿”胶囊：贴在目标行右侧，箭头指明方向（往下滑 ↓，往上滑 ↑） */
@Composable
internal fun BoxScope.ReachHereChip(target: Long?, selection: TimelineSelection, originY: Float, upward: Boolean, onReach: (Long) -> Unit) {
    val c = Rs.colors
    // 胶囊消失动画期间仍用最后一个目标的位置
    var last by androidx.compose.runtime.remember { mutableStateOf<Long?>(null) }
    if (target != null) last = target
    val rect = last?.let { selection.bounds[it] }
    var chipHeight by androidx.compose.runtime.remember { mutableIntStateOf(0) }
    AnimatedVisibility(target != null && rect != null,
        Modifier.align(Alignment.TopEnd).offset { IntOffset(0, ((rect?.center?.y ?: 0f) - originY - chipHeight / 2f).roundToInt()) }
            .padding(end = 30.dp),
        enter = scaleIn(spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMediumLow), initialScale = 0.6f) + fadeIn(),
        exit = scaleOut(targetScale = 0.8f) + fadeOut()) {
        Row(
            Modifier.onSizeChanged { chipHeight = it.height }
                .shadow(if (c.isDark) 0.dp else 10.dp, CircleShape, ambientColor = c.accent, spotColor = c.accent)
                .clip(CircleShape).background(c.accent).springClick { last?.let(onReach) }
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            androidx.compose.material3.Text(if (upward) "↑ 选到这儿" else "↓ 选到这儿",
                style = RsType.subhead.copy(fontWeight = FontWeight.SemiBold), color = Color.White)
        }
    }
}

/** 批量删除确认：删除过程中显示进度，删完自动关闭 */
@Composable
internal fun BatchDeleteSheet(count: Int, progress: Int?, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val forget = app.resound.AppGraph.isPrivacyReady
    app.resound.ui.verify.RsSheet("删除 $count 件事？",
        if (forget) "会把这些事的录音、原话和提取出的内容一起删掉，删除后无法恢复。" else "会从时间线里删掉这些事，原话还在原话页里。",
        onDismiss = { if (progress == null) onDismiss() }) {
        if (progress != null) {
            androidx.compose.material3.LinearProgressIndicator(
                progress = { progress / count.coerceAtLeast(1).toFloat() }, color = Rs.colors.critical, trackColor = Rs.colors.critical.copy(alpha = 0.12f),
                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp).clip(CircleShape))
            androidx.compose.material3.Text("正在删除 $progress / $count", style = RsType.subhead, color = Rs.colors.textSecondary)
        } else {
            app.resound.ui.verify.ActionButton("删除 $count 件", app.resound.ui.verify.ActionKind.Danger) { onConfirm() }
            app.resound.ui.verify.ActionButton("取消", app.resound.ui.verify.ActionKind.Secondary) { onDismiss() }
        }
    }
}
