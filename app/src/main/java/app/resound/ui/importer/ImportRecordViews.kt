package app.resound.ui.importer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.resound.AppGraph
import app.resound.digest.MemoryEvent
import app.resound.importer.ImportKind
import app.resound.importer.ImportRecord
import app.resound.importer.ImportRecordStatus
import app.resound.skills.SkillCard
import app.resound.transcribe.SegmentTranscript
import app.resound.ui.theme.Rs
import app.resound.ui.theme.RsType
import app.resound.ui.verify.ActionButton
import app.resound.ui.verify.ActionKind
import app.resound.ui.verify.RsCard
import app.resound.ui.verify.RsSheet
import app.resound.ui.verify.formatClock
import app.resound.ui.verify.springClick

private fun kindIcon(k: ImportKind) = when (k) { ImportKind.Meeting -> "meeting"; ImportKind.Training -> "bulb"; ImportKind.Call -> "chat"; ImportKind.Normal -> "mic" }

private fun statusText(r: ImportRecord): String? = when (r.status) {
    ImportRecordStatus.Transcribing -> "转写中"
    ImportRecordStatus.Summarizing -> if (r.kind == ImportKind.Normal) "整理中" else "生成纪要中"
    ImportRecordStatus.Failed -> "没完成"
    ImportRecordStatus.Done -> null
}

private fun length(ms: Long): String {
    val m = (ms / 60_000).coerceAtLeast(1)
    return if (m < 60) "$m 分钟" else "${m / 60} 小时" + if (m % 60 > 0) " ${m % 60} 分" else ""
}

/** 时间线上的一行：“09:15 · 导入的会议 · 项目周会（1 小时 12 分）· 5 件事 ›” */
@Composable
internal fun ImportLine(r: ImportRecord, events: Int, onOpen: () -> Unit) {
    val c = Rs.colors
    Row(Modifier.fillMaxWidth().springClick(onOpen).padding(start = 18.dp, end = 18.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(formatClock(r.startAt), style = RsType.subhead.copy(fontWeight = FontWeight.SemiBold), color = c.accent, modifier = Modifier.widthIn(min = 58.dp))
        app.resound.ui.skills.SkillIcon(kindIcon(r.kind), 28.dp)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text("导入的${r.kind.title.substringBefore(" ")}", style = RsType.subhead.copy(fontWeight = FontWeight.SemiBold), color = app.resound.ui.skills.skillInk(kindIcon(r.kind)))
            val st = statusText(r)
            Text(listOfNotNull(r.fileName.substringBeforeLast('.'), length(r.endAt - r.startAt), st ?: if (events > 0) "$events 件事" else null).joinToString(" · "),
                style = RsType.caption, color = if (r.status == ImportRecordStatus.Failed) c.critical else c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text("›", style = RsType.title, color = c.textTertiary)
    }
}

/**
 * 一份导入录音的详情：纪要入口、整理出的事、播放整段（就在面板里展开播放器）、改类型、重新生成。
 * 自己按记录那天加载纪要卡、原话、事件，所以从播放条“回到这次导入”也能在任何页面打开。
 */
@Composable
fun ImportRecordSheet(id: Long, onOpenEvent: (MemoryEvent) -> Unit, onDismiss: () -> Unit) {
    if (!AppGraph.isImportKindsReady || !AppGraph.isMemoryReady || !AppGraph.isTranscribeReady) return
    val c = Rs.colors
    val context = LocalContext.current
    val kinds = AppGraph.importKinds
    val r = remember(id) { kinds.record(id) }.collectAsState(initial = null).value ?: return
    val date = remember(r.startAt) { java.time.Instant.ofEpochMilli(r.startAt).atZone(java.time.ZoneId.systemDefault()).toLocalDate() }
    val skills = remember { app.resound.ui.skills.skillRepo() }
    val cards by remember(date) { skills.cards(date) }.collectAsState(initial = emptyList())
    val segments by remember(date) { AppGraph.transcripts.day(date) }.collectAsState(initial = emptyList())
    val events = remember(date) { AppGraph.memory.events(date) }.collectAsState(initial = null).value.orEmpty()
    var openedCard by remember { mutableStateOf<SkillCard?>(null) }
    val onOpenCard: (SkillCard) -> Unit = { openedCard = it }
    openedCard?.let { card -> app.resound.ui.skills.SkillCardSheet(card, skills) { openedCard = null } }
    val card = cards.firstOrNull { it.id == r.cardId }
    val mine = events.filter { it.startAt >= r.startAt && it.startAt < r.endAt }.sortedBy { it.startAt }
    val lines = segments.filter { it.segmentId in r.segmentIds }.sortedBy { r.segmentIds.indexOf(it.segmentId) }.flatMap { it.lines }
    var changing by remember { mutableStateOf<ImportKind?>(null) }
    RsSheet(r.fileName.substringBeforeLast('.'), "${r.kind.title} · ${formatClock(r.startAt)} 开始 · ${length(r.endAt - r.startAt)}" +
        (if (r.byChannel) " · 按左右声道分人" else ""), onDismiss = onDismiss) {
        val playingHere = (app.resound.ui.transcript.LinePlayer.source as? app.resound.ui.transcript.PlaySource.Import)?.recordId == r.id
        if (playingHere) app.resound.ui.transcript.InlinePlayer()
        statusText(r)?.let { Text(it + (r.error?.let { e -> "：$e" } ?: ""), style = RsType.subhead, color = if (r.status == ImportRecordStatus.Failed) c.critical else c.textSecondary) }
        // 纪要
        if (r.kind != ImportKind.Normal) RsCard(color = c.background, padding = PaddingValues(16.dp)) {
            if (card != null) Row(Modifier.fillMaxWidth().springClick { onOpenCard(card) }, verticalAlignment = Alignment.CenterVertically) {
                app.resound.ui.skills.SkillIcon(card.icon, 36.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(card.title, style = RsType.body.copy(fontWeight = FontWeight.SemiBold), color = c.text, maxLines = 2)
                    Text(card.summary, style = RsType.caption, color = c.textSecondary, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
                Text("›", style = RsType.title, color = c.textTertiary)
            } else Text(if (r.status == ImportRecordStatus.Done || r.status == ImportRecordStatus.Failed) "还没有纪要，可以点下面“重新生成”" else "转写完会自动生成纪要",
                style = RsType.subhead, color = c.textTertiary)
        }
        // 播放整段
        if (lines.isNotEmpty() && !playingHere) ActionButton("▶ 播放整段", ActionKind.Secondary) {
            app.resound.ui.transcript.LinePlayer.play(context, lines, 0, app.resound.ui.transcript.PlaySource.Import(r.id))
        }
        // 整理出的事
        if (mine.isNotEmpty()) {
            Text("整理出的 ${mine.size} 件事", style = RsType.caption.copy(fontWeight = FontWeight.Medium), color = c.textTertiary)
            RsCard(color = c.background, padding = PaddingValues(vertical = 4.dp)) {
                mine.forEachIndexed { i, e ->
                    if (i > 0) androidx.compose.foundation.layout.Box(Modifier.padding(start = 16.dp).fillMaxWidth().height(0.5.dp).background(c.separator))
                    Row(Modifier.fillMaxWidth().springClick { onOpenEvent(e) }.padding(horizontal = 16.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(formatClock(e.startAt), style = RsType.caption.copy(fontWeight = FontWeight.SemiBold), color = c.accent, modifier = Modifier.widthIn(min = 46.dp))
                        Text(e.title, style = RsType.subhead, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        Text("›", style = RsType.subhead, color = c.textTertiary)
                    }
                }
            }
        }
        // 改类型 / 重新生成
        Text("类型不对？改了会重新生成纪要", style = RsType.caption.copy(fontWeight = FontWeight.Medium), color = c.textTertiary)
        KindChoice(changing ?: r.kind) { changing = if (it == r.kind) null else it }
        val busy = r.status == ImportRecordStatus.Transcribing || r.status == ImportRecordStatus.Summarizing
        changing?.let { k ->
            ActionButton("改成“${k.title}”并重新整理", ActionKind.Primary, enabled = !busy) { kinds.changeKind(r.id, k); changing = null }
        } ?: if (r.kind != ImportKind.Normal) ActionButton("重新生成纪要", ActionKind.Secondary, enabled = !busy) { kinds.regenerate(r.id) } else Unit
    }
}
