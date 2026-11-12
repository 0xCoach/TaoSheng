package app.resound.ui.topic

import androidx.compose.foundation.background
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

internal val TopicWarm = Color(0xFFE89A58)

@Composable internal fun TopicTabs(history: Boolean, failed: Int, live: Boolean, select: (Boolean) -> Unit) {
    val c = Rs.colors
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.track).padding(4.dp)) {
        listOf(if (live) "当前专题" else "开始专题", "专题记录").forEachIndexed { i, label ->
            val on = history == (i == 1)
            Row(Modifier.weight(1f).clip(RoundedCornerShape(9.dp)).background(if (on) c.trackThumb else Color.Transparent)
                .then(if (on && c.isDark) Modifier.border(1.dp, c.textTertiary.copy(alpha = 0.6f), RoundedCornerShape(9.dp)) else Modifier)
                .springClick { select(i == 1) }.padding(vertical = 10.dp), horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically) {
                Text(label, style = RsType.subhead.copy(fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal), color = if (on) c.text else c.textSecondary)
                if (i == 1 && failed > 0) {
                    Spacer(Modifier.width(6.dp))
                    Box(Modifier.size(6.dp).clip(RoundedCornerShape(3.dp)).background(c.warning))
                }
            }
        }
    }
}

@Composable internal fun TopicHistoryContent(records: List<Topic>, query: String, setQuery: (String) -> Unit,
    attention: Boolean, setAttention: (Boolean) -> Unit, shown: Int, more: () -> Unit,
    progress: @Composable (Topic) -> TopicProgress, busyIds: Set<Long>, open: (Long) -> Unit, retry: (Long) -> Unit) {
    val c = Rs.colors
    BasicTextField(query, setQuery, singleLine = true, textStyle = RsType.body.copy(color = c.text), cursorBrush = SolidColor(c.accent),
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.fill).padding(horizontal = 14.dp, vertical = 13.dp),
        decorationBox = { inner -> Box { if (query.isEmpty()) Text("搜索专题名称或类型", style = RsType.body, color = c.textTertiary); inner() } })
    val failed = records.count { it.status == TopicStatus.Failed }
    if (failed > 0 || attention) Row(Modifier.fillMaxWidth().springClick { setAttention(!attention) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(if (attention) "仅显示需要处理的专题" else "$failed 场专题需要处理", style = RsType.subhead, color = c.warning, modifier = Modifier.weight(1f))
        Text(if (attention) "查看全部 ›" else "查看 ›", style = RsType.subhead, color = c.textSecondary)
    }
    val filtered = remember(records, query, attention) { TopicProcessingRules.history(records, query, attention) }
    val zone = ZoneId.systemDefault()
    val groups = filtered.take(shown).groupBy { Instant.ofEpochMilli(it.spans.minOfOrNull { s -> s.startAt } ?: it.plannedStartAt ?: it.createdAt).atZone(zone).toLocalDate() }
    if (filtered.isEmpty()) Column(Modifier.fillMaxWidth().padding(vertical = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(if (records.isEmpty()) "还没有专题记录" else "没有符合条件的专题", style = RsType.headline, color = c.textSecondary)
        Spacer(Modifier.height(8.dp))
        Text(if (records.isEmpty()) "开始一场会议，原话和纪要会留在这里" else "试试其他关键词，或查看全部记录", style = RsType.caption, color = c.textTertiary)
    }
    groups.forEach { (day, list) ->
        Text(when(day) { LocalDate.now() -> "今天"; LocalDate.now().minusDays(1) -> "昨天"; else -> "${day.monthValue}月${day.dayOfMonth}日" },
            style = RsType.caption.copy(fontWeight = FontWeight.Medium), color = c.textTertiary, modifier = Modifier.padding(top = 12.dp, start = 2.dp))
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.fill)) {
            list.forEachIndexed { index, topic -> key(topic.id) {
                TopicRecordRow(topic, progress(topic), topic.id in busyIds, { open(topic.id) }, { retry(topic.id) })
                if (index < list.lastIndex) Box(Modifier.padding(start = 58.dp, end = 14.dp).fillMaxWidth().height(0.5.dp).background(c.separator))
            } }
        }
    }
    if (filtered.size > shown) Text("加载更多专题", style = RsType.subhead, color = c.textSecondary,
        modifier = Modifier.fillMaxWidth().springClick(more).padding(vertical = 12.dp))
}

@Composable internal fun TopicRecordRow(t: Topic, progress: TopicProgress = TopicProgress(), busy: Boolean = false,
    open: () -> Unit, retry: () -> Unit = {}, retryLabel: String = "继续整理") {
    val c = Rs.colors
    val start = t.spans.minOfOrNull { it.startAt } ?: t.plannedStartAt ?: t.createdAt
    val minutes = t.spans.sumOf { ((it.endAt ?: System.currentTimeMillis()) - it.startAt).coerceAtLeast(0) } / 60000
    val duration = if (minutes >= 60) "${minutes / 60}小时${if (minutes % 60 > 0) "${minutes % 60}分" else ""}" else "${minutes}分钟"
    val label = when (t.status) {
        TopicStatus.Done -> "已完成"
        TopicStatus.Failed -> if (progress.notes.isNotEmpty()) "部分完成" else "待重试"
        TopicStatus.Summarizing -> "整理中"
        TopicStatus.Ended -> "等待整理"
        TopicStatus.Running -> "录制中"
        TopicStatus.Paused -> "已暂停"
        TopicStatus.Scheduled -> "已预约"
        TopicStatus.Cancelled -> "已取消"
    }
    Column(Modifier.fillMaxWidth().padding(14.dp)) {
        Row(Modifier.fillMaxWidth().springClick(open), verticalAlignment = Alignment.CenterVertically) {
            app.resound.ui.skills.SkillIcon(when(t.kind) {
                TopicKind.Meeting -> "meeting"; TopicKind.Training -> "bulb"; TopicKind.Call -> "chat"; TopicKind.Script -> "mic"; else -> "timeline"
            }, 32.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(topicLabel(t), style = RsType.subhead.copy(fontWeight = FontWeight.SemiBold), color = c.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(5.dp))
                Text("${formatClock(start)} · $duration", style = RsType.caption, color = c.textTertiary)
            }
            Spacer(Modifier.width(8.dp))
            Text(label, style = RsType.caption, color = if (t.status == TopicStatus.Failed) c.warning else c.textSecondary)
            Text(" ›", style = RsType.headline, color = c.textTertiary)
        }
        if (TopicProcessingRules.pending(t) && (progress.total > 0 || t.status == TopicStatus.Failed)) {
            Row(Modifier.fillMaxWidth().padding(start = 44.dp, top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (progress.total > 0) "${progress.phase} ${progress.completed}/${progress.total}" else "原话已保留", style = RsType.caption,
                    color = c.textSecondary, modifier = Modifier.weight(1f))
                if (t.status == TopicStatus.Failed) Text(if (busy) "正在提交…" else retryLabel, style = RsType.subhead.copy(fontWeight = FontWeight.Medium),
                    color = c.accent, modifier = Modifier.springClick { if (!busy) retry() }.padding(start = 10.dp, top = 5.dp, bottom = 5.dp))
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

@Composable internal fun TopicProgressContent(progress: TopicProgress, error: String?, busy: Boolean, retry: () -> Unit) {
    val c = Rs.colors
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.fill).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(if (error != null) if (progress.notes.isNotEmpty()) "部分内容已整理" else "整理暂未完成" else progress.phase.ifBlank { "等待原话处理完成" },
            style = RsType.headline, color = c.text)
        if (progress.total > 0) {
            Text("已完成 ${progress.completed} / ${progress.total} 段", style = RsType.subhead, color = c.textSecondary)
            Box(Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)).background(c.separator)) {
                Box(Modifier.fillMaxWidth((progress.completed.toFloat() / progress.total).coerceIn(0f, 1f)).height(3.dp).background(TopicWarm))
            }
        }
        Text(error ?: "已完成的内容会保留，可以先离开这里。", style = RsType.caption, color = if (error != null) c.warning else c.textSecondary)
        if (error != null) ActionButton("继续整理", ActionKind.Primary, enabled = !busy, onClick = retry)
    }
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
