package app.resound.ui.verify

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameMillis
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.first
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.resound.ui.theme.Rs
import app.resound.ui.theme.RsType
import kotlin.math.log10
import kotlin.math.pow

/** 白色圆角卡片，淡阴影分层。 */
@Composable
fun RsCard(
    modifier: Modifier = Modifier,
    padding: PaddingValues = PaddingValues(18.dp),
    /** 卡片底色；默认白卡 */
    color: Color? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = Rs.colors
    Column(
        modifier
            .fillMaxWidth()
            .shadow(if (c.isDark) 0.dp else 10.dp, RoundedCornerShape(22.dp), ambientColor = c.shadow, spotColor = c.shadow)
            .clip(RoundedCornerShape(22.dp))
            .background(color ?: c.card)
            .padding(padding),
        content = content,
    )
}

/** 点击时弹性缩小的可点区域。 */
@Composable
fun Modifier.springClick(onClick: () -> Unit): Modifier {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val s by animateFloatAsState(
        if (pressed) 0.96f else 1f,
        spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "press",
    )
    return this
        .scale(s)
        .clickable(interactionSource = source, indication = null, onClick = onClick)
}

/** 胶囊按钮；filled 为暖橙实心，否则为浅色底。 */
@Composable
fun Pill(text: String, filled: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val c = Rs.colors
    Box(
        modifier
            .springClick(onClick)
            .clip(CircleShape)
            .background(if (filled) c.accent else c.accentSoft)
            .padding(horizontal = 16.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = RsType.subhead, color = if (filled) Color.White else c.accent)
    }
}

/** 小圆点状态标记。 */
@Composable
fun Dot(color: Color, size: Dp = 8.dp) {
    Box(Modifier.size(size).clip(CircleShape).background(color))
}

/**
 * 实时声波：每根柱子是一小段时间里真实收到的音量（按分贝换算），从右往左滚动，最右边是此刻。
 * 停止或暂停时柱子慢慢落平。
 */
@Composable
fun BreathingWave(level: Float, active: Boolean, recording: Boolean, modifier: Modifier = Modifier, tint: Color? = null) {
    val c = Rs.colors
    val bars = 44
    val stepMs = 60L
    val currentLevel by rememberUpdatedState(level)
    val history = remember { FloatArray(bars) }
    var head by remember { mutableIntStateOf(0) }
    var lastPush by remember { mutableLongStateOf(0L) }
    var frameTime by remember { mutableLongStateOf(0L) }
    val currentRecording by rememberUpdatedState(recording)
    // 只在检测到人声（正在录音）时滚动；说完再滚约 2 秒让柱子退出画面，然后静止、不再刷新。
    // 风扇、空调、键盘等非人声不会让波形动，省电；波形动 = 回响正在录你的话。
    LaunchedEffect(Unit) {
        while (true) {
            snapshotFlow { currentRecording }.first { it }
            lastPush = 0L
            var tailUntil = Long.MAX_VALUE
            while (true) {
                val keepGoing = withFrameMillis { now ->
                    frameTime = now
                    if (currentRecording) tailUntil = Long.MAX_VALUE
                    else if (tailUntil == Long.MAX_VALUE) tailUntil = now + 2_000
                    if (now - lastPush >= stepMs) {
                        history[head] = if (currentRecording) loudness(currentLevel) else 0f
                        head = (head + 1) % bars
                        lastPush = now
                    }
                    now < tailUntil
                }
                if (!keepGoing) break
            }
        }
    }
    val color by animateColorAsState(
        when {
            recording -> tint ?: c.accent
            active -> (tint ?: c.accent).copy(alpha = 0.75f)
            else -> tint?.copy(alpha = 0.5f) ?: c.textTertiary
        },
        label = "waveColor",
    )
    Canvas(modifier) {
        // 两次取样之间平滑地向左移动，滚动不跳格
        val shift = ((frameTime - lastPush).toFloat() / stepMs).coerceIn(0f, 1f)
        val gap = size.width / (bars - 1)
        val w = gap * 0.46f
        for (i in 0 until bars) {
            val v = history[(head + i) % bars]
            val x = (i - shift) * gap
            if (x < -w) continue
            val edgeFade = (minOf(x, size.width - x) / (gap * 4)).coerceIn(0.15f, 1f)
            val h = (size.height * (0.06f + 0.94f * v)).coerceAtLeast(w)
            drawRoundRect(
                color = color.copy(alpha = color.alpha * (0.35f + 0.65f * edgeFade)),
                topLeft = Offset(x - w / 2, (size.height - h) / 2),
                size = Size(w, h),
                cornerRadius = CornerRadius(w / 2, w / 2),
            )
        }
    }
}

/** 原始音量换成柱高 0..1：-60 dB 以下当安静，0 dB 满格，再压一下曲线，正常说话（约 -25 dB）不到一半 */
private fun loudness(level: Float): Float =
    if (level <= 0f) 0f else ((20 * log10(level) + 60f) / 60f).coerceIn(0f, 1f).pow(1.4f)

/** 一组带标题的分节。 */
@Composable
fun Section(title: String, modifier: Modifier = Modifier, trailing: (@Composable () -> Unit)? = null, content: @Composable () -> Unit) {
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = RsType.section, color = Rs.colors.textSecondary, modifier = Modifier.weight(1f))
            trailing?.invoke()
        }
        content()
    }
}
