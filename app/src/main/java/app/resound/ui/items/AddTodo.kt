package app.resound.ui.items

import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.resound.AppGraph
import app.resound.digest.ItemOrigin
import app.resound.digest.TodoDraft
import app.resound.digest.TodoDrafts
import app.resound.digest.TodoVoiceInput
import app.resound.digest.VoiceNoteResult
import app.resound.ui.theme.Rs
import app.resound.ui.theme.RsType
import app.resound.ui.verify.ActionButton
import app.resound.ui.verify.ActionKind
import app.resound.ui.verify.BreathingWave
import app.resound.ui.verify.RsSheet
import app.resound.ui.verify.springClick
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private enum class Phase { Idle, Listening, Recognizing, Drafting }

/**
 * 待办页右下角的“添加”按钮：点一下打字，按住说话（不足半秒算点一下），上滑取消。
 * 文字交给整理模型拆成待办卡，确认后进“进行中”。底层没装配时不显示。
 */
@Composable
fun BoxScope.AddTodoHost(bottomPadding: Dp) {
    if (!AppGraph.isManualTodosReady || !AppGraph.isTodoVoiceReady) return
    val todos = AppGraph.manualTodos
    val voice = AppGraph.todoVoice
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    var phase by remember { mutableStateOf(Phase.Idle) }
    var cancelArmed by remember { mutableStateOf(false) }
    var typing by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<Triple<TodoDrafts, ItemOrigin, String>?>(null) }
    var startJob by remember { mutableStateOf<Job?>(null) }
    val prefs = remember { context.getSharedPreferences("ui", Context.MODE_PRIVATE) }
    // 前几次打开待办页时在按钮旁提示用法，之后不再打扰
    val showHint = remember { prefs.getInt(HINT_SHOWN, 0).let { prefs.edit().putInt(HINT_SHOWN, it + 1).apply(); it < 5 } }

    fun toast(text: String) = Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
    fun draft(text: String, origin: ItemOrigin) {
        phase = Phase.Drafting
        scope.launch {
            val drafts = runCatching { todos.draft(text) }.getOrNull()
            phase = Phase.Idle
            if (drafts == null || drafts.items.isEmpty()) toast("没能生成待办，请再试一次")
            else result = Triple(drafts, origin, text)
        }
    }

    val busy = phase == Phase.Recognizing || phase == Phase.Drafting
    Column(Modifier.align(Alignment.BottomEnd).padding(end = 20.dp, bottom = bottomPadding + 20.dp),
        horizontalAlignment = Alignment.End) {
        AnimatedVisibility(phase != Phase.Idle, enter = fadeIn() + scaleIn(initialScale = 0.9f), exit = fadeOut() + scaleOut(targetScale = 0.9f)) {
            StatusCard(phase, cancelArmed, voice)
        }
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (showHint && phase == Phase.Idle) {
                Text("点按打字 · 按住说话", style = RsType.caption, color = Rs.colors.textSecondary,
                    modifier = Modifier.clip(CircleShape).background(Rs.colors.card).padding(horizontal = 12.dp, vertical = 6.dp))
                Spacer(Modifier.width(10.dp))
            }
            AddButton(listening = phase == Phase.Listening, busy = busy,
                onTap = { if (busy) toast("正在生成待办，请稍等") else typing = true },
                onHoldStart = {
                    if (busy) return@AddButton false
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    phase = Phase.Listening; cancelArmed = false
                    startJob = scope.launch {
                        if (!voice.start()) { phase = Phase.Idle; toast("麦克风暂时用不了，请稍后再试") }
                    }
                    true
                },
                onDrag = { cancelArmed = it },
                onHoldEnd = { cancel ->
                    scope.launch {
                        startJob?.join()
                        if (phase != Phase.Listening) return@launch
                        if (cancel) { voice.cancel(); phase = Phase.Idle; return@launch }
                        phase = Phase.Recognizing
                        when (val r = voice.finish()) {
                            is VoiceNoteResult.Text -> draft(r.text, ItemOrigin.Voice)
                            VoiceNoteResult.Empty -> { phase = Phase.Idle; toast("没听清，再说一次") }
                            is VoiceNoteResult.Failed -> { phase = Phase.Idle; toast(r.message) }
                        }
                    }
                })
        }
    }

    if (typing) TypeTodoSheet(onDismiss = { typing = false }) { text -> typing = false; draft(text, ItemOrigin.Typed) }
    result?.let { (drafts, origin, source) ->
        DraftSheet(drafts, source, onDismiss = { result = null }) { confirmed ->
            todos.add(confirmed, origin, source)
            result = null
            toast("已添加 ${confirmed.size} 条到进行中")
        }
    }
}

private const val HINT_SHOWN = "addTodoHintShown"
private const val HOLD_MS = 500L

/** 圆形暖橙按钮：平时是“+”，按住时放大、变成话筒 */
@Composable
private fun AddButton(listening: Boolean, busy: Boolean, onTap: () -> Unit, onHoldStart: () -> Boolean,
                      onDrag: (Boolean) -> Unit, onHoldEnd: (Boolean) -> Unit) {
    val c = Rs.colors
    val cancelPx = with(LocalDensity.current) { 72.dp.toPx() }
    val scale by animateFloatAsState(if (listening) 1.18f else 1f, spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMediumLow), label = "addScale")
    val bg by animateColorAsState(if (busy) c.accent.copy(alpha = 0.6f) else c.accent, label = "addBg")
    Box(
        Modifier.size(60.dp).scale(scale).shadow(10.dp, CircleShape, ambientColor = c.accent, spotColor = c.accent)
            .clip(CircleShape).background(bg)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    // 半秒内松手算点一下；手指被列表抢走（滚动）就什么都不做
                    val up = withTimeoutOrNull(HOLD_MS) { waitForUpOrCancellation() ?: CANCELLED }
                    if (up === CANCELLED) return@awaitEachGesture
                    if (up != null) { onTap(); return@awaitEachGesture }
                    if (!onHoldStart()) return@awaitEachGesture
                    var cancel = false
                    while (true) {
                        val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                        change.consume()
                        val armed = down.position.y - change.position.y > cancelPx
                        if (armed != cancel) { cancel = armed; onDrag(armed) }
                        if (!change.pressed) break
                    }
                    onHoldEnd(cancel)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(26.dp)) {
            val w = size.width
            val stroke = w * 0.11f
            if (listening) {
                // 话筒：胶囊 + 底座弧线 + 支杆
                val capW = w * 0.36f
                drawRoundRect(Color.White, Offset((w - capW) / 2, 0f), Size(capW, w * 0.6f), CornerRadius(capW / 2))
                drawArc(Color.White, 0f, 180f, false, Offset(w * 0.16f, w * 0.18f), Size(w * 0.68f, w * 0.6f),
                    style = Stroke(stroke, cap = StrokeCap.Round))
                drawLine(Color.White, Offset(w / 2, w * 0.78f), Offset(w / 2, w * 0.96f), stroke, StrokeCap.Round)
            } else {
                drawLine(Color.White, Offset(w / 2, w * 0.12f), Offset(w / 2, w * 0.88f), stroke, StrokeCap.Round)
                drawLine(Color.White, Offset(w * 0.12f, w / 2), Offset(w * 0.88f, w / 2), stroke, StrokeCap.Round)
            }
        }
    }
}

private val CANCELLED = Any()

/** 按钮上方的小卡：听的时候显示波形，识别和生成时显示进度 */
@Composable
private fun StatusCard(phase: Phase, cancelArmed: Boolean, voice: TodoVoiceInput) {
    val c = Rs.colors
    val level by voice.level.collectAsState()
    var seconds by remember(phase) { mutableIntStateOf(0) }
    LaunchedEffect(phase) { if (phase == Phase.Listening) while (true) { delay(1000); seconds++ } }
    val bg by animateColorAsState(if (cancelArmed) c.critical else c.card, label = "statusBg")
    val fg = if (cancelArmed) Color.White else c.text
    Column(
        Modifier.width(240.dp).shadow(12.dp, RoundedCornerShape(20.dp), ambientColor = c.shadow, spotColor = c.shadow)
            .clip(RoundedCornerShape(20.dp)).background(bg).padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        when (phase) {
            Phase.Listening -> {
                BreathingWave(level, active = true, recording = true, modifier = Modifier.fillMaxWidth().height(36.dp),
                    tint = if (cancelArmed) Color.White else null)
                Spacer(Modifier.height(8.dp))
                Text(if (cancelArmed) "松开取消" else "正在听，松开生成待办", style = RsType.subhead, color = fg)
                Text(when {
                    cancelArmed -> "滑回去可继续说"
                    seconds >= 50 -> "还能说 ${(60 - seconds).coerceAtLeast(0)} 秒"
                    else -> "上滑取消"
                }, style = RsType.caption, color = if (cancelArmed) Color.White.copy(alpha = 0.8f) else c.textTertiary)
            }
            Phase.Recognizing -> Text("正在识别…", style = RsType.subhead, color = fg)
            Phase.Drafting -> Text("正在生成待办…", style = RsType.subhead, color = fg)
            Phase.Idle -> {}
        }
    }
}

/** 打字添加：写一句话，交给模型拆成待办卡 */
@Composable
private fun TypeTodoSheet(onDismiss: () -> Unit, onSubmit: (String) -> Unit) {
    val c = Rs.colors
    var text by remember { mutableStateOf("") }
    RsSheet("添加待办", "像平时说话一样写，几件事写在一起也行。", onDismiss = onDismiss) {
        BasicTextField(text, { text = it.take(500) }, textStyle = RsType.body.copy(color = c.text), cursorBrush = SolidColor(c.accent),
            minLines = 3,
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.background).padding(horizontal = 16.dp, vertical = 14.dp),
            decorationBox = { inner ->
                Box {
                    if (text.isEmpty()) Text("例如：明天下午三点给张总回电话，周五前把报销单交了", style = RsType.body, color = c.textTertiary)
                    inner()
                }
            })
        ActionButton("生成待办卡", ActionKind.Primary, enabled = text.isNotBlank()) { onSubmit(text.trim()) }
    }
}

/** 一张卡可编辑的内容；时间拆成哪天 + 几点，和编辑待办一样 */
private class DraftState(d: TodoDraft) {
    var category by mutableStateOf(d.category)
    var kind by mutableStateOf(d.kind)
    var content by mutableStateOf(d.content)
    var who by mutableStateOf(d.who?.takeIf { it != "我" }.orEmpty())
    var day by mutableStateOf<LocalDate?>(null)
    var time by mutableStateOf<LocalTime?>(null)

    init {
        d.dueAt?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()) }?.let { at ->
            day = at.toLocalDate()
            time = at.toLocalTime().takeIf { (d.dueText?.length ?: 0) > 10 }
        }
    }

    fun toDraft(): TodoDraft {
        val zone = ZoneId.systemDefault()
        val at = day?.let { d -> (time?.let { d.atTime(it) } ?: d.atStartOfDay()).atZone(zone) }
        val text = at?.format(DateTimeFormatter.ofPattern(if (time != null) "yyyy-MM-dd HH:mm" else "yyyy-MM-dd"))
        return TodoDraft(category, if (category == "待办") kind ?: "我要做" else null, content.trim(), text,
            at?.toInstant()?.toEpochMilli(), who.trim().ifEmpty { null })
    }
}

/** 类型一行点选：待办的三种 + 约定 + 备忘 */
private val TYPES = listOf("我要做" to "待办", "我答应别人" to "待办", "别人答应我" to "待办", "约定" to "约定", "备忘" to "备忘")

/** 确认待办卡：每张可改、可删，确认后一起加进“进行中” */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DraftSheet(drafts: TodoDrafts, source: String, onDismiss: () -> Unit, onConfirm: (List<TodoDraft>) -> Unit) {
    val c = Rs.colors
    val cards = remember(drafts) { mutableStateListOf(*drafts.items.map(::DraftState).toTypedArray()) }
    RsSheet(if (cards.size > 1) "生成了 ${cards.size} 条待办" else "确认待办",
        if (drafts.parsed) "改好后点添加，直接放进“进行中”。" else "没能自动识别，已按原话生成，改好后点添加。",
        onDismiss = onDismiss, fixedHeight = true) {
        Text("“$source”", style = RsType.caption, color = c.textTertiary, maxLines = 3)
        cards.forEachIndexed { i, card ->
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(c.background).padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (cards.size > 1) "第 ${i + 1} 条" else "内容", style = RsType.caption, color = c.textSecondary, modifier = Modifier.weight(1f))
                    if (cards.size > 1) Text("删掉", style = RsType.caption, color = c.critical,
                        modifier = Modifier.clip(CircleShape).springClick { cards.removeAt(i) }.padding(horizontal = 8.dp, vertical = 4.dp))
                }
                CardInput(card.content, "要做的事") { card.content = it }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    TYPES.forEach { (label, category) ->
                        val on = card.category == category && (category != "待办" || (card.kind ?: "我要做") == label)
                        Choice(label, on) { card.category = category; card.kind = if (category == "待办") label else null }
                    }
                }
                DueChoice(card.day, card.time, { card.day = it }, { card.time = it })
                Label("和谁（可不填）")
                CardInput(card.who, "例如 老王") { card.who = it }
            }
        }
        val valid = cards.isNotEmpty() && cards.all { it.content.isNotBlank() }
        ActionButton(if (cards.size > 1) "添加 ${cards.size} 条" else "添加", ActionKind.Primary, enabled = valid) {
            onConfirm(cards.map { it.toDraft() })
        }
    }
}

/** 卡片底色已是页面底色，输入框用卡片色才分得开 */
@Composable
private fun CardInput(value: String, hint: String, onChange: (String) -> Unit) {
    val c = Rs.colors
    BasicTextField(value, onChange, textStyle = RsType.body.copy(color = c.text), cursorBrush = SolidColor(c.accent),
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.card).padding(horizontal = 14.dp, vertical = 12.dp),
        decorationBox = { inner ->
            Box {
                if (value.isEmpty()) Text(hint, style = RsType.body, color = c.textTertiary)
                inner()
            }
        })
}
