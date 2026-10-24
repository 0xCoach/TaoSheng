package app.resound

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.Text
import androidx.compose.foundation.rememberScrollState
import app.resound.ui.settings.ReviewSettingsPage
import java.time.LocalDate
import android.content.Intent
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import app.resound.importer.receiveSharedAudio
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.setValue
import app.resound.capture.CaptureState
import app.resound.ui.AppShell
import app.resound.ui.settings.BackupPage
import app.resound.ui.items.MemoryScreen
import app.resound.ui.items.TodoScreen
import app.resound.ui.ask.AskScreen
import app.resound.ui.preview.FakeAskRepository
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Box
import app.resound.ui.voiceprint.VoiceprintCard
import app.resound.ui.today.ModelCard
import app.resound.ui.settings.SettingsPage
import app.resound.ui.settings.SettingsScreen
import app.resound.ui.common.ComingSoon
import app.resound.ui.voiceprint.LocalVoiceprint
import app.resound.ui.ShellBottomPadding
import app.resound.ui.preview.FakeCaptureController
import app.resound.ui.preview.FakeDigestSettings
import app.resound.ui.preview.FakeMemoryRepository
import app.resound.ui.preview.FakeSetupGuide
import app.resound.ui.preview.FakeStatsRepository
import app.resound.ui.preview.FakeTranscribeSettings
import app.resound.ui.preview.FakeTranscriptRepository
import app.resound.ui.theme.ResoundTheme
import app.resound.ui.theme.ThemeMode
import app.resound.ui.today.TodayScreen
import app.resound.ui.transcript.TranscriptScreen
import app.resound.ui.verify.VerifyScreen

class MainActivity : ComponentActivity() {
    /** 点“今日回顾”通知进来：要打开的那天，和对底栏的切页请求 */
    private var openReview by mutableStateOf<LocalDate?>(null)
    private var tabRequest by mutableStateOf<Pair<Int, Long>?>(null)
    /** 从技能卡通知点进来：打开这张卡 */
    private var openCardId by mutableStateOf<Long?>(null)

    private val _privacyRequest = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    val privacyRequest: kotlinx.coroutines.flow.StateFlow<String?> = _privacyRequest
    fun consumePrivacyRequest() { _privacyRequest.value = null }

    private fun handleIntent(intent: Intent?) {
        lifecycleScope.launch { receiveSharedAudio(intent) }
        if (intent?.getStringExtra("open") == "deleteRecent") {
            _privacyRequest.value = "deleteRecent"
            return
        }
        if (intent?.getStringExtra("open") == "card") {
            openCardId = (intent.data?.getQueryParameter("id") ?: intent.getStringExtra("id"))?.toLongOrNull()
            return
        }
        if (intent?.getStringExtra("open") != "review") return
        openReview = intent.getStringExtra("date")?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: LocalDate.now()
        tabRequest = 2 to System.nanoTime() // 第 3 页“记忆”
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIntent(intent)
        enableEdgeToEdge()
        val prefs = getSharedPreferences("ui", MODE_PRIVATE)
        // 底层未装配时（开发界面阶段）用假数据
        val capture = if (AppGraph.isReady) AppGraph.capture else FakeCaptureController()
        val stats = if (AppGraph.isReady) AppGraph.stats else FakeStatsRepository()
        val setup = if (AppGraph.isReady) AppGraph.setup else FakeSetupGuide()
        val transcripts = if (AppGraph.isTranscribeReady) AppGraph.transcripts else FakeTranscriptRepository()
        val transcribeSettings = if (AppGraph.isTranscribeReady) AppGraph.transcribeSettings else FakeTranscribeSettings()
        val memory = if (AppGraph.isMemoryReady) AppGraph.memory else FakeMemoryRepository()
        val digestSettings = if (AppGraph.isMemoryReady) AppGraph.digestSettings else FakeDigestSettings()
        val ask = if (AppGraph.isAskReady) AppGraph.ask else FakeAskRepository()
        setContent {
            var mode by remember {
                mutableStateOf(runCatching { ThemeMode.valueOf(prefs.getString("theme", "System")!!) }.getOrDefault(ThemeMode.System))
            }
            ResoundTheme(mode) {
                CompositionLocalProvider(LocalVoiceprint provides if (AppGraph.isVoiceprintReady) AppGraph.voiceprint else null) {
                val setMode: (ThemeMode) -> Unit = {
                    mode = it
                    prefs.edit().putString("theme", it.name).apply()
                }
                val privacy by privacyRequest.collectAsState()
                LaunchedEffect(privacy) {
                    if (privacy == "deleteRecent") {
                        app.resound.ui.ShellNav.forgetRecent.value = true
                        app.resound.ui.ShellNav.go(0)
                        consumePrivacyRequest()
                    }
                }
                AppShell(
                    listOf(
                        "今天" to { TodayScreen(capture, stats, transcripts, memory, digestSettings, mode, setMode, ShellBottomPadding) },
                        "问问" to { AskScreen(ask, ShellBottomPadding) },
                        "记忆" to {
                            MemoryScreen(memory, if (AppGraph.isReviewReady) AppGraph.review else null, transcripts,
                                if (AppGraph.isDiaryReady) AppGraph.diary else null, openReview, { openReview = null }, ShellBottomPadding)
                        },
                        "待办" to { TodoScreen(memory, ShellBottomPadding) },
                        "设置" to {
                            SettingsScreen(capture, transcribeSettings, digestSettings, mode, setMode, ShellBottomPadding) { page, bottom ->
                                when (page) {
                                    SettingsPage.Skills -> app.resound.ui.skills.SkillsPage(app.resound.ui.skills.skillRepo(), digestSettings.presets, bottom) { id, b ->
                                        // 基础三项：各自的设置 + 各自的提示词（原“整理指令”页拆进来）
                                        Column(Modifier.verticalScroll(rememberScrollState())) {
                                            if (id == "review" && AppGraph.isReviewReady) ReviewSettingsPage(AppGraph.review, 0.dp)
                                            Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = b + 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                                Text(when (id) {
                                                    "timeline" -> "每轮转写后，大模型把说过的话整理成一件件事，并提取待办、约定、灵感、关键信息等。"
                                                    "ask" -> "在“问问”里提问时，大模型按这份要求查记录、回答并附出处。"
                                                    else -> "每晚把一天整理成一页回顾。"
                                                }, style = app.resound.ui.theme.RsType.subhead, color = app.resound.ui.theme.Rs.colors.textSecondary,
                                                    modifier = Modifier.padding(horizontal = 4.dp))
                                                if (AppGraph.isPromptsReady) app.resound.ui.settings.PromptCard(AppGraph.prompts, if (id == "timeline") "digest" else id)
                                            }
                                        }
                                    }
                                    SettingsPage.Capture -> VerifyScreen(capture, stats, setup, mode, setMode)
                                    SettingsPage.Transcript -> TranscriptScreen(transcripts, transcribeSettings, bottom)
                                    SettingsPage.Model -> Column(Modifier.verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = bottom + 24.dp),
                                        verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                        ModelCard(digestSettings) {}
                                        if (AppGraph.isUsageReady) app.resound.ui.settings.LlmUsageCard(AppGraph.usage)
                                    }
                                    SettingsPage.Voiceprint -> LocalVoiceprint.current?.let { Box(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) { VoiceprintCard(it) } }
                                    SettingsPage.Prompts -> if (AppGraph.isPromptsReady) app.resound.ui.settings.PromptsPage(AppGraph.prompts, bottom)
                                    SettingsPage.Hotwords -> if (AppGraph.isHotwordsReady)
                                        Box(Modifier.verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = bottom + 24.dp)) { app.resound.ui.transcript.HotwordCard(AppGraph.hotwords) }
                                    SettingsPage.Push -> if (AppGraph.isPushReady) app.resound.ui.settings.PushPage(AppGraph.push, bottom)
                                    SettingsPage.Review -> if (AppGraph.isReviewReady) ReviewSettingsPage(AppGraph.review, bottom)
                                    SettingsPage.Backup -> if (AppGraph.isBackupReady) BackupPage(AppGraph.backup, bottom)
                                        else ComingSoon("坚果云备份", "每晚充电且连 WiFi 时，自动把数据备份到你的坚果云")
                                }
                            }
                        },
                    ),
                    tabRequest,
                )
                app.resound.ui.importer.ImportHost()
                openCardId?.let { id ->
                    val repo = remember { app.resound.ui.skills.skillRepo() }
                    val card by remember(id) { repo.card(id) }.collectAsState(initial = null)
                    card?.let { app.resound.ui.skills.SkillCardSheet(it, repo) { openCardId = null } }
                }
                }
            }
        }
    }

    /**
     * 升级或重启手机后，安卓不许 App 在后台自己打开麦克风，只能等用户回到前台。
     * 所以打开 App 时，如果上次是开着聆听（且不在暂停期内），就自动接着听。
     * 要等窗口真正拿到焦点再启动：onResume 时进程可能还没算“前台”，系统会悄悄拒绝麦克风前台服务。
     */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus || !AppGraph.isReady) return
        val prefs = getSharedPreferences("capture", MODE_PRIVATE)
        val wasOn = prefs.getBoolean("enabled", false) && prefs.getLong("pausedUntil", 0) <= System.currentTimeMillis()
        val micGranted = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (wasOn && micGranted && AppGraph.capture.status.value.state == CaptureState.Stopped) AppGraph.capture.start()
    }
}
