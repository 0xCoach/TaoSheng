package app.resound.ui.importer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.resound.AppGraph
import app.resound.importer.CallDirection
import app.resound.importer.FolderRecording
import app.resound.importer.ListedState
import app.resound.ui.theme.Rs
import app.resound.ui.theme.RsType
import app.resound.ui.verify.formatClock
import app.resound.ui.verify.springClick

/** 卡 199：时间线上一条同步文件夹里列出、还没转写的录音；点“转写”才排队 */
fun FolderRecording.title(): String =
    call?.display?.let { "和 $it 通话" } ?: fileName.substringBeforeLast('.')

@Composable
internal fun ListedLine(r: FolderRecording) {
    val c = Rs.colors
    val isCall = r.call != null
    Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 14.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(formatClock(r.startAt), style = RsType.subhead.copy(fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum"), color = c.textTertiary,
            modifier = Modifier.widthIn(min = 58.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                app.resound.ui.skills.SkillIcon(if (isCall) "chat" else "mic", 20.dp)
                Spacer(Modifier.width(6.dp))
                Text(r.title(), style = RsType.body.copy(fontWeight = FontWeight.SemiBold), color = c.textSecondary, maxLines = 1,
                    overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(3.dp))
            val direction = when (r.call?.direction) { CallDirection.Incoming -> "呼入"; CallDirection.Outgoing -> "呼出"; null -> null }
            val minutes = (r.durationMillis / 60_000).coerceAtLeast(1)
            val number = r.call?.number?.takeIf { r.call.fileName != null }
            val status = when (r.state) { ListedState.Missing -> "原文件不在了"; ListedState.Queued -> "排队转写中"; ListedState.Imported -> "已转写"; else -> "未转写" }
            Text(listOfNotNull(direction, "$minutes 分钟", number, status).joinToString(" · "), style = RsType.caption,
                color = if (r.state == ListedState.Missing) c.warning else c.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (r.state == ListedState.Listed) {
            Spacer(Modifier.width(8.dp))
            Text("转写", style = RsType.subhead.copy(fontWeight = FontWeight.SemiBold), color = c.accent,
                modifier = Modifier.clip(CircleShape).background(c.accentSoft).springClick { AppGraph.folderRecordings.transcribe(listOf(r.id)) }
                    .padding(horizontal = 14.dp, vertical = 6.dp))
        }
    }
}
