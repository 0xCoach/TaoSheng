package app.resound.ui.today

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.resound.digest.MemoryEvent
import app.resound.ui.items.Label
import app.resound.ui.theme.Rs
import app.resound.ui.theme.RsType
import app.resound.ui.verify.ActionButton
import app.resound.ui.verify.ActionKind
import app.resound.ui.verify.RsSheet
import app.resound.ui.verify.SheetInput
import app.resound.ui.verify.springClick

/** 编辑一件事：标题、摘要、要点（逐条改、删、加）；第一次保存时会留下整理的原样，可随时恢复 */
@Composable
internal fun EditEventSheet(event: MemoryEvent, onSave: (String, String, List<String>) -> Unit, onDismiss: () -> Unit) {
    val c = Rs.colors
    var title by remember { mutableStateOf(event.title) }
    var summary by remember { mutableStateOf(event.summary) }
    val points = remember { mutableStateListOf<String>().apply { addAll(event.points) } }
    RsSheet("编辑这件事", "原话不会改；整理时的原样会保留，可以恢复", onDismiss = onDismiss, fixedHeight = true) {
        Label("标题")
        SheetInput(title, "一句话说清这件事") { title = it.take(40) }
        Label("摘要")
        SheetInput(summary, "发生了什么", wrap = true) { summary = it.take(400) }
        Label("要点")
        points.forEachIndexed { i, p ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { SheetInput(p, "一条要点", wrap = true) { points[i] = it.take(120) } }
                Text("✕", style = RsType.headline, color = c.textTertiary,
                    modifier = Modifier.clip(CircleShape).springClick { points.removeAt(i) }.padding(horizontal = 10.dp, vertical = 6.dp))
            }
        }
        if (points.size < 10) Text("＋ 加一条", style = RsType.subhead.copy(fontWeight = FontWeight.Medium), color = c.accent,
            modifier = Modifier.springClick { points.add("") }.padding(vertical = 4.dp))
        ActionButton("保存", ActionKind.Primary, enabled = title.isNotBlank() && summary.isNotBlank()) {
            onSave(title.trim(), summary.trim(), points.map { it.trim() }.filter { it.isNotEmpty() })
            onDismiss()
        }
    }
}
