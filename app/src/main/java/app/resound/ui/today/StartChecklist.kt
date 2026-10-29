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
import app.resound.transcribe.AndroidTranscribeSettings
import app.resound.transcribe.TranscribeSource
import app.resound.transcribe.local.LocalBackend
import app.resound.transcribe.local.LocalModels
import androidx.compose.ui.graphics.Color

private class Step(
    val title: String, val hint: String, val done: Boolean, val page: SettingsPage,
    /** 正在下载等进度说明 */
    val progress: String? = null,
    /** 直接在清单里点的按钮（如一键准备），没有则只跳设置页 */
    val actions: (@Composable RowScope.() -> Unit)? = null,
)

/**
 * 首次使用的上手清单：开始聆听、转写方式、区分说话人、整理模型、后台不被关掉。
 * 本机转写可“一键准备”：下载 CPU 转写模型（适配的芯片另加 NPU 版），同时下载说话人模型并打开分人开关。
 * 全部完成或点“不再显示”后不再出现。
 */
@Composable
fun StartChecklist(capture: CaptureController, hasLlmKey: Boolean, topGap: androidx.compose.ui.unit.Dp = 0.dp) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("ui", Context.MODE_PRIVATE) }
    var dismissed by remember { mutableStateOf(prefs.getBoolean(DISMISSED, false)) }
    if (dismissed) return
    val status by capture.status.collectAsState()
    val settings = if (AppGraph.isTranscribeReady) AppGraph.transcribeSettings as? AndroidTranscribeSettings else null
    val transcribeReady = settings?.ready?.collectAsState()?.value ?: false
    val local = settings?.source?.collectAsState()?.value == TranscribeSource.Local
    val backend = settings?.backend?.collectAsState()?.value ?: LocalBackend.Cpu
    // 下载进度写在 local-asr 里，每次变化 modelRevision 都会加一；跟着它重算，不用定时轮询
    val revision = settings?.modelRevision?.collectAsState()?.value ?: 0
    val asrPrefs = remember { context.getSharedPreferences("local-asr", Context.MODE_PRIVATE) }
    val speakersDownloaded = remember(revision) { LocalModels.ready(context, LocalModels.speakers) }
    val speakersOn = remember(revision) { asrPrefs.getBoolean("speakers-enabled", true) }
    // 下载任务的真实状态；预览/截图里没有后台任务系统，当作没在下
    fun work(name: String) = if (settings == null) kotlinx.coroutines.flow.flowOf(null) else LocalModels.downloadActive(context, name)
    val asrWork by remember { work(LocalModels.workName(LocalBackend.Npu)) }.collectAsState(null)
    val asrCpuWork by remember { work(LocalModels.workName(LocalBackend.Cpu)) }.collectAsState(null)
    val speakerWork by remember { work(LocalModels.SPEAKERS_WORK) }.collectAsState(null)
    val asrDownloading = asrWork != null || asrCpuWork != null
    // 后台设置项每次回到页面都重查（用户可能刚从系统设置回来）
    // 一出现就先查一次，不等页面恢复事件：避免打开 App 的第一帧把这一项当成没做完
    var setupDone by remember { mutableStateOf(AppGraph.isReady && runCatching { AppGraph.setup.items().all { it.done == true } }.getOrDefault(false)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        setupDone = AppGraph.isReady && AppGraph.setup.items().all { it.done == true }
    }
    val c = Rs.colors
    val prepare = {
        settings?.let { s ->
            // 适配过 NPU 的芯片两个都下：NPU 跑不起来时自动退回 CPU，不至于没模型可用
            val chosen = if (LocalModels.npuSupported()) LocalBackend.Npu else LocalBackend.Cpu
            s.setBackend(chosen); s.setSource(TranscribeSource.Local); s.download(chosen)
            if (chosen == LocalBackend.Npu) s.download(LocalBackend.Cpu)
        }
        asrPrefs.edit().putBoolean("speakers-enabled", true).apply()
        LocalModels.enqueueSpeakers(context)
    }
    val listening = status.state != CaptureState.Stopped && status.state != CaptureState.NoPermission
    val steps = listOf(
        Step("开始聆听", "允许麦克风和通知，有人说话才会录", listening, SettingsPage.Capture),
        Step("选择转写方式",
            if (asrDownloading) "正在准备本机转写" else "本机转写免费、录音不出手机（约 ${if (LocalModels.npuSupported()) 330 else 170} MB，建议连 WiFi）；或用豆包云端",
            transcribeReady, SettingsPage.Transcript,
            progress = if (local && (asrDownloading || !transcribeReady)) downloadLine(settings, backend, asrPrefs) else null,
            actions = if (transcribeReady || asrDownloading) null else ({
                SmallAction("一键准备本机转写", filled = true, onClick = prepare)
                SmallAction("用豆包云端", filled = false) { ShellNav.openSettings(SettingsPage.Transcript) }
            })),
        // 云端转写自己分人，这一步只对本机转写有意义
        Step("区分说话人",
            when {
                !local -> "用云端转写时由云端区分，不用设置"
                !speakersDownloaded -> "本机转写时分出不同的人，模型约 7 MB"
                else -> "模型已下载，但开关没打开"
            },
            !local || (speakersDownloaded && speakersOn), SettingsPage.Voiceprint,
            progress = if (local && !speakersDownloaded && speakerWork != null) "说话人模型：" + (asrPrefs.getString("download-Speakers", null) ?: "排队中") else null,
            actions = when {
                !local || speakerWork != null -> null
                !speakersDownloaded -> ({ SmallAction("下载", filled = true) { asrPrefs.edit().putBoolean("speakers-enabled", true).apply(); LocalModels.enqueueSpeakers(context) } })
                !speakersOn -> ({ SmallAction("打开", filled = true) { asrPrefs.edit().putBoolean("speakers-enabled", true).apply() } })
                else -> null
            }),
        Step("设置整理模型", "填一个大模型密钥，把原话整理成事和待办", hasLlmKey, SettingsPage.Model),
        Step("后台不被关掉", "按清单关掉电池优化等，保证全天收音", setupDone, SettingsPage.Capture),
    )
    val doneCount = steps.count { it.done }
    // 全部完成过一次就永久记住：以后打开 App 不再闪出清单（启动瞬间各项状态还没读到会被当成没完成）
    if (doneCount == steps.size) {
        LaunchedEffect(Unit) { prefs.edit().putBoolean(DISMISSED, true).apply(); dismissed = true }
        return
    }
    // 和上面的标题放在同一格里：清单不显示时不留空，显示时自己留出间距
    Spacer(Modifier.height(topGap))
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
                verticalAlignment = Alignment.Top) {
                Box(Modifier.padding(top = 1.dp).size(24.dp).clip(CircleShape).background(when {
                    step.done -> c.good.copy(alpha = 0.14f)
                    i == next -> c.accent
                    else -> c.accentSoft
                }), contentAlignment = Alignment.Center) {
                    Text(if (step.done) "✓" else "${i + 1}", style = RsType.caption.copy(fontWeight = FontWeight.SemiBold),
                        color = when { step.done -> c.good; i == next -> Color.White; else -> c.accent })
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(step.title, style = RsType.body.copy(fontWeight = if (i == next) FontWeight.SemiBold else FontWeight.Normal),
                        color = if (step.done) c.textTertiary else c.text)
                    if (!step.done) Text(step.hint, style = RsType.caption, color = c.textSecondary)
                    step.progress?.let { Text(it, style = RsType.caption, color = c.accent) }
                    step.actions?.let { actions ->
                        Spacer(Modifier.height(8.dp))
                        // 屏幕窄或字大时按钮自动换行
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { actions() }
                    }
                }
                if (!step.done) Text("›", style = RsType.headline.copy(fontWeight = FontWeight.Normal), color = c.textTertiary)
            }
        }
    }
}

/** “转写模型：下载 34% · 56/162 MB”；还没开始时显示排队 */
private fun downloadLine(settings: AndroidTranscribeSettings?, backend: LocalBackend, prefs: android.content.SharedPreferences): String? {
    settings ?: return null
    if (settings.modelReady(backend)) return null
    return "转写模型：" + (prefs.getString("download-${backend.name}", null) ?: "排队中")
}

@Composable
private fun SmallAction(text: String, filled: Boolean, onClick: () -> Unit) {
    val c = Rs.colors
    Text(text, style = RsType.subhead.copy(fontWeight = FontWeight.Medium), color = if (filled) Color.White else c.accent, maxLines = 1,
        modifier = Modifier.springClick(onClick).clip(CircleShape).background(if (filled) c.accent else c.accentSoft).padding(horizontal = 14.dp, vertical = 6.dp))
}

private const val DISMISSED = "startChecklistDismissed"
