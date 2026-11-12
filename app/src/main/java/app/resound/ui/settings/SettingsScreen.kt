package app.resound.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.resound.capture.CaptureController
import app.resound.capture.CaptureState
import app.resound.digest.DigestSettings
import app.resound.transcribe.TranscribeSettings
import app.resound.ui.theme.Rs
import app.resound.ui.theme.RsType
import app.resound.ui.theme.ThemeMode
import app.resound.ui.verify.RsCard
import app.resound.ui.verify.Section
import app.resound.ui.verify.ThemeModePill
import app.resound.ui.verify.springClick
import app.resound.ui.voiceprint.LocalVoiceprint

/** 设置里的二级页面 */
/** 二级页面里再点进一层时（如某个技能），顶栏换成这一层的标题和返回 */
data class InnerPage(val title: String, val backLabel: String, val onBack: () -> Unit)

val LocalSettingsInner = androidx.compose.runtime.staticCompositionLocalOf<(InnerPage?) -> Unit> { {} }

enum class SettingsPage(val title: String) { Skills("整理技能"), Capture("录音与后台"), Devices("外接设备"), Advanced("高级与诊断"), Transcript("原话与转写"), Model("模型与整理时间"), Voiceprint("声纹与人物"), Review("每日回顾"), Push("推送通道"), Prompts("整理指令"), Hotwords("热词识别"), EnvSound("环境音识别"), Location("足迹"), Backup("数据备份"), Update("版本更新"), About("关于涛声") }

/**
 * 设置：iOS 设置页式分组圆角列表。点一行进入二级页面，左上角返回或系统返回键回到列表。
 * 二级页面的内容由调用方提供（复用原有的“录音”“原话”页等）。
 */
@Composable
fun SettingsScreen(
    capture: CaptureController,
    transcribeSettings: TranscribeSettings,
    digestSettings: DigestSettings,
    themeMode: ThemeMode,
    onThemeMode: (ThemeMode) -> Unit,
    bottomPadding: Dp,
    page: @Composable (SettingsPage, Dp) -> Unit,
) {
    var open by rememberSaveable { mutableStateOf<SettingsPage?>(null) }
    var inner by remember { mutableStateOf<InnerPage?>(null) }
    BackHandler(open != null) { open = null }
    val requested by app.resound.ui.ShellNav.settingsPage.collectAsState()
    LaunchedEffect(requested) { requested?.let { open = it; inner = null; app.resound.ui.ShellNav.settingsPage.value = null } }
    AnimatedContent(
        open,
        transitionSpec = {
            if (targetState != null) (slideInHorizontally { it / 3 } + fadeIn()) togetherWith (slideOutHorizontally { -it / 6 } + fadeOut())
            else (slideInHorizontally { -it / 6 } + fadeIn()) togetherWith (slideOutHorizontally { it / 3 } + fadeOut())
        },
        label = "settingsPage",
    ) { current ->
        if (current == null) {
            SettingsList(capture, transcribeSettings, digestSettings, themeMode, onThemeMode, bottomPadding) { open = it }
        } else {
            // 顶部安全区在这里处理一次，二级页面内部不再重复留白
            Column(Modifier.fillMaxSize().background(Rs.colors.background).windowInsetsPadding(WindowInsets.safeDrawing.only(androidx.compose.foundation.layout.WindowInsetsSides.Top))) {
                val layer = inner
                if (layer != null) SubPageBar(layer.title, layer.backLabel, layer.onBack) else SubPageBar(current.title, "设置") { open = null }
                androidx.compose.runtime.CompositionLocalProvider(LocalSettingsInner provides { inner = it }) {
                    Box(Modifier.weight(1f)) { page(current, bottomPadding) }
                }
            }
        }
    }
}

@Composable
private fun SubPageBar(title: String, backLabel: String, onBack: () -> Unit) {
    val c = Rs.colors
    Row(
        Modifier.fillMaxWidth()
            .padding(start = 8.dp, end = 16.dp, top = 6.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("‹ $backLabel", style = RsType.headline, color = c.accent, modifier = Modifier.springClick(onBack).padding(horizontal = 8.dp, vertical = 8.dp))
        Spacer(Modifier.weight(1f))
        Text(title, style = RsType.headline, color = c.text)
        Spacer(Modifier.weight(1f))
        Spacer(Modifier.width(64.dp))
    }
}

/** 备份时刻：今天只写几点，昨天写“昨天 22:00”，更早写日期 */
internal fun backupClock(at: Long, short: Boolean = false): String {
    val t = java.time.Instant.ofEpochMilli(at).atZone(java.time.ZoneId.systemDefault())
    val days = java.time.temporal.ChronoUnit.DAYS.between(t.toLocalDate(), java.time.LocalDate.now())
    val hm = "%d:%02d".format(t.hour, t.minute)
    return when (days) { 0L -> if (short) hm else "今天 $hm"; 1L -> "昨天 $hm"; else -> if (short) "${t.monthValue}/${t.dayOfMonth}" else "${t.monthValue}月${t.dayOfMonth}日 $hm" }
}
