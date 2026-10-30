package app.resound.ui.transcript

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.resound.transcribe.AsrSpeedSettings
import app.resound.transcribe.ClassModeState
import app.resound.ui.theme.Rs
import app.resound.ui.theme.RsType
import app.resound.ui.verify.springClick

/** 课堂模式时长：15 分钟～4 小时，每格 15 分钟；0 = 不限时（手动结束） */
private const val STEP = 15
private const val MAX = 240

/**
 * 课堂模式的时长滑轨 + 开始/结束按钮。开着时显示几点结束和“结束课堂模式”；
 * 关着时拖滑轨选时长（实时显示几点结束，每过整点轻震），或点“不限时”。设置页与聆听面板共用。
 */
@Composable
internal fun ClassModePicker(state: ClassModeState, speed: AsrSpeedSettings, onStarted: () -> Unit = {}) {
    val c = Rs.colors
    if (state.enabled) {
        Box(Modifier.fillMaxWidth().clip(CircleShape).background(c.accent.copy(alpha = 0.12f))
            .springClick { speed.stopClassMode() }.padding(vertical = 11.dp), contentAlignment = Alignment.Center) {
            Text("结束课堂模式", style = RsType.subhead.copy(fontWeight = FontWeight.Medium), color = c.accent)
        }
        return
    }
    var minutes by remember { mutableIntStateOf(120) }
    var unlimited by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(if (unlimited) "不限时" else durationText(minutes), style = RsType.title.copy(fontFeatureSettings = "tnum"),
                color = if (unlimited) c.textSecondary else c.text, modifier = Modifier.weight(1f))
            Text(if (unlimited) "手动结束" else "到 ${endClock(minutes)} 结束", style = RsType.caption.copy(fontFeatureSettings = "tnum"),
                color = c.textTertiary, modifier = Modifier.padding(bottom = 3.dp))
        }
        StepSlider(minutes, enabled = !unlimited) { minutes = it; unlimited = false }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            val bg by animateColorAsState(if (unlimited) c.accentSoft else c.background, label = "unlimited")
            Text("不限时", style = RsType.subhead.copy(fontWeight = FontWeight.Medium), color = if (unlimited) c.accent else c.textSecondary,
                modifier = Modifier.clip(CircleShape).background(bg).springClick { unlimited = !unlimited }.padding(horizontal = 14.dp, vertical = 9.dp))
            Box(Modifier.weight(1f).clip(CircleShape).background(c.accent)
                .springClick { speed.startClassMode(if (unlimited) null else minutes); onStarted() }.padding(vertical = 10.dp),
                contentAlignment = Alignment.Center) {
                Text("开始课堂模式", style = RsType.subhead.copy(fontWeight = FontWeight.Medium), color = Color.White)
            }
        }
    }
}

/** 分格滑轨：点或拖都对齐到 15 分钟一格；每过整点轻震 */
@Composable
private fun StepSlider(value: Int, enabled: Boolean, onChange: (Int) -> Unit) {
    val c = Rs.colors
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    val latest by rememberUpdatedState(value)
    val change by rememberUpdatedState(onChange)
    BoxWithConstraints(Modifier.fillMaxWidth().height(32.dp)) {
        val knob = 26.dp
        val width = maxWidth
        val trackPx = with(density) { (width - knob).toPx() }
        fun snap(x: Float): Int {
            val f = ((x - with(density) { knob.toPx() } / 2) / trackPx).coerceIn(0f, 1f)
            return (Math.round(f * (MAX - STEP) / STEP) * STEP + STEP).coerceIn(STEP, MAX)
        }
        fun set(v: Int) {
            if (v == latest) return
            if (v / 60 != latest / 60 || v == STEP || v == MAX) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            change(v)
        }
        val fraction = (value - STEP).toFloat() / (MAX - STEP)
        val offset by animateDpAsState((width - knob) * fraction, spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessHigh), label = "knob")
        val fill = if (enabled) c.accent else c.separator
        Box(Modifier.fillMaxWidth().height(32.dp)
            .pointerInput(trackPx) { detectTapGestures { set(snap(it.x)) } }
            .pointerInput(trackPx) { detectHorizontalDragGestures { change, _ -> change.consume(); set(snap(change.position.x)) } }) {
            Box(Modifier.align(Alignment.CenterStart).padding(horizontal = knob / 2).fillMaxWidth().height(6.dp).clip(CircleShape).background(c.background))
            Box(Modifier.align(Alignment.CenterStart).padding(start = knob / 2).width(offset).height(6.dp).clip(CircleShape).background(fill))
            // 整点刻度
            for (h in 1..MAX / 60) {
                val f = (h * 60 - STEP).toFloat() / (MAX - STEP)
                Box(Modifier.align(Alignment.CenterStart).offset(x = (width - knob) * f + knob / 2 - 1.dp).size(2.dp, 10.dp)
                    .clip(CircleShape).background(if (h * 60 <= value && enabled) Color.White.copy(alpha = 0.7f) else c.separator))
            }
            Box(Modifier.align(Alignment.CenterStart).offset(x = offset).size(knob).shadow(3.dp, CircleShape).clip(CircleShape).background(Color.White))
        }
    }
    Row(Modifier.fillMaxWidth()) {
        Text("15 分钟", style = RsType.caption, color = c.textTertiary, modifier = Modifier.weight(1f))
        Text("4 小时", style = RsType.caption, color = c.textTertiary)
    }
}

private fun durationText(m: Int) = when {
    m < 60 -> "$m 分钟"
    m % 60 == 0 -> "${m / 60} 小时"
    else -> "${m / 60} 小时 ${m % 60} 分"
}

private fun endClock(m: Int): String = java.time.LocalTime.now().plusMinutes(m.toLong()).let { t ->
    (if (java.time.LocalTime.now().plusMinutes(m.toLong()).isBefore(java.time.LocalTime.now())) "明天 " else "") + "%d:%02d".format(t.hour, t.minute)
}
