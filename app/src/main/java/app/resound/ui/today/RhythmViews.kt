package app.resound.ui.today

import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.resound.capture.QuietRule
import app.resound.digest.MemoryEvent
import app.resound.digest.RhythmBaseline
import app.resound.transcribe.TranscriptLine
import app.resound.ui.theme.Rs
import app.resound.ui.theme.RsType
import app.resound.ui.verify.RsCard
import app.resound.ui.verify.formatClock
import app.resound.ui.verify.springClick
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 当天节奏：只用数得准的东西——原话句子的时长、事件数、整理里提到的人名、会议场景；不用声纹分“我/别人”。
 * 媒体（电视、视频、外放）不算进说话，只在分布条上用灰色叠在柱子上。
 */
internal data class DayRhythm(
    val speechMinutes: Int,
    val events: Int,
    val people: Int,
    val meetings: Int,
    val meetingMinutes: Int,
    /** 0..23 点每小时的说话秒数 / 媒体秒数 */
    val speechByHour: List<Int>,
    val mediaByHour: List<Int>,
    val firstAt: Long,
    val lastAt: Long,
    /** 实际说话最长的一件事：句子时长之和（不含中间的安静） */
    val longest: Pair<MemoryEvent, Int>?,
)

private fun overlaps(l: TranscriptLine, e: MemoryEvent) = l.startAt < e.endAt && l.endAt > e.startAt
private fun minutes(ms: Long) = ((ms + 59_999) / 60_000).toInt()
private fun TranscriptLine.ms() = (endAt - startAt).coerceAtLeast(0)

internal fun dayRhythm(events: List<MemoryEvent>, lines: List<TranscriptLine>): DayRhythm? {
    val zone = ZoneId.systemDefault()
    val media = events.filter { it.isMedia() }
    val life = events.filter { !it.isMedia() }
    val (mediaLines, kept) = lines.partition { l -> media.any { overlaps(l, it) } }
    if (kept.isEmpty()) return null
    fun byHour(ls: List<TranscriptLine>) = IntArray(24).also { h -> ls.forEach { h[Instant.ofEpochMilli(it.startAt).atZone(zone).hour] += (it.ms() / 1000).toInt() } }.toList()
    val meetings = life.filter { it.scene == "会议" }
    val longest = life.map { e -> e to kept.filter { overlaps(it, e) }.sumOf { it.ms() } }.maxByOrNull { it.second }
        ?.takeIf { it.second >= 60_000 }?.let { it.first to minutes(it.second) }
    return DayRhythm(
        speechMinutes = minutes(kept.sumOf { it.ms() }),
        events = life.size,
        people = life.flatMap { it.people }.filter { it != "我" && it.isNotBlank() }.toSet().size,
        meetings = meetings.size,
        meetingMinutes = minutes(kept.filter { l -> meetings.any { overlaps(l, it) } }.sumOf { it.ms() }),
        speechByHour = byHour(kept), mediaByHour = byHour(mediaLines),
        firstAt = kept.minOf { it.startAt }, lastAt = kept.maxOf { it.endAt },
        longest = longest,
    )
}

/** 分布条显示的小时范围：从最早有声音的前一小时到最晚的后一小时（看今天时到现在），最少 8 小时 */
internal fun hourRange(r: DayRhythm, date: LocalDate): IntRange {
    val active = (0..23).filter { r.speechByHour[it] > 0 || r.mediaByHour[it] > 0 }
    if (active.isEmpty()) return 8..19
    var start = (active.min() - 1).coerceAtLeast(0)
    var end = (active.max() + 1).coerceAtMost(23)
    // 看今天时不画到未来：最多到现在这个小时
    if (date == LocalDate.now()) end = maxOf(active.max(), minOf(end, java.time.LocalTime.now().hour))
    while (end - start + 1 < 8) { if (end < 23) end++ else if (start > 0) start-- else break }
    return start..end
}

/** “比平时多 30%”：至少 3 天记录才有平时；差别不到 15% 不显示 */
private fun compare(today: Int, usual: Int?): String? {
    if (usual == null || usual <= 0) return null
    val pct = ((today - usual) * 100f / usual).toInt()
    return when { pct >= 15 -> "↑$pct%"; pct <= -15 -> "↓${-pct}%"; else -> null }
}

/** 停录时段（如睡眠）当天落在哪些小时，分布条上用浅底标出 */
private fun quietHours(rules: List<QuietRule>, date: LocalDate): Map<Int, String> = buildMap {
    rules.filter { it.enabled }.forEach { r ->
        val spans = if (r.endMinute <= r.startMinute) listOf(
            (r.startMinute / 60..23).takeIf { date.dayOfWeek in r.days },
            (0 until (r.endMinute + 59) / 60).takeIf { date.minusDays(1).dayOfWeek in r.days })
        else listOf((r.startMinute / 60 until (r.endMinute + 59) / 60).takeIf { date.dayOfWeek in r.days })
        spans.filterNotNull().forEach { span -> span.forEach { putIfAbsent(it, r.name) } }
    }
}

/**
 * 今天页“今日节奏”：说话 · 件事 · 提到几人 · 开会；数字可点（点了筛选时间线）；柱子可点（跳到那个小时）。
 * 说话不到 5 分钟的日子只显示一行。
 */
@Composable
internal fun RhythmCard(events: List<MemoryEvent>, lines: List<TranscriptLine>, date: LocalDate, pendingSegments: Int,
                        onFilter: (TimelineFilter) -> Unit, onHour: (Int) -> Unit, onOpen: (MemoryEvent) -> Unit) {
    val c = Rs.colors
    val r = remember(events, lines) { dayRhythm(events, lines) } ?: return
    val base by remember(date) { if (app.resound.AppGraph.isRhythmReady) app.resound.AppGraph.rhythm.baseline(date) else kotlinx.coroutines.flow.flowOf(null) }
        .collectAsState(initial = null)
    val rules = if (app.resound.AppGraph.isQuietScheduleReady) app.resound.AppGraph.quietSchedule.rules.collectAsState().value else emptyList()
    val pending = if (pendingSegments > 0 && date == LocalDate.now()) " · 还有 $pendingSegments 段待转写" else ""
    RsCard(padding = PaddingValues(horizontal = 18.dp, vertical = 14.dp)) {
        Text("今日节奏", style = RsType.caption.copy(fontWeight = FontWeight.Medium), color = c.textTertiary)
        if (r.speechMinutes < 5) {
            Spacer(Modifier.height(6.dp))
            Text("今天说话 ${r.speechMinutes} 分钟 · ${r.events} 件事$pending", style = RsType.subhead, color = c.textSecondary)
            return@RsCard
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth()) {
            Stat("说话", "${r.speechMinutes} 分", compare(r.speechMinutes, base?.speechMinutes), Modifier.weight(1f)) { onFilter(TimelineFilter.All) }
            Stat("件事", "${r.events}", compare(r.events, base?.events), Modifier.weight(1f)) { onFilter(TimelineFilter.All) }
            Stat("提到", "${r.people} 人", compare(r.people, base?.people), Modifier.weight(1f)) { onFilter(TimelineFilter.All) }
            Stat("开会", if (r.meetings == 0) "没有" else "${r.meetings} 次", null, Modifier.weight(1f)) { if (r.meetings > 0) onFilter(TimelineFilter.Meeting) }
        }
        Spacer(Modifier.height(12.dp))
        // 分布条：只画有声音的时段；说话主色、媒体灰色叠在上面；停录时段浅底标名字；点柱子跳到那个小时
        val range = hourRange(r, date)
        val quiet = remember(rules, date) { quietHours(rules, date) }
        val totals = range.map { r.speechByHour[it] + r.mediaByHour[it] }
        val max = (totals.maxOrNull() ?: 1).coerceAtLeast(1)
        val peak = range.maxByOrNull { r.speechByHour[it] } ?: range.first
        Row(Modifier.fillMaxWidth().height(36.dp), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.Bottom) {
            range.forEach { h ->
                val speech = r.speechByHour[h]; val mediaS = r.mediaByHour[h]; val total = speech + mediaS
                Box(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(3.dp))
                    .background(if (h in quiet) c.separator.copy(alpha = 0.35f) else androidx.compose.ui.graphics.Color.Transparent)
                    .springClick { if (total > 0) onHour(h) }, contentAlignment = Alignment.BottomCenter) {
                    if (total == 0) Box(Modifier.fillMaxWidth().fillMaxHeight(0.06f).clip(RoundedCornerShape(2.dp)).background(c.separator))
                    else Column(Modifier.fillMaxWidth().fillMaxHeight((total / max.toFloat()).coerceIn(0.12f, 1f))) {
                        if (mediaS > 0) Box(Modifier.fillMaxWidth().weight(mediaS.toFloat()).clip(RoundedCornerShape(topStart = 2.dp, topEnd = 2.dp)).background(c.textTertiary.copy(alpha = 0.45f)))
                        if (speech > 0) Box(Modifier.fillMaxWidth().weight(speech.toFloat()).clip(RoundedCornerShape(2.dp))
                            .background(if (h == peak) c.accent else c.accent.copy(alpha = 0.35f)))
                    }
                }
            }
        }
        // 刻度：按范围挑 3～4 个整点；停录时段在下方写名字
        Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            val step = when { range.count() > 16 -> 6; range.count() > 10 -> 4; else -> 2 }
            range.forEach { h ->
                Box(Modifier.weight(1f)) {
                    if ((h - range.first) % step == 0) Text("$h", style = RsType.caption, color = c.textTertiary, maxLines = 1, softWrap = false)
                }
            }
        }
        quiet.entries.firstOrNull { it.key in range }?.let { (_, name) ->
            Text("浅底为停录时段（$name）", style = RsType.caption, color = c.textTertiary, modifier = Modifier.padding(top = 2.dp))
        }
        Spacer(Modifier.height(4.dp))
        Text("${formatClock(r.firstAt)} 开始 · ${formatClock(r.lastAt)} 结束 · 说话最多在 $peak–${peak + 1} 点$pending",
            style = RsType.caption, color = c.textSecondary)
        r.longest?.let { (e, m) ->
            Text("最长的一段：${formatClock(e.startAt)} ${e.title} · 说了 $m 分钟 ›", style = RsType.caption.copy(fontWeight = FontWeight.Medium),
                color = c.accent, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp).springClick { onOpen(e) })
        }
    }
}

@Composable
private fun Stat(label: String, value: String, delta: String?, modifier: Modifier, onClick: () -> Unit) {
    val c = Rs.colors
    Column(modifier.padding(end = 6.dp).clip(RoundedCornerShape(10.dp)).springClick(onClick)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, style = RsType.headline, color = c.text, maxLines = 1, modifier = Modifier.weight(1f, fill = false),
                autoSize = TextAutoSize.StepBased(minFontSize = 11.sp, maxFontSize = RsType.headline.fontSize))
            delta?.let {
                Text(" $it", style = RsType.caption.copy(fontSize = 10.sp), color = if (it.startsWith("↑")) c.accent else c.textTertiary, maxLines = 1)
            }
        }
        Text(label, style = RsType.caption, color = c.textTertiary, maxLines = 1, textAlign = TextAlign.Start,
            autoSize = TextAutoSize.StepBased(minFontSize = 9.sp, maxFontSize = RsType.caption.fontSize))
    }
}
