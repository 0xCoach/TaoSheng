package app.resound.ui.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 设置页的线条小图标（24×24 坐标、2 粗线条，风格同 Lucide）。
 * 路径数据是纯字符串，以后 iOS 可以照搬到 SwiftUI Path。
 */
enum class SettingsGlyph(val path: String) {
    Mic("M12 3a3 3 0 0 0-3 3v6a3 3 0 0 0 6 0V6a3 3 0 0 0-3-3z M19 11a7 7 0 0 1-14 0 M12 18v3"),
    Device("M9 3h6a3 3 0 0 1 3 3v12a3 3 0 0 1-3 3H9a3 3 0 0 1-3-3V6a3 3 0 0 1 3-3z M10 7h4"),
    Lines("M4 6h16 M4 12h16 M4 18h10"),
    People("M12.5 8a3.5 3.5 0 1 1-7 0a3.5 3.5 0 1 1 7 0z M2.5 20a6.5 6.5 0 0 1 13 0 M16 4.5a3.5 3.5 0 0 1 0 7 M21.5 20a6 6 0 0 0-4-5.7"),
    Wave("M3 12h2 M7 8v8 M11 5v14 M15 9v6 M19 7v10"),
    Pin("M12 21s-7-6.2-7-11.5a7 7 0 0 1 14 0C19 14.8 12 21 12 21z M14.5 9.5a2.5 2.5 0 1 1-5 0a2.5 2.5 0 1 1 5 0z"),
    Sparkle("M12 3l1.8 4.2L18 9l-4.2 1.8L12 15l-1.8-4.2L6 9l4.2-1.8z M18 15l.9 2.1L21 18l-2.1.9L18 21l-.9-2.1L15 18l2.1-.9z"),
    Grid("M6 4h3a2 2 0 0 1 2 2v3a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2z M15 4h3a2 2 0 0 1 2 2v3a2 2 0 0 1-2 2h-3a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2z M6 13h3a2 2 0 0 1 2 2v3a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2v-3a2 2 0 0 1 2-2z M15 13h3a2 2 0 0 1 2 2v3a2 2 0 0 1-2 2h-3a2 2 0 0 1-2-2v-3a2 2 0 0 1 2-2z"),
    Send("M21 3L10 14 M21 3l-7 18-4-7-7-4z"),
    Cloud("M7 18a5 5 0 0 1-.6-9.96A6 6 0 0 1 18 9a4.5 4.5 0 0 1-.5 9z"),
    Sun("M16 12a4 4 0 1 1-8 0a4 4 0 1 1 8 0z M12 2v2 M12 20v2 M4.9 4.9l1.4 1.4 M17.7 17.7l1.4 1.4 M2 12h2 M20 12h2 M4.9 19.1l1.4-1.4 M17.7 6.3l1.4-1.4"),
    Up("M12 19V5 M5 12l7-7 7 7"),
    Info("M21 12a9 9 0 1 1-18 0a9 9 0 1 1 18 0z M12 11v6 M12 7.5v.01"),
    Tool("M14.7 6.3a4 4 0 0 0-5.4 5.4L3 18l3 3 6.3-6.3a4 4 0 0 0 5.4-5.4l-2.5 2.5-2.4-.6-.6-2.4z"),
    Search("M17.5 11a6.5 6.5 0 1 1-13 0a6.5 6.5 0 1 1 13 0z M20 20l-4-4"),
    Check("M6 12.5l4 4 8-9"),
    Alert("M12 7v6 M12 17v.01"),
}

/** 线条图标（不带底色） */
@Composable
fun GlyphIcon(glyph: SettingsGlyph, color: Color, size: Dp = 16.dp, strokeWidth: Float = 2f) {
    val path = remember(glyph) { PathParser().parsePathString(glyph.path).toPath() }
    Canvas(Modifier.size(size)) {
        scale(this.size.width / 24f, this.size.height / 24f, pivot = androidx.compose.ui.geometry.Offset.Zero) {
            drawPath(path, color, style = Stroke(strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}

/** 设置行前的彩色圆角方块图标 */
@Composable
fun SettingsIcon(glyph: SettingsGlyph, tint: Color, size: Dp = 28.dp) {
    // 夜间只用黑白灰为主：图标底色保留（表示类型），但向深灰靠 30%，不抢眼
    val bg = if (app.resound.ui.theme.Rs.colors.isDark) androidx.compose.ui.graphics.lerp(tint, Color(0xFF2C2C2E), 0.3f) else tint
    Box(Modifier.size(size).clip(RoundedCornerShape(size * 0.29f)).background(bg), contentAlignment = Alignment.Center) {
        GlyphIcon(glyph, Color.White, size * 0.57f)
    }
}
