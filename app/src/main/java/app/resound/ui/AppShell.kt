package app.resound.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.runtime.remember
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import androidx.compose.ui.unit.dp
import app.resound.ui.theme.Rs
import app.resound.ui.theme.RsType
import app.resound.ui.verify.springClick

/**
 * 外壳：底部悬浮胶囊切换各页。
 * 现在是 今天 / 原话 / 录音；正式版会换成 今天 / 问问 / 记忆 / 待办 / 设置 五个底栏页。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AppShell(tabs: List<Pair<String, @Composable () -> Unit>>, request: Pair<Int, Long>? = null) {
    val c = Rs.colors
    var tab by rememberSaveable { mutableIntStateOf(0) }
    // 外部（如点通知）要求切到某一页；Long 用来区分同一页的多次请求
    androidx.compose.runtime.LaunchedEffect(request) { request?.let { tab = it.first } }
    val navRequest by ShellNav.request.collectAsState()
    androidx.compose.runtime.LaunchedEffect(navRequest) { navRequest?.let { tab = it.first } }
    val holder = rememberSaveableStateHolder()
    val labels = remember(tabs.size) { tabs.map { it.first } }
    val haze = rememberHazeState()
    val keyboard = WindowInsets.isImeVisible
    Box(Modifier.fillMaxSize().background(c.background)) {
        // 淡入淡出切换；各页的滚动位置等状态保留。页面是毛玻璃的取景来源
        Crossfade(tab, Modifier.hazeSource(haze), animationSpec = tween(200), label = "tab") { t ->
            holder.SaveableStateProvider(t) { tabs[t].second() }
        }
        if (!keyboard) {
            // 页面底部渐隐：内容滚到底栏附近时慢慢淡进背景色，底栏不会和卡片撞色
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(
                Brush.verticalGradient(0f to c.background.copy(alpha = 0f), 0.45f to c.background.copy(alpha = 0.88f), 1f to c.background),
            )) {
                Box(Modifier.height(ShellBottomPadding + 34.dp))
                Box(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
            }
            // 底栏单独一块：只在选中页变化时重画，切页时页面重建不会牵连它
            TabBar(labels, tab, { tab = it }, haze, Modifier.align(Alignment.BottomCenter))
        }
    }
}

@Composable
private fun TabBar(labels: List<String>, selected: Int, onSelect: (Int) -> Unit, haze: HazeState, modifier: Modifier) {
    val c = Rs.colors
    // 细亮边：上沿亮、下沿淡，像玻璃边缘反光
    val rim = if (c.isDark) Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.22f), Color.White.copy(alpha = 0.05f)))
    else Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.95f), Color.White.copy(alpha = 0.35f)))
    Box(
        modifier
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(bottom = 14.dp)
            .shadow(if (c.isDark) 0.dp else 20.dp, CircleShape, ambientColor = c.shadow, spotColor = c.shadow)
            .clip(CircleShape),
    ) {
        // 毛玻璃单独一层底板：模糊底下的页面，再罩半透明卡片色；旧系统（安卓 12 以下）自动退成半透明底。
        // 不能直接加在按钮那一行上，否则会缓存按钮的绘制，切页时选中底色不跟着走
        Box(Modifier.matchParentSize().hazeEffect(haze) {
            blurRadius = 22.dp
            backgroundColor = c.background
            tints = listOf(HazeTint(c.card.copy(alpha = if (c.isDark) 0.62f else 0.78f)))
            fallbackTint = HazeTint(c.card.copy(alpha = 0.96f))
            noiseFactor = 0f
        }.border(1.dp, rim, CircleShape))
        Row(Modifier.padding(5.dp)) {
            labels.forEachIndexed { i, label ->
                val on = i == selected
                val bg by animateColorAsState(if (on) c.accentSoft else c.accentSoft.copy(alpha = 0f), spring(stiffness = Spring.StiffnessMediumLow), label = "tabBg")
                val fg by animateColorAsState(if (on) c.accent else c.textSecondary, label = "tabFg")
                Box(Modifier.clip(CircleShape).background(bg).springClick { onSelect(i) }.padding(horizontal = 13.dp, vertical = 10.dp)) {
                    Text(label, style = RsType.headline, color = fg)
                }
            }
        }
    }
}

/** 给页面底部留出悬浮胶囊的位置。 */
val ShellBottomPadding = 76.dp
