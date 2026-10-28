package app.resound.ui.verify

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.resound.ui.theme.Rs
import app.resound.ui.theme.RsType

private val ROW = 40.dp
private const val VISIBLE = 5

/** 24 小时制的时、分滚轮：上下滑动，停下时自动对齐到中间那一格；[visible] 显示几行（单数），嵌在卡片里用 3 行省地方 */
@Composable
fun WheelTimePicker(hour: Int, minute: Int, onChange: (hour: Int, minute: Int) -> Unit, modifier: Modifier = Modifier, visible: Int = VISIBLE) {
    val c = Rs.colors
    var h = hour
    var m = minute
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        // 中间一格的底色，两个滚轮共用一条
        Box(Modifier.width(200.dp).height(ROW).clip(RoundedCornerShape(12.dp)).background(c.accentSoft))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Wheel(24, hour, visible) { h = it; onChange(h, m) }
            Text(":", style = RsType.title, color = c.accent, modifier = Modifier.padding(horizontal = 12.dp))
            Wheel(60, minute, visible) { m = it; onChange(h, m) }
        }
    }
}

/** 一列循环滚轮：列表重复很多圈，从中间开始，上下都滑得动 */
@Composable
private fun Wheel(count: Int, initial: Int, visible: Int, onSelect: (Int) -> Unit) {
    val c = Rs.colors
    val loops = 200
    val rowPx = with(LocalDensity.current) { ROW.toPx() }
    val state = rememberLazyListState(count * (loops / 2) + initial - visible / 2)
    // 吸附到列表顶端对齐，所以中间那格 = 顶上那格 + 半屏行数
    val center by remember {
        derivedStateOf { state.firstVisibleItemIndex + (if (state.firstVisibleItemScrollOffset > rowPx / 2) 1 else 0) + visible / 2 }
    }
    LaunchedEffect(center) { onSelect(center % count) }
    LazyColumn(Modifier.width(72.dp).height(ROW * visible), state = state, flingBehavior = rememberSnapFlingBehavior(state)) {
        items(count * loops) { i ->
            val selected = i == center
            Box(Modifier.fillMaxSize().height(ROW), contentAlignment = Alignment.Center) {
                Text("%02d".format(i % count),
                    style = if (selected) RsType.title.copy(fontWeight = FontWeight.SemiBold) else RsType.body,
                    color = if (selected) c.accent else c.textTertiary)
            }
        }
    }
}

/** 选一个时刻的底部面板：标题 + 滚轮 + 确定按钮；[minutes] 为当天 0 点起的分钟数 */
@Composable
fun WheelTimeSheet(title: String, minutes: Int, confirm: String = "确定", onDismiss: () -> Unit, onPick: (Int) -> Unit) {
    var picked by androidx.compose.runtime.remember { androidx.compose.runtime.mutableIntStateOf(minutes) }
    RsSheet(title, "24 小时制，上下滑动选择", onDismiss = onDismiss) {
        WheelTimePicker(picked / 60, picked % 60, { h, m -> picked = h * 60 + m })
        ActionButton("$confirm %02d:%02d".format(picked / 60, picked % 60), ActionKind.Primary) { onPick(picked) }
    }
}
