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

/** 回响的配色：浅灰白底 + 白卡片 + 暖橙点缀；夜间为暖调深灰，暖橙调柔和。 */
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
    val isDark: Boolean,
)

val LightRs = RsColors(
    background = Color(0xFFF4F2EF),
    card = Color(0xFFFFFFFF),
    cardPressed = Color(0xFFF7F4F0),
    text = Color(0xFF1D1B19),
    textSecondary = Color(0xFF7D7770),
    textTertiary = Color(0xFFADA79F),
    separator = Color(0xFFECE8E3),
    accent = Color(0xFFF07B2E),
    accentSoft = Color(0xFFFDEBDD),
    good = Color(0xFF34A853),
    warning = Color(0xFFE6A23C),
    critical = Color(0xFFE5484D),
    shadow = Color(0x14583A1C),
    isDark = false,
)

val DarkRs = RsColors(
    background = Color(0xFF171513),
    card = Color(0xFF24211E),
    cardPressed = Color(0xFF2C2825),
    text = Color(0xFFF3EFEA),
    textSecondary = Color(0xFFA39C94),
    textTertiary = Color(0xFF6E6862),
    separator = Color(0xFF33302C),
    accent = Color(0xFFF59A5B),
    accentSoft = Color(0xFF3A2A1E),
    good = Color(0xFF5BC27A),
    warning = Color(0xFFEDB75E),
    critical = Color(0xFFF07175),
    shadow = Color(0x00000000),
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
