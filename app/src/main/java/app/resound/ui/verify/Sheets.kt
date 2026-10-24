package app.resound.ui.verify

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.resound.ui.theme.Rs
import app.resound.ui.theme.RsType

/** 按钮的轻重：主要 = 实心暖橙；次要 = 浅底；危险 = 红字浅红底 */
enum class ActionKind { Primary, Secondary, Danger }

/** 面板里的整行按钮 */
@Composable
fun ActionButton(text: String, kind: ActionKind, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    val c = Rs.colors
    val (bg, fg) = when (kind) {
        ActionKind.Primary -> c.accent to Color.White
        ActionKind.Secondary -> c.background to c.text
        ActionKind.Danger -> c.critical.copy(alpha = 0.1f) to c.critical
    }
    Box(
        modifier.fillMaxWidth().springClick { if (enabled) onClick() }.clip(RoundedCornerShape(16.dp))
            .background(if (enabled) bg else bg.copy(alpha = bg.alpha * 0.5f)).padding(vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = RsType.body.copy(fontWeight = FontWeight.SemiBold), color = if (enabled) fg else fg.copy(alpha = 0.5f))
    }
}

/**
 * 面板内容滚到底后还在往上甩的余量，不交给外面的面板：面板已经全展开，余量交出去它会被往上顶一下再弹回来，
 * 快速上滑时就会上下弹好几次。往下的余量照常交出去，保留“下拉关闭”。
 */
private val KeepUpwardScroll = object : androidx.compose.ui.input.nestedscroll.NestedScrollConnection {
    override fun onPostScroll(consumed: androidx.compose.ui.geometry.Offset, available: androidx.compose.ui.geometry.Offset,
                              source: androidx.compose.ui.input.nestedscroll.NestedScrollSource) =
        if (available.y < 0f) available.copy(x = 0f) else androidx.compose.ui.geometry.Offset.Zero
    override suspend fun onPostFling(consumed: androidx.compose.ui.unit.Velocity, available: androidx.compose.ui.unit.Velocity) =
        if (available.y < 0f) available.copy(x = 0f) else androidx.compose.ui.unit.Velocity.Zero
}

/** 从底部弹出的面板：标题 + 说明 + 内容；所有确认、编辑类弹窗统一用它 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RsSheet(title: String, subtitle: String? = null, onDismiss: () -> Unit,
            /** 内容会随选择变长变短的面板设为 true：固定高度，避免高度突变时系统误判成下滑关闭 */
            fixedHeight: Boolean = false,
            content: @Composable ColumnScope.() -> Unit) {
    val c = Rs.colors
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = c.card,
        dragHandle = { Box(Modifier.padding(vertical = 10.dp).size(width = 36.dp, height = 5.dp).clip(CircleShape).background(c.separator)) },
    ) {
        Column(
            Modifier.then(if (fixedHeight) Modifier.fillMaxHeight(0.92f) else Modifier)
                .nestedScroll(KeepUpwardScroll)
                .verticalScroll(rememberScrollState()).imePadding().padding(start = 20.dp, end = 20.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(title, style = RsType.title, color = c.text)
            subtitle?.let { Text(it, style = RsType.subhead, color = c.textSecondary) }
            Spacer(Modifier.height(2.dp))
            content()
        }
    }
}

/** 面板里的输入框 */
@Composable
fun SheetInput(value: String, hint: String, onChange: (String) -> Unit) {
    val c = Rs.colors
    BasicTextField(
        value, onChange, singleLine = true, textStyle = RsType.body.copy(color = c.text), cursorBrush = SolidColor(c.accent),
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.background).padding(horizontal = 16.dp, vertical = 14.dp),
        decorationBox = { inner -> Box { if (value.isEmpty()) Text(hint, style = RsType.body, color = c.textTertiary); inner() } },
    )
}
