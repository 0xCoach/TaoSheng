package app.resound.ui.topic

import android.widget.Toast
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.resound.AppGraph
import app.resound.digest.MemoryEvent
import app.resound.topic.Topic
import app.resound.topic.TopicKind
import app.resound.topic.TopicStatus
import app.resound.ui.theme.Rs
import app.resound.ui.theme.RsType
import app.resound.ui.verify.ActionButton
import app.resound.ui.verify.ActionKind
import app.resound.ui.verify.RsCard
import app.resound.ui.verify.RsSheet
import app.resound.ui.verify.formatClock
import app.resound.ui.verify.springClick
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** 卡 135 专题录音的界面入口；面板都挂在 AppShell 上，任何页面都能打开 */
object TopicUi {
    var openTopic by mutableStateOf<Long?>(null)
    var scheduling by mutableStateOf(false)
    /** 事后补：从某件事出发（可连带前后几件），或为 null 时按时间段 */
    var creatingFrom by mutableStateOf<MemoryEvent?>(null)
    var creatingRange by mutableStateOf(false)
    /** 从其他页面直接开始某类专题（如原话设置里的“开始培训专题”） */
    var starting by mutableStateOf<TopicKind?>(null)
}

private val zone get() = ZoneId.systemDefault()
fun topicLabel(t: Topic) = t.name?.takeIf { it.isNotBlank() } ?: t.kind.title
private fun minutesText(ms: Long): String {
    val m = (ms / 60_000).coerceAtLeast(0)
    return if (m < 60) "$m 分钟" else if (m % 60 == 0L) "${m / 60} 小时" else "${m / 60} 小时 ${m % 60} 分"
}
private fun startOf(t: Topic) = t.spans.minOfOrNull { it.startAt } ?: t.plannedStartAt ?: t.createdAt
private fun endOf(t: Topic, now: Long) = t.spans.maxOfOrNull { it.endAt ?: now } ?: t.plannedEndAt ?: now

/** 首页主卡专题行：“会议专题 · 23 分”“周会 · 1 小时 5 分” */
fun topicHomeTitle(t: Topic, now: Long = System.currentTimeMillis()): String {
    val m = ((now - startOf(t)) / 60_000).coerceAtLeast(0)
    val name = t.name?.takeIf { it.isNotBlank() } ?: (t.kind.title + "专题")
    return "$name · " + if (m < 60) "$m 分" else "${m / 60} 小时 ${m % 60} 分"
}

/** 首页主卡专题副行：“已暂停 · 已记下 2 处重点 · 11:00 结束”；没什么可说时为空 */
fun topicHomeSub(t: Topic): String = buildList {
    if (t.status == TopicStatus.Paused) add("已暂停")
    if (t.marks.isNotEmpty()) add("已记下 ${t.marks.size} 处重点")
    t.plannedEndAt?.let { add(formatClock(it) + " 结束") }
}.joinToString(" · ")

/** “会议中 · 已 35 分钟 · 11:00 结束” */
fun topicLine(t: Topic, now: Long = System.currentTimeMillis()): String = buildString {
    append(topicLabel(t))
    append(if (t.status == TopicStatus.Paused) " · 已暂停" else "中")
    append(" · 已 ").append(minutesText(now - startOf(t)))
    t.plannedEndAt?.let { append(" · ").append(formatClock(it)).append(" 结束") }
}

@Composable
internal fun TopicKindChoice(selected: TopicKind, onSelect: (TopicKind) -> Unit) {
    val c = Rs.colors
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TopicKind.entries.forEach { k ->
            val on = k == selected
            val bg by animateColorAsState(if (on) c.accent else c.fill, spring(stiffness = Spring.StiffnessMediumLow), label = "topicKind")
            Text(k.title, style = RsType.subhead.copy(fontWeight = FontWeight.Medium), color = if (on) Color.White else c.textSecondary,
                modifier = Modifier.clip(CircleShape).background(bg).springClick { onSelect(k) }.padding(horizontal = 14.dp, vertical = 8.dp))
        }
    }
}

private fun kindHint(k: TopicKind) = when (k) {
    TopicKind.Meeting -> "多人讨论，结束后出会议纪要"
    TopicKind.Training -> "一人主讲，不区分说话人，出培训笔记"
    TopicKind.Call -> "两人一来一回，出通话记录"
    TopicKind.Script -> "对着手机口述，出整理版和原话版两份稿子"
    TopicKind.Normal -> "合成一件事，不出纪要"
}

@Composable
internal fun NameInput(value: String, onChange: (String) -> Unit, hint: String = "名字（可不填，填了转写时认得更准）") {
    val c = Rs.colors
    BasicTextField(value, { onChange(it.take(40)) }, singleLine = true, textStyle = RsType.body.copy(color = c.text),
        cursorBrush = SolidColor(c.accent), keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), modifier = Modifier.fillMaxWidth(),
        decorationBox = { inner ->
            Box(Modifier.clip(RoundedCornerShape(14.dp)).background(c.fill).padding(horizontal = 14.dp, vertical = 12.dp)) {
                if (value.isEmpty()) Text(hint, style = RsType.body, color = c.textTertiary)
                inner()
            }
        })
}

@Composable
private fun Label(text: String) = Text(text, style = RsType.caption.copy(fontWeight = FontWeight.Medium), color = Rs.colors.textTertiary)

@Composable
private fun LinkText(text: String, onClick: () -> Unit) =
    Text(text, style = RsType.subhead.copy(fontWeight = FontWeight.Medium), color = Rs.colors.accent,
        modifier = Modifier.clip(CircleShape).springClick(onClick).padding(vertical = 4.dp))

/** 失败（时段冲突等）用固定文案提示，不抛到界面外 */
private suspend fun guarded(context: android.content.Context, block: suspend () -> Unit): Boolean =
    try { block(); true } catch (e: kotlinx.coroutines.CancellationException) { throw e }
    catch (e: Exception) { Toast.makeText(context, e.message?.takeIf { e is app.resound.topic.TopicException } ?: "没成功，请再试一次", Toast.LENGTH_SHORT).show(); false }

/** 聆听面板里的“专题录音”一节：没开始时选类型开始；进行中时直接操作 */
@Composable
fun TopicSection(onDismiss: () -> Unit, initialKind: TopicKind = TopicKind.Meeting) {
    if (!AppGraph.isTopicsReady) return
    val current by AppGraph.topics.current.collectAsState()
    Label("专题录音")
    current?.let { TopicRunningPanel(it); return }
    TopicStartPanel(initialKind, onStarted = onDismiss)
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        LinkText("预约专题 ›") { TopicUi.scheduling = true }
        LinkText("忘了点开始？选一段时间 ›") { TopicUi.creatingRange = true }
    }
}

@Composable
internal fun TopicStartPanel(initialKind: TopicKind = TopicKind.Meeting, onStarted: () -> Unit) {
    val c = Rs.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var kind by remember { mutableStateOf(initialKind) }
    var name by remember { mutableStateOf("") }
    var minutes by remember { mutableIntStateOf(60) }
    var unlimited by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    Text("把一段时间的录音合成一件事，结束后出纪要；没人说话照样不录", style = RsType.caption, color = c.textSecondary)
    TopicKindChoice(kind) { kind = it }
    Text(kindHint(kind), style = RsType.caption, color = c.textTertiary)
    NameInput(name, { name = it })
    Row(verticalAlignment = Alignment.Bottom) {
        Text(if (unlimited) "不限时" else app.resound.ui.transcript.durationText(minutes), style = RsType.title.copy(fontFeatureSettings = "tnum"),
            color = if (unlimited) c.textSecondary else c.text, modifier = Modifier.weight(1f))
        Text(if (unlimited) "手动结束，最长 4 小时" else "到 ${LocalTime.now().plusMinutes(minutes.toLong()).let { "%d:%02d".format(it.hour, it.minute) }} 结束",
            style = RsType.caption.copy(fontFeatureSettings = "tnum"), color = c.textTertiary, modifier = Modifier.padding(bottom = 3.dp))
    }
    app.resound.ui.transcript.StepSlider(minutes, enabled = !unlimited) { minutes = it; unlimited = false }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        val bg by animateColorAsState(if (unlimited) c.accentSoft else c.fill, label = "topicUnlimited")
        Text("不限时", style = RsType.subhead.copy(fontWeight = FontWeight.Medium), color = if (unlimited) c.accent else c.textSecondary,
            modifier = Modifier.clip(CircleShape).background(bg).springClick { unlimited = !unlimited }.padding(horizontal = 14.dp, vertical = 9.dp))
        Box(Modifier.weight(1f).clip(CircleShape).background(if (busy) c.separator else c.accent).springClick {
            if (busy) return@springClick
            busy = true
            scope.launch {
                val r = runCatching { AppGraph.topics.start(kind, name.trim().ifEmpty { null }, if (unlimited) null else minutes) }.getOrNull()
                busy = false
                val msg = when {
                    r == null -> "没开始成功，请再试一次"
                    r.error != null -> r.error
                    r.recordingDisabled -> "专题已开始，但录音没开，记得打开聆听"
                    r.quietOverride -> "专题已开始，这段时间原本设为不录音，专题期间照常录"
                    else -> "${kind.title}专题已开始，往前多算了 2 分钟"
                }
                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                if (r?.topicId != null) { if (AppGraph.isQuickPrefsReady) AppGraph.quickPrefs.setLastTopicKind(kind); onStarted() }
            }
        }.padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
            Text("开始专题", style = RsType.subhead.copy(fontWeight = FontWeight.Medium), color = Color.White)
        }
    }
}

/** 进行中：记一下 / 暂停·继续 / 结束；有预设结束时可延长；可随时改类型 */
@Composable
internal fun TopicRunningPanel(t: Topic) {
    val c = Rs.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(t.id) { while (true) { delay(20_000); now = System.currentTimeMillis() } }
    var confirmEnd by remember(t.id) { mutableStateOf(false) }
    LaunchedEffect(confirmEnd) { if (confirmEnd) { delay(4000); confirmEnd = false } }
    val paused = t.status == TopicStatus.Paused
    RsCard(color = c.accentSoft, padding = PaddingValues(16.dp)) {
        Text(topicLine(t, now), style = RsType.headline, color = c.accent, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (t.marks.isNotEmpty()) Text("已记下 ${t.marks.size} 处重点", style = RsType.caption, color = c.textSecondary, modifier = Modifier.padding(top = 2.dp))
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill("★ 记一下", filled = true, Modifier.weight(1f)) {
                scope.launch { if (guarded(context) { AppGraph.topics.mark() }) Toast.makeText(context, "已记下这个时刻", Toast.LENGTH_SHORT).show() }
            }
            Pill(if (paused) "继续" else "暂停", filled = false, Modifier.weight(1f)) {
                scope.launch { guarded(context) { if (paused) AppGraph.topics.resume() else AppGraph.topics.pause() } }
            }
            Pill(if (confirmEnd) "确定结束？" else "结束", filled = false, Modifier.weight(1f), danger = confirmEnd) {
                if (!confirmEnd) { confirmEnd = true; return@Pill }
                scope.launch { if (guarded(context) { AppGraph.topics.end() }) Toast.makeText(context, "专题已结束，转写好后自动出纪要", Toast.LENGTH_SHORT).show() }
            }
        }
        if (paused) Text("暂停期间说的话不算进专题；结束时间不顺延", style = RsType.caption, color = c.textTertiary, modifier = Modifier.padding(top = 8.dp))
        if (t.plannedEndAt != null) LinkText("延长 30 分钟 ›") { scope.launch { guarded(context) { AppGraph.topics.extend(30) } } }
    }
    Label("类型不对可以随时改")
    TopicKindChoice(t.kind) { k -> if (k != t.kind) scope.launch { guarded(context) { AppGraph.topics.update(t.id, k, t.name) } } }
    LinkText("查看这场专题 ›") { TopicUi.openTopic = t.id }
}

@Composable
private fun Pill(text: String, filled: Boolean, modifier: Modifier, danger: Boolean = false, onClick: () -> Unit) {
    val c = Rs.colors
    val bg = when { danger -> c.critical; filled -> c.accent; else -> c.card }
    Box(modifier.clip(CircleShape).background(bg).springClick(onClick).padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
        Text(text, style = RsType.subhead.copy(fontWeight = FontWeight.Medium), color = if (filled || danger) Color.White else c.text, maxLines = 1)
    }
}

/** 挂在 AppShell 上：按 TopicUi 的状态打开各个面板 */
@Composable
fun TopicHost() {
    if (!AppGraph.isTopicsReady) return
    TopicUi.openTopic?.let { id -> TopicSheet(id) { TopicUi.openTopic = null } }
    if (TopicUi.scheduling) TopicScheduleSheet { TopicUi.scheduling = false }
    TopicUi.creatingFrom?.let { e -> TopicCreateSheet(e) { TopicUi.creatingFrom = null } }
    if (TopicUi.creatingRange) TopicCreateSheet(null) { TopicUi.creatingRange = false }
    TopicUi.starting?.let { k ->
        val current by AppGraph.topics.current.collectAsState()
        RsSheet("开始专题", onDismiss = { TopicUi.starting = null }) {
            current?.let { TopicRunningPanel(it) } ?: TopicStartPanel(k) { TopicUi.starting = null }
        }
    }
}

private fun statusText(t: Topic): String? = when (t.status) {
    TopicStatus.Scheduled -> "已预约"
    TopicStatus.Ended -> "已结束，等这段录音转写完就整理"
    TopicStatus.Summarizing -> "正在合成这件事并写纪要…"
    TopicStatus.Failed -> t.error ?: "专题生成失败，请重试"
    TopicStatus.Cancelled -> "已取消，这段原话回到平常整理"
    else -> null
}

/** 专题详情：纪要、合成的那件事、各段时间和重点、改类型/名字、重新生成、剪掉一段、取消 */
@Composable
fun TopicSheet(id: Long, onDismiss: () -> Unit) {
    val c = Rs.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val t = remember(id) { AppGraph.topics.observe(id) }.collectAsState(initial = null).value ?: return
    val now = System.currentTimeMillis()
    val start = startOf(t); val end = endOf(t, now)
    val skills = remember { app.resound.ui.skills.skillRepo() }
    val card = t.cardId?.let { cid -> remember(cid) { skills.card(cid) }.collectAsState(initial = null).value }
    var openedCard by remember { mutableStateOf<app.resound.skills.SkillCard?>(null) }
    openedCard?.let { app.resound.ui.skills.SkillCardSheet(it, skills) { openedCard = null } }
    val event by produceState<MemoryEvent?>(null, t.eventId, t.revision) {
        value = t.eventId?.takeIf { AppGraph.isMemoryReady }?.let { AppGraph.memory.eventById(it) }
    }
    var editKind by remember(t.id, t.kind) { mutableStateOf(t.kind) }
    var editName by remember(t.id, t.name) { mutableStateOf(t.name.orEmpty()) }
    var cutting by remember { mutableStateOf(false) }
    var confirmCancel by remember { mutableStateOf(false) }
    LaunchedEffect(confirmCancel) { if (confirmCancel) { delay(4000); confirmCancel = false } }
    val day = Instant.ofEpochMilli(start).atZone(zone).toLocalDate()
    RsSheet(topicLabel(t), "${t.kind.title} · ${app.resound.ui.verify.formatUpcoming(start).substringBefore(' ')} ${formatClock(start)}–${formatClock(end)}", onDismiss = onDismiss, fixedHeight = true) {
        if (t.status == TopicStatus.Running || t.status == TopicStatus.Paused) TopicRunningPanel(t)
        statusText(t)?.let { Text(it, style = RsType.subhead, color = if (t.status == TopicStatus.Failed) c.critical else c.textSecondary) }
        // 纪要卡片 + 合成的那件事
        if (t.kind != TopicKind.Normal && t.status != TopicStatus.Scheduled) RsCard(color = c.fill, padding = PaddingValues(16.dp)) {
            if (card != null) Row(Modifier.fillMaxWidth().springClick { openedCard = card }, verticalAlignment = Alignment.CenterVertically) {
                app.resound.ui.skills.SkillIcon(card.icon, 36.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(card.title, style = RsType.body.copy(fontWeight = FontWeight.SemiBold), color = c.text, maxLines = 2)
                    Text(card.summary, style = RsType.caption, color = c.textSecondary, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
                Text("›", style = RsType.title, color = c.textTertiary)
            } else Text(if (t.status == TopicStatus.Done || t.status == TopicStatus.Failed) "还没有纪要，可以点下面“重新生成”" else "结束并转写完后自动出纪要",
                style = RsType.subhead, color = c.textTertiary)
        }
        event?.let { e ->
            RsCard(color = c.fill, padding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)) {
                Row(Modifier.fillMaxWidth().springClick { app.resound.ui.transcript.PlayerNav.openEvent = e }, verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("合成的这件事", style = RsType.caption, color = c.textTertiary)
                        Text(e.title, style = RsType.subhead, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Text("›", style = RsType.subhead, color = c.textTertiary)
                }
            }
        }
        // 各段时间与重点
        if (t.spans.isNotEmpty()) {
            Label(if (t.spans.size > 1) "分 ${t.spans.size} 段（中间暂停或剪掉的不算）" else "时间")
            t.spans.forEach { s ->
                Text("${formatClock(s.startAt)} – ${s.endAt?.let(::formatClock) ?: "进行中"}" + (s.endAt?.let { " · ${minutesText(it - s.startAt)}" } ?: ""),
                    style = RsType.subhead.copy(fontFeatureSettings = "tnum"), color = c.textSecondary)
            }
        }
        if (t.marks.isNotEmpty()) Text("★ 重点：" + t.marks.joinToString("、") { formatClock(it.at) }, style = RsType.subhead, color = c.accent)
        if (t.status == TopicStatus.Scheduled) {
            Text("预约：${formatClock(t.plannedStartAt ?: start)}–${t.plannedEndAt?.let(::formatClock) ?: ""}" +
                (if (t.repeat.isNotEmpty()) " · 每周" + t.repeat.sorted().joinToString("、") { weekName(it) } else ""), style = RsType.subhead, color = c.textSecondary)
            ActionButton("取消这次预约", ActionKind.Danger) { scope.launch { if (guarded(context) { AppGraph.topics.cancelSchedule(t.id) }) onDismiss() } }
            return@RsSheet
        }
        if (t.status == TopicStatus.Cancelled) return@RsSheet
        // 改类型 / 名字
        Label("类型或名字不对？改了会重新生成")
        TopicKindChoice(editKind) { editKind = it }
        NameInput(editName, { editName = it }, "名字（可不填）")
        val changed = editKind != t.kind || editName.trim() != t.name.orEmpty()
        val busy = t.status == TopicStatus.Summarizing
        if (changed) ActionButton("保存并重新整理", ActionKind.Primary, enabled = !busy) {
            scope.launch { guarded(context) { AppGraph.topics.update(t.id, editKind, editName.trim().ifEmpty { null }) } }
        } else if (t.status == TopicStatus.Done || t.status == TopicStatus.Failed) ActionButton("重新生成", ActionKind.Secondary, enabled = !busy) {
            scope.launch { guarded(context) { AppGraph.topics.regenerate(t.id) } }
        }
        // 剪掉中间一段（如课间闲聊）
        if (!cutting) LinkText("剪掉中间一段（如课间闲聊） ›") { cutting = true }
        else CutPanel(t, day) { cutting = false }
        ActionButton(if (confirmCancel) "确定取消？再点一次" else "取消这个专题", ActionKind.Danger) {
            if (!confirmCancel) { confirmCancel = true; return@ActionButton }
            scope.launch { if (guarded(context) { AppGraph.topics.cancel(t.id) }) onDismiss() }
        }
        Text("取消后合成的事和纪要删除，这段原话按平常方式重新整理；录音和原话不删。", style = RsType.caption, color = c.textTertiary)
    }
}

@Composable
private fun CutPanel(t: Topic, day: LocalDate, onDone: () -> Unit) {
    val c = Rs.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val start = startOf(t)
    val first = Instant.ofEpochMilli(start).atZone(zone)
    var from by remember { mutableStateOf(first.toLocalDate() to first.toLocalTime().withSecond(0).withNano(0)) }
    var to by remember { mutableStateOf(first.plusMinutes(10).let { it.toLocalDate() to it.toLocalTime().withSecond(0).withNano(0) }) }
    fun ms(p: Pair<LocalDate, LocalTime>) = p.first.atTime(p.second).atZone(zone).toInstant().toEpochMilli()
    val valid = ms(from) < ms(to)
    RsCard(color = c.fill, padding = PaddingValues(14.dp)) {
        Text("剪掉的部分回到平常整理，专题自动重新生成", style = RsType.caption, color = c.textSecondary)
        Spacer(Modifier.height(8.dp))
        app.resound.ui.transcript.RangeEnd("从", from.first, from.second, day.minusDays(1), day.plusDays(1)) { d, tm -> from = d to tm }
        app.resound.ui.transcript.RangeEnd("到", to.first, to.second, day.minusDays(1), day.plusDays(1)) { d, tm -> to = d to tm }
        if (!valid) Text("开始时间要早于结束时间", style = RsType.caption, color = c.warning)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill("剪掉这段", filled = true, Modifier.weight(1f)) {
                if (!valid) return@Pill
                scope.launch { if (guarded(context) { AppGraph.topics.cut(t.id, ms(from), ms(to)) }) { Toast.makeText(context, "已剪掉，正在重新生成", Toast.LENGTH_SHORT).show(); onDone() } }
            }
            Pill("算了", filled = false, Modifier.weight(1f)) { onDone() }
        }
    }
}

private fun weekName(d: DayOfWeek) = "一二三四五六日"[d.value - 1].toString()

/** 预约：类型、名字、哪天几点到几点、每周重复；下面列出已预约的 */
@Composable
fun TopicScheduleSheet(onDismiss: () -> Unit) {
    val c = Rs.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val today = LocalDate.now()
    var kind by remember { mutableStateOf(TopicKind.Meeting) }
    var name by remember { mutableStateOf("") }
    val nextHour = LocalTime.now().plusHours(1).withMinute(0).withSecond(0).withNano(0)
    var from by remember { mutableStateOf(today to nextHour) }
    var to by remember { mutableStateOf(today to nextHour.plusHours(1)) }
    var repeat by remember { mutableStateOf(emptySet<DayOfWeek>()) }
    var busy by remember { mutableStateOf(false) }
    fun ms(p: Pair<LocalDate, LocalTime>) = p.first.atTime(p.second).atZone(zone).toInstant().toEpochMilli()
    val valid = ms(from) < ms(to) && ms(from) > System.currentTimeMillis() && ms(to) - ms(from) <= 4 * 3600_000L
    val scheduled by remember { AppGraph.topics.observeScheduled() }.collectAsState(initial = emptyList())
    RsSheet("预约专题", "到点自动开始、自动结束", onDismiss = onDismiss, fixedHeight = true) {
        TopicKindChoice(kind) { kind = it }
        NameInput(name, { name = it }, "名字（如“周三例会”，可不填）")
        app.resound.ui.transcript.RangeEnd("开始", from.first, from.second, today, today.plusDays(60)) { d, tm ->
            val length = ms(to) - ms(from)
            from = d to tm
            Instant.ofEpochMilli(ms(from) + length.coerceAtLeast(15 * 60_000L)).atZone(zone).let { to = it.toLocalDate() to it.toLocalTime() }
        }
        app.resound.ui.transcript.RangeEnd("结束", to.first, to.second, today, today.plusDays(61)) { d, tm -> to = d to tm }
        Text(when {
            ms(from) <= System.currentTimeMillis() -> "开始时间要晚于现在；现在就要录，回聆听面板点“开始专题”"
            ms(from) >= ms(to) -> "结束要晚于开始"
            ms(to) - ms(from) > 4 * 3600_000L -> "一次最长 4 小时"
            else -> "共 ${minutesText(ms(to) - ms(from))}；暂停不顺延结束时间"
        }, style = RsType.caption, color = if (valid) c.textSecondary else c.warning)
        Label("每周重复（不选就只这一次）")
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            DayOfWeek.entries.forEach { d ->
                val on = d in repeat
                val bg by animateColorAsState(if (on) c.accent else c.fill, label = "repeatDay")
                Box(Modifier.weight(1f).clip(CircleShape).background(bg).springClick { repeat = if (on) repeat - d else repeat + d }.padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center) {
                    Text(weekName(d), style = RsType.subhead.copy(fontWeight = FontWeight.Medium), color = if (on) Color.White else c.textSecondary)
                }
            }
        }
        ActionButton(if (busy) "正在预约…" else "预约", ActionKind.Primary, enabled = valid && !busy) {
            busy = true
            scope.launch {
                val r = runCatching { AppGraph.topics.schedule(kind, name.trim().ifEmpty { null }, ms(from), ms(to), repeat) }.getOrElse { Result.failure(it) }
                busy = false
                Toast.makeText(context, r.fold({ "已预约，到点会通知你" }, { (it as? app.resound.topic.TopicException)?.message ?: it.message ?: "没预约成功，请再试一次" }), Toast.LENGTH_SHORT).show()
                if (r.isSuccess) onDismiss()
            }
        }
        if (scheduled.isNotEmpty()) {
            Label("已预约")
            RsCard(color = c.fill, padding = PaddingValues(vertical = 4.dp)) {
                scheduled.sortedBy { it.plannedStartAt ?: 0 }.forEachIndexed { i, s ->
                    if (i > 0) Box(Modifier.padding(start = 16.dp).fillMaxWidth().height(0.5.dp).background(c.separator))
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(topicLabel(s), style = RsType.subhead, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text((s.plannedStartAt?.let { app.resound.ui.verify.formatUpcoming(it) } ?: "") +
                                (s.plannedEndAt?.let { "–" + formatClock(it) } ?: "") +
                                (if (s.repeat.isNotEmpty()) " · 每周" + s.repeat.sorted().joinToString("、") { weekName(it) } else ""),
                                style = RsType.caption, color = c.textSecondary)
                        }
                        Text("取消", style = RsType.subhead.copy(fontWeight = FontWeight.Medium), color = c.critical,
                            modifier = Modifier.clip(CircleShape).springClick { scope.launch { guarded(context) { AppGraph.topics.cancelSchedule(s.id) } } }.padding(8.dp))
                    }
                }
            }
        }
    }
}

/**
 * 事后补：从一件事出发时列出当天前后 3 小时的事，勾选要合并的（默认只勾这件）；
 * 不传事件时按时间段选。先预览会替换哪些事，再确认。
 */
@Composable
fun TopicCreateSheet(from: MemoryEvent?, onDismiss: () -> Unit) {
    val c = Rs.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var kind by remember { mutableStateOf(TopicKind.Meeting) }
    var name by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    // 按事件
    val day = from?.let { Instant.ofEpochMilli(it.startAt).atZone(zone).toLocalDate() } ?: LocalDate.now()
    val dayEvents = if (from != null && AppGraph.isMemoryReady) remember(day) { AppGraph.memory.events(day) }.collectAsState(initial = null).value.orEmpty() else emptyList()
    val nearby = dayEvents.filter { from != null && it.endAt > from.startAt - 3 * 3600_000L && it.startAt < from.endAt + 3 * 3600_000L }.sortedBy { it.startAt }
    var picked by remember(from?.id) { mutableStateOf(setOfNotNull(from?.id)) }
    // 按时间段
    val now = java.time.LocalDateTime.now().withSecond(0).withNano(0)
    var rFrom by remember { mutableStateOf(now.minusHours(1).let { it.toLocalDate() to it.toLocalTime() }) }
    var rTo by remember { mutableStateOf(now.toLocalDate() to now.toLocalTime()) }
    fun ms(p: Pair<LocalDate, LocalTime>) = p.first.atTime(p.second).atZone(zone).toInstant().toEpochMilli()
    val rangeValid = ms(rFrom) < ms(rTo)
    val preview by produceState<app.resound.topic.TopicReplacePreview?>(null, picked, rFrom, rTo) {
        value = null
        value = runCatching {
            if (from != null) picked.takeIf { it.isNotEmpty() }?.let { AppGraph.topics.previewFromEvents(it.toList()) }
            else if (rangeValid) AppGraph.topics.previewFromRange(ms(rFrom), ms(rTo)) else null
        }.getOrNull()
    }
    RsSheet("设为专题", if (from != null) "勾选要合成一件事的几件事" else "选出这场专题的时间", onDismiss = onDismiss, fixedHeight = true) {
        if (from != null) RsCard(color = c.fill, padding = PaddingValues(vertical = 4.dp)) {
            nearby.forEachIndexed { i, e ->
                if (i > 0) Box(Modifier.padding(start = 16.dp).fillMaxWidth().height(0.5.dp).background(c.separator))
                val on = e.id in picked
                Row(Modifier.fillMaxWidth().springClick { picked = if (on) picked - e.id else picked + e.id }.padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(20.dp).clip(CircleShape).background(if (on) c.accent else c.separator), contentAlignment = Alignment.Center) {
                        if (on) Text("✓", style = RsType.caption, color = Color.White)
                    }
                    Spacer(Modifier.width(10.dp))
                    Text(formatClock(e.startAt), style = RsType.caption.copy(fontWeight = FontWeight.SemiBold), color = c.accent, modifier = Modifier.widthIn(min = 46.dp))
                    Text(e.title, style = RsType.subhead, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                }
            }
        } else {
            app.resound.ui.transcript.RangeEnd("从", rFrom.first, rFrom.second, LocalDate.now().minusDays(30), LocalDate.now()) { d, tm -> rFrom = d to tm }
            app.resound.ui.transcript.RangeEnd("到", rTo.first, rTo.second, LocalDate.now().minusDays(30), LocalDate.now()) { d, tm -> rTo = d to tm }
        }
        val p = preview
        Text(when {
            from == null && !rangeValid -> "开始时间要早于结束时间"
            from != null && picked.isEmpty() -> "至少勾一件"
            p == null -> "正在计算…"
            p.error != null -> p.error
            p.conflict -> "和已有的专题时间重叠了"
            else -> buildString {
                append("${formatClock(p.startAt)}–${formatClock(p.endAt)}")
                if (p.events.isNotEmpty()) append("，会把 ${p.events.size} 件事合成一件")
                if (p.changedTodoCount > 0) append("；你改过的 ${p.changedTodoCount} 条待办保留")
                if (p.starredEventCount > 0) append("；星标会转到新专题上")
            }
        }, style = RsType.caption, color = if (p != null && p.error == null && !p.conflict) c.textSecondary else c.warning)
        if (p != null && p.events.isNotEmpty() && from == null) Text("包括：" + p.events.joinToString("、") { it.title }, style = RsType.caption, color = c.textTertiary, maxLines = 3, overflow = TextOverflow.Ellipsis)
        Label("类型")
        TopicKindChoice(kind) { kind = it }
        NameInput(name, { name = it }, "名字（可不填）")
        val ok = p != null && p.error == null && !p.conflict && !busy
        ActionButton(if (busy) "正在合成…" else "设为${kind.title}专题", ActionKind.Primary, enabled = ok) {
            busy = true
            scope.launch {
                val r = runCatching {
                    if (from != null) AppGraph.topics.createFromEvents(picked.toList(), kind, name.trim().ifEmpty { null })
                    else AppGraph.topics.createFromRange(ms(rFrom), ms(rTo), kind, name.trim().ifEmpty { null })
                }.getOrElse { Result.failure(it) }
                busy = false
                r.onSuccess { id -> Toast.makeText(context, "已设为专题，正在合成", Toast.LENGTH_SHORT).show(); onDismiss(); TopicUi.openTopic = id }
                    .onFailure { Toast.makeText(context, (it as? app.resound.topic.TopicException)?.message ?: it.message ?: "没成功，请再试一次", Toast.LENGTH_SHORT).show() }
            }
        }
    }
}
