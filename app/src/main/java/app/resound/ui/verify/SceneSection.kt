package app.resound.ui.verify

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.resound.capture.AndroidScene
import app.resound.capture.SceneDecision
import app.resound.ui.skills.Toggle
import app.resound.ui.theme.Rs
import app.resound.ui.theme.RsType
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 录音与后台页的“场景感知”：总开关、当前场景、隐私暂停、记灵感、最近场景记录。 */
@Composable
internal fun SceneSection(scene: AndroidScene) {
    val c = Rs.colors
    val enabled by scene.enabled.collectAsStateWithLifecycle()
    val privacy by scene.privacyPaused.collectAsStateWithLifecycle()
    val sessions by scene.sessions(8).collectAsStateWithLifecycle(emptyList())
    var decision by remember { mutableStateOf<SceneDecision?>(null) }
    var markFeedback by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { while (true) { decision = scene.decide(); delay(15_000) } }

    Section("场景感知", trailing = {
        Text(if (!enabled) "已关闭" else decision?.scene?.label ?: "日常",
            style = RsType.caption, color = if (decision?.pausing == true) c.accent else c.textTertiary)
    }) {
        RsCard(Modifier, padding = PaddingValues(horizontal = 18.dp, vertical = 6.dp)) {
            SceneRow("场景感知", "按场景调整录音策略；停录时段、会议专题等用户规则始终生效", enabled) { scene.setEnabled(it) }
            Divider(c)
            Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("当前场景", style = RsType.body.copy(fontWeight = FontWeight.Medium), color = c.text)
                    Spacer(Modifier.height(2.dp))
                    Text(decision?.let { it.scene.label + (it.reason?.let { r -> " · $r" } ?: "") } ?: "日常",
                        style = RsType.caption, color = c.textSecondary)
                }
            }
            Divider(c)
            SceneRow("隐私暂停", "停止采集麦克风，不会自动恢复；再次点开亲手恢复", privacy) { scene.setPrivacy(it) }
            Divider(c)
            Row(Modifier.fillMaxWidth().springClick {
                scope.launch { markFeedback = if (scene.markIdea() != null) "已记一条灵感" else "灵感箱未就绪" }
            }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("记灵感", style = RsType.body.copy(fontWeight = FontWeight.Medium), color = c.accent)
                    Spacer(Modifier.height(2.dp))
                    Text(markFeedback ?: "标记此刻并关联当前录音片段，可稍后在灵感箱补文字",
                        style = RsType.caption, color = c.textSecondary)
                }
            }
            if (sessions.isNotEmpty()) {
                Divider(c)
                Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
                    Text("最近场景记录", style = RsType.caption.copy(fontWeight = FontWeight.Medium), color = c.textTertiary)
                    sessions.take(5).forEach { s ->
                        Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(sessionText(s.kind), style = RsType.caption, color = c.textSecondary)
                            Spacer(Modifier.width(8.dp))
                            Text(sessionTime(s.startedAt, s.endedAt), style = RsType.caption, color = c.textTertiary)
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text("麦克风占用与会话归因见「运行记录与诊断」；隐私暂停期间闹钟、蓝牙重连都不会恢复录音。",
            style = RsType.caption, color = c.textTertiary, modifier = Modifier.padding(horizontal = 4.dp))
    }
}

@Composable
private fun SceneRow(title: String, desc: String, on: Boolean, onToggle: (Boolean) -> Unit) {
    val c = Rs.colors
    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = RsType.body.copy(fontWeight = FontWeight.Medium), color = c.text)
            Spacer(Modifier.height(2.dp))
            Text(desc, style = RsType.caption, color = c.textSecondary)
        }
        Spacer(Modifier.width(12.dp))
        Toggle(on, onToggle)
    }
}

@Composable
private fun Divider(c: app.resound.ui.theme.RsColors) =
    Box(Modifier.fillMaxWidth().height(0.5.dp).background(c.separator))

private fun sessionText(kind: String): String =
    runCatching { app.resound.capture.RecordingScene.valueOf(kind).label }.getOrDefault(kind)

private fun sessionTime(start: Long, end: Long?): String {
    val f = DateTimeFormatter.ofPattern("MM-dd HH:mm")
    val s = Instant.ofEpochMilli(start).atZone(ZoneId.systemDefault()).format(f)
    val e = end?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm")) } ?: "现在"
    return "$s – $e"
}
