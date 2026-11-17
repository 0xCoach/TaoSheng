package app.resound.ui.night

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.resound.AppGraph
import app.resound.night.Feedback
import app.resound.night.NightDetail
import app.resound.night.NightEvent
import app.resound.night.NightEventKind
import app.resound.night.NightState
import app.resound.ui.theme.Rs
import app.resound.ui.theme.RsType
import app.resound.ui.verify.Pill
import app.resound.ui.verify.RsCard
import app.resound.ui.verify.springClick
import kotlinx.coroutines.launch
import java.time.LocalDate
import kotlin.math.roundToInt

/**
 * 首页“昨晚”卡：一句话 + 三个时间 + 小声音地图；下面最多 3 段“是不是”（左滑不是、右滑是，也能点），
 * 再下面是标签建议和鼾声中断提示。点卡片打开详情。夜里功能没开或这天没有记录时不显示。
 */
@Composable
fun NightHomeCard(date: LocalDate) {
    if (!AppGraph.isNightReady) return
    val repo = AppGraph.night
    val settings by repo.settings.collectAsStateWithLifecycle()
    if (!settings.enabled) return
    val detail by remember(date) { repo.night(date.toKotlin()) }.collectAsStateWithLifecycle(initialValue = null)
    val d = detail ?: return
    val c = Rs.colors
    val s = d.summary
    val scope = rememberCoroutineScope()
    RsCard(Modifier.springClick { NightUi.openDate = date }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("昨晚", style = RsType.headline, color = c.text)
            Spacer(Modifier.weight(1f))
            s.confidence?.let { conf ->
                Row(Modifier.clip(RoundedCornerShape(50)).background(confidenceColor(conf).copy(alpha = .14f)).padding(horizontal = 8.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(if (conf == app.resound.night.NightConfidence.Good) "记录完整" else conf.title, style = RsType.caption, color = confidenceColor(conf))
                }
            }
            Text("  ›", style = RsType.headline, color = c.textTertiary)
        }
        Spacer(Modifier.height(8.dp))
        when (s.state) {
            NightState.Processing -> Text("正在整理昨晚的声音…", style = RsType.body, color = c.textSecondary)
            NightState.Provisional, NightState.Active, NightState.Waiting -> Text("夜里记录中", style = RsType.body, color = c.textSecondary)
            else -> Text(s.sentence ?: s.sleepMillis?.let { "睡了约 ${span(it)}" } ?: "昨晚没记到完整的睡眠", style = RsType.body.copy(fontWeight = FontWeight.Medium), color = c.text)
        }
        s.times?.let { t ->
            Spacer(Modifier.height(6.dp))
            Text("上床 ${clock(t.bedtime)} · ${if (t.onsetEstimated && !t.editedOnset) "约 " else ""}${clock(t.onset)} 入睡 · ${clock(t.wake)} 起床",
                style = RsType.subhead, color = c.textSecondary)
            Spacer(Modifier.height(12.dp))
            NightMap(t.bedtime, t.wake, d.envelope, d.events, selected = null, height = 44.dp, showHours = false)
        }
        Questions(d)
        Suggestions(d)
        d.breakNotice?.let { note ->
            Spacer(Modifier.height(12.dp))
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.accentSoft).padding(12.dp)) {
                Text(note, style = RsType.subhead, color = c.text)
                Spacer(Modifier.height(6.dp))
                Text("不再提示", style = RsType.caption, color = c.accent, modifier = Modifier.springClick {
                    scope.launch { AppGraph.nightInsights.muteBreakNotice() }
                })
            }
        }
    }
}

/** 最多 3 段拿不准的：一次一张，滑走或点完换下一张 */
@Composable
private fun Questions(d: NightDetail) {
    val asks = d.questions.mapNotNull { id -> d.events.firstOrNull { it.id == id } }.filter { it.feedback == Feedback.None }
    if (asks.isEmpty()) return
    val c = Rs.colors
    Spacer(Modifier.height(14.dp))
    Text("帮我确认 ${asks.size} 段", style = RsType.caption.copy(fontWeight = FontWeight.Medium), color = c.textTertiary)
    Spacer(Modifier.height(6.dp))
    AnimatedContent(asks.first(), transitionSpec = { (slideInHorizontally { it / 4 } + fadeIn()) togetherWith fadeOut() }, label = "nightAsk") { e ->
        AskCard(e)
    }
}

@Composable
private fun AskCard(e: NightEvent) {
    val c = Rs.colors
    val scope = rememberCoroutineScope()
    val dx = remember(e.id) { Animatable(0f) }
    val limit = with(LocalDensity.current) { 110.dp.toPx() }
    fun answer(v: Feedback) = scope.launch { AppGraph.night.feedback(e.id, v) }
    val playing = app.resound.ui.voiceprint.SamplePlayer.playingKey == e.clipId?.let(::clipKey)
    var missing by remember(e.id) { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth()
            .offset { IntOffset(dx.value.roundToInt(), 0) }
            .graphicsLayer { rotationZ = dx.value / 60f; alpha = 1f - (kotlin.math.abs(dx.value) / (limit * 2.5f)).coerceIn(0f, .6f) }
            .clip(RoundedCornerShape(16.dp)).background(c.fill)
            .pointerInput(e.id) {
                detectHorizontalDragGestures(
                    onDragEnd = {
                        scope.launch {
                            when {
                                dx.value > limit -> { dx.animateTo(limit * 3); answer(Feedback.Yes) }
                                dx.value < -limit -> { dx.animateTo(-limit * 3); answer(Feedback.No) }
                                else -> dx.animateTo(0f)
                            }
                        }
                    },
                ) { _, delta -> scope.launch { dx.snapTo(dx.value + delta) } }
            }
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            app.resound.ui.verify.Dot(kindColor(e.kind), 8.dp)
            Text("  ${clock(e.start)} · 这段是${if (e.kind == NightEventKind.Snore) "鼾声" else "咳嗽或清嗓"}吗？", style = RsType.body, color = c.text, modifier = Modifier.weight(1f))
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (e.clipId != null && e.audioAvailable) Pill(if (playing) "■ 停" else "▶ 听一下", filled = false) {
                if (playing) app.resound.ui.voiceprint.SamplePlayer.stop()
                else scope.launch { missing = !runCatching { playClip(e.clipId!!) }.getOrDefault(false) }
            }
            Spacer(Modifier.weight(1f))
            Pill("不是", filled = false) { answer(Feedback.No) }
            Pill("是", filled = true) { answer(Feedback.Yes) }
        }
        if (missing) Text("录音已不在了", style = RsType.caption, color = c.textTertiary, modifier = Modifier.padding(top = 6.dp))
        Text("左滑不是 · 右滑是", style = RsType.caption, color = c.textTertiary, modifier = Modifier.padding(top = 8.dp))
    }
}

/** “昨晚好像 喝了酒？”：点了才算标上 */
@Composable
private fun Suggestions(d: NightDetail) {
    if (d.suggestedTags.isEmpty()) return
    val c = Rs.colors
    val scope = rememberCoroutineScope()
    Spacer(Modifier.height(12.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("昨晚好像", style = RsType.subhead, color = c.textSecondary)
        d.suggestedTags.take(3).forEach { tag ->
            Spacer(Modifier.width(8.dp))
            Pill("${tag.name}？", filled = false) { scope.launch { AppGraph.nightInsights.setTag(d.summary.id, tag.id, true) } }
        }
    }
}

internal fun LocalDate.toKotlin(): kotlinx.datetime.LocalDate = kotlinx.datetime.LocalDate(year, monthValue, dayOfMonth)
