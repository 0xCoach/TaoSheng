package app.resound.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * 涛声的配色：日间浅灰白底 + 白卡片 + 暖橙点缀。
 * 夜间（11-12 重做）：纯黑底（OLED 不发光）+ 中性深灰卡片加极细白边；文字只用一种白，按透明度分三档；
 * 橙色只做小面积点缀。越“浮”起来的东西越亮：底 < 卡片 < 面板。
 */
@Immutable
data class RsColors(
    val background: Color,
    val card: Color,
    val cardPressed: Color,
    val text: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val separator: Color,
    val accent: Color,
    val accentSoft: Color,
    val good: Color,
    val warning: Color,
    val critical: Color,
    val shadow: Color,
    /** 弹出面板、提示条的底色（比卡片再亮一级）；日间同卡片 */
    val elevated: Color,
    /** 按钮、输入框、小胶囊的填充色（放在卡片或面板上）；日间同页面底色，夜间是半透明白，在任何深色层上都稍亮一点 */
    val fill: Color,
    /** 分段切换器的底条；日间是浅灰，夜间是半透明白（不能用分隔线色改透明度，夜间分隔线本身就是半透明的） */
    val track: Color,
    /** 分段切换器选中的那一格；日间白色，夜间比底条亮一级 */
    val trackThumb: Color,
    /** 卡片描边；日间透明（靠阴影），夜间是极细的白边 */
    val hairline: Color,
    val isDark: Boolean,
)

val LightRs = RsColors(
    background = Color(0xFFF4F2EF),
    card = Color(0xFFFFFFFF),
    cardPressed = Color(0xFFF7F4F0),
    text = Color(0xFF1D1B19),
    textSecondary = Color(0xFF7D7770),
    textTertiary = Color(0xFF8A847C),
    separator = Color(0xFFECE8E3),
    accent = Color(0xFFF07B2E),
    accentSoft = Color(0xFFFDEBDD),
    good = Color(0xFF34A853),
    warning = Color(0xFFE6A23C),
    critical = Color(0xFFE5484D),
    shadow = Color(0x14583A1C),
    elevated = Color(0xFFFFFFFF),
    fill = Color(0xFFF4F2EF),
    track = Color(0xB3ECE8E3),
    trackThumb = Color(0xFFFFFFFF),
    hairline = Color(0x00000000),
    isDark = false,
)

val DarkRs = RsColors(
    background = Color(0xFF000000),
    card = Color(0xFF1C1C1E),
    cardPressed = Color(0xFF2C2C2E),
    text = Color(0xEBFFFFFF),          // 白 92%
    textSecondary = Color(0x99FFFFFF), // 白 60%
    textTertiary = Color(0x7AFFFFFF),  // 白 48%（深灰卡片上对比度约 4.9）
    separator = Color(0x14FFFFFF),     // 白 8%
    accent = Color(0xFFD47A44),       // 夜间橙：压低亮度，铺底不发光、当文字仍清楚（卡片上对比度 5.4，白字在其上 3.2）
    accentSoft = Color(0xFF3A2618),
    good = Color(0xFF5BC27A),
    warning = Color(0xFFEDB75E),
    critical = Color(0xFFF07175),
    shadow = Color(0x00000000),
    elevated = Color(0xFF2C2C2E),
    fill = Color(0x1FFFFFFF),          // 白 12%
    track = Color(0x1FFFFFFF),
    trackThumb = Color(0xFF48484A),
    hairline = Color(0x12FFFFFF),      // 白 7%
    isDark = true,
)

/** 字号层级（澎湃 OS 默认字体即 MiSans，不另外打包字体）。 */
object RsType {
    val largeTitle = TextStyle(fontSize = 32.sp, lineHeight = 38.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.2.sp)
    val title = TextStyle(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold)
    val headline = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold)
    val body = TextStyle(fontSize = 15.sp, lineHeight = 21.sp)
    val subhead = TextStyle(fontSize = 13.sp, lineHeight = 18.sp)
    val caption = TextStyle(fontSize = 12.sp, lineHeight = 16.sp)
    val number = TextStyle(fontSize = 26.sp, lineHeight = 30.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.3).sp)
    val section = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.4.sp)
}

enum class ThemeMode { System, Light, Dark }

val LocalRs = staticCompositionLocalOf { LightRs }

object Rs {
    val colors: RsColors
        @Composable get() = LocalRs.current
}

@Composable
fun ResoundTheme(mode: ThemeMode = ThemeMode.System, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.System -> isSystemInDarkTheme()
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }
    val hx = if (dark) DarkRs else LightRs
    val material = if (dark) {
        darkColorScheme(primary = hx.accent, background = hx.background, surface = hx.card, onSurface = hx.text)
    } else {
        lightColorScheme(primary = hx.accent, background = hx.background, surface = hx.card, onSurface = hx.text)
    }
    CompositionLocalProvider(LocalRs provides hx) {
        MaterialTheme(colorScheme = material, content = content)
    }
}

/**
 * 在颜色原有透明度上再按比例变淡。主题里夜间的文字、分隔线等本身就是半透明的，
 * 直接 copy(alpha = x) 会把它们变回不透明（例如分隔线变成纯白），所以一律用 fade。
 */
fun Color.fade(fraction: Float): Color = copy(alpha = alpha * fraction)
