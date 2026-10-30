package app.resound.ui.ask

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.resound.ask.AskMessage
import app.resound.ask.AskRepository
import app.resound.ask.AskRole
import app.resound.ask.AskSource
import app.resound.ui.theme.Rs
import app.resound.ui.theme.RsType
import app.resound.ui.verify.RsCard
import app.resound.ui.verify.formatClock
import app.resound.ui.verify.springClick
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 问问：打字提问，回答逐字流出、附原话出处。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AskScreen(repo: AskRepository, bottomPadding: Dp) {
    var showHistory by remember { mutableStateOf(false) }
    if (showHistory) { AskHistoryScreen(repo, bottomPadding) { showHistory = false }; return }
    val current by repo.currentConversation.collectAsState()
    val c = Rs.colors
    val messages by repo.messages.collectAsState(initial = emptyList())
    val suggestions by repo.suggestions.collectAsState()
    val streaming = messages.any { it.streaming }
    val listState = rememberLazyListState()
    // 有新消息或回答变长时滚到底
    val lastLen = messages.lastOrNull()?.let { it.text.length + it.sources.size + (it.progress?.length ?: 0) } ?: 0
    LaunchedEffect(messages.size, lastLen) { if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size) }

    Column(Modifier.fillMaxSize().background(c.background).windowInsetsPadding(WindowInsets.safeDrawing).imePadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 16.dp, top = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.Bottom) {
            Text("问问", style = RsType.largeTitle, color = c.text, modifier = Modifier.weight(1f))
            // 有对话时才显示“新对话”
            AnimatedVisibility(messages.isNotEmpty(), enter = fadeIn(), exit = fadeOut()) {
                Row {
                    IconCircle(ICON_NEW, accent = true) { repo.newConversation() }
                    Spacer(Modifier.width(10.dp))
                }
            }
            IconCircle(ICON_HISTORY) { showHistory = true }
        }
        Box(Modifier.weight(1f)) {
            if (messages.isEmpty()) {
                Welcome(suggestions) { repo.ask(it) }
            } else {
                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    items(messages, key = { it.id }) { m ->
                        if (m.role == AskRole.User) UserBubble(m.text) else AnswerCard(m, onConfirm = { repo.confirmAction(m.id) }, onCancel = { repo.cancelAction(m.id) })
                    }
                    item { Spacer(Modifier.height(1.dp)) }
                }
            }
        }
        androidx.compose.runtime.key(current) { InputBar(streaming, onSend = { repo.ask(it) }, onStop = { repo.stop() }, if (WindowInsets.isImeVisible) 0.dp else bottomPadding) }
    }
}

@Composable
private fun Welcome(suggestions: List<String>, onAsk: (String) -> Unit) {
    val c = Rs.colors
    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp), verticalArrangement = Arrangement.Center) {
        Text("想问点什么？", style = RsType.title, color = c.text)
        Spacer(Modifier.height(6.dp))
        Text("哪天谁说过什么、答应过什么、定过什么，都可以问。回答会附上原话出处。", style = RsType.subhead, color = c.textSecondary)
        Spacer(Modifier.height(20.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            suggestions.forEach { q ->
                Text(q, style = RsType.subhead, color = c.accent,
                    modifier = Modifier.clip(CircleShape).background(c.accentSoft).springClick { onAsk(q) }.padding(horizontal = 14.dp, vertical = 8.dp))
            }
        }
    }
}

@Composable
private fun UserBubble(text: String) {
    val c = Rs.colors
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        Text(text, style = RsType.body, color = Color.White,
            modifier = Modifier.widthIn(max = 300.dp).clip(RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp)).background(c.accent).padding(horizontal = 16.dp, vertical = 11.dp))
    }
}

@Composable
private fun AnswerCard(m: AskMessage, onConfirm: () -> Unit, onCancel: () -> Unit) {
    val c = Rs.colors
    var openSource by remember(m.id) { mutableStateOf<Int?>(null) }
    RsCard(Modifier.animateContentSize(), padding = PaddingValues(horizontal = 18.dp, vertical = 16.dp)) {
        when {
            m.error != null -> Text(m.error, style = RsType.body, color = c.critical)
            m.text.isEmpty() && m.progress != null -> Progress(m.progress)
            else -> {
                AnswerText(m.text)
                if (m.streaming && m.progress != null) { Spacer(Modifier.height(8.dp)); Progress(m.progress) }
            }
        }
        m.action?.let { a ->
            Spacer(Modifier.height(14.dp))
            ActionCard(a, onConfirm, onCancel)
        }
        if (m.sources.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            Box(Modifier.fillMaxWidth().height(0.5.dp).background(c.separator))
            Spacer(Modifier.height(10.dp))
            Text("出处", style = RsType.caption.copy(fontWeight = FontWeight.Medium), color = c.textTertiary)
            Spacer(Modifier.height(6.dp))
            m.sources.forEachIndexed { i, s ->
                SourceRow(i + 1, s, open = openSource == i) { openSource = if (openSource == i) null else i }
            }
        }
    }
}

/** 改数据前的确认卡：浅橙底，一句话 + 前后对比；确认后变成结果 */
@Composable
private fun ActionCard(a: app.resound.ask.AskAction, onConfirm: () -> Unit, onCancel: () -> Unit) {
    val c = Rs.colors
    val (bg, tag, tagColor) = when (a.state) {
        app.resound.ask.AskActionState.Pending -> Triple(c.accentSoft, "要改一下吗", c.accent)
        app.resound.ask.AskActionState.Done -> Triple(c.good.copy(alpha = 0.1f), "✓ 已改好", c.good)
        app.resound.ask.AskActionState.Cancelled -> Triple(c.background, "已取消", c.textTertiary)
        app.resound.ask.AskActionState.Failed -> Triple(c.critical.copy(alpha = 0.08f), "没改成", c.critical)
    }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(bg).padding(14.dp)) {
        Text(tag, style = RsType.caption.copy(fontWeight = FontWeight.SemiBold), color = tagColor)
        Spacer(Modifier.height(4.dp))
        Text(a.summary, style = RsType.body.copy(fontWeight = FontWeight.Medium), color = if (a.state == app.resound.ask.AskActionState.Cancelled) c.textTertiary else c.text)
        a.detail?.let { Text(it, style = RsType.subhead, color = c.textSecondary, modifier = Modifier.padding(top = 2.dp)) }
        if (a.state == app.resound.ask.AskActionState.Pending) {
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("确认", style = RsType.subhead.copy(fontWeight = FontWeight.SemiBold), color = Color.White,
                    modifier = Modifier.clip(CircleShape).background(c.accent).springClick(onConfirm).padding(horizontal = 18.dp, vertical = 8.dp))
                Text("算了", style = RsType.subhead.copy(fontWeight = FontWeight.Medium), color = c.textSecondary,
                    modifier = Modifier.clip(CircleShape).background(c.card).springClick(onCancel).padding(horizontal = 18.dp, vertical = 8.dp))
            }
        }
    }
}

/** “正在翻……”：文字带呼吸感 */
@Composable
private fun Progress(text: String) {
    val c = Rs.colors
    val t = rememberInfiniteTransition(label = "progress")
    val a by t.animateFloat(0.45f, 1f, infiniteRepeatable(tween(700, easing = LinearEasing), RepeatMode.Reverse), label = "a")
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(7.dp).alpha(a).clip(CircleShape).background(c.accent))
        Spacer(Modifier.width(8.dp))
        Text(text, style = RsType.subhead, color = c.textSecondary, modifier = Modifier.alpha(0.6f + a * 0.4f))
    }
}

@Composable
private fun SourceRow(n: Int, s: AskSource, open: Boolean, onClick: () -> Unit) {
    val c = Rs.colors
    // 引用的事件按 id 取当前内容：编辑过就显示新标题并标“已编辑”，点开详情看到的也是新的；回答正文是当时写的，不改
    val memory = if (app.resound.AppGraph.isMemoryReady) app.resound.AppGraph.memory else null
    val current by androidx.compose.runtime.produceState<app.resound.digest.MemoryEvent?>(null, s.id, s.kind) {
        value = if (s.kind == "event") memory?.let { runCatching { it.eventById(s.id) }.getOrNull() } else null
    }
    var detail by remember { mutableStateOf(false) }
    if (detail && memory != null) current?.let { e -> app.resound.ui.today.EventSheet(e, memory) { detail = false } }
    Column(Modifier.fillMaxWidth().springClick(onClick).padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(18.dp).clip(CircleShape).background(c.accentSoft), contentAlignment = Alignment.Center) {
                Text("$n", style = RsType.caption.copy(fontSize = 11.sp, fontWeight = FontWeight.SemiBold), color = c.accent)
            }
            Spacer(Modifier.width(8.dp))
            Text(sourceTime(s.at), style = RsType.caption, color = c.textTertiary)
            Spacer(Modifier.width(6.dp))
            // 原话类出处的 title 只是说话人（名字或临时编号），后面接一截原话才看得出是哪句
            val title = if (s.kind == "line") "${lineSpeaker(s.title)}：${s.quote}" else current?.title ?: s.title
            Text(title, style = RsType.subhead, color = c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            if (current?.editedAt != null) {
                Spacer(Modifier.width(6.dp))
                Text("已编辑", style = RsType.caption, color = c.textTertiary)
            }
        }
        AnimatedVisibility(open) {
            Column(Modifier.padding(start = 26.dp, top = 6.dp)) {
                Text("“${s.quote}”", style = RsType.subhead, color = c.text,
                    modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(c.background).padding(10.dp))
                if (current != null) Text("查看这件事 ›", style = RsType.subhead.copy(fontWeight = FontWeight.Medium), color = c.accent,
                    modifier = Modifier.padding(top = 6.dp).springClick { detail = true })
            }
        }
    }
}

/** 与原话页一致：临时编号 0 → 说话人 1，uncertain:* → 待确认，有名字直接用。 */
private fun lineSpeaker(title: String): String = when {
    title.startsWith("uncertain:") -> "待确认"
    title.toIntOrNull() != null -> "说话人 ${title.toInt() + 1}"
    else -> title
}

private fun sourceTime(at: Long): String =
    Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("M月d日")) + " " + formatClock(at)

@Composable
private fun InputBar(streaming: Boolean, onSend: (String) -> Unit, onStop: () -> Unit, bottomPadding: Dp) {
    val c = Rs.colors
    var input by remember { mutableStateOf("") }
    val send = { if (input.isNotBlank() && !streaming) { onSend(input.trim()); input = "" } }
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = bottomPadding + 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicTextField(
            value = input,
            onValueChange = { input = it },
            textStyle = RsType.body.copy(color = c.text),
            cursorBrush = SolidColor(c.accent),
            maxLines = 4,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { send() }),
            modifier = Modifier.weight(1f),
            decorationBox = { inner ->
                Box(
                    Modifier.shadow(if (c.isDark) 0.dp else 8.dp, RoundedCornerShape(24.dp), ambientColor = c.shadow, spotColor = c.shadow)
                        .clip(RoundedCornerShape(24.dp)).background(c.card).padding(horizontal = 18.dp, vertical = 13.dp),
                ) {
                    if (input.isEmpty()) Text("念念不忘，必有回响...", style = RsType.body, color = c.textTertiary)
                    inner()
                }
            },
        )
        Spacer(Modifier.width(10.dp))
        val active = streaming || input.isNotBlank()
        Box(
            Modifier.size(46.dp).clip(CircleShape).background(if (active) c.accent else c.accentSoft)
                .springClick { if (streaming) onStop() else send() },
            contentAlignment = Alignment.Center,
        ) {
            if (streaming) Box(Modifier.size(14.dp).clip(RoundedCornerShape(3.dp)).background(Color.White))
            else Text("↑", style = RsType.title.copy(fontWeight = FontWeight.Bold), color = if (active) Color.White else c.accent)
        }
    }
}
