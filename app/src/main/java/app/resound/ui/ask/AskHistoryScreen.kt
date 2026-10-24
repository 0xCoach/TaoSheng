package app.resound.ui.ask

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.resound.ask.AskConversation
import app.resound.ask.AskRepository
import app.resound.ui.theme.Rs
import app.resound.ui.theme.RsType
import app.resound.ui.verify.ActionButton
import app.resound.ui.verify.ActionKind
import app.resound.ui.verify.RsCard
import app.resound.ui.verify.RsSheet
import app.resound.ui.verify.SheetInput
import app.resound.ui.verify.springClick
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/** 历史对话：顶栏同设置子页；按今天 / 昨天 / 本周 / 更早分组，每组一张卡；点一行打开，长按改名或删除 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun AskHistoryScreen(repo: AskRepository, bottomPadding: Dp, onBack: () -> Unit) {
    val c = Rs.colors
    var query by remember { mutableStateOf("") }
    var limit by remember(query) { mutableIntStateOf(30) }
    val flow = remember(repo, query, limit) { repo.history(query, limit + 1) }
    val rows by flow.collectAsState(initial = emptyList())
    val current by repo.currentConversation.collectAsState()
    var managing by remember { mutableStateOf<AskConversation?>(null) }
    val haptic = LocalHapticFeedback.current
    BackHandler(onBack = onBack)
    val groups = remember(rows, limit) { rows.take(limit).groupBy { historyGroup(it.updatedAt) }.toList() }

    Column(Modifier.fillMaxSize().background(c.background).windowInsetsPadding(WindowInsets.safeDrawing).imePadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 16.dp, top = 6.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("‹ 问问", style = RsType.headline, color = c.accent, modifier = Modifier.springClick(onBack).padding(horizontal = 8.dp, vertical = 8.dp))
            Spacer(Modifier.weight(1f))
            Text("历史对话", style = RsType.headline, color = c.text)
            Spacer(Modifier.weight(1f))
            Box(Modifier.width(72.dp), contentAlignment = Alignment.CenterEnd) { IconCircle(ICON_NEW, accent = true) { repo.newConversation(); onBack() } }
        }
        SearchField(query, Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp)) { query = it }
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = bottomPadding + 24.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            if (rows.isEmpty()) item {
                Text(if (query.isBlank()) "还没有历史对话" else "没有找到相关对话", style = RsType.subhead, color = c.textTertiary,
                    textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 60.dp))
            }
            items(groups, key = { it.first }) { (group, list) ->
                Column {
                    Text(group, style = RsType.caption.copy(fontWeight = FontWeight.Medium), color = c.textTertiary, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp))
                    RsCard(padding = PaddingValues(vertical = 2.dp)) {
                        list.forEachIndexed { i, row ->
                            Row(
                                Modifier.fillMaxWidth().combinedClickable(
                                    interactionSource = remember(row.id) { MutableInteractionSource() }, indication = null,
                                    onClick = { repo.openConversation(row.id); onBack() },
                                    onLongClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); managing = row },
                                ).padding(horizontal = 16.dp, vertical = 13.dp),
                                verticalAlignment = Alignment.Top,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        if (current == row.id) {
                                            Box(Modifier.size(7.dp).clip(CircleShape).background(c.accent))
                                            Spacer(Modifier.width(6.dp))
                                        }
                                        Text(row.title.ifBlank { "未命名对话" }, style = RsType.body.copy(fontWeight = FontWeight.SemiBold), color = c.text,
                                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                    if (row.preview.isNotBlank()) {
                                        Spacer(Modifier.height(3.dp))
                                        Text(row.preview, style = RsType.caption, color = c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                                Spacer(Modifier.width(10.dp))
                                Text(historyTime(row.updatedAt), style = RsType.caption, color = c.textTertiary, modifier = Modifier.padding(top = 2.dp))
                            }
                            if (i != list.lastIndex) Box(Modifier.padding(start = 16.dp).fillMaxWidth().height(0.5.dp).background(c.separator))
                        }
                    }
                }
            }
            if (rows.size > limit) item { LaunchedEffect(limit) { limit += 30 }; Text("加载更多…", style = RsType.caption, color = c.textTertiary) }
            if (rows.isNotEmpty()) item { Text("长按一条可以改名或删除", style = RsType.caption, color = c.textTertiary, modifier = Modifier.padding(start = 4.dp)) }
        }
    }
    managing?.let { row -> ManageSheet(repo, row) { managing = null } }
}

/** 管理一条对话：改名 / 删除（删除点两次） */
@Composable
private fun ManageSheet(repo: AskRepository, row: AskConversation, onDismiss: () -> Unit) {
    var title by remember { mutableStateOf(row.title) }
    var confirm by remember { mutableStateOf(false) }
    RsSheet("管理对话", onDismiss = onDismiss) {
        SheetInput(title, "对话标题") { title = it.take(80) }
        ActionButton("保存标题", ActionKind.Primary, enabled = title.isNotBlank() && title != row.title) {
            repo.renameConversation(row.id, title.trim()); onDismiss()
        }
        ActionButton(if (confirm) "确定删除？再点一次" else "删除这段对话", ActionKind.Danger) {
            if (!confirm) confirm = true else { repo.deleteConversation(row.id); onDismiss() }
        }
        Text("只删除聊天记录，引用的原话和记忆都保留。", style = RsType.caption, color = Rs.colors.textTertiary)
    }
}

@Composable
private fun SearchField(value: String, modifier: Modifier = Modifier, onChange: (String) -> Unit) {
    val c = Rs.colors
    BasicTextField(value, onChange, singleLine = true, textStyle = RsType.body.copy(color = c.text), cursorBrush = SolidColor(c.accent),
        modifier = modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.card).padding(horizontal = 14.dp, vertical = 11.dp),
        decorationBox = { inner ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Canvas(Modifier.size(16.dp)) { ICON_SEARCH(this, c.textTertiary) }
                Spacer(Modifier.width(8.dp))
                Box { if (value.isEmpty()) Text("搜索标题或聊天内容", style = RsType.body, color = c.textTertiary); inner() }
            }
        })
}

// ---------- 线条小图标（自绘，免引图标库） ----------

internal typealias Icon = DrawScope.(Color) -> Unit

/** 时钟：历史 */
internal val ICON_HISTORY: Icon = { color ->
    val w = size.minDimension * 0.09f
    drawCircle(color, radius = size.minDimension * 0.4f, style = Stroke(w))
    drawLine(color, center, Offset(center.x, center.y - size.height * 0.22f), w, StrokeCap.Round)
    drawLine(color, center, Offset(center.x + size.width * 0.16f, center.y + size.height * 0.1f), w, StrokeCap.Round)
}

/** 加号：新对话 */
internal val ICON_NEW: Icon = { color ->
    val w = size.minDimension * 0.1f
    val r = size.minDimension * 0.32f
    drawLine(color, Offset(center.x - r, center.y), Offset(center.x + r, center.y), w, StrokeCap.Round)
    drawLine(color, Offset(center.x, center.y - r), Offset(center.x, center.y + r), w, StrokeCap.Round)
}

/** 放大镜：搜索 */
internal val ICON_SEARCH: Icon = { color ->
    val w = size.minDimension * 0.12f
    val r = size.minDimension * 0.3f
    val o = Offset(size.width * 0.42f, size.height * 0.42f)
    drawCircle(color, radius = r, center = o, style = Stroke(w))
    drawLine(color, Offset(o.x + r * 0.75f, o.y + r * 0.75f), Offset(size.width * 0.9f, size.height * 0.9f), w, StrokeCap.Round)
}

/** 圆形图标按钮：白底带淡阴影；accent 时浅橙底橙线 */
@Composable
internal fun IconCircle(icon: Icon, accent: Boolean = false, onClick: () -> Unit) {
    val c = Rs.colors
    Box(
        Modifier.springClick(onClick).shadow(if (c.isDark || accent) 0.dp else 6.dp, CircleShape, ambientColor = c.shadow, spotColor = c.shadow)
            .size(40.dp).clip(CircleShape).background(if (accent) c.accentSoft else c.card),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(20.dp)) { icon(this, if (accent) c.accent else c.textSecondary) }
    }
}

private fun historyGroup(at: Long): String {
    val days = ChronoUnit.DAYS.between(Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).toLocalDate(), LocalDate.now())
    return when { days <= 0L -> "今天"; days == 1L -> "昨天"; days < 7L -> "这一周"; else -> "更早" }
}

private fun historyTime(at: Long): String {
    val t = Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault())
    val days = ChronoUnit.DAYS.between(t.toLocalDate(), LocalDate.now())
    return t.format(DateTimeFormatter.ofPattern(if (days <= 1L) "HH:mm" else "M月d日"))
}
