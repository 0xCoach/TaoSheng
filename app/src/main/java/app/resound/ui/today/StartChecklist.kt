package app.resound.ui.today

import android.content.Context
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import app.resound.AppGraph
import app.resound.capture.CaptureController
import app.resound.capture.CaptureState
import app.resound.ui.ShellNav
import app.resound.ui.settings.SettingsPage
import app.resound.ui.theme.Rs
import app.resound.ui.theme.RsType
import app.resound.ui.verify.RsCard
import app.resound.ui.verify.springClick
import app.resound.ui.voiceprint.LocalVoiceprint

private data class Step(val title: String, val hint: String, val done: Boolean, val page: SettingsPage)

/**
 * 首次使用的上手清单：几步都已有现成设置页，这里只列状态、点一下跳过去。
 * 全部完成或点“不再显示”后不再出现。
 */
@Composable
fun StartChecklist(capture: CaptureController, hasLlmKey: Boolean) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("ui", Context.MODE_PRIVATE) }
    var dismissed by remember { mutableStateOf(prefs.getBoolean(DISMISSED, false)) }
    if (dismissed) return
    val status by capture.status.collectAsState()
    val transcribeReady = if (AppGraph.isTranscribeReady) AppGraph.transcribeSettings.ready.collectAsState().value else false
    val voiceprintReady = LocalVoiceprint.current?.state?.collectAsState()?.value?.ready ?: false
    // 后台设置项每次回到页面都重查（用户可能刚从系统设置回来）
    var setupDone by remember { mutableStateOf(false) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        setupDone = AppGraph.isReady && AppGraph.setup.items().all { it.done == true }
    }
    val listening = status.state != CaptureState.Stopped && status.state != CaptureState.NoPermission
    val steps = listOf(
        Step("开始聆听", "允许麦克风和通知，有人说话才会录", listening, SettingsPage.Capture),
        Step("选择转写方式", "填豆包密钥，或下载本机转写模型", transcribeReady, SettingsPage.Transcript),
        Step("设置整理模型", "填一个大模型密钥，把原话整理成事和待办", hasLlmKey, SettingsPage.Model),
        Step("后台不被关掉", "按清单关掉电池优化等，保证全天收音", setupDone, SettingsPage.Capture),
        Step("录入你的声音", "可选：认出哪些话是你说的", voiceprintReady, SettingsPage.Voiceprint),
    )
    val doneCount = steps.count { it.done }
    if (doneCount == steps.size) return
    val c = Rs.colors
    RsCard(padding = PaddingValues(start = 18.dp, end = 18.dp, top = 16.dp, bottom = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("上手清单", style = RsType.headline, color = c.text)
            Spacer(Modifier.width(8.dp))
            Text("$doneCount / ${steps.size}", style = RsType.caption, color = c.accent)
            Spacer(Modifier.weight(1f))
            Text("不再显示", style = RsType.caption, color = c.textTertiary,
                modifier = Modifier.springClick { prefs.edit().putBoolean(DISMISSED, true).apply(); dismissed = true }.padding(4.dp))
        }
        Spacer(Modifier.height(4.dp))
        // 进度条：完成几步亮几格
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            steps.forEach { step ->
                val color by animateColorAsState(if (step.done) c.accent else c.separator, label = "stepBar")
                Box(Modifier.weight(1f).height(4.dp).clip(CircleShape).background(color))
            }
        }
        // 第一个没做的步骤高亮，做完的变淡
        val next = steps.indexOfFirst { !it.done }
        steps.forEachIndexed { i, step ->
            Row(Modifier.fillMaxWidth().springClick { ShellNav.openSettings(step.page) }.padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(24.dp).clip(CircleShape).background(when {
                    step.done -> c.good.copy(alpha = 0.14f)
                    i == next -> c.accent
                    else -> c.accentSoft
                }), contentAlignment = Alignment.Center) {
                    Text(if (step.done) "✓" else "${i + 1}", style = RsType.caption.copy(fontWeight = FontWeight.SemiBold),
                        color = when { step.done -> c.good; i == next -> androidx.compose.ui.graphics.Color.White; else -> c.accent })
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(step.title, style = RsType.body.copy(fontWeight = if (i == next) FontWeight.SemiBold else FontWeight.Normal),
                        color = if (step.done) c.textTertiary else c.text)
                    if (!step.done) Text(step.hint, style = RsType.caption, color = c.textSecondary)
                }
                if (!step.done) Text("›", style = RsType.headline.copy(fontWeight = FontWeight.Normal), color = c.textTertiary)
            }
        }
    }
}

private const val DISMISSED = "startChecklistDismissed"
