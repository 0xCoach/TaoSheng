package app.resound.ui.topic

import kotlinx.datetime.toKotlinLocalDate
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.widget.Toast
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import app.resound.topic.TopicProgress
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
import androidx.compose.ui.unit.sp
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
import kotlinx.coroutines.flow.first
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** 卡 135 专题录音的界面入口；面板都挂在 AppShell 上，任何页面都能打开 */
object TopicUi {
    var openTopic by mutableStateOf<Long?>(null)
    var historyRequested by mutableStateOf(false)
    var historyQuery by mutableStateOf("")
    var historyAttention by mutableStateOf(false)
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

/**
 * 首页主卡专题行，一行说完不加高卡片：“会议专题 · 23 分”；有计划时长时“会议专题 · 23/60 分”；暂停中带“已暂停”。
 */
fun topicHomeTitle(t: Topic, now: Long = System.currentTimeMillis()): String {
    val start = startOf(t)
    val m = ((now - start) / 60_000).coerceAtLeast(0)
    val name = t.name?.takeIf { it.isNotBlank() } ?: (t.kind.title + "专题")
    val planned = t.plannedEndAt?.let { ((it - start) / 60_000).coerceAtLeast(1) }
    val time = when {
        planned != null -> "$m/$planned 分"
        m < 60 -> "$m 分"
        else -> "${m / 60} 小时 ${m % 60} 分"
    }
    return "$name · " + (if (t.status == TopicStatus.Paused) "已暂停 · " else "") + time
}

/** “会议中 · 已 35 分钟 · 11:00 结束” */
fun topicLine(t: Topic, now: Long = System.currentTimeMillis()): String = buildString {
    append(topicLabel(t))
    append(if (t.status == TopicStatus.Paused) " · 已暂停" else "中")
    append(" · 已 ").append(minutesText(now - startOf(t)))
    t.plannedEndAt?.let { append(" · ").append(formatClock(it)).append(" 结束") }
}

internal fun kindIcon(k: TopicKind) = when (k) {
    TopicKind.Meeting -> "meeting"; TopicKind.Training -> "bulb"; TopicKind.Call -> "chat"; TopicKind.Script -> "mic"; else -> "timeline"
}

/** 类型：一排图标方块，点哪个选哪个；选中的橙色描边加浅橙底 */
@Composable
internal fun TopicKindChoice(selected: TopicKind, onSelect: (TopicKind) -> Unit) {
    val c = Rs.colors
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TopicKind.entries.forEach { k ->
            val on = k == selected
            val bg by animateColorAsState(if (on) c.accentSoft else c.fill, spring(stiffness = Spring.StiffnessMediumLow), label = "topicKind")
            val edge by animateColorAsState(if (on) c.accent else Color.Transparent, label = "topicKindEdge")
            Column(Modifier.weight(1f).clip(RoundedCornerShape(16.dp)).background(bg).border(1.5.dp, edge, RoundedCornerShape(16.dp))
                .springClick { onSelect(k) }.padding(top = 12.dp, bottom = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                app.resound.ui.skills.SkillIcon(kindIcon(k), 30.dp)
                Spacer(Modifier.height(7.dp))
                Text(k.title.replace(" / ", "·"), style = RsType.caption.copy(fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium),
                    color = if (on) c.text else c.textSecondary, maxLines = 1, softWrap = false)
            }
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
fun TopicSection(onDismiss: () -> Unit, initialKind: TopicKind = TopicKind.Meeting, showLabel: Boolean = true) {
    if (!AppGraph.isTopicsReady) return
    val current by AppGraph.topics.current.collectAsStateWithLifecycle()
    if (showLabel) Label("专题录音")
    current?.let { TopicRunningPanel(it); return }
    TopicStartPanel(initialKind, onStarted = onDismiss)
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        SideEntry("预约专题", "到点自动开始", Modifier.weight(1f)) { TopicUi.scheduling = true }
        SideEntry("忘了点开始", "事后选一段时间", Modifier.weight(1f)) { TopicUi.creatingRange = true }
    }
}

/** 开始面板下方的次要入口：标题 + 一句说明 */
@Composable
private fun SideEntry(title: String, note: String, modifier: Modifier, onClick: () -> Unit) {
    val c = Rs.colors
    Row(modifier.clip(RoundedCornerShape(16.dp)).background(c.fill).springClick(onClick).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = RsType.subhead.copy(fontWeight = FontWeight.SemiBold), color = c.text, maxLines = 1)
            Text(note, style = RsType.caption, color = c.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text("›", style = RsType.headline.copy(fontWeight = FontWeight.Normal), color = c.textTertiary)
    }
}

/** 点选胶囊：选中是橙色描边加浅橙底 */
@Composable
internal fun ChoicePill(text: String, on: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val c = Rs.colors
    val bg by animateColorAsState(if (on) c.accentSoft else c.fill, label = "choicePill")
    val edge by animateColorAsState(if (on) c.accent else Color.Transparent, label = "choicePillEdge")
    Box(modifier.clip(CircleShape).background(bg).border(1.5.dp, edge, CircleShape).springClick(onClick).padding(vertical = 9.dp), contentAlignment = Alignment.Center) {
        Text(text, style = RsType.subhead.copy(fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium),
            color = if (on) c.text else c.textSecondary, maxLines = 1, softWrap = false)
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
    TopicKindChoice(kind) { kind = it }
    Text(kindHint(kind), style = RsType.subhead, color = c.textSecondary)
    Spacer(Modifier.height(2.dp))
    NameInput(name, { name = it }, "名字，例如：产品讨论会（可不填）")
    // 结束方式：直接排出来点选，不用再展开
    Label("什么时候结束")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(0 to "我来结束", 30 to "30 分钟", 60 to "1 小时", 120 to "2 小时").forEach { (value, text) ->
            val on = if (value == 0) unlimited else !unlimited && minutes == value
            ChoicePill(text, on, Modifier.weight(1f)) { unlimited = value == 0; if (value > 0) minutes = value }
        }
    }
    androidx.compose.animation.AnimatedVisibility(!unlimited) {
        Column {
            app.resound.ui.transcript.StepSlider(minutes, enabled = true) { minutes = it }
            Text("到点前会提醒，可以延长；一次最长 4 小时。", style = RsType.caption, color = c.textTertiary)
        }
    }
    Spacer(Modifier.height(4.dp))
    ActionButton(if (busy) "正在开始…" else "开始${if (kind == TopicKind.Call) "访谈" else kind.title}", ActionKind.Primary, enabled = !busy) {
            if (busy) return@ActionButton
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
    var justMarked by remember(t.id) { mutableStateOf<Triple<Long, Long, Long>?>(null) }
    LaunchedEffect(confirmEnd) { if (confirmEnd) { delay(4000); confirmEnd = false } }
    val paused = t.status == TopicStatus.Paused
    RsCard(color = c.accentSoft, padding = PaddingValues(16.dp)) {
        Text(topicLine(t, now), style = RsType.headline, color = c.accent, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (t.marks.isNotEmpty()) Text("已记下 ${t.marks.size} 处重点", style = RsType.caption, color = c.textSecondary, modifier = Modifier.padding(top = 2.dp))
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill("★ 记一下", filled = true, Modifier.weight(1f)) {
                scope.launch { var got: Triple<Long, Long, Long>? = null; if (guarded(context) { got = TopicMarks.markAndGet() }) justMarked = got }
            }
            Pill(if (paused) "继续" else "暂停", filled = false, Modifier.weight(1f)) {
                scope.launch { guarded(context) { if (paused) AppGraph.topics.resume() else AppGraph.topics.pause() } }
            }
            Pill(if (confirmEnd) "确定结束？" else "结束", filled = false, Modifier.weight(1f), danger = confirmEnd) {
                if (!confirmEnd) { confirmEnd = true; return@Pill }
                scope.launch { if (guarded(context) { AppGraph.topics.end() }) Toast.makeText(context, "专题已结束，转写好后自动出纪要", Toast.LENGTH_SHORT).show() }
            }
        }
        MarkUndoLine(justMarked, c.textSecondary, c.text) { justMarked = null }
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
        Text(text, style = RsType.subhead.copy(fontWeight = FontWeight.Medium), color = if (danger) Color.White else if (filled) c.onAccent else c.text, maxLines = 1)
    }
}

/** 挂在 AppShell 上：按 TopicUi 的状态打开各个面板 */
@Composable
fun TopicHost() {
    if (!AppGraph.isTopicsReady) return
    val historyScroll = rememberScrollState()
    TopicUi.openTopic?.let { id -> TopicSheet(id) { TopicUi.openTopic = null } }
    if (TopicUi.scheduling) TopicScheduleSheet { TopicUi.scheduling = false }
    TopicUi.creatingFrom?.let { e -> TopicCreateSheet(e) { TopicUi.creatingFrom = null } }
    if (TopicUi.creatingRange) TopicCreateSheet(null) { TopicUi.creatingRange = false }
    TopicUi.starting?.let { k ->
        val current by AppGraph.topics.current.collectAsStateWithLifecycle()
        // 与聆听面板里的“专题录音”一节相同：开始、预约、忘了点开始时事后选一段时间；进行中则直接操作
        var history by remember { mutableStateOf(TopicUi.historyRequested) }
        LaunchedEffect(Unit) { TopicUi.historyRequested = false }
        val records by remember { AppGraph.topics.observeHistory() }.collectAsStateWithLifecycle(initialValue = emptyList())
        val failed = records.count { it.status == TopicStatus.Failed }
        RsSheet("专题", onDismiss = { TopicUi.starting = null }, fixedHeight = history,
            scrollState = if (history) historyScroll else null) {
            TopicTabs(history, failed, current != null) { history = it }
            if (history) TopicHistory(records) { id ->
                TopicUi.historyRequested = true
                TopicUi.starting = null
                TopicUi.openTopic = id
            } else TopicSection({ TopicUi.starting = null }, k, showLabel = false)
        }
    }
}

@Composable
private fun TopicHistory(records: List<Topic>, open: (Long) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var retrying by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var shown by remember { mutableIntStateOf(30) }
    var managing by remember { mutableStateOf(false) }
    TopicHistoryContent(records, TopicUi.historyQuery, { TopicUi.historyQuery = it; shown = 30 },
        TopicUi.historyAttention, { TopicUi.historyAttention = it; shown = 30 }, shown, { shown += 30 },
        progress = { t -> remember(t.id) { AppGraph.topics.observeProgress(t.id) }.collectAsStateWithLifecycle(initialValue = TopicProgress()).value },
        busyIds = retrying, open = open, retry = { id ->
            retrying = retrying + id
            scope.launch { try { guarded(context) { AppGraph.topics.regenerate(id) } } finally { retrying = retrying - id } }
        }, managing = managing, setManaging = { managing = it }, delete = { id ->
            scope.launch { if (guarded(context) { AppGraph.topics.delete(id) }) Toast.makeText(context, "专题已删除", Toast.LENGTH_SHORT).show() }
        })
}

private fun statusText(t: Topic): String? = when (t.status) {
    TopicStatus.Scheduled -> "已预约"
    TopicStatus.Ended -> "已结束，等这段录音转写完就整理"
    TopicStatus.Summarizing -> "正在合成这件事并写纪要…"
    TopicStatus.Failed -> t.error ?: "专题生成失败，请重试"
    TopicStatus.Cancelled -> "已取消，这段原话回到平常整理"
    else -> null
}

/**
 * 专题详情（11-12 改版）：四块——头部（标题、类型胶囊、时间时长、右上“⋯”）、主角区（随状态变）、时间轴、相关。
 * 改类型或名字、剪掉一段、重新生成、取消专题收进“⋯”，点了再弹出对应的小面板。
 */
@Composable
fun TopicSheet(id: Long, onDismiss: () -> Unit) {
    val c = Rs.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val t = remember(id) { AppGraph.topics.observe(id) }.collectAsStateWithLifecycle(initialValue = null).value ?: return
    val live = t.status == TopicStatus.Running || t.status == TopicStatus.Paused
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(live) { while (live) { now = System.currentTimeMillis(); delay(1_000) } }
    val start = startOf(t); val end = endOf(t, now)
    val skills = remember { app.resound.ui.skills.skillRepo() }
    val card = t.cardId?.let { cid -> remember(cid) { skills.card(cid) }.collectAsStateWithLifecycle(initialValue = null).value }
    var openedCard by remember { mutableStateOf<app.resound.skills.SkillCard?>(null) }
    openedCard?.let { app.resound.ui.skills.SkillCardSheet(it, skills) { openedCard = null } }
    val event by produceState<MemoryEvent?>(null, t.eventId, t.revision) {
        value = t.eventId?.takeIf { AppGraph.isMemoryReady }?.let { AppGraph.memory.eventById(it) }
    }
    var cutting by remember { mutableStateOf(false) }
    // 已出纪要的专题删了重点：纪要不自动重写，提示可“重新生成”
    var marksChanged by remember(id) { mutableStateOf(false) }
    var confirmCancel by remember { mutableStateOf(false) }
    LaunchedEffect(confirmCancel) { if (confirmCancel) { delay(4000); confirmCancel = false } }
    val day = Instant.ofEpochMilli(start).atZone(zone).toLocalDate()
    val canEdit = t.status != TopicStatus.Scheduled && t.status != TopicStatus.Cancelled
    var actionBusy by remember(t.id) { mutableStateOf(false) }
    val busy = t.status == TopicStatus.Summarizing || actionBusy
    val progress by remember(t.id) { AppGraph.topics.observeProgress(t.id) }.collectAsStateWithLifecycle(initialValue = TopicProgress())
    var adjusting by remember { mutableStateOf(false) }
    var timelineOpen by remember { mutableStateOf(false) }
    val retry: () -> Unit = {
        if (!actionBusy) {
            actionBusy = true
            scope.launch { try { guarded(context) { AppGraph.topics.regenerate(t.id) } } finally { actionBusy = false } }
        }
    }

    RsSheet(topicLabel(t), onDismiss = onDismiss, fixedHeight = true, trailing = {
        if (canEdit) Text("⋯", style = RsType.title, color = c.textSecondary, modifier = Modifier.springClick { adjusting = !adjusting }.padding(10.dp))
    }) {
        LinkText("‹ 专题记录") {
            onDismiss()
            TopicUi.historyRequested = true
            TopicUi.starting = TopicKind.Meeting
        }
        // ① 头部副行：类型胶囊 + 时间时长
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(t.kind.title, style = RsType.caption.copy(fontWeight = FontWeight.SemiBold), color = c.accent,
                modifier = Modifier.clip(CircleShape).background(c.accentSoft).padding(horizontal = 9.dp, vertical = 3.dp))
            Spacer(Modifier.width(8.dp))
            val dayText = app.resound.ui.verify.formatUpcoming(start).substringBefore(' ')
            Text(when {
                t.status == TopicStatus.Scheduled -> "预约 $dayText ${formatClock(t.plannedStartAt ?: start)}" + (t.plannedEndAt?.let { "–" + formatClock(it) } ?: "")
                live -> "$dayText ${formatClock(start)} 开始" + (t.plannedEndAt?.let { " · ${formatClock(it)} 结束" } ?: "")
                else -> "$dayText ${formatClock(start)}–${formatClock(end)}"
            }, style = RsType.subhead.copy(fontFeatureSettings = "tnum"), color = c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        val tl = app.resound.topic.TopicTimeline.layout(t.spans.map { it.startAt to it.endAt }, t.marks.map { it.at }, now, t.plannedEndAt)

        // ② 主角区
        when {
            live -> LiveHero(t, tl?.includedMs ?: 0L)
            t.status == TopicStatus.Done && card != null -> TopicCompletedContent(card)
            t.status == TopicStatus.Scheduled -> HeroNote("到点自动开始、自动结束" + (if (t.repeat.isNotEmpty()) " · 每周" + t.repeat.sorted().joinToString("、") { weekName(DayOfWeek.valueOf(it.name)) } else "")) {
                ActionButton("取消这次预约", ActionKind.Danger) { scope.launch { if (guarded(context) { AppGraph.topics.cancelSchedule(t.id) }) onDismiss() } }
            }
            t.status == TopicStatus.Failed -> TopicProgressContent(progress, progress.error ?: t.error, busy, retry)
            t.status == TopicStatus.Summarizing || t.status == TopicStatus.Ended -> TopicProgressContent(progress, null, busy, retry)
            t.status == TopicStatus.Done && t.kind != TopicKind.Normal -> HeroNote("还没有纪要") {
                ActionButton("生成纪要", ActionKind.Primary, enabled = !busy) { scope.launch { guarded(context) { AppGraph.topics.regenerate(t.id) } } }
            }
            else -> statusText(t)?.let { HeroNote(it, spinning = t.status == TopicStatus.Summarizing || t.status == TopicStatus.Ended) }
        }

        // ③ 时间轴
        if (tl != null) {
            Row(Modifier.fillMaxWidth().springClick { timelineOpen = !timelineOpen }.padding(vertical = 10.dp)) {
                Text("录制时间轴 · ${t.marks.size} 处重点", style = RsType.subhead, color = c.textSecondary, modifier = Modifier.weight(1f))
                Text(if (timelineOpen) "收起 ⌃" else "展开 ⌄", style = RsType.caption, color = c.textTertiary)
            }
            if (timelineOpen) TimelineCard(tl, live, t.marks, onRemove = { m ->
                scope.launch { if (guarded(context) { AppGraph.topics.unmark(t.id, m.id) } && t.status == TopicStatus.Done) marksChanged = true }
            }) { at ->
                // 点 ★：找到这一刻所在的录音段，打开原话页并滚到那里；找不到就打开合成的那件事
                scope.launch {
                    val atDay = Instant.ofEpochMilli(at).atZone(zone).toLocalDate()
                    val seg = if (AppGraph.isTranscribeReady) runCatching { AppGraph.transcripts.day(atDay.toKotlinLocalDate()).first() }.getOrNull()
                        ?.filter { it.startAt <= at }?.maxByOrNull { it.startAt } else null
                    if (seg != null) {
                        app.resound.ui.transcript.PlayerNav.transcriptFocus = atDay to seg.segmentId
                        onDismiss(); app.resound.ui.ShellNav.openSettings(app.resound.ui.settings.SettingsPage.Transcript)
                    } else event?.let { e -> app.resound.ui.transcript.PlayerNav.openEvent = e }
                }
            }
        }

        // ④ 相关
        val related = buildList<Triple<String, String, () -> Unit>> {
            if (t.status != TopicStatus.Done && card != null) add(Triple("上次生成的纪要", "查看") { openedCard = card })
            event?.let { e -> add(Triple("合成的这件事", e.title) { app.resound.ui.transcript.PlayerNav.openEvent = e }) }
            if (t.status != TopicStatus.Scheduled) add(Triple("这段时间的原话", day.format(java.time.format.DateTimeFormatter.ofPattern("M月d日"))) {
                scope.launch {
                    val segment = if (AppGraph.isTranscribeReady) AppGraph.transcripts.day(day.toKotlinLocalDate()).first()
                        .firstOrNull { it.startAt >= start && it.startAt <= end } else null
                    segment?.let { app.resound.ui.transcript.PlayerNav.transcriptFocus = day to it.segmentId }
                    onDismiss(); app.resound.ui.ShellNav.openSettings(app.resound.ui.settings.SettingsPage.Transcript)
                }
            })
            val plannedEndAt = t.plannedEndAt
            if (live && plannedEndAt != null) add(Triple("延长 30 分钟", "到 " + formatClock(plannedEndAt + 30 * 60_000L)) {
                scope.launch { guarded(context) { AppGraph.topics.extend(30) } }
            })
        }
        if (related.isNotEmpty()) {
            Label("相关")
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(if (c.isDark) c.fill else c.card)) {
                related.forEachIndexed { i, (title, value, go) ->
                    if (i > 0) Box(Modifier.padding(start = 16.dp).fillMaxWidth().height(0.5.dp).background(c.separator))
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).springClick(go).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(title, style = RsType.body, color = c.text)
                        Text(value, style = RsType.subhead, color = c.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            textAlign = androidx.compose.ui.text.style.TextAlign.End, modifier = Modifier.weight(1f).padding(start = 12.dp, end = 8.dp))
                        Text("›", style = RsType.headline.copy(fontWeight = FontWeight.Normal), color = c.textTertiary)
                    }
                }
            }
        }

    }
        // 独立调整面板，长纪要也能直接从右上角进入操作。
        if (canEdit && adjusting) RsSheet("调整专题", onDismiss = { adjusting = false }) {
            var editKind by remember(t.id, t.kind) { mutableStateOf(t.kind) }
            var editName by remember(t.id, t.name) { mutableStateOf(t.name.orEmpty()) }
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(if (c.isDark) c.fill else c.card).padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                TopicKindChoice(editKind) { editKind = it }
                NameInput(editName, { editName = it }, "名字（可不填，填了转写时认得更准）")
                val changed = editKind != t.kind || editName.trim() != t.name.orEmpty()
                androidx.compose.animation.AnimatedVisibility(changed) {
                    ActionButton(if (live) "保存" else "保存并重新整理", ActionKind.Primary, enabled = !busy) {
                        scope.launch { guarded(context) { AppGraph.topics.update(t.id, editKind, editName.trim().ifEmpty { null }) } }
                    }
                }
            }
            // ⑥ 操作：一排按钮直接放出来；剪掉一段在原地展开，取消点两次
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionPill(if (cutting) "收起" else "剪掉一段", Modifier.weight(1f)) { cutting = !cutting }
                if (t.status == TopicStatus.Done || t.status == TopicStatus.Failed) ActionPill("重新生成", Modifier.weight(1f), enabled = !busy) {
                    scope.launch { if (guarded(context) { AppGraph.topics.regenerate(t.id) }) Toast.makeText(context, "正在重新生成", Toast.LENGTH_SHORT).show() }
                }
                // 进行中只能取消；已结束的直接删除（记录一起删掉）
                ActionPill(if (confirmCancel) (if (live) "确定取消？" else "确定删除？") else if (live) "取消专题" else "删除专题", Modifier.weight(1f), danger = true, confirm = confirmCancel) {
                    if (!confirmCancel) { confirmCancel = true; return@ActionPill }
                    scope.launch {
                        if (guarded(context) { if (live) AppGraph.topics.cancel(t.id) else AppGraph.topics.delete(t.id) }) {
                            if (!live) Toast.makeText(context, "专题已删除", Toast.LENGTH_SHORT).show()
                            adjusting = false; onDismiss()
                        }
                    }
                }
            }
            androidx.compose.animation.AnimatedVisibility(cutting) {
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(if (c.isDark) c.fill else c.card).padding(14.dp)) {
                    Text("剪掉的部分（如课间闲聊）回到平常整理，专题自动重新生成", style = RsType.caption, color = c.textSecondary)
                    Spacer(Modifier.height(8.dp))
                    CutPanel(t, day) { cutting = false }
                }
            }
            if (marksChanged) Text("重点改了，可点上面“重新生成”更新纪要", style = RsType.caption, color = c.textSecondary)
            if (confirmCancel) Text((if (live) "取消后" else "删除后") + "合成的事和纪要删除，这段原话按平常方式重新整理；录音和原话不删。", style = RsType.caption, color = c.textTertiary)
        }
}

/** 操作按钮：浅底胶囊；危险操作红字，确认时变红底 */
@Composable
private fun ActionPill(text: String, modifier: Modifier, danger: Boolean = false, confirm: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    val c = Rs.colors
    val bg by animateColorAsState(if (confirm) c.critical else if (c.isDark) c.fill else c.card, label = "actionPill")
    Box(modifier.clip(CircleShape).background(bg).then(if (enabled) Modifier.springClick(onClick) else Modifier).padding(vertical = 11.dp),
        contentAlignment = Alignment.Center) {
        Text(text, style = RsType.subhead.copy(fontWeight = FontWeight.Medium), maxLines = 1,
            color = when { confirm -> Color.White; danger -> c.critical; !enabled -> c.textTertiary; else -> c.text })
    }
}

/** 进行中：大字计时 + ★ 记一下 / 暂停·继续 / 结束（结束点两次） */
@Composable
private fun LiveHero(t: Topic, includedMs: Long) {
    val c = Rs.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    val paused = t.status == TopicStatus.Paused
    var confirmEnd by remember(t.id) { mutableStateOf(false) }
    var justMarked by remember(t.id) { mutableStateOf<Triple<Long, Long, Long>?>(null) }
    LaunchedEffect(confirmEnd) { if (confirmEnd) { delay(4000); confirmEnd = false } }
    val shape = RoundedCornerShape(20.dp)
    val bg = Modifier.clip(shape).background(c.fill)
    val fg = c.text
    val soft = c.textSecondary
    val pill = c.trackThumb
    Column(Modifier.fillMaxWidth().then(bg).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(c.accent).then(if (paused) Modifier.background(Color.Transparent) else Modifier))
            Spacer(Modifier.width(8.dp))
            Text(listOfNotNull(if (paused) "已暂停 · 暂停期间不算进专题" else "正在录", t.marks.takeIf { it.isNotEmpty() }?.let { "已记下 ${it.size} 处重点" }).joinToString(" · "),
                style = RsType.subhead, color = soft, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(app.resound.topic.TopicTimeline.clock(includedMs), style = RsType.largeTitle.copy(fontSize = 40.sp, lineHeight = 46.sp,
            fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum", letterSpacing = (-0.5).sp), color = fg, modifier = Modifier.padding(top = 4.dp))
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HeroButton("★ 记一下", primary = true, Modifier.weight(1f)) {
                haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                scope.launch { var got: Triple<Long, Long, Long>? = null; if (guarded(context) { got = TopicMarks.markAndGet() }) justMarked = got }
            }
            HeroButton(if (paused) "继续" else "暂停", primary = false, Modifier.weight(1f), bg = pill, fg = fg) {
                haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                scope.launch { guarded(context) { if (paused) AppGraph.topics.resume() else AppGraph.topics.pause() } }
            }
            HeroButton(if (confirmEnd) "确定结束？" else "结束", primary = false, Modifier.weight(1f), bg = if (confirmEnd) c.critical else pill, fg = if (confirmEnd) Color.White else fg) {
                if (!confirmEnd) { confirmEnd = true; return@HeroButton }
                scope.launch { if (guarded(context) { AppGraph.topics.end() }) Toast.makeText(context, "专题已结束，转写好后自动出纪要", Toast.LENGTH_SHORT).show() }
            }
        }
        MarkUndoLine(justMarked, soft, fg) { justMarked = null }
    }
}

/** 刚记下一个重点：按钮下方出现“已记下 10:05 · 撤销”，5 秒后消失（误触当场就能撤） */
@Composable
private fun MarkUndoLine(mark: Triple<Long, Long, Long>?, soft: Color, strong: Color, onDone: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(mark) { if (mark != null) { delay(5_000); onDone() } }
    androidx.compose.animation.AnimatedVisibility(mark != null) {
        val m = mark ?: return@AnimatedVisibility
        Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("已记下 ${formatClock(m.third)}", style = RsType.subhead, color = soft, modifier = Modifier.weight(1f))
            Text("撤销", style = RsType.subhead.copy(fontWeight = FontWeight.SemiBold), color = strong,
                modifier = Modifier.clip(CircleShape).springClick {
                    onDone(); scope.launch { guarded(context) { TopicMarks.unmark(m.first, m.second) } }
                }.padding(horizontal = 8.dp, vertical = 4.dp))
        }
    }
}

@Composable
private fun HeroButton(text: String, primary: Boolean, modifier: Modifier, bg: Color? = null, fg: Color? = null, onClick: () -> Unit) {
    val c = Rs.colors
    val b = bg ?: c.accent
    val f = fg ?: if (c.isDark) c.onAccent else Color(0xFFD2511F)
    Box(modifier.clip(CircleShape).background(b).springClick(onClick).padding(vertical = 11.dp), contentAlignment = Alignment.Center) {
        Text(text, style = RsType.subhead.copy(fontWeight = FontWeight.SemiBold), color = f, maxLines = 1)
    }
}

/** 主角区的说明卡：预约、整理中、失败、没有纪要 */
@Composable
private fun HeroNote(text: String, error: Boolean = false, spinning: Boolean = false, action: (@Composable () -> Unit)? = null) {
    val c = Rs.colors
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(if (c.isDark) c.fill else c.card).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (spinning) {
                androidx.compose.material3.CircularProgressIndicator(Modifier.size(16.dp), color = c.accent, strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
            }
            Text(text, style = RsType.subhead, color = if (error) c.critical else c.textSecondary)
        }
        action?.invoke()
    }
}

/** 时间轴：橙色段算进专题，灰色段是暂停或剪掉的；★ 是重点；进行中有一条竖线表示“现在” */
@Composable
private fun TimelineCard(tl: app.resound.topic.TopicTimelineLayout, live: Boolean, marks: List<app.resound.topic.TopicMark>,
                         onRemove: (app.resound.topic.TopicMark) -> Unit, onMark: (Long) -> Unit) {
    val c = Rs.colors
    val gray = if (c.isDark) Color.White.copy(alpha = 0.22f) else Color(0xFFD8D3CC)
    val track = if (c.isDark) Color.White.copy(alpha = 0.1f) else Color(0xFFECE8E3)
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(if (c.isDark) c.fill else c.card).padding(horizontal = 16.dp, vertical = 14.dp)) {
        BoxWithConstraints(Modifier.fillMaxWidth().height(30.dp)) {
            val w = maxWidth
            Box(Modifier.fillMaxWidth().padding(top = 12.dp).height(8.dp).clip(CircleShape).background(track))
            tl.bars.forEach { b ->
                Box(Modifier.offset(x = w * b.from).padding(top = 12.dp).width(w * (b.to - b.from).coerceAtLeast(0.01f)).height(8.dp)
                    .clip(CircleShape).background(if (b.included) c.accent else gray))
            }
            tl.now?.let { p -> Box(Modifier.offset(x = w * p - 1.dp).padding(top = 7.dp).width(2.dp).height(18.dp).clip(CircleShape).background(c.text)) }
            tl.marks.forEach { (p, at) ->
                Text("★", style = RsType.subhead, color = c.accent, modifier = Modifier.offset(x = w * p - 7.dp, y = (-6).dp).springClick { onMark(at) })
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 2.dp)) {
            val mid = tl.startAt + (tl.endAt - tl.startAt) / 2
            listOf(tl.startAt, mid, tl.endAt).forEachIndexed { i, at ->
                Text(formatClock(at), style = RsType.caption.copy(fontFeatureSettings = "tnum"), color = c.textTertiary,
                    textAlign = when (i) { 0 -> androidx.compose.ui.text.style.TextAlign.Start; 1 -> androidx.compose.ui.text.style.TextAlign.Center; else -> androidx.compose.ui.text.style.TextAlign.End },
                    modifier = Modifier.weight(1f))
            }
        }
        // 重点：一个个小胶囊，点时刻跳到原话，点 × 删除标错的
        if (marks.isNotEmpty()) FlowRow(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            marks.sortedBy { it.at }.forEach { m ->
                Row(Modifier.clip(CircleShape).background(c.fill), verticalAlignment = Alignment.CenterVertically) {
                    Text("★ ${formatClock(m.at)}", style = RsType.subhead.copy(fontFeatureSettings = "tnum"), color = c.text,
                        modifier = Modifier.springClick { onMark(m.at) }.padding(start = 10.dp, end = 4.dp, top = 5.dp, bottom = 5.dp))
                    Text("×", style = RsType.subhead, color = c.textTertiary,
                        modifier = Modifier.springClick { onRemove(m) }.padding(start = 4.dp, end = 10.dp, top = 5.dp, bottom = 5.dp))
                }
            }
        }
        if (tl.bars.any { !it.included } || tl.marks.isNotEmpty()) Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            LegendDot(c.accent, "算进专题")
            if (tl.bars.any { !it.included }) LegendDot(gray, "暂停或剪掉")
            if (tl.marks.isNotEmpty()) Text("★ 重点", style = RsType.caption, color = c.textTertiary)
        }
    }
}

@Composable
private fun LegendDot(color: Color, text: String) = Row(verticalAlignment = Alignment.CenterVertically) {
    Box(Modifier.size(width = 10.dp, height = 6.dp).clip(CircleShape).background(color))
    Spacer(Modifier.width(4.dp))
    Text(text, style = RsType.caption, color = Rs.colors.textTertiary)
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
    Column {
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
    val scheduled by remember { AppGraph.topics.observeScheduled() }.collectAsStateWithLifecycle(initialValue = emptyList())
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
                val bg by animateColorAsState(if (on) c.selected else c.fill, label = "repeatDay")
                Box(Modifier.weight(1f).clip(CircleShape).background(bg).springClick { repeat = if (on) repeat - d else repeat + d }.padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center) {
                    Text(weekName(d), style = RsType.subhead.copy(fontWeight = FontWeight.Medium), color = if (on) c.onSelected else c.textSecondary)
                }
            }
        }
        ActionButton(if (busy) "正在预约…" else "预约", ActionKind.Primary, enabled = valid && !busy) {
            busy = true
            scope.launch {
                val r = runCatching { AppGraph.topics.schedule(kind, name.trim().ifEmpty { null }, ms(from), ms(to), repeat.map { kotlinx.datetime.DayOfWeek.valueOf(it.name) }.toSet()) }.getOrElse { Result.failure(it) }
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
                                (if (s.repeat.isNotEmpty()) " · 每周" + s.repeat.sorted().joinToString("、") { weekName(DayOfWeek.valueOf(it.name)) } else ""),
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
    val dayEvents = if (from != null && AppGraph.isMemoryReady) remember(day) { AppGraph.memory.events(day.toKotlinLocalDate()) }.collectAsStateWithLifecycle(initialValue = null).value.orEmpty() else emptyList()
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
                    Box(Modifier.size(20.dp).clip(CircleShape).background(if (on) c.selected else c.separator), contentAlignment = Alignment.Center) {
                        if (on) Text("✓", style = RsType.caption, color = c.onSelected)
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
        val previewError = p?.error
        Text(when {
            from == null && !rangeValid -> "开始时间要早于结束时间"
            from != null && picked.isEmpty() -> "至少勾一件"
            p == null -> "正在计算…"
            previewError != null -> previewError
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
