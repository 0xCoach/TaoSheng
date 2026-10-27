package app.resound.ui.settings

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.resound.digest.AndroidDigestSettings
import app.resound.ui.theme.Rs
import app.resound.ui.theme.RsType
import app.resound.ui.verify.RsCard
import app.resound.ui.verify.springClick

/** 整理时机：停多久算一段结束、一直在说时最多等多久先整理。改了下一轮整理生效。 */
@Composable
fun DigestTimingCard(settings: AndroidDigestSettings) {
    val c = Rs.colors
    val pause by settings.pauseMinutes.collectAsState()
    val maxWait by settings.maxWaitMinutes.collectAsState()
    RsCard(padding = PaddingValues(horizontal = 20.dp, vertical = 18.dp)) {
        Text("什么时候整理", style = RsType.body, color = c.text)
        Spacer(Modifier.height(4.dp))
        Text("转写完的话要等对话告一段落才整理，免得一件事被切成两半。", style = RsType.caption, color = c.textSecondary)

        Spacer(Modifier.height(16.dp))
        Text("停多久算说完了", style = RsType.subhead, color = c.text)
        Spacer(Modifier.height(8.dp))
        MinuteChoices(listOf(5, 10, 20), pause, settings::setPauseMinutes)
        Spacer(Modifier.height(6.dp))
        Text("两句话之间停了 $pause 分钟以上，就算前面那段对话结束。", style = RsType.caption, color = c.textTertiary)

        Box(Modifier.padding(vertical = 16.dp).fillMaxWidth().height(0.5.dp).background(c.separator))

        Text("一直在说时，最多等多久", style = RsType.subhead, color = c.text)
        Spacer(Modifier.height(8.dp))
        MinuteChoices(listOf(30, 60, 120), maxWait, settings::setMaxWaitMinutes)
        Spacer(Modifier.height(6.dp))
        Text("对话超过 ${label(maxWait)} 还没结束，就在前面找个停顿切开，先整理前半截。",
            style = RsType.caption, color = c.textTertiary)
        Spacer(Modifier.height(10.dp))
        Text("转写是随说随转；整理在下一轮自动整理时进行（间隔在「原话与转写」里设），想马上看结果就在“今天”页点“现在整理”。",
            style = RsType.caption, color = c.textTertiary)
    }
}

private fun label(minutes: Int) = if (minutes % 60 == 0) "${minutes / 60} 小时" else "$minutes 分钟"

@Composable
private fun MinuteChoices(options: List<Int>, selected: Int, onSelect: (Int) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { minutes -> MinuteChip(label(minutes), minutes == selected) { onSelect(minutes) } }
    }
}

@Composable
private fun MinuteChip(text: String, on: Boolean, onClick: () -> Unit) {
    val c = Rs.colors
    // 卡片本身是白底，未选中用页面底色才看得出是可点的胶囊
    val bg by animateColorAsState(if (on) c.accent else c.background, spring(stiffness = Spring.StiffnessMediumLow), label = "minuteChip")
    val fg by animateColorAsState(if (on) Color.White else c.textSecondary, label = "minuteChipFg")
    Text(text, style = RsType.subhead.copy(fontWeight = FontWeight.Medium), color = fg,
        modifier = Modifier.springClick(onClick).clip(CircleShape).background(bg).padding(horizontal = 16.dp, vertical = 8.dp))
}
