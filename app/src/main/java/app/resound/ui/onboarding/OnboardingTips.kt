package app.resound.ui.onboarding

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.resound.AppGraph
import app.resound.onboarding.OnboardingTip
import app.resound.ui.ShellNav
import app.resound.ui.settings.SettingsPage
import app.resound.ui.theme.Rs
import app.resound.ui.theme.RsType
import app.resound.ui.verify.Pill
import app.resound.ui.verify.RsCard

/** 0.2.21 按需提示：到了合适时机才出现，一次只显示最重要的一条，“知道了”后不再出现 */
@Composable
fun OnboardingTipCard(topGap: Dp = 0.dp) {
    if (!AppGraph.isOnboardingReady) return
    val tips by AppGraph.onboarding.tips.collectAsState()
    val tip = tips.firstOrNull() ?: return
    val c = Rs.colors
    val (icon, title, body, page) = when (tip) {
        OnboardingTip.KeepAlive -> Quad("🔋", "涛声刚才被系统关掉过", "小米等手机还要打开“自启动”、把电池设为“无限制”，才能全天收音不断。", SettingsPage.Capture)
        OnboardingTip.Speakers -> Quad("👥", "最近常有几个人一起说话", "打开“区分说话人”，原话里会分清是谁说的，整理出的事也更准。", SettingsPage.Voiceprint)
        OnboardingTip.Backup -> Quad("☁️", "已经用了几天，设个备份吧", "换手机、误删都能找回来。可以存在手机文件夹，或坚果云、WebDAV、S3。", SettingsPage.Backup)
    }
    Spacer(Modifier.height(topGap))
    AnimatedContent(tip, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "tip") { _ ->
        RsCard(padding = PaddingValues(horizontal = 18.dp, vertical = 16.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Text(icon, style = RsType.headline)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, style = RsType.body, color = c.text)
                    Spacer(Modifier.height(4.dp))
                    Text(body, style = RsType.caption, color = c.textSecondary)
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Pill("去设置", filled = true) { ShellNav.openSettings(page) }
                        Pill("知道了", filled = false) { AppGraph.onboarding.dismiss(tip) }
                    }
                }
            }
        }
    }
}

private data class Quad(val a: String, val b: String, val c: String, val d: SettingsPage)
