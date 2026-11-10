package app.resound.ui.context

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.resound.AppGraph
import app.resound.context.PlaceAt
import app.resound.digest.MemoryEvent
import app.resound.ui.theme.Rs
import app.resound.ui.theme.RsType
import app.resound.ui.verify.springClick
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/*
 * 0.2.21 声音 · 位置联动的界面小件。数据全部在手机本地按时间对齐（AppGraph.situation），不联网。
 */

/** 事件发生时停留最久的地方；主要在路上、没记录或足迹没开时为 null */
@Composable
internal fun rememberPlaceDuring(start: Long, end: Long): PlaceAt? {
    if (!AppGraph.isSituationReady) return null
    val place by produceState<PlaceAt?>(null, start, end) {
        value = runCatching { AppGraph.situation.placeDuring(start, maxOf(end, start + 60_000)) }.getOrNull()
    }
    return place
}

internal fun PlaceAt.label() = if (home) "在家" else "在$name"

/** 事件卡片上的一行：📍 在公司 */
@Composable
fun EventPlaceLine(event: MemoryEvent, modifier: Modifier = Modifier) {
    val place = rememberPlaceDuring(event.startAt, event.endAt) ?: return
    Text("📍 " + place.label(), style = RsType.caption, color = Rs.colors.textTertiary, maxLines = 1, modifier = modifier)
}

private val hm = DateTimeFormatter.ofPattern("H:mm")
private fun clock(at: Long) = Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).format(hm)

/**
 * 足迹停留点下面挂这段时间里的事：最多显示 3 件，多的写“还有 N 件”，点一下打开事件详情。
 * [events] 是当天的事件（调用方按天取一次，避免每个停留点各查一次）。
 */
@Composable
fun StayEvents(start: Long, end: Long, events: List<MemoryEvent>, onOpen: (MemoryEvent) -> Unit) {
    val c = Rs.colors
    val here = remember(events, start, end) {
        // 事件与停留时间重叠就算在这里发生；按开始时间排
        events.filter { it.startAt < end && it.endAt > start }.sortedBy { it.startAt }
    }
    if (here.isEmpty()) return
    var all by remember(start) { mutableStateOf(false) }
    Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        (if (all) here else here.take(3)).forEach { e ->
            Row(Modifier.fillMaxWidth().springClick { onOpen(e) }, verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(5.dp).clip(CircleShape).background(c.accent))
                Spacer(Modifier.width(8.dp))
                Text(clock(e.startAt), style = RsType.caption, color = c.textTertiary)
                Spacer(Modifier.width(8.dp))
                Text(e.title, style = RsType.subhead, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            }
        }
        if (here.size > 3) Text(if (all) "收起" else "还有 ${here.size - 3} 件", style = RsType.caption, color = c.accent,
            modifier = Modifier.padding(start = 13.dp).springClick { all = !all })
    }
}
