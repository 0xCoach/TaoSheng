package app.resound.ui.today

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.resound.AppGraph
import app.resound.capture.CaptureController
import app.resound.home.HomeRules
import app.resound.ui.theme.Rs
import app.resound.ui.theme.RsType
import app.resound.ui.verify.RsSheet
import app.resound.ui.verify.springClick
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 暂停可选的时长（分钟），与通知栏、快捷开关一致 */
internal val PAUSE_CHOICES = listOf(15, 30, 60, 120)

/** 记住这次选的暂停时长，下次一点就用它 */
internal fun rememberPauseMinutes(minutes: Int) {
    if (AppGraph.isQuickPrefsReady) AppGraph.quickPrefs.setLastPauseMinutes(minutes)
}

/**
 * 主卡上点了暂停后，底部浮一条提示：“已暂停 30 分钟 · 改时长 · 撤销”，5 秒后自己消失。
 * 一步做完常用的，要调整再说（和闹钟“稍后提醒”、邮件“撤销”同一种做法）。
 */
@Composable
internal fun BoxScope.PauseBar(note: Pair<Int, Long>?, bottomPadding: Dp, capture: CaptureController, onDismiss: () -> Unit) {
    val c = Rs.colors
    var minutes by remember(note) { mutableIntStateOf(note?.first ?: 30) }
    var choosing by remember { mutableStateOf(false) }
    LaunchedEffect(note, choosing) { if (note != null && !choosing) { delay(5_000); onDismiss() } }
    AnimatedVisibility(note != null, Modifier.align(Alignment.BottomCenter).padding(start = 16.dp, end = 16.dp, bottom = bottomPadding + 16.dp),
        enter = slideInVertically { it } + fadeIn(), exit = slideOutVertically { it } + fadeOut()) {
        val shape = RoundedCornerShape(16.dp)
        Row(Modifier.fillMaxWidth()
            .shadow(if (c.isDark) 0.dp else 14.dp, shape, ambientColor = c.shadow, spotColor = Color(0x33000000))
            .clip(shape).background(if (c.isDark) c.elevated else Color(0xFF2B2724))
            .then(if (c.isDark) Modifier.border(0.5.dp, c.hairline, shape) else Modifier)
            .padding(start = 16.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text("已暂停 ${HomeRules.pauseLabel(minutes)}", style = RsType.subhead.copy(fontWeight = FontWeight.Medium),
                color = Color.White.copy(alpha = 0.92f), modifier = Modifier.weight(1f))
            val action = if (c.isDark) c.accent else Color(0xFFFFA86B)
            Text("改时长", style = RsType.subhead.copy(fontWeight = FontWeight.SemiBold), color = action,
                modifier = Modifier.clip(CircleShape).springClick { choosing = true }.padding(horizontal = 10.dp, vertical = 10.dp))
            Text("撤销", style = RsType.subhead.copy(fontWeight = FontWeight.SemiBold), color = action,
                modifier = Modifier.clip(CircleShape).springClick { capture.resume(); onDismiss() }.padding(horizontal = 10.dp, vertical = 10.dp))
        }
    }
    if (choosing) PauseLengthSheet(minutes, onPick = { m ->
        capture.pause(m); rememberPauseMinutes(m); minutes = m; choosing = false
    }) { choosing = false }
}

/** 选暂停时长：点一下就按新时长重新计时，并记住它 */
@Composable
internal fun PauseLengthSheet(current: Int, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    val c = Rs.colors
    RsSheet("暂停多久", "从现在算起，到点自动恢复", onDismiss = onDismiss) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PAUSE_CHOICES.forEach { m ->
                val on = m == current
                val bg by animateColorAsState(if (on) c.accent else c.fill, label = "pauseChoice")
                Box(Modifier.weight(1f).springClick { onPick(m) }.clip(CircleShape).background(bg).padding(vertical = 11.dp), contentAlignment = Alignment.Center) {
                    Text(HomeRules.pauseLabel(m), style = RsType.subhead.copy(fontWeight = FontWeight.Medium),
                        color = if (on) c.onAccent else c.textSecondary, maxLines = 1)
                }
            }
        }
    }
}

/** 首页底部的通用“撤销”提示（如“已记下 10:05 · 撤销”），5 秒后自动消失 */
data class HomeSnack(val text: String, val action: String, val id: Long = System.nanoTime(), val onAction: suspend () -> Unit)

object HomeSnacks {
    var current by mutableStateOf<HomeSnack?>(null)
}

@Composable
internal fun BoxScope.HomeSnackBar(bottomPadding: Dp) {
    val c = Rs.colors
    val snack = HomeSnacks.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    LaunchedEffect(snack?.id) { if (snack != null) { delay(5_000); if (HomeSnacks.current?.id == snack.id) HomeSnacks.current = null } }
    AnimatedVisibility(snack != null, Modifier.align(Alignment.BottomCenter).padding(start = 16.dp, end = 16.dp, bottom = bottomPadding + 16.dp),
        enter = slideInVertically { it } + fadeIn(), exit = slideOutVertically { it } + fadeOut()) {
        val shown = snack ?: return@AnimatedVisibility
        val shape = RoundedCornerShape(16.dp)
        Row(Modifier.fillMaxWidth()
            .shadow(if (c.isDark) 0.dp else 14.dp, shape, ambientColor = c.shadow, spotColor = Color(0x33000000))
            .clip(shape).background(if (c.isDark) c.elevated else Color(0xFF2B2724))
            .then(if (c.isDark) Modifier.border(0.5.dp, c.hairline, shape) else Modifier)
            .padding(start = 16.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text(shown.text, style = RsType.subhead.copy(fontWeight = FontWeight.Medium), color = Color.White.copy(alpha = 0.92f), modifier = Modifier.weight(1f))
            Text(shown.action, style = RsType.subhead.copy(fontWeight = FontWeight.SemiBold), color = if (c.isDark) c.text else Color(0xFFFFA86B),
                modifier = Modifier.clip(CircleShape).springClick { HomeSnacks.current = null; scope.launch { shown.onAction() } }
                    .padding(horizontal = 10.dp, vertical = 10.dp))
        }
    }
}
