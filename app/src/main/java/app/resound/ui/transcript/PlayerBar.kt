package app.resound.ui.transcript

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import app.resound.ui.theme.Rs
import app.resound.ui.theme.RsType
import app.resound.ui.verify.springClick

private val SPEEDS = listOf(1f, 1.5f, 2f)

/**
 * 迷你播放条：播放/暂停 · 播到的时刻 · 倍速 · 关闭。用独立的浮层窗口，详情面板打开时也盖在最上面；
 * 每次新开始播放重建一次，保证在最新打开的面板之上。不抢触摸，面板照常能滑。
 */
@Composable
fun PlayerBar(bottomPadding: Dp) {
    val context = LocalContext.current
    LinePlayer.notice?.let { msg ->
        LaunchedEffect(msg) { android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show(); LinePlayer.notice = null }
    }
    if (!LinePlayer.active) return
    key(LinePlayer.session) {
        Popup(alignment = Alignment.BottomCenter, properties = PopupProperties(focusable = false, clippingEnabled = false)) {
            Box(Modifier.windowInsetsPadding(WindowInsets.navigationBars).padding(start = 16.dp, end = 16.dp, bottom = bottomPadding + 12.dp)) {
                Bar()
            }
        }
    }
}

@Composable
private fun Bar() {
    val c = Rs.colors
    AnimatedVisibility(true, enter = slideInVertically { it } + fadeIn(), exit = slideOutVertically { it } + fadeOut()) {
        Row(
            Modifier.fillMaxWidth().shadow(if (c.isDark) 0.dp else 16.dp, RoundedCornerShape(26.dp), ambientColor = c.shadow, spotColor = c.shadow)
                .clip(RoundedCornerShape(26.dp)).background(c.card).padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // 播放/暂停：实心圆键
            Box(Modifier.size(40.dp).clip(CircleShape).background(c.accent).springClick { LinePlayer.toggle() }, contentAlignment = Alignment.Center) {
                Text(if (LinePlayer.playing) "❚❚" else "▶", style = RsType.subhead.copy(fontWeight = FontWeight.Bold), color = Color.White)
            }
            Spacer(Modifier.width(2.dp))
            androidx.compose.foundation.layout.Column(Modifier.weight(1f)) {
                Text(if (LinePlayer.playing) "正在播放原声" else "已暂停", style = RsType.subhead.copy(fontWeight = FontWeight.SemiBold), color = c.text)
                Text(app.resound.ui.verify.formatStamp(LinePlayer.positionAt) + ":" + "%02d".format(LinePlayer.positionAt / 1000 % 60),
                    style = RsType.caption, color = c.textTertiary)
            }
            // 倍速：点一下 1× → 1.5× → 2× 循环
            val s = LinePlayer.speed
            Text(if (s == 1f) "1×" else if (s == 1.5f) "1.5×" else "2×", style = RsType.subhead.copy(fontWeight = FontWeight.SemiBold), color = c.accent,
                modifier = Modifier.clip(CircleShape).background(c.accentSoft).springClick {
                    LinePlayer.changeSpeed(SPEEDS[(SPEEDS.indexOf(s) + 1) % SPEEDS.size])
                }.padding(horizontal = 12.dp, vertical = 7.dp))
            Text("✕", style = RsType.headline, color = c.textTertiary, modifier = Modifier.clip(CircleShape).springClick { LinePlayer.stop() }.padding(horizontal = 12.dp, vertical = 6.dp))
        }
    }
}
