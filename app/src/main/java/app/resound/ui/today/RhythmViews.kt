package app.resound.ui.today

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.resound.digest.MemoryEvent
import app.resound.transcribe.TranscriptLine
import app.resound.ui.theme.Rs
import app.resound.ui.theme.RsType
import app.resound.ui.verify.RsCard
import java.time.Instant
import java.time.ZoneId

/** 不算“自己生活”的场景：电视视频、旁人的对话 */
private val NOT_MINE = setOf("媒体", "旁人")

/** 当天节奏的客观数字：只数得清的东西，不猜情绪 */
internal data class DayRhythm(
    val myMinutes: Int,
    val othersMinutes: Int,
    val people: Int,
    val meetings: Int,
    val meetingMinutes: Int,
    /** 0..23 点每小时的说话分钟数 */
    val byHour: List<Int>,
)

internal fun dayRhythm(events: List<MemoryEvent>, lines: List<TranscriptLine>): DayRhythm? {
    val zone = ZoneId.systemDefault()
    val media = events.filter { it.scene in NOT_MINE }
    val kept = lines.filter { l -> media.none { l.startAt < it.endAt && l.endAt > it.startAt } }
    if (kept.isEmpty()) return null
    fun minutes(ms: Long) = ((ms + 59_999) / 60_000).toInt()
    val mine = kept.filter { it.speakerName == "我" }.sumOf { (it.endAt - it.startAt).coerceAtLeast(0) }
    val others = kept.filter { it.speakerName != "我" }.sumOf { (it.endAt - it.startAt).coerceAtLeast(0) }
    val life = events.filter { it.scene !in NOT_MINE }
    val meetings = life.filter { it.scene == "会议" }
    val hours = IntArray(24)
    kept.forEach { l -> hours[Instant.ofEpochMilli(l.startAt).atZone(zone).hour] += ((l.endAt - l.startAt).coerceAtLeast(0) / 1000).toInt() }
    return DayRhythm(
        myMinutes = minutes(mine), othersMinutes = minutes(others),
        people = life.flatMap { it.people }.filter { it != "我" && it.isNotBlank() }.toSet().size,
        meetings = meetings.size, meetingMinutes = minutes(meetings.sumOf { it.endAt - it.startAt }),
        byHour = hours.map { (it + 59) / 60 },
    )
}

/** 今天页“今日节奏”：我说 / 别人说、聊过几个人、开会，以及一条 24 小时说话分布 */
@Composable
internal fun RhythmCard(events: List<MemoryEvent>, lines: List<TranscriptLine>) {
    val c = Rs.colors
    val r = dayRhythm(events, lines) ?: return
    RsCard(padding = PaddingValues(horizontal = 18.dp, vertical = 14.dp)) {
        Text("今日节奏", style = RsType.caption.copy(fontWeight = FontWeight.Medium), color = c.textTertiary)
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth()) {
            Stat("我说了", "${r.myMinutes} 分", Modifier.weight(1f))
            Stat("别人说了", "${r.othersMinutes} 分", Modifier.weight(1f))
            Stat("聊过", "${r.people} 人", Modifier.weight(1f))
            Stat("开会", if (r.meetings == 0) "没有" else "${r.meetings} 次", Modifier.weight(1f))
        }
        Spacer(Modifier.height(12.dp))
        // 24 小时说话分布：最忙的那个小时用主色，其余浅色
        val max = r.byHour.maxOrNull()?.coerceAtLeast(1) ?: 1
        val peak = r.byHour.indexOf(r.byHour.max())
        Row(Modifier.fillMaxWidth().height(32.dp), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.Bottom) {
            r.byHour.forEachIndexed { h, m ->
                Box(Modifier.weight(1f).fillMaxHeight(if (m == 0) 0.06f else (m / max.toFloat()).coerceIn(0.12f, 1f))
                    .clip(RoundedCornerShape(2.dp))
                    .background(when { m == 0 -> c.separator; h == peak -> c.accent; else -> c.accent.copy(alpha = 0.35f) }))
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            // 每 6 小时一格，刻度对齐到这一格的第一根柱子
            listOf("0", "6", "12", "18").forEach { t ->
                Text(t, style = RsType.caption, color = c.textTertiary, modifier = Modifier.weight(1f))
            }
        }
        if (r.byHour.any { it > 0 }) Text("说话最多在 $peak–${peak + 1} 点" + if (r.meetings > 0) " · 开会共 ${r.meetingMinutes} 分钟" else "",
            style = RsType.caption, color = c.textSecondary)
    }
}

@Composable
private fun Stat(label: String, value: String, modifier: Modifier) {
    val c = Rs.colors
    Column(modifier) {
        Text(value, style = RsType.headline, color = c.text)
        Text(label, style = RsType.caption, color = c.textTertiary)
    }
}
