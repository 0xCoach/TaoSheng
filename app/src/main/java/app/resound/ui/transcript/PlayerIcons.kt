package app.resound.ui.transcript

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.resound.ui.theme.Rs
import app.resound.ui.theme.RsType
import app.resound.ui.verify.springClick

/* 播放器统一用画出来的图标：播放/暂停、前后 15 秒、下一段、关闭 */

/** 主色实心圆 + 画出来的播放/暂停图标，切换时淡入淡出并轻微缩放 */
@Composable
internal fun PlayCircle(size: androidx.compose.ui.unit.Dp, playing: Boolean, onClick: () -> Unit) {
    val c = Rs.colors
    Box(Modifier.size(size).shadow(if (c.isDark) 0.dp else 6.dp, CircleShape, ambientColor = c.accent, spotColor = c.accent)
        .clip(CircleShape).background(c.accent).springClick(onClick), contentAlignment = Alignment.Center) {
        androidx.compose.animation.Crossfade(playing, label = "playIcon") { on ->
            val icon = size * 0.36f
            androidx.compose.foundation.Canvas(Modifier.size(icon)) {
                val w = this.size.width; val h = this.size.height
                if (on) {
                    // 暂停：两根圆角竖条
                    val bar = w * 0.3f; val r = androidx.compose.ui.geometry.CornerRadius(bar / 2.5f)
                    drawRoundRect(Color.White, topLeft = androidx.compose.ui.geometry.Offset(w * 0.12f, 0f), size = androidx.compose.ui.geometry.Size(bar, h), cornerRadius = r)
                    drawRoundRect(Color.White, topLeft = androidx.compose.ui.geometry.Offset(w * 0.88f - bar, 0f), size = androidx.compose.ui.geometry.Size(bar, h), cornerRadius = r)
                } else {
                    // 播放：圆角三角，略向右偏，视觉上居中
                    val path = androidx.compose.ui.graphics.Path().apply { moveTo(w * 0.2f, 0f); lineTo(w * 1.0f, h / 2); lineTo(w * 0.2f, h); close() }
                    drawPath(path, Color.White, style = androidx.compose.ui.graphics.drawscope.Fill)
                    drawPath(path, Color.White, style = androidx.compose.ui.graphics.drawscope.Stroke(width = w * 0.12f, join = androidx.compose.ui.graphics.StrokeJoin.Round))
                }
            }
        }
    }
}

/** 32dp 浅底圆钮，放一个线条图标 */
@Composable
internal fun IconCircle(bg: Color, onClick: () -> Unit, icon: @Composable () -> Unit) {
    Box(Modifier.size(32.dp).clip(CircleShape).background(bg).springClick(onClick), contentAlignment = Alignment.Center) { icon() }
}

/** 下一段：三角 + 竖条 */
@Composable
internal fun NextIcon(color: Color) = androidx.compose.foundation.Canvas(Modifier.size(12.dp)) {
    val w = size.width; val h = size.height
    val path = androidx.compose.ui.graphics.Path().apply { moveTo(0f, h * 0.1f); lineTo(w * 0.7f, h / 2); lineTo(0f, h * 0.9f); close() }
    drawPath(path, color)
    drawPath(path, color, style = androidx.compose.ui.graphics.drawscope.Stroke(width = w * 0.1f, join = androidx.compose.ui.graphics.StrokeJoin.Round))
    drawRoundRect(color, topLeft = androidx.compose.ui.geometry.Offset(w * 0.8f, h * 0.1f), size = androidx.compose.ui.geometry.Size(w * 0.18f, h * 0.8f),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.09f))
}

/** 关闭：圆头细叉 */
@Composable
internal fun CloseIcon(color: Color) = androidx.compose.foundation.Canvas(Modifier.size(10.dp)) {
    val stroke = 1.8.dp.toPx()
    drawLine(color, androidx.compose.ui.geometry.Offset(0f, 0f), androidx.compose.ui.geometry.Offset(size.width, size.height), stroke, androidx.compose.ui.graphics.StrokeCap.Round)
    drawLine(color, androidx.compose.ui.geometry.Offset(size.width, 0f), androidx.compose.ui.geometry.Offset(0f, size.height), stroke, androidx.compose.ui.graphics.StrokeCap.Round)
}

/** 后退 / 前进 15 秒：浅底圆钮里一个弧形箭头 + 小号“15” */
@Composable
internal fun SkipCircle(back: Boolean, onClick: () -> Unit) {
    val c = Rs.colors
    Box(Modifier.size(40.dp).clip(CircleShape).background(c.accentSoft.copy(alpha = 0.6f)).springClick(onClick), contentAlignment = Alignment.Center) {
        androidx.compose.foundation.Canvas(Modifier.size(24.dp)) {
            val stroke = 1.6.dp.toPx()
            val inset = stroke * 1.5f
            val rect = androidx.compose.ui.geometry.Rect(inset, inset, size.width - inset, size.height - inset)
            // 270° 的圆弧，缺口在上方；箭头在缺口一端
            drawArc(c.accent, startAngle = if (back) -60f else -120f, sweepAngle = if (back) -300f else 300f, useCenter = false,
                topLeft = rect.topLeft, size = rect.size, style = androidx.compose.ui.graphics.drawscope.Stroke(stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round))
            val tipX = if (back) rect.center.x - rect.width * 0.25f else rect.center.x + rect.width * 0.25f
            val tipY = rect.top + rect.height * 0.07f
            val d = size.width * 0.16f
            val dir = if (back) 1f else -1f
            drawLine(c.accent, androidx.compose.ui.geometry.Offset(tipX, tipY), androidx.compose.ui.geometry.Offset(tipX + dir * d, tipY - d * 0.7f), stroke, androidx.compose.ui.graphics.StrokeCap.Round)
            drawLine(c.accent, androidx.compose.ui.geometry.Offset(tipX, tipY), androidx.compose.ui.geometry.Offset(tipX + dir * d, tipY + d * 0.7f), stroke, androidx.compose.ui.graphics.StrokeCap.Round)
        }
        Text("15", style = RsType.caption.copy(fontSize = 9.sp, fontWeight = FontWeight.SemiBold), color = c.accent)
    }
}

/** 收起：圆头细线的向下箭头 */
@Composable
internal fun ChevronDownIcon(color: Color) = androidx.compose.foundation.Canvas(Modifier.size(12.dp)) {
    val stroke = 1.8.dp.toPx()
    val path = androidx.compose.ui.graphics.Path().apply { moveTo(size.width * 0.12f, size.height * 0.32f); lineTo(size.width * 0.5f, size.height * 0.7f); lineTo(size.width * 0.88f, size.height * 0.32f) }
    drawPath(path, color, style = androidx.compose.ui.graphics.drawscope.Stroke(stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
}

/** 回到顶部：圆头细线的向上箭头（竖线 + 尖头） */
@Composable
internal fun ArrowUpIcon(color: Color) = androidx.compose.foundation.Canvas(Modifier.size(16.dp)) {
    val stroke = 2.dp.toPx()
    val cx = size.width / 2
    drawLine(color, androidx.compose.ui.geometry.Offset(cx, size.height * 0.88f), androidx.compose.ui.geometry.Offset(cx, size.height * 0.14f), stroke, androidx.compose.ui.graphics.StrokeCap.Round)
    val path = androidx.compose.ui.graphics.Path().apply { moveTo(size.width * 0.18f, size.height * 0.46f); lineTo(cx, size.height * 0.12f); lineTo(size.width * 0.82f, size.height * 0.46f) }
    drawPath(path, color, style = androidx.compose.ui.graphics.drawscope.Stroke(stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
}
