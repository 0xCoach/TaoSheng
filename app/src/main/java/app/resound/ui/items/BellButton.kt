package app.resound.ui.items

import android.widget.Toast
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import app.resound.ui.theme.Rs
import app.resound.ui.verify.springClick

/**
 * 待办行右边的小铃铛：橙色实心 = 强提醒（到点弹出、响铃、震动，响到你回应），灰色空心 = 普通提醒。
 * 打开时铃铛左右晃一下，像真的响了。
 */
@Composable
internal fun BellButton(on: Boolean, onChange: (Boolean) -> Unit) {
    val c = Rs.colors
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val bg by animateColorAsState(if (on) c.accentSoft else Color.Transparent, spring(stiffness = Spring.StiffnessMediumLow), label = "bellBg")
    val tint by animateColorAsState(if (on) c.accent else c.textTertiary, label = "bellTint")
    val swing = remember { Animatable(0f) }
    // 第一次显示不晃，只在用户刚打开时晃
    val first = remember { booleanArrayOf(true) }
    LaunchedEffect(on) {
        if (first[0]) { first[0] = false; return@LaunchedEffect }
        if (on) for (angle in listOf(-18f, 14f, -9f, 5f, 0f)) swing.animateTo(angle, spring(dampingRatio = 0.5f, stiffness = 900f))
    }
    Box(
        Modifier.size(34.dp).clip(CircleShape).background(bg).springClick {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            onChange(!on)
            Toast.makeText(context, if (on) "已改为普通提醒" else "已设为强提醒：到点响铃震动，直到你回应", Toast.LENGTH_SHORT).show()
        },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(18.dp).rotate(swing.value)) { bell(tint, filled = on) }
    }
}

/** 铃铛：圆顶 + 喇叭口 + 底边 + 铃舌 */
private fun DrawScope.bell(color: Color, filled: Boolean) {
    val w = size.width
    val stroke = w * 0.1f
    val body = Path().apply {
        moveTo(w * 0.5f, w * 0.1f)
        cubicTo(w * 0.24f, w * 0.1f, w * 0.2f, w * 0.34f, w * 0.2f, w * 0.52f)
        lineTo(w * 0.2f, w * 0.64f)
        lineTo(w * 0.1f, w * 0.76f)
        lineTo(w * 0.9f, w * 0.76f)
        lineTo(w * 0.8f, w * 0.64f)
        lineTo(w * 0.8f, w * 0.52f)
        cubicTo(w * 0.8f, w * 0.34f, w * 0.76f, w * 0.1f, w * 0.5f, w * 0.1f)
        close()
    }
    drawPath(body, color, style = if (filled) Fill else Stroke(stroke, join = androidx.compose.ui.graphics.StrokeJoin.Round))
    drawLine(color, Offset(w * 0.5f, w * 0.02f), Offset(w * 0.5f, w * 0.1f), stroke, StrokeCap.Round)
    drawCircle(color, w * 0.09f, Offset(w * 0.5f, w * 0.88f))
}
