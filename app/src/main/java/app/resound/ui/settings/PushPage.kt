package app.resound.ui.settings

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.resound.push.PushChannel
import app.resound.push.PushChannels
import app.resound.push.PushFormat
import app.resound.push.PushKind
import app.resound.push.PushLog
import app.resound.push.PushTopic
import app.resound.ui.theme.Rs
import app.resound.ui.theme.RsType
import app.resound.ui.verify.ActionButton
import app.resound.ui.verify.ActionKind
import app.resound.ui.verify.Pill
import app.resound.ui.verify.RsCard
import app.resound.ui.verify.RsSheet
import app.resound.ui.verify.formatClock
import app.resound.ui.verify.springClick
import kotlinx.coroutines.launch

/** 设置 → 推送通道：通道列表（开关、点开编辑）+ 最近推送记录 */
@Composable
fun PushPage(repo: PushChannels, bottomPadding: Dp) {
    val c = Rs.colors
    val channels by repo.channels.collectAsState(initial = emptyList())
    val logs by remember { repo.logs(30) }.collectAsState(initial = emptyList())
    var editing by remember { mutableStateOf<PushChannel?>(null) }
    var adding by remember { mutableStateOf(false) }

    LazyColumn(
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = bottomPadding + 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("手机通知照常发。加一个通道，还能把每日回顾、每天的记忆文档推到微信、飞书、钉钉、邮件、Obsidian，或你自己的 Agent。",
                style = RsType.subhead, color = c.textSecondary, modifier = Modifier.padding(horizontal = 4.dp))
        }
        item {
            Row(Modifier.padding(start = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("通道 · ${channels.size}", style = RsType.section, color = c.textSecondary, modifier = Modifier.weight(1f))
                Pill("添加通道", filled = channels.isEmpty()) { adding = true }
            }
        }
        if (channels.isNotEmpty()) item {
            RsCard(padding = PaddingValues(vertical = 4.dp)) {
                channels.forEachIndexed { i, ch ->
                    ChannelRow(ch, onToggle = { repo.setEnabled(ch.id, it) }) { editing = ch }
                    if (i != channels.lastIndex) Box(Modifier.padding(start = 68.dp).fillMaxWidth().height(0.5.dp).background(c.separator))
                }
            }
        }
        if (logs.isNotEmpty()) {
            item { Text("最近推送", style = RsType.section, color = c.textSecondary, modifier = Modifier.padding(start = 4.dp, top = 12.dp)) }
            item {
                RsCard(padding = PaddingValues(vertical = 4.dp)) {
                    logs.forEachIndexed { i, log ->
                        LogRow(log)
                        if (i != logs.lastIndex) Box(Modifier.padding(start = 16.dp).fillMaxWidth().height(0.5.dp).background(c.separator))
                    }
                }
            }
        }
    }
    if (adding) ChannelSheet(repo, null) { adding = false }
    editing?.let { ch -> ChannelSheet(repo, ch) { editing = null } }
}

@Composable
private fun ChannelRow(ch: PushChannel, onToggle: (Boolean) -> Unit, onOpen: () -> Unit) {
    val c = Rs.colors
    Row(Modifier.fillMaxWidth().springClick(onOpen).padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(kindColor(ch.kind).copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
            Text(ch.kind.title.take(1), style = RsType.headline, color = kindColor(ch.kind))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(ch.name, style = RsType.body.copy(fontWeight = FontWeight.SemiBold), color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                (if (ch.topics.isEmpty()) "还没选推什么" else ch.topics.joinToString("、") { it.title }) + " · " + ch.format.title,
                style = RsType.caption, color = c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(10.dp))
        Switch(ch.enabled, onToggle)
    }
}

@Composable
private fun LogRow(log: PushLog) {
    val c = Rs.colors
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 11.dp), verticalAlignment = Alignment.Top) {
        Box(Modifier.padding(top = 6.dp).size(7.dp).clip(CircleShape).background(if (log.ok) c.good else c.critical))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(log.title, style = RsType.subhead, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(log.channelName, log.topic?.title, if (log.ok) "已送达" else (log.error ?: "没发出去")).joinToString(" · "),
                style = RsType.caption, color = if (log.ok) c.textTertiary else c.critical, maxLines = 2,
            )
        }
        Text(formatClock(log.at), style = RsType.caption, color = c.textTertiary)
    }
}

/** 新建或编辑一个通道 */
@Composable
private fun ChannelSheet(repo: PushChannels, ch: PushChannel?, onDismiss: () -> Unit) {
    val c = Rs.colors
    val scope = rememberCoroutineScope()
    var kind by remember { mutableStateOf(ch?.kind ?: PushKind.PushPlus) }
    var name by remember { mutableStateOf(ch?.name ?: "") }
    val values = remember { mutableStateMapOf<String, String>() }
    var topics by remember { mutableStateOf(ch?.topics ?: setOf(PushTopic.DailyReview)) }
    var format by remember { mutableStateOf(ch?.format ?: PushFormat.Markdown) }
    var withLines by remember { mutableStateOf(ch?.memoryWithLines ?: false) }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<Result<String>?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    val fields = remember(kind) { repo.fields(kind) }

    RsSheet(if (ch == null) "添加推送通道" else ch.name, ch?.summary, onDismiss = { if (!busy) onDismiss() }, fixedHeight = true) {
        if (ch == null) {
            KIND_GROUPS.forEach { (group, kinds) ->
                Label(group)
                // 三列等宽小方块：色块首字 + 短名字
                kinds.chunked(3).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { k ->
                            KindTile(k, k == kind, Modifier.weight(1f)) { kind = k; values.clear(); result = null; topics = topics intersect repo.topics(k) }
                        }
                        repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
        Label("名字")
        Field(name, "例如：我的微信", secret = false) { name = it.take(20) }
        fields.forEach { f ->
            Label(f.label)
            Field(values[f.key] ?: "", if (ch != null && f.secret) "已保存，留空表示不改" else f.hint, f.secret) { values[f.key] = it.trim() }
        }
        Label("推什么")
        val allowed = remember(kind) { repo.topics(kind) }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PushTopic.entries.filter { it in allowed }.forEach { t -> Choice(t.title, t in topics) { topics = if (t in topics) topics - t else topics + t } }
        }
        if (PushTopic.DailyMemory !in allowed) {
            Text("这种通道有长度限制，不能推“每日记忆文档”；想给自己的 Agent 用，选自定义接口、Obsidian / 笔记文件夹、邮件、Telegram 或 Notion。",
                style = RsType.caption, color = c.textTertiary)
        } else if (PushTopic.DailyMemory in topics) {
            Text("每晚生成回顾后，把当天完整的记忆（回顾、时间线、技能卡片、待办、灵感）整理成 Markdown 推过去，方便你自己的 Agent 再加工。",
                style = RsType.caption, color = c.textSecondary)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("附上当天原话", style = RsType.body, color = c.text)
                    Text("更完整，但内容多、更私密", style = RsType.caption, color = c.textSecondary)
                }
                Switch(withLines) { withLines = it }
            }
        }
        Label("格式")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PushFormat.entries.forEach { f -> Choice(f.title, f == format) { format = f } }
        }
        result?.let { r ->
            Text(r.getOrNull() ?: r.exceptionOrNull()?.message ?: "没成功", style = RsType.subhead, color = if (r.isSuccess) c.good else c.critical)
        }
        Spacer(Modifier.height(4.dp))
        // 新建时：保存后自动发一条测试；编辑时可单独测试
        // 新建时至少要填第一项（token / 地址）；其余项可选
        val canSave = name.isNotBlank() && !busy && (ch != null || fields.firstOrNull()?.let { (values[it.key] ?: "").isNotBlank() } != false)
        ActionButton(if (busy) "正在保存…" else if (ch == null) "保存并测试" else "保存", ActionKind.Primary, enabled = canSave) {
            busy = true; result = null
            scope.launch {
                val saved = repo.save(ch?.id, kind, name.trim(), values.toMap(), topics intersect allowed, format, withLines)
                result = saved.fold({ id -> if (ch == null) repo.test(id) else Result.success("已保存") }, { Result.failure(it) })
                busy = false
                if (saved.isSuccess && result?.isSuccess == true) onDismiss()
            }
        }
        if (ch != null) {
            ActionButton(if (busy) "正在发送…" else "测试一下", ActionKind.Secondary, enabled = !busy) {
                busy = true; result = null
                scope.launch { result = repo.test(ch.id); busy = false }
            }
            ActionButton(if (confirmDelete) "确定删除？再点一次" else "删除这个通道", ActionKind.Danger) {
                if (!confirmDelete) confirmDelete = true else { repo.delete(ch.id); onDismiss() }
            }
        }
    }
}

@Composable
private fun Label(text: String) {
    Text(text, style = RsType.caption.copy(fontWeight = FontWeight.Medium), color = Rs.colors.textTertiary, modifier = Modifier.padding(top = 4.dp))
}

@Composable
private fun Field(value: String, hint: String, secret: Boolean, onChange: (String) -> Unit) {
    val c = Rs.colors
    BasicTextField(
        value, onChange, singleLine = true, textStyle = RsType.body.copy(color = c.text), cursorBrush = SolidColor(c.accent),
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.background).padding(horizontal = 14.dp, vertical = 12.dp),
        decorationBox = { inner -> Box { if (value.isEmpty()) Text(hint, style = RsType.body, color = c.textTertiary); inner() } },
    )
}

@Composable
private fun Choice(text: String, on: Boolean, onClick: () -> Unit) {
    val c = Rs.colors
    val bg by animateColorAsState(if (on) c.accent else c.background, spring(stiffness = Spring.StiffnessMediumLow), label = "pushChoice")
    val fg by animateColorAsState(if (on) Color.White else c.textSecondary, label = "pushChoiceFg")
    Text(text, style = RsType.subhead.copy(fontWeight = FontWeight.Medium), color = fg,
        modifier = Modifier.springClick(onClick).clip(CircleShape).background(bg).padding(horizontal = 14.dp, vertical = 8.dp))
}

@Composable
private fun Switch(on: Boolean, onChange: (Boolean) -> Unit) {
    val c = Rs.colors
    val track by animateColorAsState(if (on) c.accent else c.separator, label = "track")
    val x by animateDpAsState(if (on) 20.dp else 0.dp, spring(dampingRatio = 0.65f, stiffness = Spring.StiffnessMedium), label = "knob")
    Box(Modifier.size(width = 50.dp, height = 30.dp).clip(CircleShape).background(track).springClick { onChange(!on) }.padding(2.dp)) {
        Box(Modifier.padding(start = x).size(26.dp).clip(CircleShape).background(Color.White))
    }
}

private val KIND_GROUPS = listOf(
    "消息推送" to listOf(PushKind.PushPlus, PushKind.WeCom, PushKind.ServerChan, PushKind.Feishu, PushKind.DingTalk, PushKind.Telegram),
    "文档与笔记（可推每日记忆文档）" to listOf(PushKind.Email, PushKind.Notion, PushKind.MarkdownFolder),
    "自定义" to listOf(PushKind.Webhook),
)

private fun shortName(k: PushKind) = when (k) {
    PushKind.WeCom -> "企业微信"; PushKind.Feishu -> "飞书"; PushKind.DingTalk -> "钉钉"
    PushKind.MarkdownFolder -> "Obsidian"; PushKind.Webhook -> "自定义接口"; else -> k.title
}

/** 类型小方块：选中时底色换成该通道的颜色 */
@Composable
private fun KindTile(k: PushKind, on: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val c = Rs.colors
    val tint = kindColor(k)
    val bg by animateColorAsState(if (on) tint.copy(alpha = 0.14f) else c.background, label = "kindBg")
    Column(modifier.springClick(onClick).clip(RoundedCornerShape(14.dp)).background(bg)
        .then(if (on) Modifier.border(1.5.dp, tint, RoundedCornerShape(14.dp)) else Modifier)
        .padding(vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(30.dp).clip(CircleShape).background(tint.copy(alpha = if (on) 1f else 0.85f)), contentAlignment = Alignment.Center) {
            Text(shortName(k).take(1), style = RsType.subhead.copy(fontWeight = FontWeight.SemiBold), color = Color.White)
        }
        Spacer(Modifier.height(6.dp))
        Text(shortName(k), style = RsType.caption.copy(fontWeight = FontWeight.Medium), color = if (on) c.text else c.textSecondary, maxLines = 1)
    }
}

private fun kindColor(k: PushKind) = when (k) {
    PushKind.PushPlus -> Color(0xFF2E9C93)
    PushKind.WeCom -> Color(0xFF4F86C6)
    PushKind.ServerChan -> Color(0xFF7E68C2)
    PushKind.Webhook -> Color(0xFFB08A2E)
    PushKind.Feishu -> Color(0xFF3370FF)
    PushKind.DingTalk -> Color(0xFF1F7AE0)
    PushKind.Telegram -> Color(0xFF2AA3DF)
    PushKind.Email -> Color(0xFFD0743C)
    PushKind.Notion -> Color(0xFF5F5A54)
    PushKind.MarkdownFolder -> Color(0xFF7C5CC4)
}
