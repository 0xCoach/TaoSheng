package app.resound.ui.night

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.resound.AppGraph
import app.resound.night.Feedback
import app.resound.night.NightDays
import app.resound.night.NightDetail
import app.resound.night.NightState
import app.resound.ui.theme.Rs
import app.resound.ui.theme.RsType
import app.resound.ui.verify.springClick
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.ZoneId

/**
 * 今日节奏里要用的夜里数据：这一天里的睡眠（早上醒来那一夜 + 晚上开始的那一夜）、
 * 早上那一夜的概要、还有几段待确认、现在是不是在记。夜里功能没开为 null。
 */
internal data class NightRhythmUi(
    val date: LocalDate,
    val day: NightDays.Day?,
    /** 这天早上醒来那一夜（整理好了才有） */
    val last: NightDetail?,
    val pending: Int,
    val recording: Boolean,
    val since: Long?,
)

@Composable
internal fun rememberNightRhythm(date: LocalDate): NightRhythmUi? {
    if (!AppGraph.isNightReady) return null
    val repo = AppGraph.night
    val settings by repo.settings.collectAsStateWithLifecycle()
    if (!settings.enabled) return null
    val morning by remember(date) { repo.night(date.toKotlin()) }.collectAsStateWithLifecycle(initialValue = null)
    val evening by remember(date) { repo.night(date.plusDays(1).toKotlin()) }.collectAsStateWithLifecycle(initialValue = null)
    val live by repo.live.collectAsStateWithLifecycle()
    val recording = live.state == NightState.Active || live.state == NightState.Provisional
    // 正在记的那一夜每分钟往前长一点
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(recording) { while (recording) { now = System.currentTimeMillis(); delay(60_000) } }
    val zone = ZoneId.systemDefault()
    val hourStarts = remember(date) { (0..24).map { date.atStartOfDay().plusHours(it.toLong()).atZone(zone).toInstant().toEpochMilli() } }
    fun input(d: NightDetail?): NightDays.Input? {
        d ?: return null
        val active = d.summary.state in setOf(NightState.Provisional, NightState.Active)
        val t = d.summary.times
        return when {
            t != null && !active -> NightDays.Input(d.summary.date, t.onset, t.wake, d.events)
            active -> (t?.onset ?: live.since ?: return null).let { NightDays.Input(d.summary.date, it, now, d.events, live = true) }
            else -> null
        }
    }
    val day = remember(morning, evening, now, hourStarts) {
        NightDays.build(hourStarts, listOfNotNull(input(morning), input(evening)))
    }
    val last = morning?.takeIf { it.summary.state !in setOf(NightState.Provisional, NightState.Active, NightState.Waiting) }
    val pending = last?.let { d -> d.questions.count { id -> d.events.firstOrNull { it.id == id }?.feedback == Feedback.None } } ?: 0
    return NightRhythmUi(date, day, last, pending, recording, live.since)
}

/** 节奏卡标题右边的小入口：没睡过“🌙 夜里”、在记“🌙 记录中”、早上“🌙 昨晚 6小时40分” */
@Composable
internal fun NightChip(ui: NightRhythmUi) {
    val c = Rs.colors
    val label = when {
        ui.last?.summary?.sleepMillis != null -> "🌙 昨晚 ${hours(ui.last.summary.sleepMillis!!)}"
        ui.last != null -> "🌙 昨晚"
        ui.recording && ui.date == LocalDate.now() -> "🌙 记录中"
        else -> "🌙 夜里"
    }
    Text("$label ›", style = RsType.caption.copy(fontWeight = FontWeight.SemiBold), color = SnoreColor, maxLines = 1,
        modifier = Modifier.clip(CircleShape).background(SleepColor.copy(alpha = .18f)).springClick {
            when {
                ui.last != null -> NightUi.openDate = ui.date
                else -> NightUi.trendOpen = true
            }
        }.padding(horizontal = 10.dp, vertical = 4.dp))
}

/** 图下面一行：有待确认的鼾声才出现 */
@Composable
internal fun NightPendingLine(ui: NightRhythmUi) {
    if (ui.pending <= 0) return
    Row(Modifier.fillMaxWidth().padding(top = 6.dp).clip(RoundedCornerShape(10.dp)).springClick { NightUi.openDate = ui.date }.padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(SnoreColor))
        Text("  昨晚有 ${ui.pending} 段声音等你确认 ›", style = RsType.subhead, color = Rs.colors.text)
    }
}
