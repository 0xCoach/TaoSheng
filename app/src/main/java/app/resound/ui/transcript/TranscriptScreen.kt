package app.resound.ui.transcript

import kotlinx.coroutines.flow.map
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.TextAutoSize
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
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.layout.BoxScope
import app.resound.ui.today.trackRow
import app.resound.ui.today.ReachHereChip
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.foundation.relocation.bringIntoViewRequester
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
    // 话都已记在语音输入那条里的录音段不显示
    val segments by remember(date) { repo.day(date).map { day -> day.filterNot { it.status == TranscriptStatus.Done && it.lines.isEmpty() } } }
        .collectAsState(initial = null)
    val hasKey by settings.ready.collectAsState()
    val realtime = (settings as? AndroidTranscribeSettings)?.realtime?.collectAsState()?.value == true
    val local = (settings as? AndroidTranscribeSettings)?.source?.collectAsState()?.value == TranscribeSource.Local
    var settingsOpen by remember { mutableStateOf(false) }
    // 多选（和首页同一套：长按进入、点选、选到这儿）；换一天就重置
    val selection = remember(date) { app.resound.ui.today.TimelineSelection() }
    val order = remember(segments) { segments.orEmpty().map { it.segmentId } }
    LaunchedEffect(order) { selection.retain(order); if (order.isEmpty() && selection.active) selection.exit() }
    androidx.activity.compose.BackHandler(selection.active) { selection.exit() }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    // 播放卡片“回到原话”：切到那天并滚到正在播的那段
    val focus = PlayerNav.transcriptFocus
    LaunchedEffect(focus) {
        val (d, _) = focus ?: return@LaunchedEffect
        dayOffset = java.time.temporal.ChronoUnit.DAYS.between(d, LocalDate.now()).toInt().coerceAtLeast(0)
    }
    LaunchedEffect(focus, segments) {
        val (d, segId) = focus ?: return@LaunchedEffect
        if (d != date) return@LaunchedEffect
        val i = segments?.indexOfFirst { it.segmentId == segId } ?: return@LaunchedEffect
        if (i >= 0) { listState.animateScrollToItem(i + 2 + (if (settingsOpen || !hasKey) 1 else 0)); PlayerNav.transcriptFocus = null }
    }
    var viewport by remember { mutableStateOf(androidx.compose.ui.geometry.Rect.Zero) }
    var boxTop by remember { mutableStateOf(0f) }
    var bottomEdge by remember { mutableStateOf(Float.MAX_VALUE) }
    var scrolledAt by remember { mutableIntStateOf(-1) }
    LaunchedEffect(listState.isScrollInProgress) { if (listState.isScrollInProgress) scrolledAt = selection.version }
    val reach = if (selection.active && !listState.isScrollInProgress && scrolledAt == selection.version)
        selection.reachTarget(order, viewport.top, minOf(viewport.bottom, bottomEdge)) else null
    val context = LocalContext.current
    val memory = if (app.resound.AppGraph.isMemoryReady) app.resound.AppGraph.memory else null
    val dayEvents by remember(date, memory) { memory?.events(date) ?: kotlinx.coroutines.flow.flowOf(emptyList()) }.collectAsState(initial = emptyList())
    var exporting by remember { mutableStateOf<Pair<List<Long>, String>?>(null) }
    var deleting by remember { mutableStateOf<List<Long>?>(null) }
    var retranscribing by remember { mutableStateOf<List<Long>?>(null) }
    retranscribing?.let { ids -> app.resound.ui.redo.RedoSheet(segmentIds = ids) { retranscribing = null } }
    val chosen = segments.orEmpty().filter { it.segmentId in selection.ids }.sortedBy { it.startAt }
    Box(Modifier.fillMaxSize().background(c.background).onGloballyPositioned { boxTop = it.boundsInRoot().top }) {
    LazyColumn(
        Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).onGloballyPositioned { viewport = it.boundsInRoot() },
        state = listState,
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = bottomPadding + 24.dp + if (selection.active) 120.dp else 0.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        item {
            QueueCard(repo, hasKey, realtime, local, settingsOpen || !hasKey, footer = {
                if (app.resound.AppGraph.isUsageReady) app.resound.ui.settings.AsrUsageRow(app.resound.AppGraph.usage)
                if (app.resound.AppGraph.isOriginalAudioReady) OriginalAudioRow(app.resound.AppGraph.originalAudio)
                RecordingPackRow()
            }) { settingsOpen = !settingsOpen }
        }
        // 收起时不占位置（用列表自带的出现/消失动画）
        if (settingsOpen || !hasKey) item(key = "settings") { SettingsCard(settings, onSaved = { settingsOpen = false }) }
        item {
            val list = segments.orEmpty()
            Section("录音原话", trailing = {
                if (list.isNotEmpty()) {
                    val done = list.count { it.status == TranscriptStatus.Done || it.status == TranscriptStatus.Empty }
                    Text("${list.size} 段 · 已转 $done", style = RsType.caption, color = c.textTertiary)
                }
            }) { DaySwitcher(dayOffset) { dayOffset = it } }
        }
        // 环境音识别：这天被跳过 / 可跳过 / 疑似影视的录音，点开试听、找回
        item(key = "env-$date") { app.resound.ui.envsound.EnvFlaggedRow(date) }
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
          androidx.compose.runtime.CompositionLocalProvider(LocalPlaySource provides PlaySource.Transcript(date)) {
            val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
            val id = seg.segmentId
            SegmentCard(seg, Modifier.animateItem().then(if (selection.active) Modifier.trackRow(selection, id) else Modifier),
                realtime, onTranscribeAnyway = { repo.transcribeAnyway(id) },
                onRetry = { repo.retrySegment(id) }, onDelete = { repo.deleteFailed(id) },
                selected = if (selection.active) id in selection.ids else null,
                onLongPress = {
                    haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                    if (selection.active) selection.rangeTo(id, order) else selection.start(id)
                },
                onTap = { selection.toggle(id, order) })
          }
        }
    }
    ReachHereChip(reach, selection, boxTop, reach != null && order.indexOf(reach) < order.indexOf(selection.anchor)) { selection.rangeTo(it, order) }
    // 底部两行：✕ · 已选 N 段 · 共几分钟 · 全选 / 播放 · 导出 · 复制原话 · 删除
    SegmentSelectBar(selection, order, chosen, bottomPadding, onTop = { bottomEdge = it },
        onRetranscribe = { retranscribing = chosen.map { it.segmentId }; selection.exit() },
        onPlay = {
            val lines = chosen.flatMap { it.lines }.sortedBy { it.startAt }
            if (lines.isEmpty()) LinePlayer.notice = "选中的录音没有可播放的原话" else LinePlayer.play(context, lines, 0, PlaySource.Transcript(date))
        },
        onExport = {
            val ids = chosen.map { it.segmentId }
            exporting = ids to exportTitle(dayEvents, chosen.minOf { it.startAt }, chosen.maxOf { it.endAt })
        },
        onCopy = {
            val text = chosen.flatMap { it.lines }.sortedBy { it.startAt }.joinToString("\n") { l -> "${lineTime(l.startAt)} ${speakerLabel(l)}：${l.text}" }
            (context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                .setPrimaryClip(android.content.ClipData.newPlainText("原话", text))
            Toast.makeText(context, "已复制 ${chosen.size} 段原话", Toast.LENGTH_SHORT).show()
            selection.exit()
        },
        onDelete = { deleting = chosen.map { it.segmentId } })
    }
    exporting?.let { (ids, title) -> ExportSheet(ids, title) { exporting = null; selection.exit() } }
    deleting?.let { ids -> DeleteSegmentsSheet(ids.size, onConfirm = {
        if (app.resound.AppGraph.isSegmentDeleterReady) {
            val n = runCatching { app.resound.AppGraph.segmentDeleter.deleteSegments(ids) }.getOrDefault(0)
            Toast.makeText(context, "已删除 $n 段", Toast.LENGTH_SHORT).show()
        }
        deleting = null; selection.exit()
    }) { deleting = null } }
}

// ---------- 队列状态 ----------

@Composable
private fun QueueCard(repo: TranscriptRepository, hasKey: Boolean, realtime: Boolean, local: Boolean, settingsShown: Boolean,
                      footer: @Composable () -> Unit = {}, onToggleSettings: () -> Unit) {
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
                if (settingsShown) "收起设置" else "模型和转写设置",
                style = RsType.subhead,
                color = c.accent,
                modifier = Modifier.springClick(onToggleSettings).padding(4.dp),
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            queueDetail(q, hasKey, realtime, now),
            style = RsType.subhead,
            color = if (hasKey && q.lastError != null && !q.running) c.critical else c.textSecondary,
            modifier = Modifier.padding(start = 17.dp),
        )
        AnimatedVisibility(q.running) {
            ProgressSweep(Modifier.fillMaxWidth().padding(top = 14.dp).height(4.dp))
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
                if (q.failed > 0 && !q.running) {
                    Pill("重试没转成功的", filled = false) { repo.retryFailed() }
                    // 暂时不想管：忽略后不再提醒，列表里灰色显示，以后仍可重试
                    Pill("忽略", filled = false) { repo.ignoreFailed() }
                }
                app.resound.ui.importer.ImportButton()
            }
        }
        // 下面接着两行设置：云端用量、原声（各自点开看详情）
        footer()
    }
}

private fun queueDetail(q: TranscribeQueueState, hasKey: Boolean, realtime: Boolean, now: Long): String = when {
    !hasKey -> "请配置云端服务，或下载本地模型后开始转写"
    q.running -> "逐段识别中，可以离开这个页面"
    q.lastError != null -> q.lastError
    else -> buildString {
        // 实时转写没有“下一轮”：录完就转
        if (realtime) append("录完就转")
        q.lastRunAt?.let { if (isNotEmpty()) append(" · "); append("上次 ${app.resound.ui.verify.formatStamp(it)}") }
        if (!realtime) q.nextRunAt?.let {
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
        val realtime = advanced?.realtime?.collectAsState()?.value
        IntervalChoice(interval, realtime, { advanced?.setRealtime(it) }) { settings.setIntervalMinutes(it) }
        Spacer(Modifier.height(6.dp))
        Text(when {
            realtime == true && local -> "录完一段就转。电量低于 20% 或手机发热时先攒着，每 30 分钟补转一次。"
            realtime == true -> "录完一段就上传转写，更及时，也更耗电和流量。电量低或手机发热时改为每 30 分钟一次。"
            else -> "每 $interval 分钟把攒下的录音转写一次。整理成事件另在「整理模型」里设置。"
        }, style = RsType.caption, color = c.textTertiary)

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
    Text("录音不出手机。按下面“多久转写一次”识别；说话期间模型常驻，安静 6 分钟后释放内存。", style=RsType.caption,color=c.textSecondary)
    Spacer(Modifier.height(10.dp))
    // 不支持 NPU 的手机不给选项，免得看到了却用不了
    if(supported) Choice(LocalBackend.entries.toList(),backend,{it.title}){settings.setBackend(it)}
    Text(if(supported) "您的手机支持 NPU 识别，更快、更省电；不成功时自动改用 CPU。" else "您的手机暂不支持 NPU 识别，本机识别默认使用 CPU。",style=RsType.caption,color=c.textSecondary)
    Text(actual,style=RsType.caption,color=c.accent)
    val context=androidx.compose.ui.platform.LocalContext.current
    LocalBackend.entries.filter { it==LocalBackend.Cpu || supported }.forEach { mode ->
        val downloaded=remember(revision,mode){settings.modelReady(mode)}
        val work by remember(mode){LocalModels.downloadActive(context,LocalModels.workName(mode))}.collectAsState(null)
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment=Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("${mode.title}模型 · 下载约 ${LocalModels.spec(mode).archiveBytes/1_000_000} MB",style=RsType.subhead,color=c.text)
                Text(if(downloaded) "已下载并校验" else downloadLine(settings.downloadStatus(mode),work),style=RsType.caption,color=c.textSecondary)
            }
            // 有任务在跑或排队时也给“重试”：换新任务从断点接着下，不会被卡住的旧任务挡住
            val active=work!=null
            if(!downloaded) Pill(if(active || settings.downloadStatus(mode)!="未下载") "重试" else "下载",filled=false){
                settings.download(mode,retry=active)
            }
        }
    }
    Text(if(supported) "从官方 GitHub 下载，建议连 WiFi。CPU 模型作备用，NPU 识别不可用时自动改用。" else "从官方 GitHub 下载，建议连 WiFi。",style=RsType.caption,color=c.textTertiary)
}

/** 进度文字只在任务真的在跑时才可信；排队时说明在等什么，没任务时把过时的“下载 45%”改成可重试 */
internal fun downloadLine(status: String, work: androidx.work.WorkInfo.State?): String = when (work) {
    androidx.work.WorkInfo.State.RUNNING -> status
    androidx.work.WorkInfo.State.ENQUEUED -> if (status.startsWith("下载中断")) status else "排队中：等网络连上后开始，或点重试"
    androidx.work.WorkInfo.State.BLOCKED -> "排队中"
    else -> if (status.startsWith("下载 ") || status.startsWith("连接") || status.startsWith("校验") || status.startsWith("解压"))
        "下载中断（${status.substringAfter("下载 ").substringBefore(" ·")}），点重试接着下" else status
}

@Composable
private fun Divider() {
    Box(Modifier.padding(vertical = 16.dp).fillMaxWidth().height(0.5.dp).background(Rs.colors.separator))
}

/** 胶囊分段选择。 */
@Composable
private fun <T> Choice(options: List<T>, selected: T, label: (T) -> String, fill: Boolean = false, onSelect: (T) -> Unit) {
    val c = Rs.colors
    // fill：选项多时平分整行、文字放不下自动缩小，不会挤出屏幕或折成竖排
    Row(Modifier.then(if (fill) Modifier.fillMaxWidth() else Modifier).clip(CircleShape)
        .background(c.separator.copy(alpha = if (c.isDark) 1f else 0.7f)).padding(3.dp)) {
        options.forEach { option ->
            val on = option == selected
            val bg by animateColorAsState(if (on) c.card else c.card.copy(alpha = 0f), spring(stiffness = Spring.StiffnessMediumLow), label = "choice")
            Box(Modifier.then(if (fill) Modifier.weight(1f) else Modifier).clip(CircleShape).background(bg).springClick { onSelect(option) }
                .padding(horizontal = if (fill) 4.dp else 16.dp, vertical = 7.dp), contentAlignment = Alignment.Center) {
                if (fill) Text(label(option), style = RsType.subhead, color = if (on) c.text else c.textSecondary, maxLines = 1, softWrap = false,
                    autoSize = TextAutoSize.StepBased(minFontSize = 10.sp, maxFontSize = RsType.subhead.fontSize))
                else Text(label(option), style = RsType.subhead, color = if (on) c.text else c.textSecondary)
            }
        }
    }
}

/**
 * 常用间隔或“自定义”填分钟数（15～720，安卓后台定时最短 15 分钟）。
 * 给了 [realtime] 时是转写用：实时 / 15 / 30 / 自定义；否则是整理用：15 / 30 / 60 / 自定义。
 */
@Composable
internal fun IntervalChoice(interval: Int, realtime: Boolean? = null, onRealtime: (Boolean) -> Unit = {}, onSet: (Int) -> Unit) {
    val c = Rs.colors
    // 转写：实时 / 15 / 30 / 自定义；整理：15 / 30 / 60 / 自定义。都是四个，一行放得下
    val presets = if (realtime != null) listOf(15, 30) else listOf(15, 30, 60)
    var custom by remember { mutableStateOf(interval !in presets) }
    var input by remember(interval) { mutableStateOf(if (interval in presets) "" else interval.toString()) }
    val value = input.toIntOrNull()
    val valid = value != null && value in MIN_INTERVAL..MAX_INTERVAL
    val options = (if (realtime != null) listOf(REALTIME) else emptyList()) + presets + CUSTOM
    val selected = when { realtime == true -> REALTIME; custom -> CUSTOM; else -> interval }
    Choice(options, selected, { when (it) { REALTIME -> "实时"; CUSTOM -> "自定义"; else -> "$it 分钟" } }, fill = true) {
        when (it) {
            REALTIME -> { custom = false; onRealtime(true) }
            CUSTOM -> { custom = true; if (realtime == true) onRealtime(false) }
            else -> { custom = false; if (realtime == true) onRealtime(false); onSet(it) }
        }
    }
    AnimatedVisibility(custom && realtime != true) {
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
private const val REALTIME = -2

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
private fun SegmentCard(seg: SegmentTranscript, modifier: Modifier, realtime: Boolean, onTranscribeAnyway: () -> Unit,
                        onRetry: () -> Unit = {}, onDelete: () -> Unit = {},
                        /** 多选时是否选中；不在多选为 null */
                        selected: Boolean? = null, onLongPress: () -> Unit = {}, onTap: () -> Unit = {}) {
    val c = Rs.colors
    val bg by animateColorAsState(if (selected == true) c.accentSoft else c.card, label = "segSel")
    // 长按整张卡进入多选；多选时点卡片勾选（原话里的点按播放等暂停）
    Box(modifier) {
    RsCard(Modifier.animateContentSize().combinedClickable(
        interactionSource = remember { MutableInteractionSource() }, indication = null,
        onClick = {}, onLongClick = onLongPress,
    ), padding = PaddingValues(horizontal = 18.dp, vertical = 16.dp), color = bg) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AnimatedVisibility(selected != null) {
                Box(Modifier.padding(end = 10.dp)) { app.resound.ui.today.SelectCircle(selected == true) }
            }
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
                TranscriptStatus.Pending -> Note(if (realtime) "马上转写" else "等下一轮转写")
                TranscriptStatus.Empty -> Note("只有声音，没识别出说话内容")
                TranscriptStatus.Failed -> Note(seg.error ?: "没转成功，可以在上面点重试", c.critical)
                TranscriptStatus.SkippedPlayback -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Note("手机外放声音时录的，没有转写", modifier = Modifier.weight(1f))
                    if (seg.audioAvailable) Pill("仍然转写", filled = false, onClick = onTranscribeAnyway)
                }
                // 修不好的：灰色说明 + 删除；不再挂红字提醒
                TranscriptStatus.Abandoned -> FailedActions(seg.error ?: "录音损坏，无法转写", retry = null, onDelete = onDelete)
                // 忽略的、或失败超过 3 天的：灰色说明 + 重试 / 删除
                TranscriptStatus.Quiet -> FailedActions(seg.error ?: "多次转写失败", retry = if (seg.audioAvailable) onRetry else null, onDelete = onDelete)
            }
        }
    }
    // 多选时整张卡盖一层：点 = 勾选，长按 = 选到这段（原话里的点按播放暂停）
    if (selected != null) Box(Modifier.matchParentSize().combinedClickable(
        interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onTap, onLongClick = onLongPress))
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
        TranscriptStatus.Abandoned -> "无法转写" to c.textTertiary
        TranscriptStatus.Quiet -> "已忽略" to c.textTertiary
    }
    Box(Modifier.clip(CircleShape).background(color.copy(alpha = 0.12f)).padding(horizontal = 10.dp, vertical = 3.dp)) {
        Text(text, style = RsType.caption, color = color)
    }
}

/** 没转成功、不再提醒的段：灰色原因，右边“重试”“删除”；删除点两次才删 */
@Composable
private fun FailedActions(reason: String, retry: (() -> Unit)?, onDelete: () -> Unit) {
    val c = Rs.colors
    var armed by remember { mutableStateOf(false) }
    LaunchedEffect(armed) { if (armed) { delay(3000); armed = false } }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Note(reason, modifier = Modifier.weight(1f))
        if (retry != null) { Pill("重试", filled = false, onClick = retry); Spacer(Modifier.width(8.dp)) }
        Text(if (armed) "确认删除" else "删除", style = RsType.subhead.copy(fontWeight = FontWeight.Medium),
            color = if (armed) Color.White else c.critical,
            modifier = Modifier.clip(CircleShape).background(if (armed) c.critical else c.critical.copy(alpha = 0.08f))
                .springClick { if (armed) onDelete() else armed = true }.padding(horizontal = 14.dp, vertical = 8.dp))
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
    var hotwordFrom by remember { mutableStateOf<String?>(null) }
    val playSource = LocalPlaySource.current
    // 哪些录音段还有原声：有的在时间旁显示 ▶，点句子就从这句播起
    val playable by androidx.compose.runtime.produceState(emptySet<Long>(), lines) {
        value = if (app.resound.AppGraph.isOriginalAudioReady) runCatching {
            app.resound.AppGraph.originalAudio.available(lines.map { it.segmentId }.filter { it > 0 }.toSet())
        }.getOrDefault(emptySet()) else emptySet()
    }
    fun tap(line: TranscriptLine) {
        when {
            LinePlayer.currentLineId == line.id -> LinePlayer.toggle()
            line.segmentId in playable -> LinePlayer.play(context, lines, lines.indexOf(line), playSource)
            line.segmentId > 0 -> Toast.makeText(context, "这段录音已过保留期，只剩文字", Toast.LENGTH_SHORT).show()
        }
    }
    hotwordFrom?.let { text -> AddHotwordSheet(text) { hotwordFrom = null } }
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
                            if (first.segmentId in playable) {
                                Spacer(Modifier.width(6.dp))
                                Text("▶", style = RsType.caption, color = c.accent)
                            }
                            first.voiceInput?.let { v ->
                                Spacer(Modifier.width(6.dp))
                                Text("⌨ 语音输入 · ${v.app}" + (v.title?.let { " · $it" } ?: ""), style = RsType.caption.copy(fontWeight = FontWeight.Medium),
                                    color = c.accent, maxLines = 1, modifier = Modifier.clip(CircleShape).background(c.accentSoft).padding(horizontal = 8.dp, vertical = 1.dp))
                            }
                        }
                        group.forEach { line ->
                            // 正在播的那句高亮，并滚到能看见的位置
                            val now = LinePlayer.currentLineId == line.id
                            val bg by androidx.compose.animation.animateColorAsState(if (now) c.accentSoft else Color.Transparent, label = "playing")
                            val seen = remember(line.id) { androidx.compose.foundation.relocation.BringIntoViewRequester() }
                            androidx.compose.runtime.LaunchedEffect(now) { if (now) runCatching { seen.bringIntoView() } }
                            Text(line.text, style = RsType.body, color = if (now) c.accent else c.text, modifier = Modifier.fillMaxWidth()
                                .bringIntoViewRequester(seen).clip(RoundedCornerShape(8.dp)).background(bg).combinedClickable(
                                interactionSource = remember(line.id) { MutableInteractionSource() }, indication = null,
                                onClick = { tap(line) }, onLongClick = { menuFor = line.id },
                            ).padding(vertical = 2.dp, horizontal = if (now) 6.dp else 0.dp))
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
                        if (selected.segmentId in playable) DropdownMenuItem(text = { Text("从这里播放", style = RsType.body, color = c.text) }, onClick = {
                            LinePlayer.play(context, lines, lines.indexOf(selected), playSource); menuFor = null
                        })
                        // 认错的词加进热词：以后转写认得准，整理时也会按热词纠正同音字
                        if (app.resound.AppGraph.isHotwordsReady) DropdownMenuItem(text = { Text("加入热词", style = RsType.body, color = c.text) }, onClick = {
                            hotwordFrom = selected.text; menuFor = null
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

/** 原话页多选的底部两行：✕ · 已选 N 段 · 共几分几秒 · 全选；播放 · 导出 · 复制原话 · 删除 */
@Composable
private fun BoxScope.SegmentSelectBar(selection: app.resound.ui.today.TimelineSelection, order: List<Long>, chosen: List<SegmentTranscript>, bottomPadding: androidx.compose.ui.unit.Dp,
                                      onTop: (Float) -> Unit, onPlay: () -> Unit, onExport: () -> Unit, onCopy: () -> Unit, onDelete: () -> Unit, onRetranscribe: () -> Unit = {}) {
    val c = Rs.colors
    AnimatedVisibility(selection.active, Modifier.align(Alignment.BottomCenter)) {
        val n = chosen.size
        val total = chosen.sumOf { it.endAt - it.startAt }
        val all = order.isNotEmpty() && order.all { it in selection.ids }
        Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = bottomPadding + 12.dp).fillMaxWidth()
            .shadow(if (c.isDark) 0.dp else 16.dp, RoundedCornerShape(26.dp), ambientColor = c.shadow, spotColor = c.shadow)
            .clip(RoundedCornerShape(26.dp)).background(c.card).onGloballyPositioned { onTop(it.boundsInRoot().top) }.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("✕", style = RsType.headline, color = c.textSecondary, modifier = Modifier.clip(CircleShape).springClick { selection.exit() }.padding(horizontal = 12.dp, vertical = 6.dp))
                Text(if (n == 0) "选择录音" else "已选 $n 段 · 共 ${formatDuration(total, seconds = true)}", style = RsType.body.copy(fontWeight = FontWeight.SemiBold),
                    color = c.text, maxLines = 1, modifier = Modifier.weight(1f))
                Text(if (all) "全不选" else "全选", style = RsType.body.copy(fontWeight = FontWeight.Medium), color = c.accent,
                    modifier = Modifier.clip(CircleShape).springClick { if (all) selection.removeAll(order) else selection.addAll(order) }.padding(horizontal = 12.dp, vertical = 6.dp))
            }
            Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BarAction("▶ 播放", c.accentSoft, c.accent, n > 0, Modifier.weight(1f), onPlay)
                BarAction("导出", c.accent, Color.White, n > 0, Modifier.weight(1f), onExport)
                BarAction("复制", c.background, c.text, n > 0, Modifier.weight(1f), onCopy)
                if (app.resound.AppGraph.isRedoReady) BarAction("重新转写", c.background, c.text, n > 0, Modifier.weight(1.4f), onRetranscribe)
                BarAction("删除", c.critical.copy(alpha = 0.1f), c.critical, n > 0, Modifier.weight(1f), onDelete)
            }
        }
    }
}

@Composable
private fun BarAction(text: String, bg: Color, fg: Color, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Box(modifier.clip(CircleShape).background(bg.copy(alpha = bg.alpha * if (enabled) 1f else 0.4f)).springClick { if (enabled) onClick() }
        .padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
        Text(text, style = RsType.subhead.copy(fontWeight = FontWeight.SemiBold), color = fg.copy(alpha = if (enabled) 1f else 0.4f), maxLines = 1)
    }
}

/** 批量删除录音确认：写清楚删什么、不删什么 */
@Composable
private fun DeleteSegmentsSheet(count: Int, onConfirm: suspend () -> Unit, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    app.resound.ui.verify.RsSheet("删除 $count 段录音？", "录音和原话会一起删除，整理出的事件不受影响；删除后无法恢复", onDismiss = { if (!busy) onDismiss() }) {
        app.resound.ui.verify.ActionButton(if (busy) "正在删除…" else "删除 $count 段", app.resound.ui.verify.ActionKind.Danger, enabled = !busy) {
            busy = true; scope.launch { onConfirm() }
        }
        app.resound.ui.verify.ActionButton("取消", app.resound.ui.verify.ActionKind.Secondary, enabled = !busy) { onDismiss() }
    }
}
