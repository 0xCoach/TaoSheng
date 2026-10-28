package app.resound.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.resound.capture.CaptureController
import app.resound.capture.CaptureState
import app.resound.digest.DigestSettings
import app.resound.transcribe.TranscribeSettings
import app.resound.ui.theme.Rs
import app.resound.ui.theme.RsType
import app.resound.ui.theme.ThemeMode
import app.resound.ui.verify.RsCard
import app.resound.ui.verify.Section
import app.resound.ui.verify.ThemeModePill
import app.resound.ui.verify.springClick
import app.resound.ui.voiceprint.LocalVoiceprint

/** 设置里的二级页面 */
/** 二级页面里再点进一层时（如某个技能），顶栏换成这一层的标题和返回 */
data class InnerPage(val title: String, val backLabel: String, val onBack: () -> Unit)

val LocalSettingsInner = androidx.compose.runtime.staticCompositionLocalOf<(InnerPage?) -> Unit> { {} }

enum class SettingsPage(val title: String) { Skills("整理技能"), Capture("录音与后台"), Transcript("原话与转写"), Model("整理模型"), Voiceprint("声纹与人物"), Review("每日回顾"), Push("推送通道"), Prompts("整理指令"), Hotwords("热词识别"), Backup("数据备份"), Update("版本更新"), About("关于涛声") }

/**
 * 设置：iOS 设置页式分组圆角列表。点一行进入二级页面，左上角返回或系统返回键回到列表。
 * 二级页面的内容由调用方提供（复用原有的“录音”“原话”页等）。
 */
@Composable
fun SettingsScreen(
    capture: CaptureController,
    transcribeSettings: TranscribeSettings,
    digestSettings: DigestSettings,
    themeMode: ThemeMode,
    onThemeMode: (ThemeMode) -> Unit,
    bottomPadding: Dp,
    page: @Composable (SettingsPage, Dp) -> Unit,
) {
    var open by rememberSaveable { mutableStateOf<SettingsPage?>(null) }
    var inner by remember { mutableStateOf<InnerPage?>(null) }
    BackHandler(open != null) { open = null }
    val requested by app.resound.ui.ShellNav.settingsPage.collectAsState()
    LaunchedEffect(requested) { requested?.let { open = it; inner = null; app.resound.ui.ShellNav.settingsPage.value = null } }
    AnimatedContent(
        open,
        transitionSpec = {
            if (targetState != null) (slideInHorizontally { it / 3 } + fadeIn()) togetherWith (slideOutHorizontally { -it / 6 } + fadeOut())
            else (slideInHorizontally { -it / 6 } + fadeIn()) togetherWith (slideOutHorizontally { it / 3 } + fadeOut())
        },
        label = "settingsPage",
    ) { current ->
        if (current == null) {
            SettingsList(capture, transcribeSettings, digestSettings, themeMode, onThemeMode, bottomPadding) { open = it }
        } else {
            // 顶部安全区在这里处理一次，二级页面内部不再重复留白
            Column(Modifier.fillMaxSize().background(Rs.colors.background).windowInsetsPadding(WindowInsets.safeDrawing.only(androidx.compose.foundation.layout.WindowInsetsSides.Top))) {
                val layer = inner
                if (layer != null) SubPageBar(layer.title, layer.backLabel, layer.onBack) else SubPageBar(current.title, "设置") { open = null }
                androidx.compose.runtime.CompositionLocalProvider(LocalSettingsInner provides { inner = it }) {
                    Box(Modifier.weight(1f)) { page(current, bottomPadding) }
                }
            }
        }
    }
}

@Composable
private fun SubPageBar(title: String, backLabel: String, onBack: () -> Unit) {
    val c = Rs.colors
    Row(
        Modifier.fillMaxWidth()
            .padding(start = 8.dp, end = 16.dp, top = 6.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("‹ $backLabel", style = RsType.headline, color = c.accent, modifier = Modifier.springClick(onBack).padding(horizontal = 8.dp, vertical = 8.dp))
        Spacer(Modifier.weight(1f))
        Text(title, style = RsType.headline, color = c.text)
        Spacer(Modifier.weight(1f))
        Spacer(Modifier.width(64.dp))
    }
}

@Composable
private fun SettingsList(
    capture: CaptureController,
    transcribeSettings: TranscribeSettings,
    digestSettings: DigestSettings,
    themeMode: ThemeMode,
    onThemeMode: (ThemeMode) -> Unit,
    bottomPadding: Dp,
    onOpen: (SettingsPage) -> Unit,
) {
    val c = Rs.colors
    val status by capture.status.collectAsState()
    val asrKey by transcribeSettings.ready.collectAsState()
    val llmKey by digestSettings.hasKey.collectAsState()
    val preset by digestSettings.presetId.collectAsState()
    val voiceprint = LocalVoiceprint.current
    val vp = voiceprint?.state?.collectAsState()?.value

    LazyColumn(
        Modifier.fillMaxSize().background(c.background).windowInsetsPadding(WindowInsets.safeDrawing),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = bottomPadding + 24.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        item {
            Text("设置", style = RsType.largeTitle, color = c.text, modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp))
        }
        item {
            Section("记录") {
                Group {
                    val inputLabel = if (app.resound.AppGraph.isInputsReady)
                        app.resound.AppGraph.inputs.status.collectAsState().value.using.let { if (it.kind == app.resound.audio.InputKind.Phone) null else it.name } else null
                    Row1("录音与后台", when (status.state) {
                        CaptureState.Listening, CaptureState.Recording -> "正在聆听" + (inputLabel?.let { " · $it" } ?: "")
                        CaptureState.Paused -> "已暂停"
                        CaptureState.MicBusy -> "麦克风被占用"
                        CaptureState.Stopped -> "未开启"
                        CaptureState.NoPermission -> "缺少权限"
                    }, dot = if (status.state == CaptureState.Listening || status.state == CaptureState.Recording) c.good else c.warning) { onOpen(SettingsPage.Capture) }
                    Divider()
                    // 右侧一眼看清：用什么转写、多久转一次
                    val asr = transcribeSettings as? app.resound.transcribe.AndroidTranscribeSettings
                    val asrSource = asr?.source?.collectAsState()?.value?.title ?: "豆包"
                    val asrRealtime = asr?.realtime?.collectAsState()?.value == true
                    val asrInterval = transcribeSettings.intervalMinutes.collectAsState().value
                    Row1("原话与转写", if (asrKey) "$asrSource · ${if (asrRealtime) "实时" else "每 $asrInterval 分钟"}" else "转写未就绪", dot = if (asrKey) c.good else c.warning) { onOpen(SettingsPage.Transcript) }
                }
            }
        }
        item {
            Section("智能") {
                Group {
                    // 先模型后技能：技能都靠整理模型跑。右侧写模型和整理间隔，整理时机也在这一页里设
                    val digestInterval = (digestSettings as? app.resound.digest.AndroidDigestSettings)?.intervalMinutes?.collectAsState()?.value
                    val model = digestSettings.presets.firstOrNull { it.id == preset }?.name ?: ""
                    Row1("整理模型", if (llmKey) model + (digestInterval?.let { " · 每 $it 分钟" } ?: "") else "未填密钥", dot = if (llmKey) c.good else c.warning) { onOpen(SettingsPage.Model) }
                    Divider()
                    val skillList = app.resound.ui.skills.skillRepo().skills.collectAsState().value
                    Row1("整理技能", "开了 ${skillList.count { it.enabled && it.origin != app.resound.skills.SkillOrigin.Core }} 个", dot = null) { onOpen(SettingsPage.Skills) }
                    if (app.resound.AppGraph.isHotwordsReady) {
                        val words = app.resound.AppGraph.hotwords.words.collectAsState().value
                        Divider()
                        Row1("热词识别", if (words.isEmpty()) "未添加" else "${words.size} 个", dot = null) { onOpen(SettingsPage.Hotwords) }
                    }
                    if (app.resound.AppGraph.isPushReady) {
                        val channels = app.resound.AppGraph.push.channels.collectAsState(initial = emptyList()).value
                        Divider()
                        Row1("推送通道", channels.count { it.enabled }.let { if (it == 0) "只发手机通知" else "$it 个通道" }, dot = null) { onOpen(SettingsPage.Push) }
                    }
                    if (vp != null) {
                        Divider()
                        Row1("声纹与人物", if (vp.ready) "已能认出你 · ${vp.samples} 段" else "还差 ${maxOf(0, 3 - vp.samples)} 段样本", dot = if (vp.ready) c.good else c.warning) { onOpen(SettingsPage.Voiceprint) }
                    }
                }
            }
        }
        item {
            Section("数据") {
                Group {
                    val b = if (app.resound.AppGraph.isBackupReady) app.resound.AppGraph.backup.state.collectAsState().value else null
                    val lb = if (app.resound.AppGraph.isBackupReady) app.resound.AppGraph.backup.local.collectAsState().value else null
                    val failed = b?.lastError != null || lb?.lastError != null
                    val last = listOfNotNull(b?.lastBackupAt, lb?.lastAt).maxOrNull()
                    Row1("数据备份", when {
                        b == null -> "开发中"
                        last != null -> listOfNotNull(lb?.lastAt?.let { "本机" }, b.lastBackupAt?.let { "坚果云" }).joinToString("、") + "已备份"
                        failed -> "尚无成功备份"
                        b.account == null && lb?.folder == null -> "未开启"
                        else -> "还没备份"
                    }, dot = when { b == null -> null; last != null -> c.good; failed -> c.critical; b.account == null && lb?.folder == null -> null; else -> c.warning }) { onOpen(SettingsPage.Backup) }
                }
            }
        }
        item {
            Section("外观") {
                Group {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("日间 / 夜间", style = RsType.body, color = c.text, modifier = Modifier.weight(1f))
                        Text(
                            when (themeMode) { ThemeMode.System -> "跟随系统"; ThemeMode.Light -> "日间"; ThemeMode.Dark -> "夜间" },
                            style = RsType.subhead, color = c.textTertiary,
                        )
                        Spacer(Modifier.width(10.dp))
                        ThemeModePill(themeMode, onThemeMode)
                    }
                }
            }
        }
        item {
            Section("关于") {
                Group {
                    if (app.resound.AppGraph.isUpdaterReady) {
                        val updater = app.resound.AppGraph.updater
                        val (summary, fresh) = updateSummary(updater.currentVersion, updater.state.collectAsState().value)
                        Row1("版本更新", summary, dot = if (fresh) c.accent else null) { onOpen(SettingsPage.Update) }
                        Divider()
                    }
                    Row1("关于涛声", "", dot = null) { onOpen(SettingsPage.About) }
                }
            }
        }
    }
}

@Composable
private fun Group(content: @Composable () -> Unit) {
    RsCard(padding = PaddingValues(vertical = 2.dp)) { content() }
}

@Composable
private fun Divider() {
    Box(Modifier.padding(start = 18.dp).fillMaxWidth().height(0.5.dp).background(Rs.colors.separator))
}

@Composable
private fun Row1(title: String, value: String, dot: Color?, onClick: () -> Unit) {
    val c = Rs.colors
    Row(
        Modifier.fillMaxWidth().springClick(onClick).padding(horizontal = 18.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 标题保持完整；右侧说明放不下时省略，不把标题挤成两行
        Text(title, style = RsType.body, color = c.text, maxLines = 1, softWrap = false)
        Row(Modifier.weight(1f).padding(start = 12.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            if (dot != null) {
                Box(Modifier.size(7.dp).clip(RoundedCornerShape(50)).background(dot))
                Spacer(Modifier.width(6.dp))
            }
            Text(value, style = RsType.subhead, color = c.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        }
        Spacer(Modifier.width(8.dp))
        Text("›", style = RsType.headline.copy(fontWeight = FontWeight.Normal), color = c.textTertiary)
    }
}
