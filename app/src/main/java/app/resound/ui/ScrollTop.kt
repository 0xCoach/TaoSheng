package app.resound.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.resound.ui.theme.Rs
import app.resound.ui.theme.RsType
import app.resound.ui.verify.springClick
import kotlinx.coroutines.launch

/** 再点一下当前底栏图标的次数（每页一个）；页面据此回到顶部 */
val LocalTabReselect = compositionLocalOf { 0 }

/** 再点一下当前底栏图标：平滑回到顶部 */
@Composable
fun ScrollToTopOnReselect(state: LazyListState) {
    val n = LocalTabReselect.current
    LaunchedEffect(n) { if (n > 0) state.animateScrollToItem(0) }
}

/**
 * 滑得比较远（约一屏半）时右下角淡入“↑”，点一下回到顶部；回到上面后自动消失。
 * 从节奏卡片点柱子跳下去后也会立刻出现。[lift]：底部有别的浮条（如多选操作栏）时往上让开。
 */
@Composable
fun BoxScope.ScrollTopButton(state: LazyListState, bottomPadding: Dp, lift: Dp = 0.dp, end: Dp = 20.dp) {
    val c = Rs.colors
    val scope = rememberCoroutineScope()
    val far by remember(state) { derivedStateOf { state.firstVisibleItemIndex >= 3 } }
    AnimatedVisibility(far, Modifier.align(Alignment.BottomEnd).windowInsetsPadding(WindowInsets.navigationBars)
        .padding(end = end, bottom = bottomPadding + 20.dp + lift),
        enter = scaleIn(initialScale = 0.6f) + fadeIn(), exit = scaleOut(targetScale = 0.6f) + fadeOut()) {
        Box(Modifier.size(42.dp).shadow(if (c.isDark) 0.dp else 10.dp, CircleShape, ambientColor = c.shadow, spotColor = c.shadow)
            .clip(CircleShape).background(c.card).springClick { scope.launch { state.animateScrollToItem(0) } },
            contentAlignment = Alignment.Center) {
            Text("↑", style = RsType.headline.copy(fontWeight = FontWeight.SemiBold), color = c.accent)
        }
    }
}

/**
 * 在内容区左右滑动切换顶部选项卡（往左滑下一个、往右滑上一个）。
 * 单条事件/待办自己处理的左右滑会先消费掉手势，这里只接空白处和卡片之间的滑动，互不误触。
 */
fun Modifier.swipeTabs(count: Int, index: Int, onChange: (Int) -> Unit): Modifier = pointerInput(count, index) {
    var total = 0f
    val threshold = 80.dp.toPx()
    detectHorizontalDragGestures(onDragStart = { total = 0f }, onDragEnd = {
        if (total <= -threshold && index < count - 1) onChange(index + 1)
        else if (total >= threshold && index > 0) onChange(index - 1)
    }) { _, drag -> total += drag }
}
