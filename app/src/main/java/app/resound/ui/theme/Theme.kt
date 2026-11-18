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
 * 声声不息的配色（11-18 高对比极简版）：黑白灰为主，日间近黑当主题色、夜间近白；
 * 状态色（成功/警告/严重）保留给小圆点和状态字；唯一的暖橙留在录制指示（OverviewCard 自带 BrandOrange）。
 * 越“浮”起来的东西越亮：底 < 卡片 < 面板。
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
    /** 铺在主题色上的文字 / 图标颜色：日间主题色是橙，配白字；夜间主题色是近白，配深色字 */
    val onAccent: Color,
    /** 选中状态的底色（筛选胶囊、分段、日历选中日等）：日间同主题橙；夜间浅灰，把白色留给主要按钮 */
    val selected: Color,
    /** 选中状态上的文字 */
    val onSelected: Color,
    /** 分段切换器的底条；日间是浅灰，夜间是半透明白（不能用分隔线色改透明度，夜间分隔线本身就是半透明的） */
    val track: Color,
    /** 分段切换器选中的那一格；日间白色，夜间比底条亮一级 */
    val trackThumb: Color,
    /** 卡片描边；日间透明（靠阴影），夜间是极细的白边 */
    val hairline: Color,
    /** 主要按钮（整行大按钮）的底色：日间同主题橙；夜间是略灰的白，大块橙色在纯黑屏上太抢 */
    val primary: Color,
    /** 主要按钮上的文字 */
    val onPrimary: Color,
    val isDark: Boolean,
)

val LightRs = RsColors(
    background = Color(0xFFF6F6F5),
    card = Color(0xFFFFFFFF),
    cardPressed = Color(0xFFF0F0EE),
    text = Color(0xFF141414),
    textSecondary = Color(0xFF6E6E6E),
    textTertiary = Color(0xFF8E8E8E),
    separator = Color(0xFFE9E9E7),
    accent = Color(0xFF1B1B1B),
    accentSoft = Color(0xFFEEEEEC),
    good = Color(0xFF2E8B57),
    warning = Color(0xFFB8860B),
    critical = Color(0xFFCC3333),
    shadow = Color(0x14141414),
    elevated = Color(0xFFFFFFFF),
    fill = Color(0xFFF1F1EF),
    onAccent = Color(0xFFFFFFFF),
    selected = Color(0xFF1B1B1B),
    onSelected = Color(0xFFFFFFFF),
    track = Color(0xB3E9E9E7),
    trackThumb = Color(0xFFFFFFFF),
    hairline = Color(0x00000000),
    primary = Color(0xFF1B1B1B),
    onPrimary = Color(0xFFFFFFFF),
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
    // 高对比极简：主题色换成近白（日间近黑），选中/按钮都是中性色；
    // 唯一的暖橙在录制指示卡（OverviewCard 自带 BrandOrange，不走主题板）。
    accent = Color(0xFFE8E8EA),
    accentSoft = Color(0xFF252527),
    // 状态色保留但调暗调淡，只用在小圆点和文字上
    good = Color(0xFF55A86F),
    warning = Color(0xFFD4A65E),
    critical = Color(0xFFE0686C),
    shadow = Color(0x00000000),
    elevated = Color(0xFF2C2C2E),
    fill = Color(0x1FFFFFFF),          // 白 12%
    onAccent = Color(0xFF1C1C1E),     // 橙底上的字用深色，对比度约 7
    selected = Color(0xFF48484A),
    onSelected = Color(0xEBFFFFFF),
    track = Color(0x1FFFFFFF),
    trackThumb = Color(0xFF48484A),
    hairline = Color(0x12FFFFFF),      // 白 7%
    primary = Color(0xFFE3E3E6),       // 略灰的白：纯黑 OLED 上大块纯白会发光刺眼
    onPrimary = Color(0xFF1C1C1E),
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
