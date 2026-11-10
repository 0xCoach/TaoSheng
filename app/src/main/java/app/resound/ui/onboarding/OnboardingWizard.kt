package app.resound.ui.onboarding

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import app.resound.AppGraph
import app.resound.capture.CaptureController
import app.resound.capture.CaptureState
import app.resound.digest.MemoryRepository
import app.resound.transcribe.AndroidTranscribeSettings
import app.resound.transcribe.TranscriptRepository
import app.resound.ui.theme.Rs
import app.resound.ui.theme.RsType
import app.resound.ui.verify.ActionButton
import app.resound.ui.verify.ActionKind
import app.resound.ui.verify.RsCard
import app.resound.ui.verify.SheetInput
import app.resound.ui.verify.springClick
import kotlinx.coroutines.launch
import java.time.LocalDate

/*
 * 0.2.21 开箱向导：第一次打开 4 页走完 —— 欢迎 → 必要权限（同时后台准备本机转写）→ 配置 DeepSeek → 说一句试试。
 * 借鉴成熟引导：先说明再要权限、一页一件事、默认替用户选好、慢的事并行、尽快看到结果。
 * 区分说话人、后台保活细项、备份、推送、足迹都放到进 App 后的“继续完善”和按需提示里。
 */

private const val ONBOARDED = "onboarded"

/** 只给新用户看：没走过向导、没开过录音、老的上手清单也没关过 */
fun shouldShowOnboarding(context: Context): Boolean {
    val ui = context.getSharedPreferences("ui", Context.MODE_PRIVATE)
    if (ui.getBoolean(ONBOARDED, false) || ui.getBoolean("startChecklistDismissed", false)) return false
    val capture = context.getSharedPreferences("capture", Context.MODE_PRIVATE)
    return !capture.getBoolean("enabled", false) && capture.getLong("heartbeat", 0) == 0L
}

private fun markOnboarded(context: Context) {
    context.getSharedPreferences("ui", Context.MODE_PRIVATE).edit().putBoolean(ONBOARDED, true).apply()
    if (AppGraph.isOnboardingReady) AppGraph.onboarding.wizardFinished()
}

private fun record(event: String) { if (AppGraph.isOnboardingReady) AppGraph.onboarding.record(event) }

/** DeepSeek 密钥的样子：sk- 开头加一串字母数字 */
internal val deepseekKey = Regex("""\bsk-[A-Za-z0-9]{20,}\b""")

@Composable
fun OnboardingWizard(capture: CaptureController, transcripts: TranscriptRepository, memory: MemoryRepository, onDone: () -> Unit) {
    val context = LocalContext.current
    var page by rememberSaveable { mutableIntStateOf(0) }
    val startedAt = rememberSaveable { System.currentTimeMillis() }
    LaunchedEffect(Unit) { record("wizard_shown") }
    fun finish(event: String) { record(event); markOnboarded(context); onDone() }
    BackHandler(page > 0) { page-- }
    val c = Rs.colors
    Column(Modifier.fillMaxSize().background(c.background).systemBarsPadding()) {
        if (page > 0) Dots(minOf(page, 4) - 1, 4, Modifier.align(Alignment.CenterHorizontally).padding(top = 16.dp))
        AnimatedContent(page, transitionSpec = {
            (slideInHorizontally { if (targetState > initialState) it / 3 else -it / 3 } + fadeIn()) togetherWith fadeOut(tween(150))
        }, label = "wizard", modifier = Modifier.weight(1f)) { p ->
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 20.dp)) {
                when (p) {
                    0 -> Welcome(onStart = { page = 1 }, onRestore = {
                        finish("wizard_restore")
                        app.resound.ui.ShellNav.openSettings(app.resound.ui.settings.SettingsPage.Backup)
                    })
                    1 -> Permissions(capture) { page = 2 }
                    2 -> DeepSeek(onNext = { page = 3 }, onLater = { record("key_skipped"); page = 3 })
                    3 -> TryIt(capture, transcripts, memory, startedAt) { page = 4 }
                    4 -> Extras(onNow = { record("extras_now"); page = 5 }, onSkip = {
                        // 直接开始：今天页不再显示“继续完善”清单
                        context.getSharedPreferences("ui", Context.MODE_PRIVATE).edit().putBoolean("startChecklistDismissed", true).apply()
                        finish("wizard_done_skip_extras")
                    })
                    else -> KeepAlive { finish("wizard_done") }
                }
            }
        }
    }
}

@Composable
private fun Dots(current: Int, total: Int, modifier: Modifier) {
    val c = Rs.colors
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(total) { i ->
            val w by animateFloatAsState(if (i == current) 22f else 8f, label = "dot")
            Box(Modifier.height(8.dp).width(w.dp).clip(CircleShape).background(if (i <= current) c.accent else c.separator))
        }
    }
}

@Composable
private fun Logo() {
    val heights = listOf(14, 26, 38, 22, 32, 16)
    Box(Modifier.size(84.dp).clip(RoundedCornerShape(24.dp)).background(Rs.colors.accent), contentAlignment = Alignment.Center) {
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
            heights.forEach { Box(Modifier.width(5.dp).height(it.dp).clip(CircleShape).background(androidx.compose.ui.graphics.Color.White)) }
        }
    }
}

@Composable
private fun ColumnScope.Welcome(onStart: () -> Unit, onRestore: () -> Unit) {
    val c = Rs.colors
    Spacer(Modifier.height(48.dp))
    Logo()
    Spacer(Modifier.height(28.dp))
    Text("涛声", style = RsType.title.copy(fontSize = 34.sp), color = c.text)
    Spacer(Modifier.height(10.dp))
    Text("全天听你说的话，整理成一件件事和待办。", style = RsType.headline, color = c.text)
    Spacer(Modifier.height(28.dp))
    listOf("🎙" to "有人说话才录，没人说话不录", "🔒" to "录音和原话只存在你的手机里", "✨" to "自动整理成事件、待办和每日回顾").forEach { (icon, text) ->
        Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(icon, style = RsType.headline); Spacer(Modifier.width(12.dp))
            Text(text, style = RsType.body, color = c.textSecondary)
        }
    }
    Spacer(Modifier.weight(1f).heightIn(min = 40.dp))
    ActionButton("开始", ActionKind.Primary, Modifier.fillMaxWidth(), onClick = onStart)
    Text("换手机了？从备份恢复", style = RsType.subhead, color = c.accent, textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(top = 14.dp).springClick(onRestore))
}

private fun metered(context: Context) =
    runCatching { context.getSystemService(ConnectivityManager::class.java).isActiveNetworkMetered }.getOrDefault(false)

@Composable
private fun ColumnScope.Permissions(capture: CaptureController, onNext: () -> Unit) {
    val c = Rs.colors
    val context = LocalContext.current
    var denied by remember { mutableStateOf(false) }
    var asked by remember { mutableStateOf(false) }
    val settings = if (AppGraph.isTranscribeReady) AppGraph.transcribeSettings as? AndroidTranscribeSettings else null
    fun micGranted() = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED
    fun batteryOk() = runCatching { context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName) }.getOrDefault(true)
    val askBattery = {
        if (!batteryOk()) runCatching {
            context.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        asked = true
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        if (r[Manifest.permission.RECORD_AUDIO] == true) {
            denied = false; record("mic_granted")
            capture.start()
            // 本机转写默认就用：现在开始下，用户配 DeepSeek 的工夫模型就下好了（用流量也下，下面提醒）
            app.resound.ui.today.prepareLocalAsr(context, settings)
            askBattery()
        } else { denied = true; record("mic_denied") }
    }
    // 从电池优化弹窗回来就进下一页
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { if (asked && micGranted()) onNext() }
    Spacer(Modifier.height(24.dp))
    Text("先打开几项必要权限", style = RsType.title, color = c.text)
    Spacer(Modifier.height(8.dp))
    Text("系统会依次弹出确认，请都点“允许”。", style = RsType.body, color = c.textSecondary)
    Spacer(Modifier.height(24.dp))
    RsCard(padding = PaddingValues(horizontal = 18.dp, vertical = 6.dp)) {
        Reason("🎙", "麦克风", "用来录下说话的声音。必需。")
        Reason("🔔", "通知", "后台运行时，安卓要求常驻一条通知，也用来提醒待办。")
        Reason("🔋", "不被系统关掉", "全天收音需要，不然锁屏一会儿就会被系统停掉。")
    }
    Spacer(Modifier.height(16.dp))
    Text(if (metered(context)) "同时会下载本机转写模型（约 170～330 MB）。你现在用的是流量，会消耗流量；连上 WiFi 再打开也可以。"
        else "同时会下载本机转写模型（约 170～330 MB）：转写免费，录音不出手机。",
        style = RsType.caption, color = if (metered(context)) c.warning else c.textTertiary)
    if (denied) {
        Spacer(Modifier.height(16.dp))
        Text("没有麦克风权限，涛声就录不了音。可以再试一次；如果系统不再弹窗，请到系统设置里手动打开。", style = RsType.subhead, color = c.warning)
        Text("去系统设置", style = RsType.subhead, color = c.accent, modifier = Modifier.padding(top = 8.dp).springClick {
            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        })
    }
    Spacer(Modifier.weight(1f).heightIn(min = 32.dp))
    ActionButton(if (denied) "再试一次" else "允许并继续", ActionKind.Primary, Modifier.fillMaxWidth()) {
        if (micGranted() && capture.status.value.state != CaptureState.NoPermission) {
            capture.start(); app.resound.ui.today.prepareLocalAsr(context, settings); askBattery()
            if (batteryOk()) onNext()
        } else launcher.launch(buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.toTypedArray())
    }
}

@Composable
private fun Reason(icon: String, title: String, why: String) {
    val c = Rs.colors
    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.Top) {
        Text(icon, style = RsType.headline); Spacer(Modifier.width(14.dp))
        Column {
            Text(title, style = RsType.body.copy(fontWeight = FontWeight.SemiBold), color = c.text)
            Text(why, style = RsType.caption, color = c.textSecondary)
        }
    }
}

/** 本机转写模型是否已装好（向导各页共用） */
@Composable
private fun modelReady(): Boolean {
    val settings = (if (AppGraph.isTranscribeReady) AppGraph.transcribeSettings as? AndroidTranscribeSettings else null) ?: return true
    return settings.ready.collectAsState().value
}

private val percent = Regex("""下载 (\d+)%""")

/**
 * 本机转写模型的下载与安装进度条：下载阶段按百分比走，解压校验（安装）阶段滚动；装好后变绿勾。
 * [big] 为 true 时用在“说句话试试”页，字号和留白大一些。
 */
@Composable
private fun ModelProgress(big: Boolean = false) {
    val c = Rs.colors
    val context = LocalContext.current
    val settings = (if (AppGraph.isTranscribeReady) AppGraph.transcribeSettings as? AndroidTranscribeSettings else null) ?: return
    val ready by settings.ready.collectAsState()
    val revision by settings.modelRevision.collectAsState()
    val backend by settings.backend.collectAsState()
    val prefs = remember { context.getSharedPreferences("local-asr", Context.MODE_PRIVATE) }
    val status = remember(revision, ready) { if (ready) null else prefs.getString("download-${backend.name}", null) }
    val pct = status?.let { percent.find(it)?.groupValues?.get(1)?.toIntOrNull() }
    val installing = status?.contains("解压") == true || status?.contains("校验下载") == true
    val shown by animateFloatAsState((pct ?: 0) / 100f, tween(400), label = "model")
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(if (ready) c.good.copy(alpha = 0.1f) else c.accentSoft)
        .padding(horizontal = 16.dp, vertical = if (big) 16.dp else 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(when { ready -> "✓ 本机转写已装好"; installing -> "正在安装转写模型"; pct != null -> "正在下载转写模型"; else -> "正在准备转写模型" },
                style = (if (big) RsType.body else RsType.subhead).copy(fontWeight = FontWeight.SemiBold), color = if (ready) c.good else c.text,
                modifier = Modifier.weight(1f))
            if (!ready && pct != null && !installing) Text("$pct%", style = RsType.subhead.copy(fontWeight = FontWeight.SemiBold), color = c.accent)
        }
        if (!ready) {
            Spacer(Modifier.height(10.dp))
            if (pct != null && !installing) androidx.compose.material3.LinearProgressIndicator(progress = { shown }, color = c.accent, trackColor = c.background,
                modifier = Modifier.fillMaxWidth().height(if (big) 8.dp else 6.dp).clip(CircleShape))
            else androidx.compose.material3.LinearProgressIndicator(color = c.accent, trackColor = c.background,
                modifier = Modifier.fillMaxWidth().height(if (big) 8.dp else 6.dp).clip(CircleShape))
            Spacer(Modifier.height(8.dp))
            Text(status?.substringAfter(" · ", "")?.takeIf { pct != null && !installing && it.isNotBlank() }
                ?: if (installing) "解压与校验，马上就好" else (status ?: "排队中…"), style = RsType.caption, color = c.textSecondary)
        }
    }
}

@Composable
private fun ColumnScope.DeepSeek(onNext: () -> Unit, onLater: () -> Unit) {
    val c = Rs.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val providers = if (AppGraph.isLlmProvidersReady) AppGraph.llmProviders else null
    val template = providers?.templates?.firstOrNull { it.id == "deepseek" }
    var key by remember { mutableStateOf("") }
    var found by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<Result<String>?>(null) }
    // 从 DeepSeek 网页复制好密钥回来：看一眼剪贴板，像密钥就问一句要不要填入
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        val clip = runCatching { context.getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.coerceToText(context)?.toString() }.getOrNull()
        found = clip?.let { deepseekKey.find(it)?.value }?.takeIf { it != key }
    }
    fun save(k: String) {
        if (providers == null || template == null || busy) return
        busy = true; result = null
        scope.launch {
            result = runCatching {
                val model = template.models.first()
                val r = providers.test(null, template.baseUrl, model, k)
                r.getOrThrow()
                val id = providers.save(null, template.id, template.name, template.baseUrl, model, k,
                    template.balance?.url.orEmpty(), template.balance?.path.orEmpty())
                providers.activate(id)
                record("key_saved")
                "可以用了"
            }
            busy = false
        }
    }
    Spacer(Modifier.height(20.dp))
    ModelProgress()
    Spacer(Modifier.height(20.dp))
    Text("配置整理模型", style = RsType.title, color = c.text)
    Spacer(Modifier.height(8.dp))
    Text("填一个 DeepSeek 密钥，涛声就能把原话整理成一件件事、待办和每日回顾。按用量付费，日常一天大约几毛钱（以 DeepSeek 官网为准）。",
        style = RsType.body, color = c.textSecondary)
    Spacer(Modifier.height(20.dp))
    RsCard(padding = PaddingValues(horizontal = 18.dp, vertical = 14.dp)) {
        listOf("注册并登录 DeepSeek 开放平台，按提示充值少量余额", "在“API keys”里点“创建 API key”", "复制密钥，回到涛声").forEachIndexed { i, t ->
            Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(24.dp).clip(CircleShape).background(c.accentSoft), contentAlignment = Alignment.Center) {
                    Text("${i + 1}", style = RsType.caption.copy(fontWeight = FontWeight.Bold), color = c.accent)
                }
                Spacer(Modifier.width(12.dp))
                Text(t, style = RsType.subhead, color = c.text)
            }
        }
    }
    Spacer(Modifier.height(14.dp))
    ActionButton("去 DeepSeek 获取密钥", ActionKind.Secondary, Modifier.fillMaxWidth()) {
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(template?.keyUrl ?: "https://platform.deepseek.com/api_keys")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
    AnimatedVisibility(found != null) {
        val f = found.orEmpty()
        Row(Modifier.fillMaxWidth().padding(top = 14.dp).clip(RoundedCornerShape(14.dp)).background(c.accentSoft).springClick {
            key = f; found = null; record("key_from_clipboard"); save(f)
        }.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("检测到密钥 ${f.take(5)}…${f.takeLast(4)}", style = RsType.subhead, color = c.text, modifier = Modifier.weight(1f))
            Text("填入", style = RsType.subhead.copy(fontWeight = FontWeight.SemiBold), color = c.accent)
        }
    }
    Spacer(Modifier.height(14.dp))
    SheetInput(key, "或者在这里粘贴密钥（sk- 开头）") { key = it.trim(); result = null }
    result?.let { r ->
        Text(r.fold({ "✓ $it" }, { it.message ?: "没连上，请检查密钥和余额" }), style = RsType.subhead,
            color = if (r.isSuccess) c.good else c.critical, modifier = Modifier.padding(top = 10.dp))
    }
    Spacer(Modifier.weight(1f).heightIn(min = 28.dp))
    if (result?.isSuccess == true) ActionButton("继续", ActionKind.Primary, Modifier.fillMaxWidth(), onClick = onNext)
    else ActionButton(if (busy) "正在测试…" else "保存并测试", ActionKind.Primary, Modifier.fillMaxWidth(), enabled = !busy && key.isNotBlank()) { save(key) }
    Text("以后再说", style = RsType.caption, color = c.textTertiary, textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(top = 14.dp).springClick(onLater))
}

@Composable
private fun ColumnScope.TryIt(capture: CaptureController, transcripts: TranscriptRepository, memory: MemoryRepository, since: Long, onDone: () -> Unit) {
    val c = Rs.colors
    val status by capture.status.collectAsState()
    val today = remember { LocalDate.now() }
    val segments by remember { transcripts.day(today) }.collectAsState(emptyList())
    val line = segments.flatMap { it.lines }.filter { it.startAt >= since && it.text.isNotBlank() }.maxByOrNull { it.startAt }
    val events by remember { memory.events(today) }.collectAsState(emptyList())
    val event = events.filter { it.startAt >= since - 60_000 }.maxByOrNull { it.startAt }
    val hasKey = if (AppGraph.isMemoryReady) AppGraph.digestSettings.hasKey.collectAsState().value else false
    var organizing by remember { mutableStateOf(false) }
    val ready = modelReady()
    Spacer(Modifier.height(20.dp))
    Text("说句话试试", style = RsType.title, color = c.text)
    if (!ready) {
        // 模型装好之前转写不了：先显示进度，装好后自动进入试一试
        Spacer(Modifier.height(8.dp))
        Text("本机转写模型装好后就能开始。下载期间可以停在这一页，建议连着 WiFi。", style = RsType.body, color = c.textSecondary)
        Spacer(Modifier.height(24.dp))
        ModelProgress(big = true)
        Spacer(Modifier.weight(1f).heightIn(min = 28.dp))
        ActionButton("模型准备中…", ActionKind.Primary, Modifier.fillMaxWidth(), enabled = false) {}
        Text("不等了，先往下走（装好后自动转写）", style = RsType.caption, color = c.textTertiary, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 14.dp).springClick(onDone))
        return
    }
    Spacer(Modifier.height(8.dp))
    Text("比如：“明天下午三点和老王开会。”说完停一两秒。", style = RsType.body, color = c.textSecondary)
    Spacer(Modifier.height(28.dp))
    // 实时音量：说话时柱子跟着跳，让人知道涛声在听
    val level by animateFloatAsState(status.level.coerceIn(0f, 1f), tween(80), label = "level")
    Row(Modifier.fillMaxWidth().height(72.dp), horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically) {
        listOf(0.5f, 0.8f, 1f, 0.7f, 0.9f, 0.6f, 0.4f).forEach { k ->
            Box(Modifier.width(8.dp).height((10 + 60 * level * k).dp).clip(CircleShape).background(c.accent))
        }
    }
    Spacer(Modifier.height(20.dp))
    RsCard(Modifier.fillMaxWidth(), padding = PaddingValues(horizontal = 18.dp, vertical = 16.dp)) {
        Text("转写", style = RsType.caption, color = c.textTertiary)
        Spacer(Modifier.height(6.dp))
        Text(line?.text ?: "等你说话…（本机模型还在准备时，下载完会自动转写）", style = RsType.body,
            color = if (line != null) c.text else c.textTertiary)
        if (line != null && hasKey) {
            Box(Modifier.padding(vertical = 14.dp).fillMaxWidth().height(0.5.dp).background(c.separator))
            Text("整理", style = RsType.caption, color = c.textTertiary)
            Spacer(Modifier.height(6.dp))
            when {
                event != null -> {
                    Text(event.title, style = RsType.body.copy(fontWeight = FontWeight.SemiBold), color = c.text)
                    Text(event.summary, style = RsType.caption, color = c.textSecondary, maxLines = 3)
                    event.items.forEach { Text("• ${it.content}", style = RsType.caption, color = c.accent, modifier = Modifier.padding(top = 4.dp)) }
                }
                organizing -> Text("正在整理…", style = RsType.subhead, color = c.textSecondary)
                else -> Text("整理成事件和待办", style = RsType.subhead, color = c.accent, modifier = Modifier.springClick {
                    organizing = true; memory.runNow()
                })
            }
        }
    }
    if (line != null && !hasKey) Text("配好整理模型后，原话会自动整理成事和待办（设置 → 整理模型）。", style = RsType.caption,
        color = c.textTertiary, modifier = Modifier.padding(top = 10.dp))
    Spacer(Modifier.weight(1f).heightIn(min = 28.dp))
    ActionButton(if (line != null) "下一步" else "跳过，下一步", ActionKind.Primary, Modifier.fillMaxWidth(), onClick = onDone)
}

@Composable
private fun ColumnScope.Extras(onNow: () -> Unit, onSkip: () -> Unit) {
    val c = Rs.colors
    Spacer(Modifier.height(32.dp))
    Text("基本设置好了 🎉", style = RsType.title, color = c.text)
    Spacer(Modifier.height(8.dp))
    Text("还有几项可以让涛声更好用，要现在设置吗？以后也能在设置里找到。", style = RsType.body, color = c.textSecondary)
    Spacer(Modifier.height(24.dp))
    RsCard(padding = PaddingValues(horizontal = 18.dp, vertical = 6.dp)) {
        Reason("🔋", "后台保活", "小米等手机还要打开自启动，全天收音才不会断。")
        Reason("☁️", "备份", "换手机、误删都能找回来。")
        Reason("📮", "推送", "每日回顾、待办提醒推到微信等常用的地方。")
        Reason("📍", "足迹", "记下每天去过哪，事件上显示当时在哪。")
    }
    Spacer(Modifier.weight(1f).heightIn(min = 28.dp))
    ActionButton("现在设置", ActionKind.Primary, Modifier.fillMaxWidth(), onClick = onNow)
    Spacer(Modifier.height(10.dp))
    ActionButton("直接开始", ActionKind.Secondary, Modifier.fillMaxWidth(), onClick = onSkip)
}

/** 现在设置的第一步：后台保活（最影响全天收音）；其余几项进 App 后在今天页“继续完善”里接着引导 */
@Composable
private fun ColumnScope.KeepAlive(onDone: () -> Unit) {
    val c = Rs.colors
    val setup = if (AppGraph.isReady) AppGraph.setup else null
    var items by remember { mutableStateOf(setup?.items().orEmpty()) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { items = setup?.items().orEmpty() }
    Spacer(Modifier.height(24.dp))
    Text("让涛声在后台不被关掉", style = RsType.title, color = c.text)
    Spacer(Modifier.height(8.dp))
    Text("逐项点“去设置”，按系统页面打开后返回这里。手机型号不同，项目会略有不同。", style = RsType.body, color = c.textSecondary)
    Spacer(Modifier.height(20.dp))
    RsCard(padding = PaddingValues(horizontal = 18.dp, vertical = 6.dp)) {
        items.forEach { item ->
            Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(item.title, style = RsType.body.copy(fontWeight = FontWeight.SemiBold), color = if (item.done == true) c.textTertiary else c.text)
                    Text(item.description, style = RsType.caption, color = c.textSecondary)
                }
                Spacer(Modifier.width(10.dp))
                when (item.done) {
                    true -> Text("✓", style = RsType.headline, color = c.good)
                    false -> app.resound.ui.verify.Pill("去设置", filled = true) { setup?.open(item.key) }
                    null -> app.resound.ui.verify.Pill("已设置", filled = false) { setup?.markDone(item.key); items = setup?.items().orEmpty() }
                }
            }
        }
        if (items.isEmpty()) Text("这台手机不需要额外设置。", style = RsType.subhead, color = c.textTertiary, modifier = Modifier.padding(vertical = 14.dp))
    }
    Spacer(Modifier.height(12.dp))
    Text("备份、推送、足迹在今天页顶部的“继续完善”里，接着点就能设置。", style = RsType.caption, color = c.textTertiary)
    Spacer(Modifier.weight(1f).heightIn(min = 28.dp))
    ActionButton("进入涛声", ActionKind.Primary, Modifier.fillMaxWidth(), onClick = onDone)
}
