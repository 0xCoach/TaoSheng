package app.resound.ui.topic

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.resound.topic.*
import app.resound.topic.core.TopicProcessingRules
import app.resound.ui.theme.*
import app.resound.ui.verify.*
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** 面板顶部的两格切换；记录里有没整理完的专题时带一个小橙点 */
@Composable internal fun TopicTabs(history: Boolean, failed: Int, live: Boolean, select: (Boolean) -> Unit) {
    val c = Rs.colors
    Row(Modifier.fillMaxWidth().clip(CircleShape).background(c.track).padding(4.dp)) {
        listOf(if (live) "当前专题" else "开始专题", "专题记录").forEachIndexed { i, label ->
            val on = history == (i == 1)
            // 夜间滑块要比底条亮得明显一些，否则看不出选的是哪一格
            val bg by animateColorAsState(if (on) (if (c.isDark) Color(0xFF636366) else c.trackThumb) else Color.Transparent, label = "topicTab")
            Row(Modifier.weight(1f).clip(CircleShape).background(bg).springClick { select(i == 1) }.padding(vertical = 9.dp),
                horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                Text(label, style = RsType.subhead.copy(fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium), color = if (on) c.text else c.textSecondary)
                if (i == 1 && failed > 0) {
                    Spacer(Modifier.width(6.dp))
                    Box(Modifier.size(6.dp).clip(CircleShape).background(c.accent))
                }
            }
        }
    }
}

@Composable private fun SearchField(query: String, setQuery: (String) -> Unit) {
    val c = Rs.colors
    Row(Modifier.fillMaxWidth().clip(CircleShape).background(c.fill).padding(horizontal = 14.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
        val ink = c.textTertiary
        Canvas(Modifier.size(16.dp)) {
            val w = 1.6.dp.toPx(); val r = size.minDimension * 0.34f
            drawCircle(ink, r, Offset(r + w, r + w), style = Stroke(w))
            drawLine(ink, Offset(r * 1.75f + w, r * 1.75f + w), Offset(size.width - w, size.height - w), w, StrokeCap.Round)
        }
        Spacer(Modifier.width(10.dp))
        BasicTextField(query, setQuery, singleLine = true, textStyle = RsType.body.copy(color = c.text), cursorBrush = SolidColor(c.accent),
            modifier = Modifier.weight(1f), decorationBox = { inner -> Box { if (query.isEmpty()) Text("搜索名字或类型", style = RsType.body, color = c.textTertiary); inner() } })
        if (query.isNotEmpty()) Text("×", style = RsType.headline.copy(fontWeight = FontWeight.Normal), color = c.textTertiary,
            modifier = Modifier.clip(CircleShape).springClick { setQuery("") }.padding(horizontal = 6.dp))
    }
}

@Composable internal fun TopicHistoryContent(records: List<Topic>, query: String, setQuery: (String) -> Unit,
    attention: Boolean, setAttention: (Boolean) -> Unit, shown: Int, more: () -> Unit,
    progress: @Composable (Topic) -> TopicProgress, busyIds: Set<Long>, open: (Long) -> Unit, retry: (Long) -> Unit,
    managing: Boolean = false, setManaging: (Boolean) -> Unit = {}, delete: (Long) -> Unit = {}) {
    val c = Rs.colors
    if (records.isEmpty()) {
        Column(Modifier.fillMaxWidth().padding(vertical = 56.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            app.resound.ui.skills.SkillIcon("meeting", 52.dp)
            Spacer(Modifier.height(16.dp))
            Text("还没有专题", style = RsType.headline, color = c.text)
            Spacer(Modifier.height(6.dp))
            Text("开一场会议或培训，纪要和原话会留在这里", style = RsType.subhead, color = c.textTertiary)
        }
        return
    }
    SearchField(query, setQuery)
    val failed = records.count { it.status == TopicStatus.Failed }
    // 没整理完的专题：一张浅色提示卡，点右边只看这些
    if (failed > 0 || attention) Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.accentSoft)
        .springClick { setAttention(!attention) }.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(c.accent))
        Spacer(Modifier.width(10.dp))
        Text(if (attention) "只显示没整理完的" else "$failed 场专题没整理完", style = RsType.subhead.copy(fontWeight = FontWeight.Medium), color = c.text, modifier = Modifier.weight(1f))
        Text(if (attention) "看全部" else "只看这些", style = RsType.subhead.copy(fontWeight = FontWeight.SemiBold), color = c.accent)
    }
    val filtered = remember(records, query, attention) { TopicProcessingRules.history(records, query, attention) }
    Row(Modifier.fillMaxWidth().padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(if (filtered.size == records.size) "共 ${records.size} 场" else "${filtered.size} / ${records.size} 场", style = RsType.caption, color = c.textTertiary, modifier = Modifier.weight(1f))
        Text(if (managing) "完成" else "管理", style = RsType.subhead.copy(fontWeight = FontWeight.SemiBold), color = c.accent,
            modifier = Modifier.clip(CircleShape).springClick { setManaging(!managing) }.padding(horizontal = 8.dp, vertical = 4.dp))
    }
    AnimatedVisibility(managing) {
        Text("删除后，纪要和合成的那件事一并删除，这段原话回到平常整理；录音和原话保留。", style = RsType.caption, color = c.textTertiary)
    }
    if (filtered.isEmpty()) Column(Modifier.fillMaxWidth().padding(vertical = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("没有找到", style = RsType.headline, color = c.textSecondary)
        Spacer(Modifier.height(6.dp))
        Text("换个关键词，或点“看全部”", style = RsType.subhead, color = c.textTertiary)
    }
    val zone = ZoneId.systemDefault()
    val groups = filtered.take(shown).groupBy { Instant.ofEpochMilli(it.spans.minOfOrNull { s -> s.startAt } ?: it.plannedStartAt ?: it.createdAt).atZone(zone).toLocalDate() }
    val today = LocalDate.now()
    groups.forEach { (day, list) ->
        Text(when {
            day == today -> "今天"; day == today.minusDays(1) -> "昨天"
            day.year == today.year -> "${day.monthValue}月${day.dayOfMonth}日"
            else -> "${day.year}年${day.monthValue}月${day.dayOfMonth}日"
        }, style = RsType.section, color = c.textTertiary, modifier = Modifier.padding(top = 10.dp, start = 4.dp))
        list.forEach { topic -> key(topic.id) {
            TopicRecordRow(topic, progress(topic), topic.id in busyIds, { open(topic.id) }, { retry(topic.id) }, managing = managing, delete = { delete(topic.id) })
        } }
    }
    if (filtered.size > shown) Box(Modifier.fillMaxWidth().clip(CircleShape).background(c.fill).springClick(more).padding(vertical = 11.dp), contentAlignment = Alignment.Center) {
        Text("再看 ${minOf(30, filtered.size - shown)} 场", style = RsType.subhead.copy(fontWeight = FontWeight.Medium), color = c.textSecondary)
    }
}

/** 状态小胶囊：完成是绿、没整理完和进行中是橙、其余是灰 */
@Composable private fun StatusChip(text: String, tone: Color) {
    Text(text, style = RsType.caption.copy(fontWeight = FontWeight.Medium), color = tone, maxLines = 1,
        modifier = Modifier.clip(CircleShape).background(tone.copy(alpha = 0.14f)).padding(horizontal = 9.dp, vertical = 4.dp))
}

@Composable internal fun TopicRecordRow(t: Topic, progress: TopicProgress = TopicProgress(), busy: Boolean = false,
    open: () -> Unit, retry: () -> Unit = {}, retryLabel: String = "继续整理", managing: Boolean = false, delete: () -> Unit = {}) {
    val c = Rs.colors
    val start = t.spans.minOfOrNull { it.startAt } ?: t.plannedStartAt ?: t.createdAt
    val minutes = t.spans.sumOf { ((it.endAt ?: System.currentTimeMillis()) - it.startAt).coerceAtLeast(0) } / 60000
    val duration = if (minutes >= 60) "${minutes / 60} 小时" + (if (minutes % 60 > 0) " ${minutes % 60} 分" else "") else "$minutes 分钟"
    val live = t.status == TopicStatus.Running || t.status == TopicStatus.Paused
    // 灰色状态不用半透明的文字色做底（夜间文字色本身半透明）
    val gray = if (c.isDark) Color(0xFF9A9AA0) else Color(0xFF7D7770)
    val (label, tone) = when (t.status) {
        TopicStatus.Done -> "已完成" to c.good
        TopicStatus.Failed -> (if (progress.notes.isNotEmpty()) "整理了一部分" else "没整理完") to c.accent
        TopicStatus.Summarizing -> "整理中" to c.accent
        TopicStatus.Ended -> "等待整理" to c.accent
        TopicStatus.Running -> "进行中" to c.accent
        TopicStatus.Paused -> "已暂停" to gray
        TopicStatus.Scheduled -> "已预约" to gray
        TopicStatus.Cancelled -> "已取消" to gray
    }
    var confirm by remember(t.id, managing) { mutableStateOf(false) }
    LaunchedEffect(confirm) { if (confirm) { kotlinx.coroutines.delay(4000); confirm = false } }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(c.fill).border(0.5.dp, c.hairline, RoundedCornerShape(18.dp))) {
        Row(Modifier.fillMaxWidth().springClick(open).padding(start = 14.dp, end = 12.dp, top = 14.dp, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            app.resound.ui.skills.SkillIcon(kindIcon(t.kind), 40.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(topicLabel(t), style = RsType.headline, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(3.dp))
                Text(listOfNotNull(formatClock(start), duration.takeIf { minutes > 0 }, t.kind.title.takeIf { !t.name.isNullOrBlank() }).joinToString(" · "),
                    style = RsType.subhead.copy(fontFeatureSettings = "tnum"), color = c.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(8.dp))
            if (managing && !live) {
                val bg by animateColorAsState(if (confirm) c.critical else c.critical.copy(alpha = 0.12f), label = "topicDelete")
                Text(if (confirm) "确定删除？" else "删除", style = RsType.subhead.copy(fontWeight = FontWeight.SemiBold), color = if (confirm) Color.White else c.critical,
                    modifier = Modifier.clip(CircleShape).background(bg).springClick { if (confirm) delete() else confirm = true }.padding(horizontal = 14.dp, vertical = 7.dp))
            } else {
                StatusChip(label, tone)
                Text("›", style = RsType.headline.copy(fontWeight = FontWeight.Normal), color = c.textTertiary, modifier = Modifier.padding(start = 6.dp))
            }
        }
        if (!managing && TopicProcessingRules.pending(t) && (progress.total > 0 || t.status == TopicStatus.Failed)) {
            Box(Modifier.padding(horizontal = 14.dp).fillMaxWidth().height(0.5.dp).background(c.separator))
            Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 12.dp, top = 10.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(if (progress.total > 0) "${progress.phase} ${progress.completed}/${progress.total}" else "原话都在，可以接着整理", style = RsType.caption.copy(fontFeatureSettings = "tnum"), color = c.textSecondary)
                    if (progress.total > 0) {
                        Spacer(Modifier.height(6.dp))
                        Box(Modifier.fillMaxWidth().height(4.dp).clip(CircleShape).background(c.track)) {
                            Box(Modifier.fillMaxWidth((progress.completed.toFloat() / progress.total).coerceIn(0f, 1f)).fillMaxHeight().clip(CircleShape).background(c.accent))
                        }
                    }
                }
                if (t.status == TopicStatus.Failed) {
                    Spacer(Modifier.width(14.dp))
                    Text(if (busy) "正在提交…" else retryLabel, style = RsType.subhead.copy(fontWeight = FontWeight.SemiBold), color = if (busy) c.textTertiary else c.accent,
                        modifier = Modifier.clip(CircleShape).background(c.accentSoft).springClick { if (!busy) retry() }.padding(horizontal = 14.dp, vertical = 7.dp))
                }
            }
        }
    }
}

@Composable internal fun TopicCompletedContent(card: app.resound.skills.SkillCard) {
    app.resound.ui.ReportCopyActions(blocks = { app.resound.ui.skillCardCopyBlocks(card) }) { app.resound.ui.skillCardCopyText(card) }
    SelectionContainer {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (app.resound.ui.showReportSummary(card)) Text(card.summary, style = RsType.body, color = Rs.colors.textSecondary)
            app.resound.ui.skills.SkillCardBody(card)
        }
    }
}

@Composable internal fun TopicProgressContent(progress: TopicProgress, error: String?, busy: Boolean, retry: () -> Unit, summarizeNow: (() -> Unit)? = null) {
    val c = Rs.colors
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.fill).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // 卡 217：按 10 分钟时间块整理，说清楚整理到哪、在等什么
        val waiting = listOfNotNull(
            progress.waitingTranscribe.takeIf { it > 0 }?.let { "$it 段转写" },
            progress.waitingVoiceprint.takeIf { it > 0 }?.let { "$it 段认人" },
            "网络".takeIf { progress.waitingNetwork })
        Text(when {
            error != null -> if (progress.notes.isNotEmpty() || progress.blocksDone > 0) "部分内容已整理" else "整理暂未完成"
            progress.blocksTotal > 0 && progress.blocksDone >= progress.blocksTotal && waiting.isEmpty() -> "正在汇总纪要"
            progress.phase.isNotBlank() -> progress.phase
            waiting.isNotEmpty() -> "等原话处理完成"
            else -> "正在整理"
        }, style = RsType.headline, color = c.text)
        if (progress.blocksTotal > 0) {
            Text("已整理 ${progress.blocksDone} / ${progress.blocksTotal} 块（每块 10 分钟）" +
                (progress.blocksFailed.takeIf { it > 0 }?.let { " · $it 块没整理成" } ?: ""), style = RsType.subhead, color = c.textSecondary)
            ProgressBar(progress.blocksDone, progress.blocksTotal)
        } else if (progress.total > 0) {
            Text("已完成 ${progress.completed} / ${progress.total} 段", style = RsType.subhead, color = c.textSecondary)
            ProgressBar(progress.completed, progress.total)
        }
        if (waiting.isNotEmpty() && error == null) Text("还在等：" + waiting.joinToString(" · "), style = RsType.subhead, color = c.textSecondary)
        val stalled = progress.stalledReason
        Text(error ?: stalled ?: "已完成的内容会保留，可以先离开这里。", style = RsType.caption,
            color = if (error != null || stalled != null) c.warning else c.textSecondary)
        if (stalled != null && summarizeNow != null) ActionButton("先整理（剩下的不等了）", ActionKind.Primary, enabled = !busy, onClick = summarizeNow)
        if (error != null || progress.blocksFailed > 0) ActionButton(if (progress.blocksFailed > 0) "补上没整理成的 ${progress.blocksFailed} 块" else "继续整理",
            if (stalled != null) ActionKind.Secondary else ActionKind.Primary, enabled = !busy, onClick = retry)
    }
    TopicBlockNotes(progress.blockNotes, collapsedTitle = "已整理的分段要点")
    val draft = progress.draft
    if (draft != null && error != null) {
        var draftOpen by remember { mutableStateOf(false) }
        Text(if (draftOpen) "收起待确认草稿 ⌃" else "查看待确认草稿 ⌄", style = RsType.subhead, color = c.textSecondary,
            modifier = Modifier.springClick { draftOpen = !draftOpen }.padding(vertical = 8.dp))
        if (draftOpen) {
            Text("这份草稿尚未通过校验，不会自动创建待办。", style = RsType.caption, color = c.warning)
            SelectionContainer { Text(draft, style = RsType.body, color = c.textSecondary) }
        }
    }
    if (progress.notes.isNotEmpty()) {
        Text("已完成的分段内容", style = RsType.subhead.copy(fontWeight = FontWeight.SemiBold), color = c.text, modifier = Modifier.padding(top = 12.dp))
        Text("以下为过程结果，尚未形成完整会议纪要。", style = RsType.caption, color = c.textTertiary)
        SelectionContainer { Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            progress.readableNotes.forEachIndexed { index, text ->
                Text("${index + 1}. $text", style = RsType.body, color = c.textSecondary)
            }
        } }
    }
}

@Composable private fun ProgressBar(done: Int, total: Int) {
    val c = Rs.colors
    Box(Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)).background(c.separator)) {
        Box(Modifier.fillMaxWidth((done.toFloat() / total.coerceAtLeast(1)).coerceIn(0f, 1f)).height(3.dp).background(c.accent))
    }
}

/** 卡 217：每块的时间、状态、要点；默认收起，开会中也能展开看前面谈了什么 */
@Composable internal fun TopicBlockNotes(notes: List<app.resound.topic.core.TopicBlockNote>, collapsedTitle: String) {
    val c = Rs.colors
    val shown = notes.filter { it.state != app.resound.topic.core.TopicBlockState.Waiting || it.reason != null }
    if (shown.isEmpty()) return
    var open by remember { mutableStateOf(false) }
    Text(if (open) "收起$collapsedTitle ⌃" else "$collapsedTitle（${shown.count { it.state == app.resound.topic.core.TopicBlockState.Done }} 块） ⌄",
        style = RsType.subhead, color = c.textSecondary, modifier = Modifier.springClick { open = !open }.padding(vertical = 8.dp))
    if (!open) return
    SelectionContainer { Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        shown.forEach { n ->
            val from = n.ranges.firstOrNull()?.startAt; val to = n.ranges.lastOrNull()?.endAt
            val time = if (from != null && to != null) "${app.resound.ui.verify.formatClock(from)}–${app.resound.ui.verify.formatClock(to)}" else "第 ${n.index + 1} 块"
            Column {
                Text(time + when (n.state) {
                    app.resound.topic.core.TopicBlockState.Done -> ""
                    app.resound.topic.core.TopicBlockState.Failed -> " · 没整理成"
                    app.resound.topic.core.TopicBlockState.Skipped -> " · 已跳过"
                    app.resound.topic.core.TopicBlockState.Waiting -> " · 等待中"
                }, style = RsType.caption.copy(fontWeight = FontWeight.SemiBold),
                    color = if (n.state == app.resound.topic.core.TopicBlockState.Done) c.textTertiary else c.warning)
                (n.text.takeIf { it.isNotBlank() } ?: n.reason)?.let { Text(it, style = RsType.body, color = c.textSecondary) }
            }
        }
    } }
}
