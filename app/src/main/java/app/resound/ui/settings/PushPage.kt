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
import androidx.compose.foundation.layout.offset
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
import app.resound.push.FolderLayout
import app.resound.push.LinesMode
import app.resound.push.RecordTrigger
import app.resound.push.RecordKind
import app.resound.push.PushChannelStatus
import app.resound.push.PushCapabilities
import app.resound.push.PushSubscription
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
            Text("手机通知照常发。加一个通道，还能把每日回顾、每天的记忆文档推到微信、飞书、钉钉、邮件；想交给你自己的智能体读，用“坚果云同步文件夹”或自定义接口。",
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
                subscriptionLine(ch.subscription),
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
                style = RsType.caption, color = if (log.ok) c.textTertiary else c.critical, maxLines = 3,
            )
        }
        Text(app.resound.ui.verify.formatStamp(log.at), style = RsType.caption, color = c.textTertiary)
    }
}

/** 新建或编辑一个通道 */
@Composable
private fun ChannelSheet(repo: PushChannels, ch: PushChannel?, onDismiss: () -> Unit) {
    val c = Rs.colors
    val scope = rememberCoroutineScope()
    var kind by remember { mutableStateOf(ch?.kind ?: PushKind.PushPlus) }
    var name by remember { mutableStateOf(ch?.name ?: shortName(PushKind.PushPlus)) }
    // 编辑时把已保存的地址等非密钥项填回来；密钥仍显示“已保存，留空表示不改”
    val values = remember { mutableStateMapOf<String, String>().apply { ch?.plainValues?.let { putAll(it) } } }
    var topics by remember { mutableStateOf(ch?.topics ?: setOf(PushTopic.DailyReview)) }
    var format by remember { mutableStateOf(ch?.format ?: PushFormat.Markdown) }
    var sub by remember { mutableStateOf(ch?.subscription ?: PushSubscription.PhoneNotice) }
    var linked by remember { mutableStateOf(ch?.useLinkedNutstore ?: true) }
    val linkedAccount by repo.linkedNutstoreAccount.collectAsState(initial = null)
    val caps = remember(kind) { repo.capabilities(kind) }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<Result<String>?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmRebuild by remember { mutableStateOf(false) }
    val fields = remember(kind) { repo.fields(kind) }

    RsSheet(if (ch == null) "添加推送通道" else ch.name, ch?.summary, onDismiss = { if (!busy) onDismiss() }, fixedHeight = true) {
        if (ch == null) {
            KIND_GROUPS.forEach { (group, kinds) ->
                Label(group)
                // 三列等宽小方块：色块首字 + 短名字
                kinds.chunked(3).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { k ->
                            KindTile(k, k == kind, Modifier.weight(1f)) {
                                // 名字没改过就跟着类型走，免得保存按钮因为没填名字一直是灰的
                                if (ch == null && (name.isBlank() || name == shortName(kind))) name = shortName(k)
                                kind = k; values.clear(); result = null; topics = topics intersect repo.topics(k)
                                // 换通道时给一个合适的起点：文件夹类默认“给智能体用”，其余默认“手机通知”
                                if (ch == null) sub = if (repo.capabilities(k).records) PushSubscription.ForAgents else PushSubscription.PhoneNotice
                                if (ch == null && k == PushKind.MarkdownFolder) {
                                    if (values["folder"].isNullOrBlank()) values["folder"] = "Resound-Memory"
                                    if (values["url"].isNullOrBlank()) values["url"] = NUTSTORE_DAV
                                }
                            }
                        }
                        repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
        Label("名字")
        Field(name, "例如：我的微信", secret = false) { name = it.take(20) }
        val folderKind = kind == PushKind.MarkdownFolder
        if (folderKind) {
            Label("坚果云账号")
            app.resound.ui.skills.Segments(listOf("用已连接的坚果云", "单独配置"), if (linked) 0 else 1) {
                linked = it == 0
                // 单独配置时服务器地址直接填好坚果云的，只需填账号和应用密码
                if (!linked && values["url"].isNullOrBlank()) values["url"] = NUTSTORE_DAV
            }
            if (linked) Text(linkedAccount?.let { "用数据备份里已连接的 $it，不用再填账号密码" } ?: "数据备份里还没连接坚果云，先去连接，或改为单独配置",
                style = RsType.caption, color = if (linkedAccount == null) c.critical else c.textSecondary)
        }
        fields.filter { !(folderKind && linked && it.key != "folder") }.forEach { f ->
            Label(f.label)
            Field(values[f.key] ?: "", if (ch != null && f.secret) "已保存，留空表示不改" else f.hint, f.secret) { values[f.key] = it.trim() }
        }
        val allowed = remember(kind) { repo.topics(kind) }
        SubscriptionEditor(sub, caps, isNew = ch == null) { sub = it }
        if (caps.formats.size > 1) {
            Label("格式")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PushFormat.entries.filter { it in caps.formats }.forEach { f -> Choice(f.title, f == format) { format = f } }
            }
            // 自定义接口：三种格式发出去的内容不同，说清楚各自适合什么
            if (kind == PushKind.Webhook) Text(when (format) {
                PushFormat.Json -> "发 JSON，适合程序对接"
                PushFormat.Markdown -> "直接发 Markdown 正文，适合存成笔记"
                PushFormat.Text -> "发纯文字，不带格式符号"
            }, style = RsType.caption, color = c.textTertiary)
        }
        result?.let { r ->
            Text(r.getOrNull() ?: r.exceptionOrNull()?.message ?: "没成功", style = RsType.subhead, color = if (r.isSuccess) c.good else c.critical)
        }
        Spacer(Modifier.height(4.dp))
        // 新建时：保存后自动发一条测试；编辑时可单独测试
        // 新建时至少要填第一项（token / 地址）；其余项可选
        val needFirst = !(kind == PushKind.MarkdownFolder && linked)
        val canSave = name.isNotBlank() && !busy && (ch != null || !needFirst || fields.firstOrNull()?.let { (values[it.key] ?: "").isNotBlank() } != false)
        ActionButton(if (busy) "正在保存…" else if (ch == null) "保存并测试" else "保存", ActionKind.Primary, enabled = canSave) {
            busy = true; result = null
            scope.launch {
                val saved = repo.saveWithSubscription(ch?.id, kind, name.trim(), values.toMap(), sub, format, kind == PushKind.MarkdownFolder && linked)
                result = saved.fold({ id -> if (ch == null) repo.test(id) else Result.success("已保存") }, { Result.failure(it) })
                busy = false
                if (saved.isSuccess && result?.isSuccess == true) onDismiss()
            }
        }
        if (ch != null) {
            // 推送状态 + 现在推一次 / 全部重新生成
            val st by remember(ch.id) { repo.status(ch.id) }.collectAsState(initial = null)
            st?.let { s -> StatusLine(s) }
            if (caps.records && (ch.subscription.records.isNotEmpty() || ch.subscription.lines != LinesMode.Off)) {
                ActionButton(if (st?.running == true) "正在推…" else "现在推一次", ActionKind.Secondary, enabled = !busy && st?.running != true) {
                    busy = true; result = null
                    scope.launch { result = repo.pushNow(ch.id); busy = false }
                }
                if (caps.folder) ActionButton(if (confirmRebuild) "确定全部重新生成？再点一次" else "全部重新生成", ActionKind.Secondary, enabled = !busy && st?.running != true) {
                    if (!confirmRebuild) { confirmRebuild = true; return@ActionButton }
                    confirmRebuild = false; busy = true; result = null
                    scope.launch { result = repo.rebuild(ch.id); busy = false }
                }
            }
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
    // 未选中用白底：放在浅色分块里也看得清
    val bg by animateColorAsState(if (on) c.accent else c.card, spring(stiffness = Spring.StiffnessMediumLow), label = "pushChoice")
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
        Box(Modifier.offset(x = x).size(26.dp).clip(CircleShape).background(Color.White))
    }
}

private val KIND_GROUPS = listOf(
    "消息推送" to listOf(PushKind.PushPlus, PushKind.WeCom, PushKind.ServerChan, PushKind.Feishu, PushKind.DingTalk, PushKind.Telegram),
    "资料（可推整理结果，给智能体读）" to listOf(PushKind.Email, PushKind.Notion, PushKind.MarkdownFolder),
    "自定义" to listOf(PushKind.Webhook),
)

private fun shortName(k: PushKind) = when (k) {
    PushKind.WeCom -> "企业微信"; PushKind.Feishu -> "飞书"; PushKind.DingTalk -> "钉钉"
    PushKind.MarkdownFolder -> "坚果云同步"; PushKind.Webhook -> "自定义接口"; else -> k.title
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


private const val NUTSTORE_DAV = "https://dav.jianguoyun.com/dav/"

/** 列表里一行概括订阅：如“摘要 · 提醒 · 整理结果 5 类 · 有新内容就推 · 增量” */
private fun subscriptionLine(s: PushSubscription): String = buildList {
    if (s.summaries) add("摘要")
    if (s.reminders) add("提醒")
    if (s.records.isNotEmpty()) add("整理结果 ${s.records.size} 类")
    if (s.lines != LinesMode.Off) add("原话")
    if (s.records.isNotEmpty() || s.lines != LinesMode.Off) {
        add(when (val t = s.trigger) {
            is RecordTrigger.OnChange -> "有新内容就推"
            is RecordTrigger.Schedule -> t.times.sorted().joinToString("、") { "%d:%02d".format(it / 60, it % 60) } + " 推"
            RecordTrigger.Manual -> "只手动推"
        })
        add(if (s.incremental) "增量" else "全量")
    }
}.ifEmpty { listOf("还没选推什么") }.joinToString(" · ")

@Composable
private fun StatusLine(s: PushChannelStatus) {
    val c = Rs.colors
    val text = when {
        s.running -> "正在推送…"
        s.lastError != null -> "上次没推成功：${s.lastError}"
        s.lastPushAt != null -> "上次 ${app.resound.ui.verify.formatStamp(s.lastPushAt)}" + (s.lastResult?.let { " · $it" } ?: "")
        else -> "还没推过"
    }
    Text(text, style = RsType.subhead, color = if (s.lastError != null && !s.running) c.critical else c.textSecondary)
}

private fun hm(m: Int) = "%d:%02d".format(m / 60, m % 60)

/**
 * 推送订阅：模板 → 推什么 → 整理结果什么时候推 → 增量还是全量 → 范围 → 文件夹布局。
 * 只显示这种通道能用的选项（caps）；所有默认值都能改，模板只是预先填好。
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun SubscriptionEditor(sub: PushSubscription, caps: PushCapabilities, isNew: Boolean, onChange: (PushSubscription) -> Unit) {
    val c = Rs.colors
    // 一键模板
    val templates = buildList {
        add("手机通知" to PushSubscription.PhoneNotice)
        if (caps.records) { add("给智能体用" to PushSubscription.ForAgents); add("完整归档" to PushSubscription.FullArchive) }
    }
    if (templates.size > 1) Block("一键模板", "选了再细调，所有设置都能改") {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            templates.forEach { (n, t) ->
                val fixed = if (caps.incremental) t else t.copy(incremental = false, fullCheckAt = null)
                Choice(n, sub == fixed) { onChange(fixed) }
            }
        }
    }

    Block("推什么") {
    ToggleRow("摘要", "每日回顾、每周回顾、技能卡片，生成后自动推", sub.summaries) { onChange(sub.copy(summaries = it)) }
    ToggleRow("提醒", "待办到期、标了重点 3 天没进展，到点推", sub.reminders) { onChange(sub.copy(reminders = it)) }
    if (!caps.records) {
        Text("这种通道只能发短消息，推不了整理结果和原话；想给智能体读，用坚果云同步文件夹或自定义接口。", style = RsType.caption, color = c.textTertiary)
    } else {
    SubLabel("整理结果（可多选）")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        RecordKind.entries.forEach { k ->
            Choice(k.title, k in sub.records) { onChange(sub.copy(records = if (k in sub.records) sub.records - k else sub.records + k)) }
        }
    }
    SubLabel("原话")
    app.resound.ui.skills.Segments(LinesMode.entries.map { it.title }, sub.lines.ordinal) { onChange(sub.copy(lines = LinesMode.entries[it])) }
    if (sub.lines != LinesMode.Off) ToggleRow("带上语音输入的上下文", "语音输入时屏幕上的对话（别人的消息、AI 回复等）", sub.lineContext) { onChange(sub.copy(lineContext = it)) }
    if (sub.lines != LinesMode.Off) Text("原话会以明文推出去，注意保管；“只推我说的”只含声纹认出是你的话。", style = RsType.caption, color = c.warning)
    }
    }
    if (!caps.records || (sub.records.isEmpty() && sub.lines == LinesMode.Off)) return

    Block("什么时候推", "摘要生成后自动推、提醒到点推；下面只管整理结果和原话") {
    val ti = when (sub.trigger) { is RecordTrigger.OnChange -> 0; is RecordTrigger.Schedule -> 1; RecordTrigger.Manual -> 2 }
    app.resound.ui.skills.Segments(listOf("有新内容就推", "按时间推", "只手动"), ti) {
        onChange(sub.copy(trigger = when (it) {
            0 -> sub.trigger as? RecordTrigger.OnChange ?: RecordTrigger.OnChange(30)
            1 -> sub.trigger as? RecordTrigger.Schedule ?: RecordTrigger.Schedule(listOf(22 * 60))
            else -> RecordTrigger.Manual
        }))
    }
    when (val t = sub.trigger) {
        is RecordTrigger.OnChange -> {
            SubLabel("两次之间至少隔")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(15, 30, 60, 120, 240).forEach { m -> Choice(if (m < 60) "$m 分钟" else "${m / 60} 小时", t.minGapMinutes == m) { onChange(sub.copy(trigger = RecordTrigger.OnChange(m))) } }
            }
        }
        is RecordTrigger.Schedule -> {
            SubLabel("每天这些时间推（可多选）")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(7, 9, 12, 15, 18, 21, 22, 23).map { it * 60 }.forEach { m ->
                    Choice(hm(m), m in t.times) {
                        val next = if (m in t.times) t.times - m else t.times + m
                        if (next.isNotEmpty()) onChange(sub.copy(trigger = t.copy(times = next.sorted())))
                    }
                }
            }
            SubLabel("哪几天")
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("一", "二", "三", "四", "五", "六", "日").forEachIndexed { i, w ->
                    val d = i + 1
                    val on = t.weekdays.isEmpty() || d in t.weekdays
                    Box(Modifier.size(34.dp).clip(CircleShape).background(if (on) c.accent else c.background).springClick {
                        val cur = t.weekdays.ifEmpty { (1..7).toSet() }
                        val next = if (d in cur) cur - d else cur + d
                        if (next.isNotEmpty()) onChange(sub.copy(trigger = t.copy(weekdays = if (next.size == 7) emptySet() else next)))
                    }, contentAlignment = Alignment.Center) { Text(w, style = RsType.subhead, color = if (on) Color.White else c.textSecondary) }
                }
            }
        }
        RecordTrigger.Manual -> Text("只在通道详情里点“现在推一次”时推。", style = RsType.caption, color = c.textSecondary)
    }
    }

    if (caps.incremental) Block("增量还是全量") {
        app.resound.ui.skills.Segments(listOf("增量（只推变化）", "全量（每次完整）"), if (sub.incremental) 0 else 1) { onChange(sub.copy(incremental = it == 0)) }
        if (sub.incremental) {
            SubLabel("每天全量校对一次，防止漏推")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                (listOf<Int?>(null) + listOf(21, 22, 23).map { it * 60 }).forEach { m ->
                    Choice(m?.let { hm(it) } ?: "不校对", sub.fullCheckAt == m) { onChange(sub.copy(fullCheckAt = m)) }
                }
            }
        }
    }

    Block("范围") {
    SubLabel("只推最近")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf<Int?>(7, 30, 90, 365, null).forEach { d -> Choice(d?.let { "$it 天" } ?: "不限", sub.rangeDays == d) { onChange(sub.copy(rangeDays = d)) } }
    }
    ToggleRow("排除电视、视频等媒体", "外放的剧、视频不推", sub.excludeMedia) { onChange(sub.copy(excludeMedia = it)) }
    if (isNew) {
        SubLabel("第一次开启时，补推以前的")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf<Pair<String, Int?>>("从今天开始" to 0, "最近 30 天" to 30, "全部" to null).forEach { (n, d) ->
                Choice(n, sub.backfillDays == d) { onChange(sub.copy(backfillDays = d)) }
            }
        }
    }
    }
    if (caps.folder) Block("文件怎么放") {
        app.resound.ui.skills.Segments(FolderLayout.entries.map { it.title }, sub.layout.ordinal) { onChange(sub.copy(layout = FolderLayout.entries[it])) }
        Text(if (sub.layout == FolderLayout.ByCategory) "重点、待办、灵感、决定各一个汇总文件，再加每天一个文件；适合给智能体读。"
            else "只按天放，每天一个文件写全当天内容；适合归档。", style = RsType.caption, color = c.textTertiary)
    }
}

/** 订阅设置里的一块：浅底圆角，标题统一样式 */
@Composable
private fun Block(title: String, desc: String? = null, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    val c = Rs.colors
    Column(Modifier.fillMaxWidth().clip(androidx.compose.foundation.shape.RoundedCornerShape(18.dp)).background(c.background).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, style = RsType.body.copy(fontWeight = FontWeight.SemiBold), color = c.text)
        desc?.let { Text(it, style = RsType.caption, color = c.textTertiary) }
        content()
    }
}

@Composable
private fun SubLabel(text: String) {
    Text(text, style = RsType.caption.copy(fontWeight = FontWeight.Medium), color = Rs.colors.textSecondary, modifier = Modifier.padding(top = 2.dp))
}

@Composable
private fun ToggleRow(title: String, desc: String, on: Boolean, onChange: (Boolean) -> Unit) {
    val c = Rs.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = RsType.body, color = c.text)
            Text(desc, style = RsType.caption, color = c.textSecondary)
        }
        Switch(on, onChange)
    }
}
