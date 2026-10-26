package app.resound.ui.transcript

import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.material3.CircularProgressIndicator
import app.resound.voiceprint.VoiceprintThresholds
import app.resound.ui.voiceprint.LocalVoiceprint
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenu
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.combinedClickable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.resound.transcribe.SegmentTranscript
import app.resound.transcribe.TranscribeQueueState
import app.resound.ui.settings.CustomModelForm
import app.resound.ui.settings.SettingsInput
import app.resound.transcribe.AndroidTranscribeSettings
import app.resound.transcribe.TranscribeSource
import app.resound.transcribe.local.LocalBackend
import app.resound.transcribe.local.LocalModels
import app.resound.transcribe.TranscribeSettings
import app.resound.transcribe.MIN_INTERVAL
import app.resound.transcribe.MAX_INTERVAL
import app.resound.transcribe.TranscriptLine
import app.resound.transcribe.TranscriptRepository
import app.resound.transcribe.TranscriptStatus
import app.resound.ui.theme.Rs
import app.resound.ui.theme.RsType
import app.resound.ui.verify.DaySwitcher
import app.resound.ui.verify.Dot
import app.resound.ui.verify.RsCard
import app.resound.ui.verify.Pill
import app.resound.ui.verify.Section
import app.resound.ui.verify.formatClock
import app.resound.ui.verify.formatDuration
import app.resound.ui.verify.springClick
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.util.Locale

/** 原话页：转写队列状态、转写设置、按天查看每段录音转出来的文字。 */
@Composable
fun TranscriptScreen(
    repo: TranscriptRepository,
    settings: TranscribeSettings,
    bottomPadding: androidx.compose.ui.unit.Dp,
) {
    val c = Rs.colors
    var dayOffset by remember { mutableIntStateOf(0) }
    val date = remember(dayOffset) { LocalDate.now().minusDays(dayOffset.toLong()) }
    val segments by remember(date) { repo.day(date) }.collectAsState(initial = null)
    val hasKey by settings.ready.collectAsState()
    var settingsOpen by remember { mutableStateOf(false) }
    LazyColumn(
        Modifier.fillMaxSize().background(c.background).windowInsetsPadding(WindowInsets.safeDrawing),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = bottomPadding + 24.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        item {
            QueueCard(repo, hasKey, settingsOpen || !hasKey) { settingsOpen = !settingsOpen }
        }
        // 收起时不占位置（用列表自带的出现/消失动画）
        if (settingsOpen || !hasKey) item(key = "settings") { SettingsCard(settings, onSaved = { settingsOpen = false }) }
        if (app.resound.AppGraph.isUsageReady) item(key = "asrUsage") { app.resound.ui.settings.AsrUsageCard(app.resound.AppGraph.usage) }
        item {
            val list = segments.orEmpty()
            Section("录音原话", trailing = {
                if (list.isNotEmpty()) {
                    val done = list.count { it.status == TranscriptStatus.Done || it.status == TranscriptStatus.Empty }
                    Text("${list.size} 段 · 已转 $done", style = RsType.caption, color = c.textTertiary)
                }
            }) { DaySwitcher(dayOffset) { dayOffset = it } }
        }
        val list = segments
        if (list != null && list.isEmpty()) {
            item {
                RsCard(padding = PaddingValues(vertical = 36.dp, horizontal = 20.dp)) {
                    Text(
                        "这一天没有录到人声",
                        style = RsType.subhead,
                        color = c.textTertiary,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
        items(list.orEmpty(), key = { it.segmentId }) { seg ->
            SegmentCard(seg, Modifier.animateItem()) { repo.transcribeAnyway(seg.segmentId) }
        }
    }
}

// ---------- 队列状态 ----------

@Composable
private fun QueueCard(repo: TranscriptRepository, hasKey: Boolean, settingsShown: Boolean, onToggleSettings: () -> Unit) {
    val c = Rs.colors
    val q by repo.queue.collectAsState()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(30_000)
        }
    }

    val (title, color) = when {
        !hasKey -> "转写尚未就绪" to c.warning
        q.running -> "正在转写…" to c.accent
        q.failed > 0 -> "有 ${q.failed} 段没转成功" to c.critical
        q.pending > 0 -> "还有 ${q.pending} 段等着转" to c.accent
        else -> "都转完了" to c.good
    }
    val dot by animateColorAsState(color, label = "queueDot")

    RsCard(padding = PaddingValues(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Dot(dot, 9.dp)
            Spacer(Modifier.width(8.dp))
            AnimatedContent(title, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "queueTitle") {
                Text(it, style = RsType.headline, color = c.text)
            }
            Spacer(Modifier.weight(1f))
            Text(
                if (settingsShown) "收起模型设置" else "模型设置",
                style = RsType.subhead,
                color = c.accent,
                modifier = Modifier.springClick(onToggleSettings).padding(4.dp),
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            queueDetail(q, hasKey, now),
            style = RsType.subhead,
            color = if (hasKey && q.lastError != null && !q.running) c.critical else c.textSecondary,
            modifier = Modifier.padding(start = 17.dp),
        )
        AnimatedVisibility(q.running) {
            ProgressSweep(Modifier.fillMaxWidth().padding(top = 14.dp).height(4.dp))
        }
        // 云端用量：用过才显示，一行小字；本地转写不计
        if (q.usedSecondsTotal > 0) {
            Spacer(Modifier.height(12.dp))
            Text(
                "云端转写 今天 ${usage(q.usedSecondsToday)} · 累计 ${usage(q.usedSecondsTotal)}，费用以账单为准",
                style = RsType.caption, color = c.textTertiary, modifier = Modifier.padding(start = 17.dp),
            )
        }
        if (hasKey) {
            Spacer(Modifier.height(14.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val context = LocalContext.current
                // 转写中再点只提示，按钮里转圈，避免以为卡死
                Row(
                    Modifier.clip(CircleShape).background(if (q.running) c.accentSoft else c.accent).springClick {
                        if (q.running) Toast.makeText(context, "正在后台转写，请稍等", Toast.LENGTH_SHORT).show() else repo.runNow()
                    }.padding(horizontal = 16.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (q.running) {
                        ProgressRing(q.runDone, q.runTotal, c.accent, Modifier.size(14.dp))
                        Spacer(Modifier.width(7.dp))
                    }
                    Text(if (q.running) runLabel(q.runDone, q.runTotal) else "现在转写", style = RsType.subhead, color = if (q.running) c.accent else Color.White)
                }
                if (q.failed > 0 && !q.running) Pill("重试没转成功的", filled = false) { repo.retryFailed() }
                app.resound.ui.importer.ImportButton()
            }
        }
    }
}

private fun queueDetail(q: TranscribeQueueState, hasKey: Boolean, now: Long): String = when {
    !hasKey -> "请配置云端服务，或下载本地模型后开始转写"
    q.running -> "逐段识别中，可以离开这个页面"
    q.lastError != null -> q.lastError
    else -> buildString {
        q.lastRunAt?.let { append("上次 ${formatClock(it)}") }
        q.nextRunAt?.let {
            if (isNotEmpty()) append(" · ")
            val mins = ((it - now) / 60_000).coerceAtLeast(0)
            append(if (mins == 0L) "马上进行下一轮" else "约 $mins 分钟后下一轮")
        }
        if (isEmpty()) append("还没有转写过")
    }
}

private fun usage(seconds: Long) = if (seconds in 0..59) "${seconds}秒" else formatDuration(seconds * 1000)

/** 转写中：一道暖橙光从左到右扫过。 */
@Composable
private fun ProgressSweep(modifier: Modifier) {
    val c = Rs.colors
    val t = rememberInfiniteTransition(label = "sweep")
    val x by t.animateFloat(-0.4f, 1.4f, infiniteRepeatable(tween(1400, easing = FastOutSlowInEasing)), label = "x")
    Canvas(modifier.clip(CircleShape).background(c.accentSoft)) {
        val w = size.width * 0.4f
        drawRoundRect(
            brush = Brush.horizontalGradient(
                listOf(Color.Transparent, c.accent, Color.Transparent),
                startX = size.width * x - w / 2,
                endX = size.width * x + w / 2,
            ),
            size = size,
            cornerRadius = CornerRadius(size.height / 2),
        )
    }
}

// ---------- 设置 ----------

@Composable
private fun SettingsCard(settings: TranscribeSettings, onSaved: () -> Unit) {
    val c = Rs.colors
    val scope = rememberCoroutineScope()
    val hasKey by settings.hasKey.collectAsState()
    val ready by settings.ready.collectAsState()
    val advanced = settings as? AndroidTranscribeSettings
    val source = advanced?.source?.collectAsState()?.value ?: TranscribeSource.Doubao
    val local = source == TranscribeSource.Local
    val interval by settings.intervalMinutes.collectAsState()
    val wifiOnly by settings.wifiOnly.collectAsState()
    var editing by remember(source) { mutableStateOf(false) }
    var input by remember(source) { mutableStateOf("") }
    var testing by remember { mutableStateOf(false) }
    var testResult by remember(source) { mutableStateOf<Result<String>?>(null) }

    RsCard(Modifier.animateContentSize(), padding = PaddingValues(horizontal = 20.dp, vertical = 18.dp)) {
        if (advanced != null) {
            Text("语音转写", style = RsType.body, color = c.text)
            Spacer(Modifier.height(10.dp))
            Choice(TranscribeSource.entries.toList(), source, { it.title }) { advanced.setSource(it) }
            Divider()
            if (local) LocalModelOptions(advanced)
            if (source == TranscribeSource.Custom) {
                CustomModelForm(advanced.customEndpoint,advanced.customModel,hasKey,
                    "自定义（OpenAI 兼容音频转写）",advanced::fetchModels,advanced::saveCustomAll)

            }
        }
        if (!local && source != TranscribeSource.Custom) {
        Text(if(source == TranscribeSource.Custom) "自定义服务密钥" else "豆包语音密钥", style = RsType.body, color = c.text)
        Spacer(Modifier.height(8.dp))
        if (hasKey && !editing) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Dot(c.good, 7.dp)
                Spacer(Modifier.width(8.dp))
                Text("已填写", style = RsType.subhead, color = c.textSecondary, modifier = Modifier.weight(1f))
                Pill("更换", filled = false) { editing = true }
                Spacer(Modifier.width(8.dp))
                Pill("清除", filled = false) {
                    settings.clearKey()
                    testResult = null
                }
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SettingsInput(input, { input=it.trim() }, "粘贴 API Key", Modifier.weight(1f), secret=true)
                Spacer(Modifier.width(10.dp))
                Pill("保存", filled = input.isNotEmpty()) {
                    if (input.isEmpty()) return@Pill
                    settings.saveKey(input)
                    input = ""
                    editing = false
                    testResult = null
                    if(source == TranscribeSource.Doubao) onSaved()
                }
            }
            if (editing) {
                Text(
                    "取消",
                    style = RsType.subhead,
                    color = c.textSecondary,
                    modifier = Modifier.padding(top = 8.dp).springClick { editing = false; input = "" },
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text("只存在这台手机的安全区里，填好后任何地方都不再显示。", style = RsType.caption, color = c.textTertiary)
        }
        Divider()

        Text("多久转写一次", style = RsType.body, color = c.text)
        Spacer(Modifier.height(10.dp))
        IntervalChoice(interval) { settings.setIntervalMinutes(it) }

        Divider()

        if (!local) Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("只在 WiFi 下转写", style = RsType.body, color = c.text)
                Text("关掉后用流量也会上传，每小时录音约 10 MB", style = RsType.caption, color = c.textSecondary)
            }
            Toggle(wifiOnly) { settings.setWifiOnly(it) }
        }

        if (app.resound.AppGraph.isPlaybackSkipReady) {
            val skip by app.resound.AppGraph.playbackSkip.enabled.collectAsState()
            if (!local) Divider()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("外放声音时不转写", style = RsType.body, color = c.text)
                    Text(if (skip) "手机扬声器在放视频、音乐时录到的先不转写，原话里可单段“仍然转写”；戴耳机不受影响"
                        else "外放时录到的也照常转写，视频里的声音可能混进原话", style = RsType.caption, color = c.textSecondary)
                }
                Toggle(skip) { app.resound.AppGraph.playbackSkip.setEnabled(it) }
            }
        }

        if (ready) {
            Divider()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("测试一下", style = RsType.body, color = c.text)
                    val r = testResult
                    Text(
                        when {
                            testing -> if(local) "正在本地识别 3 秒测试音频…" else "正在发送 3 秒测试音频…"
                            r == null -> if(local) "不上传录音；检查本机模型是否可用" else "发一小段测试音频，确认密钥和服务可用"
                            r.isSuccess -> r.getOrNull().orEmpty()
                            else -> r.exceptionOrNull()?.message ?: "测试没通过"
                        },
                        style = RsType.caption,
                        color = when {
                            testing || r == null -> c.textSecondary
                            r.isSuccess -> c.good
                            else -> c.critical
                        },
                    )
                }
                Spacer(Modifier.width(10.dp))
                Pill(if (testing) "测试中" else "测试", filled = false) {
                    if (testing) return@Pill
                    testing = true
                    scope.launch {
                        testResult = runCatching { settings.test().getOrThrow() }
                        testing = false
                    }
                }
            }
        }
    }
}

@Composable
private fun LocalModelOptions(settings: AndroidTranscribeSettings) {
    val c=Rs.colors
    val backend by settings.backend.collectAsState()
    val actual by settings.localStatus.collectAsState()
    val revision by settings.modelRevision.collectAsState()
    val supported=LocalModels.npuSupported()
    Text("SenseVoice · 离线识别", style=RsType.body,color=c.text)
    Text("按设定间隔分批转写，完成后释放模型。录音不上传；识别时可能增加耗电。", style=RsType.caption,color=c.textSecondary)
    Spacer(Modifier.height(10.dp))
    Choice(LocalBackend.entries.toList(),backend,{it.title}){settings.setBackend(it)}
    Text(if(supported) "检测到已适配芯片；第一次转写时检查一次 NPU，结果会记住。区分说话人始终用 CPU。" else "本机 NPU 尚未适配，将使用 CPU。",style=RsType.caption,color=c.textSecondary)
    Text(actual,style=RsType.caption,color=c.accent)
    LocalBackend.entries.forEach { mode ->
        val downloaded=remember(revision,mode){settings.modelReady(mode)}
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment=Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("${mode.title} · ${if(mode==LocalBackend.Npu) "244" else "228"} MiB",style=RsType.subhead,color=c.text)
                Text(if(downloaded) "已下载并校验" else settings.downloadStatus(mode),style=RsType.caption,color=c.textSecondary)
            }
            val status=settings.downloadStatus(mode)
            val downloading=status.startsWith("下载 ") || status.startsWith("连接") || status.startsWith("解压") || status.startsWith("校验")
            if(!downloaded && (mode==LocalBackend.Cpu || supported)) Pill(if(downloading) "下载中" else if(status.contains("未完成")) "重试" else "下载",filled=false){settings.download(mode)}
        }
    }
    Text("官方 GitHub 源，每份下载约 162 MB；国内网络可能不稳定。仅下载需要的模型，CPU 包用于手动选择或 NPU 失败回退。",style=RsType.caption,color=c.textTertiary)
}

@Composable
private fun Divider() {
    Box(Modifier.padding(vertical = 16.dp).fillMaxWidth().height(0.5.dp).background(Rs.colors.separator))
}

/** 胶囊分段选择。 */
@Composable
private fun <T> Choice(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    val c = Rs.colors
    Row(Modifier.clip(CircleShape).background(c.separator.copy(alpha = if (c.isDark) 1f else 0.7f)).padding(3.dp)) {
        options.forEach { option ->
            val on = option == selected
            val bg by animateColorAsState(if (on) c.card else c.card.copy(alpha = 0f), spring(stiffness = Spring.StiffnessMediumLow), label = "choice")
            Box(Modifier.clip(CircleShape).background(bg).springClick { onSelect(option) }.padding(horizontal = 16.dp, vertical = 7.dp)) {
                Text(label(option), style = RsType.subhead, color = if (on) c.text else c.textSecondary)
            }
        }
    }
}

/** 常用间隔三选一，或“自定义”填分钟数（15～720，安卓后台定时最短 15 分钟）。 */
@Composable
private fun IntervalChoice(interval: Int, onSet: (Int) -> Unit) {
    val c = Rs.colors
    val presets = listOf(30, 45, 60)
    var custom by remember { mutableStateOf(interval !in presets) }
    var input by remember(interval) { mutableStateOf(if (interval in presets) "" else interval.toString()) }
    val value = input.toIntOrNull()
    val valid = value != null && value in MIN_INTERVAL..MAX_INTERVAL
    Choice(presets + CUSTOM, if (custom) CUSTOM else interval, { if (it == CUSTOM) "自定义" else "$it 分钟" }) {
        if (it == CUSTOM) custom = true else { custom = false; onSet(it) }
    }
    AnimatedVisibility(custom) {
        Column {
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 最多三位数，用小胶囊框+数字键盘，不撑满整行
                BasicTextField(input, { t -> input = t.filter(Char::isDigit).take(3) }, singleLine = true,
                    textStyle = RsType.body.copy(color = c.text, textAlign = TextAlign.Center), cursorBrush = SolidColor(c.accent),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                    modifier = Modifier.width(76.dp),
                    decorationBox = { inner ->
                        Box(Modifier.clip(CircleShape).background(c.background).padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                            if (input.isEmpty()) Text("如 20", style = RsType.body, color = c.textTertiary)
                            inner()
                        }
                    })
                Spacer(Modifier.width(8.dp))
                Text("分钟", style = RsType.subhead, color = c.textSecondary, modifier = Modifier.weight(1f))
                Pill(if (value == interval && valid) "已生效" else "保存", filled = valid && value != interval) {
                    if (valid && value != interval) onSet(value!!)
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(if (input.isEmpty() || valid) "可填 $MIN_INTERVAL～$MAX_INTERVAL 分钟；间隔越短越及时，也越费电" else "请填 $MIN_INTERVAL～$MAX_INTERVAL 之间的数",
                style = RsType.caption, color = if (input.isEmpty() || valid) c.textTertiary else c.warning)
        }
    }
}

private const val CUSTOM = -1

/** 苹果风格开关，暖橙底。 */
@Composable
private fun Toggle(on: Boolean, onChange: (Boolean) -> Unit) {
    val c = Rs.colors
    val track by animateColorAsState(if (on) c.accent else c.separator, label = "track")
    val x by animateDpAsState(if (on) 20.dp else 0.dp, spring(dampingRatio = 0.65f, stiffness = Spring.StiffnessMedium), label = "knob")
    Box(Modifier.size(width = 50.dp, height = 30.dp).clip(CircleShape).background(track).springClick { onChange(!on) }.padding(2.dp)) {
        Box(Modifier.offset(x = x).size(26.dp).clip(CircleShape).background(Color.White))
    }
}

// ---------- 每段录音 ----------

@Composable
private fun SegmentCard(seg: SegmentTranscript, modifier: Modifier, onTranscribeAnyway: () -> Unit) {
    val c = Rs.colors
    RsCard(modifier.animateContentSize(), padding = PaddingValues(horizontal = 18.dp, vertical = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(formatClock(seg.startAt), style = RsType.headline, color = c.text)
            val end = formatClock(seg.endAt)
            Text(
                (if (end == formatClock(seg.startAt)) "" else " – $end") + " · ${formatDuration(seg.endAt - seg.startAt, seconds = true)}",
                style = RsType.subhead,
                color = c.textSecondary,
                modifier = Modifier.weight(1f),
            )
            StatusChip(seg)
        }
        Spacer(Modifier.height(10.dp))
        AnimatedContent(seg.status, transitionSpec = { fadeIn(tween(300)) togetherWith fadeOut(tween(150)) }, label = "segBody") { status ->
            when (status) {
                TranscriptStatus.Done -> SpeakerLines(seg.lines)
                TranscriptStatus.Running -> Placeholder()
                TranscriptStatus.Pending -> Note("等下一轮转写")
                TranscriptStatus.Empty -> Note("只有声音，没识别出说话内容")
                TranscriptStatus.Failed -> Note(seg.error ?: "没转成功，可以在上面点重试", c.critical)
                TranscriptStatus.SkippedPlayback -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Note("手机外放声音时录的，没有转写", modifier = Modifier.weight(1f))
                    if (seg.audioAvailable) Pill("仍然转写", filled = false, onClick = onTranscribeAnyway)
                }
            }
        }
    }
}

@Composable
private fun StatusChip(seg: SegmentTranscript) {
    val c = Rs.colors
    val (text, color) = when (seg.status) {
        TranscriptStatus.Pending -> "等待中" to c.textTertiary
        TranscriptStatus.Running -> "转写中" to c.accent
        TranscriptStatus.Failed -> "没转成功" to c.critical
        TranscriptStatus.Empty -> "没有文字" to c.textTertiary
        TranscriptStatus.Done -> if (seg.audioAvailable) return else "录音已删" to c.textTertiary
        TranscriptStatus.SkippedPlayback -> "外放时录的" to c.textTertiary
    }
    Box(Modifier.clip(CircleShape).background(color.copy(alpha = 0.12f)).padding(horizontal = 10.dp, vertical = 3.dp)) {
        Text(text, style = RsType.caption, color = color)
    }
}

@Composable
private fun Note(text: String, color: Color = Rs.colors.textTertiary, modifier: Modifier = Modifier) {
    Text(text, style = RsType.subhead, color = color, modifier = modifier)
}

/** 真实进度环：知道总数时按完成比例画，不知道时转圈 */
@Composable
fun ProgressRing(done: Int, total: Int, color: Color, modifier: Modifier) {
    if (total > 0) {
        val p by androidx.compose.animation.core.animateFloatAsState((done.toFloat() / total).coerceIn(0f, 1f), label = "ring")
        CircularProgressIndicator(progress = { p }, modifier = modifier, color = color, strokeWidth = 1.8.dp, trackColor = color.copy(alpha = 0.2f))
    } else {
        CircularProgressIndicator(modifier, color = color, strokeWidth = 1.8.dp)
    }
}

/** “转写中 3/37” */
fun runLabel(done: Int, total: Int, verb: String = "转写中") = if (total > 0) "$verb ${minOf(done + 1, total)}/$total" else verb

/** 原话：同一说话人连续的句子并成一组，左侧是说话人标记。 */
@Composable
fun SpeakerLines(lines: List<TranscriptLine>) {
    val c = Rs.colors
    val voiceprint = LocalVoiceprint.current
    val context = LocalContext.current
    val groups = remember(lines) {
        lines.fold(mutableListOf<MutableList<TranscriptLine>>()) { acc, line ->
            val last = acc.lastOrNull()
            if (last != null && last.first().segmentId == line.segmentId && last.first().speakerKey() == line.speakerKey() && (line.speakerTag != null || line.speakerName != null)) last += line else acc += mutableListOf(line)
            acc
        }
    }
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    val multi = lines.mapNotNull { it.speakerKey() }.distinct().size > 1
    var menuFor by remember { mutableStateOf<Long?>(null) }
    var assignFor by remember { mutableStateOf<Long?>(null) }
    androidx.compose.foundation.text.selection.DisableSelection {
        assignFor?.let { id -> voiceprint?.let { app.resound.ui.voiceprint.AssignPersonDialog(it, id) { assignFor = null } } }
    }
    // 声纹弹出菜单在独立窗口，不能继承详情页的文本选择注册器。
    androidx.compose.foundation.text.selection.DisableSelection {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        groups.forEach { group ->
            val first = group.first()
            val labeled = multi || first.speakerTag != null || first.speakerName != null || first.maybeMe()
            Box {
                // 长按一段话：纠正“这是我 / 不是我”，声纹会记住
                Row(
                    Modifier.fillMaxWidth().combinedClickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {},
                        onLongClick = { menuFor = first.id },
                    ),
                ) {
                    if (labeled) {
                        SpeakerBadge(first)
                        Spacer(Modifier.width(10.dp))
                    }
                    Column(Modifier.weight(1f)) {
                        // 说话人和时间同一行；时间用灰色，一组只标开头那句的时间
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 3.dp, bottom = 1.dp)) {
                            if (labeled) {
                                Text(speakerLabel(first), style = RsType.caption.copy(fontWeight = FontWeight.Medium), color = speakerColor(first))
                                Spacer(Modifier.width(6.dp))
                            }
                            Text(lineTime(first.startAt), style = RsType.caption, color = c.textTertiary)
                            first.voiceInput?.let { v ->
                                Spacer(Modifier.width(6.dp))
                                Text("⌨ 语音输入 · ${v.app}" + (v.title?.let { " · $it" } ?: ""), style = RsType.caption.copy(fontWeight = FontWeight.Medium),
                                    color = c.accent, maxLines = 1, modifier = Modifier.clip(CircleShape).background(c.accentSoft).padding(horizontal = 8.dp, vertical = 1.dp))
                            }
                        }
                        group.forEach { line ->
                            Text(line.text, style = RsType.body, color = c.text, modifier = Modifier.fillMaxWidth().combinedClickable(
                                interactionSource = remember(line.id) { MutableInteractionSource() }, indication = null,
                                onClick = {}, onLongClick = { menuFor = line.id },
                            ).padding(vertical = 2.dp))
                            line.voiceInput?.let { VoiceInputContext(it) }
                        }
                    }
                }
                run {
                    val selected = group.find { it.id == menuFor } ?: first
                    DropdownMenu(expanded = group.any { it.id == menuFor }, onDismissRequest = { menuFor = null }, containerColor = c.card) {
                        DropdownMenuItem(text = { Text("复制这段", style = RsType.body, color = c.text) }, onClick = {
                            clipboard.setText(androidx.compose.ui.text.AnnotatedString(selected.text))
                            menuFor = null
                            Toast.makeText(context, "已复制这段原话", Toast.LENGTH_SHORT).show()
                        })
                        // 语音输入的句子本来就是你说的，不用纠正说话人
                        if (voiceprint != null && selected.voiceInput == null) {
                        DropdownMenuItem(text = { Text("这是谁 / 输入名字", style = RsType.body, color = c.text) }, onClick = {
                            assignFor = selected.id; menuFor = null
                        })
                        DropdownMenuItem(text = { Text("这是我", style = RsType.body, color = c.text) }, onClick = {
                            voiceprint.markMe(selected.id); menuFor = null
                            Toast.makeText(context, "已记下，正在学习你的声音", Toast.LENGTH_SHORT).show()
                        })
                        DropdownMenuItem(text = { Text("不是我", style = RsType.body, color = c.text) }, onClick = {
                            voiceprint.markNotMe(selected.id); menuFor = null
                            Toast.makeText(context, "已记下，这段不再标成你", Toast.LENGTH_SHORT).show()
                        })
                        }
                    }
                }
            }
        }
    }
    }
}

/** 语音输入时屏幕上的上下文：默认收起，点开看之前的内容和之后的回复 */
@Composable
private fun VoiceInputContext(v: app.resound.transcribe.VoiceInputMeta) {
    val c = Rs.colors
    if (v.before == null && v.after == null) return
    var open by remember { mutableStateOf(false) }
    Column(Modifier.padding(top = 4.dp).fillMaxWidth().clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
        .background(c.background).springClick { open = !open }.padding(horizontal = 12.dp, vertical = 8.dp)) {
        Text(if (open) "当时的上下文 · 收起" else "当时的上下文 · 点开看", style = RsType.caption.copy(fontWeight = FontWeight.Medium), color = c.textSecondary)
        androidx.compose.animation.AnimatedVisibility(open) {
            Column(Modifier.padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                v.before?.let {
                    Text("之前", style = RsType.caption, color = c.textTertiary)
                    Text(it, style = RsType.subhead, color = c.textSecondary)
                }
                v.after?.let {
                    Text("之后的回复", style = RsType.caption, color = c.textTertiary)
                    Text(it, style = RsType.subhead, color = c.textSecondary)
                }
            }
        }
    }
}

private fun lineTime(at: Long) = java.time.Instant.ofEpochMilli(at).atZone(java.time.ZoneId.systemDefault())
    .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss"))

/** 声纹分数在“可能是我”区间，但还没确定 */
private fun TranscriptLine.maybeMe() = speakerName == null && (speakerScore ?: 0f) >= VoiceprintThresholds.MAYBE

private fun TranscriptLine.speakerKey() = speakerName ?: if (maybeMe()) "可能是我" else speakerTag

private fun speakerLabel(line: TranscriptLine): String =
    line.speakerName ?: if (line.maybeMe()) "可能是我？" else line.speakerTag?.let { tag -> if (tag.startsWith("uncertain:")) "待确认" else "说话人 ${tag.toIntOrNull()?.plus(1) ?: tag}" } ?: "未知"

@Composable
private fun speakerColor(line: TranscriptLine): Color {
    val c = Rs.colors
    if (line.speakerName == "我") return c.accent
    if (line.maybeMe()) return c.accent.copy(alpha = 0.6f)
    val palette = if (c.isDark) {
        listOf(c.accent, Color(0xFF6CC3BB), Color(0xFFA897D8), Color(0xFFE48FA8), Color(0xFF8DB8E8))
    } else {
        listOf(c.accent, Color(0xFF2E9C93), Color(0xFF7E68C2), Color(0xFFCC5E82), Color(0xFF4F86C6))
    }
    val key = line.speakerKey() ?: return c.textTertiary
    val index = key.toIntOrNull() ?: (key.hashCode() and 0x7fffffff)
    return palette[index % palette.size]
}

@Composable
private fun SpeakerBadge(line: TranscriptLine) {
    val color = speakerColor(line)
    val initial = line.speakerName?.take(1) ?: if (line.maybeMe()) "?" else line.speakerTag?.toIntOrNull()?.plus(1)?.toString() ?: "?"
    Box(
        Modifier.padding(top = 1.dp).size(26.dp).clip(CircleShape).background(color.copy(alpha = 0.14f)),
        contentAlignment = Alignment.Center,
    ) { Text(initial, style = RsType.caption.copy(fontWeight = FontWeight.SemiBold), color = color) }
}

/** 转写中：两条呼吸的占位文字条。 */
@Composable
private fun Placeholder() {
    val c = Rs.colors
    val t = rememberInfiniteTransition(label = "placeholder")
    val a by t.animateFloat(0.35f, 0.8f, infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Reverse), label = "a")
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(1f, 0.62f).forEach { w ->
            Box(Modifier.fillMaxWidth(w).height(12.dp).clip(CircleShape).background(c.separator.copy(alpha = a)))
        }
    }
}
