package app.resound.ui.today

import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.resound.AppGraph
import app.resound.PipelineProgress.Stage
import app.resound.capture.CaptureController
import app.resound.capture.CaptureState
import app.resound.digest.MemoryRepository
import app.resound.home.HomeCapture
import app.resound.home.HomeControls
import app.resound.home.HomeRules
import app.resound.home.NumberPart
import app.resound.transcribe.TranscriptRepository
import app.resound.ui.theme.Rs
import app.resound.ui.theme.RsType
import app.resound.ui.transcript.ProgressRing
import app.resound.ui.transcript.runLabel
import app.resound.ui.verify.BreathingWave
import app.resound.ui.verify.formatClock
import app.resound.ui.verify.springClick
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** 主卡用色：日间是暖橙渐变上的白字；夜间是深灰卡片 + 左上角一束淡淡暖光，橙色只做点缀 */
private class CardInk(
    val text: Color, val soft: Color, val faint: Color,
    val pill: Color, val pillText: Color,
    val primary: Color, val primaryText: Color,
    val wave: Color, val dot: Color, val divider: Color,
)

@Composable
private fun cardInk(): CardInk {
    val c = Rs.colors
    return if (c.isDark) CardInk(
        text = c.text, soft = c.textSecondary, faint = c.textTertiary,
        pill = c.elevated, pillText = c.text,
        primary = c.accent, primaryText = Color(0xFF1A0F07),
        wave = c.accent, dot = c.accent, divider = c.separator,
    ) else CardInk(
        text = Color.White, soft = Color.White.copy(alpha = 0.85f), faint = Color.White.copy(alpha = 0.7f),
        pill = Color.White.copy(alpha = 0.2f), pillText = Color.White,
        primary = Color.White, primaryText = Color(0xFFD2511F),
        wave = Color.White, dot = Color.White, divider = Color.White.copy(alpha = 0.3f),
    )
}

private fun homeCapture(state: CaptureState, inCall: Boolean): HomeCapture = when (state) {
    CaptureState.Listening, CaptureState.Recording -> HomeCapture.Active
    CaptureState.Paused -> HomeCapture.Paused
    CaptureState.MicBusy -> if (inCall) HomeCapture.InCall else HomeCapture.MicTaken
    CaptureState.Stopped -> HomeCapture.Stopped
    CaptureState.NoPermission -> HomeCapture.NoPermission
}

/**
 * 今日概览主卡（11-12 改版）：状态行即控制条（专题、暂停直接点，不加高卡片），
 * 专题进行中整张卡变成专题样式；三个统计数字大、单位小、间距相等；看前几天时换成那天的回顾样式。
 */
@Composable
internal fun OverviewCard(
    capture: CaptureController,
    memory: MemoryRepository,
    transcripts: TranscriptRepository,
    hasKey: Boolean,
    speechMillis: Long?,
    eventCount: Int?,
    pendingCount: Int,
    /** 看的不是今天时传那一天：卡片换成回顾样式 */
    pastDate: LocalDate?,
    onPending: () -> Unit,
    onPaused: (Int) -> Unit,
) {
    val c = Rs.colors
    val ink = cardInk()
    val shape = RoundedCornerShape(26.dp)
    val background = if (c.isDark) Modifier
        .clip(shape)
        .background(c.card)
        .background(Brush.radialGradient(listOf(c.accent.copy(alpha = 0.22f), c.accent.copy(alpha = 0.06f), Color.Transparent),
            center = Offset(0f, 0f), radius = 900f))
        .border(0.5.dp, c.hairline, shape)
    else Modifier
        .shadow(12.dp, shape, ambientColor = Color(0xFFD9531F), spotColor = Color(0xFFD9531F))
        .clip(shape)
        .background(Brush.linearGradient(listOf(Color(0xFFF2873F), Color(0xFFE2632A), Color(0xFFCF4A1E))))

    Column(Modifier.fillMaxWidth().then(background).padding(start = 18.dp, end = 18.dp, top = 14.dp, bottom = 12.dp).animateContentSize()) {
        if (pastDate != null) {
            PastDayCard(pastDate, speechMillis, eventCount, ink)
            return@Column
        }
        LiveCard(capture, memory, transcripts, hasKey, speechMillis, eventCount, pendingCount, ink, onPending, onPaused)
    }
}

@Composable
private fun PastDayCard(date: LocalDate, speechMillis: Long?, eventCount: Int?, ink: CardInk) {
    Row(Modifier.height(36.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(date.format(DateTimeFormatter.ofPattern("M月d日 EEEE", Locale.CHINA)) + " · 回顾", style = RsType.headline, color = ink.text)
    }
    Spacer(Modifier.height(6.dp))
    Row(Modifier.fillMaxWidth()) {
        Stat(speechMillis?.let { HomeRules.speechParts(it) } ?: listOf(NumberPart("—", false)), "人声", ink)
        Spacer(Modifier.width(36.dp))
        Stat(eventCount?.let { listOf(NumberPart("$it", false), NumberPart("件", true)) } ?: listOf(NumberPart("—", false)), "事件", ink)
    }
    Spacer(Modifier.height(4.dp))
}

@Composable
private fun ColumnScope.LiveCard(
    capture: CaptureController, memory: MemoryRepository, transcripts: TranscriptRepository, hasKey: Boolean,
    speechMillis: Long?, eventCount: Int?, pendingCount: Int, ink: CardInk, onPending: () -> Unit, onPaused: (Int) -> Unit,
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val status by capture.status.collectAsState()
    val digest by memory.state.collectAsState()
    val queue by transcripts.queue.collectAsState()
    val digestProgress by (if (AppGraph.isDigestProgressReady) AppGraph.digestProgress
        else remember { kotlinx.coroutines.flow.MutableStateFlow(app.resound.digest.DigestProgress()) }).collectAsState()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(digest.running) { while (true) { now = System.currentTimeMillis(); delay(if (digest.running) 1_000 else 20_000) } }
    val topic = if (AppGraph.isTopicsReady) AppGraph.topics.current.collectAsState().value else null
    val active = status.state == CaptureState.Listening || status.state == CaptureState.Recording
    val recording = status.state == CaptureState.Recording
    val inCall = status.busyDetail == app.resound.audio.MicYield.IN_CALL
    val controls = HomeRules.controls(homeCapture(status.state, inCall), topic != null)
    val lastPause = if (AppGraph.isQuickPrefsReady) AppGraph.quickPrefs.lastPauseMinutes.collectAsState().value else 30

    var listenOpen by remember { mutableStateOf(false) }
    val forgetRequested by app.resound.ui.ShellNav.forgetRecent.collectAsState()
    if (listenOpen || forgetRequested) ListenSheet(capture, startOnForget = forgetRequested) {
        listenOpen = false; app.resound.ui.ShellNav.forgetRecent.value = false
    }

    // ---------- 状态行：左边状态，右边按钮；声波淡淡铺在两者之间 ----------
    val stateText = when {
        topic != null -> app.resound.ui.topic.topicHomeTitle(topic, now)
        else -> when (status.state) {
            CaptureState.Recording -> "正在录音"
            CaptureState.Listening -> "正在聆听"
            CaptureState.Paused -> (status.quietName?.let { "${it}中" } ?: "已暂停") +
                (app.resound.ui.verify.resumeAt(status.pausedUntil)?.let { " · ${app.resound.ui.verify.formatUpcoming(it)} 恢复" } ?: "")
            CaptureState.MicBusy -> if (inCall) "通话中，暂停收音" else "麦克风被别的应用占用"
            CaptureState.Stopped -> "没在聆听"
            CaptureState.NoPermission -> "缺少权限"
        }
    }
    val waveAlpha = if (Rs.colors.isDark) 0.55f else 0.7f
    Box(Modifier.fillMaxWidth().height(36.dp)) {
        // 专题进行中不放声波（那一行按钮多）；平时声波两头淡出，不压字和按钮
        if (topic == null && controls != HomeControls.None) BreathingWave(status.level, active, recording,
            Modifier.matchParentSize().padding(vertical = 7.dp)
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen; alpha = waveAlpha }
                .drawWithContent {
                    drawContent()
                    drawRect(Brush.horizontalGradient(0.30f to Color.Transparent, 0.48f to Color.Black, 0.58f to Color.Black, 0.72f to Color.Transparent),
                        blendMode = BlendMode.DstIn)
                }, tint = ink.wave)
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f, fill = false).springClick {
                if (topic != null) app.resound.ui.topic.TopicUi.openTopic = topic.id else listenOpen = true
            }, verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(if (active || topic != null) ink.dot else ink.dot.copy(alpha = 0.45f)))
                Spacer(Modifier.width(8.dp))
                Text(stateText, style = RsType.headline, color = ink.text, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                    autoSize = TextAutoSize.StepBased(minFontSize = 13.sp, maxFontSize = RsType.headline.fontSize))
                Text(" ›", style = RsType.headline, color = ink.faint)
            }
            Spacer(Modifier.weight(1f).widthIn(min = 8.dp))
            fun buzz() = haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            when (controls) {
                HomeControls.TopicAndPause, HomeControls.TopicAndResume -> {
                    CardPill("◉ 专题", ink) { buzz(); app.resound.ui.topic.TopicUi.starting =
                        if (AppGraph.isQuickPrefsReady) AppGraph.quickPrefs.lastTopicKind.value else app.resound.topic.TopicKind.Meeting }
                    Spacer(Modifier.width(8.dp))
                    if (controls == HomeControls.TopicAndPause) RoundKey(ink, filled = false, glyph = Glyph.Pause, label = "暂停") {
                        buzz(); capture.pause(lastPause); onPaused(lastPause)
                    } else RoundKey(ink, filled = true, glyph = Glyph.Play, label = "现在恢复") { buzz(); capture.resume() }
                }
                HomeControls.StartListening -> CardPill("开始聆听", ink, filled = true) { buzz(); capture.start() }
                HomeControls.GrantPermission -> CardPill("去开启", ink, filled = true) {
                    app.resound.ui.ShellNav.openSettings(app.resound.ui.settings.SettingsPage.Capture)
                }
                HomeControls.TopicRunning -> {
                    val t = topic!!
                    val paused = t.status == app.resound.topic.TopicStatus.Paused
                    var confirmEnd by remember(t.id) { mutableStateOf(false) }
                    LaunchedEffect(confirmEnd) { if (confirmEnd) { delay(4000); confirmEnd = false } }
                    RoundKey(ink, filled = false, glyph = Glyph.Star, label = "记一下", accent = true) {
                        buzz(); scope.launch { runCatching { AppGraph.topics.mark() }.onSuccess { Toast.makeText(context, "已记下这个时刻", Toast.LENGTH_SHORT).show() } }
                    }
                    Spacer(Modifier.width(8.dp))
                    RoundKey(ink, filled = false, glyph = if (paused) Glyph.Play else Glyph.Pause, label = if (paused) "继续专题" else "暂停专题") {
                        buzz(); scope.launch { runCatching { if (paused) AppGraph.topics.resume() else AppGraph.topics.pause() } }
                    }
                    Spacer(Modifier.width(8.dp))
                    CardPill(if (confirmEnd) "确定结束？" else "结束", ink, danger = confirmEnd) {
                        if (!confirmEnd) { confirmEnd = true; return@CardPill }
                        buzz(); scope.launch {
                            runCatching { AppGraph.topics.end() }.onSuccess { Toast.makeText(context, "专题已结束，转写好后自动出纪要", Toast.LENGTH_SHORT).show() }
                        }
                    }
                }
                HomeControls.None -> {}
            }
        }
    }
    // 专题副行 / 麦克风被谁占
    val subLine = when {
        topic != null -> app.resound.ui.topic.topicHomeSub(topic).ifEmpty { null }
        status.state == CaptureState.MicBusy && !inCall -> status.busyDetail
        else -> null
    }
    subLine?.let { Text(it, style = RsType.caption, color = ink.soft, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 16.dp, top = 1.dp)) }
    // 旧版开启的课堂模式（升级前开的）仍提醒一句，点开聆听面板
    if (AppGraph.isAsrSpeedReady) {
        val classMode by AppGraph.asrSpeed.classMode.collectAsState()
        if (classMode.enabled) Row(Modifier.padding(top = 6.dp).clip(CircleShape).background(ink.pill)
            .springClick { listenOpen = true }.padding(horizontal = 10.dp, vertical = 3.dp)) {
            Text(app.resound.ui.transcript.classModeLine(classMode, now) + " ›", style = RsType.caption, color = ink.pillText, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }

    // ---------- 三个统计：数字大、单位小，三项间距相等（首项靠左、末项靠右） ----------
    Spacer(Modifier.height(10.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Stat(speechMillis?.let { HomeRules.speechParts(it) } ?: listOf(NumberPart("—", false)), "人声", ink)
        Stat(eventCount?.let { listOf(NumberPart("$it", false), NumberPart("件", true)) } ?: listOf(NumberPart("—", false)), "事件", ink)
        Stat(listOf(NumberPart("$pendingCount", false), NumberPart("条", true)), "待确认", ink,
            dim = pendingCount == 0, onClick = if (pendingCount > 0) onPending else null)
    }

    // ---------- 整理行：状态文字 + 现在整理 + 模型 ----------
    Spacer(Modifier.height(10.dp))
    Box(Modifier.fillMaxWidth().height(0.5.dp).background(ink.divider))
    Spacer(Modifier.height(8.dp))
    val timing = if (AppGraph.isDigestTimingReady) AppGraph.digestTiming else null
    val timedMode = timing?.mode?.collectAsState()?.value == app.resound.digest.DigestMode.TimePoints
    val nextAt = timing?.nextDigestAt?.collectAsState()?.value
    val nextDigest = if (!timedMode) null else nextAt?.let { at ->
        val day = java.time.Instant.ofEpochMilli(at).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
        "下次整理 " + (if (day != LocalDate.now()) "明天 " else "") + formatClock(at)
    } ?: "未设整理时间点"
    val pipeline by app.resound.PipelineProgress.state.collectAsState()
    val stage = pipeline.stage
    val busy = digest.running || queue.running || stage != null
    val toTranscribe = queue.pending + queue.failed
    val err = digest.lastError
    val quotaOrAuth = !digest.running && (err == app.resound.digest.LlmError.Quota.human || err == app.resound.digest.LlmError.Auth.human)
    val provider = if (AppGraph.isLlmProvidersReady) AppGraph.llmProviders.let { p ->
        val saved by p.saved.collectAsState(); val id by p.activeId.collectAsState(); saved.firstOrNull { it.id == id }
    } else null
    val model = HomeRules.modelButton(provider?.name, hasKey, quotaOrAuth)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            when {
                stage != null -> stageLine(pipeline, queue.runDone, queue.runTotal)
                digest.running -> "按已保存的原话计数"
                queue.running -> "正在转写录音…"
                toTranscribe > 0 -> "$toTranscribe 段录音待转写" + (queue.lastError?.let { " · $it" } ?: "")
                else -> digestLine(digest.running, digest.pendingLines, digest.lastRunAt, digest.lastError, hasKey, now, nextDigest)
            },
            style = RsType.caption, color = ink.soft, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
        if (hasKey) {
            Spacer(Modifier.width(8.dp))
            Row(
                Modifier.clip(CircleShape).background(ink.primary).springClick {
                    if (busy) Toast.makeText(context, "正在后台处理，请稍等", Toast.LENGTH_SHORT).show() else memory.runNow()
                }.padding(horizontal = 12.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (busy) {
                    val (done, total) = when {
                        stage == Stage.Digest || (stage == null && digest.running) -> digestProgress.done to digestProgress.total
                        stage == Stage.Transcribe && pipeline.total == 0 -> queue.runDone to queue.runTotal
                        stage == Stage.Queued || stage == Stage.Skills -> 0 to 0
                        stage != null -> pipeline.done to pipeline.total
                        else -> queue.runDone to queue.runTotal
                    }
                    ProgressRing(done, total, ink.primaryText, Modifier.size(12.dp))
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    when {
                        stage == Stage.Queued -> "排队中"
                        stage == Stage.Transcribe -> runLabel(if (pipeline.total > 0) pipeline.done else queue.runDone,
                            if (pipeline.total > 0) pipeline.total else queue.runTotal, "转写")
                        stage == Stage.Voiceprint -> runLabel(pipeline.done, pipeline.total, "认人")
                        stage == Stage.Skills -> "生成卡片"
                        stage == Stage.Digest -> digestProgress.label
                        queue.running -> runLabel(queue.runDone, queue.runTotal)
                        busy -> digestProgress.label
                        else -> "现在整理"
                    },
                    style = RsType.caption.copy(fontWeight = FontWeight.SemiBold), color = ink.primaryText,
                )
            }
        }
        Spacer(Modifier.width(6.dp))
        // 模型按钮：直接写当前服务商名，点了去整理模型页；没设模型时强调
        Box {
            Text(model.label + " ›", style = RsType.caption.copy(fontWeight = FontWeight.Medium),
                color = if (model.emphasize) ink.primaryText else ink.pillText, maxLines = 1,
                modifier = Modifier.clip(CircleShape).background(if (model.emphasize) ink.primary else ink.pill)
                    .springClick { app.resound.ui.ShellNav.openSettings(app.resound.ui.settings.SettingsPage.Model) }
                    .padding(horizontal = 12.dp, vertical = 5.dp))
            if (model.alertDot) Box(Modifier.align(Alignment.TopEnd).offset(x = 1.dp, y = (-1).dp).size(8.dp).clip(CircleShape)
                .background(Rs.colors.critical).border(1.5.dp, if (Rs.colors.isDark) Rs.colors.card else Color(0xFFE2632A), CircleShape))
        }
    }
    // 整理因余额不足 / 密钥无效失败时，直接给出处理入口（DeepSeek 直达充值页，其他去模型设置）
    if (quotaOrAuth) {
        val quota = err == app.resound.digest.LlmError.Quota.human
        val deepseek = provider?.templateId == "deepseek"
        Spacer(Modifier.height(8.dp))
        Text(if (quota) (if (deepseek) "去 DeepSeek 充值 ›" else "去模型设置看看 ›") else "检查整理模型的密钥 ›",
            style = RsType.caption.copy(fontWeight = FontWeight.SemiBold), color = ink.pillText,
            modifier = Modifier.clip(CircleShape).background(ink.pill).springClick {
                if (quota && deepseek) runCatching { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,
                    android.net.Uri.parse("https://platform.deepseek.com/top_up")).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
                else app.resound.ui.ShellNav.openSettings(app.resound.ui.settings.SettingsPage.Model)
            }.padding(horizontal = 12.dp, vertical = 5.dp))
    }
    if (digest.running) {
        Spacer(Modifier.height(6.dp))
        DigestElapsedText(digestProgress, ink.soft)
    } else if (stage != null) {
        Spacer(Modifier.height(6.dp))
        StageElapsedText(pipeline, ink.soft)
    } else if (digestProgress.completion != null) {
        Spacer(Modifier.height(6.dp))
        Text(digestProgress.completion!!, style = RsType.caption.copy(fontFeatureSettings = "tnum"), color = ink.soft)
    }
}

/** 一个统计：数字 26 号中粗、等宽数字，单位小一号淡一级；数字变化时向上滚动 */
@Composable
private fun Stat(parts: List<NumberPart>, label: String, ink: CardInk, dim: Boolean = false, onClick: (() -> Unit)? = null) {
    val alpha = if (dim) 0.5f else 1f
    Column(Modifier.then(if (onClick != null) Modifier.springClick(onClick) else Modifier).graphicsLayer { this.alpha = alpha }) {
        AnimatedContent(parts, transitionSpec = {
            (slideInVertically(tween(260)) { it / 2 } + fadeIn(tween(260))) togetherWith
                (slideOutVertically(tween(200)) { -it / 2 } + fadeOut(tween(200))) using SizeTransform(clip = false)
        }, label = "stat") { p ->
            Text(buildAnnotatedString {
                // 单位前留一点空，单位后（若还有数字）再留一点空：“2 小时 13 分”
                p.forEachIndexed { i, part ->
                    if (part.isUnit) withStyle(SpanStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium, color = ink.soft)) {
                        append("\u2009" + part.text + if (i < p.lastIndex) "\u2009" else "")
                    } else append(part.text)
                }
            },
                style = RsType.number.copy(fontSize = 26.sp, lineHeight = 30.sp, fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum"),
                color = ink.text, maxLines = 1)
        }
        Text(label + if (onClick != null) " ›" else "", style = RsType.caption, color = ink.soft, maxLines = 1)
    }
}

private enum class Glyph { Pause, Play, Star }

/** 圆形按键：画出来 34dp，可点区域按 44dp 留足 */
@Composable
private fun RoundKey(ink: CardInk, filled: Boolean, glyph: Glyph, label: String, accent: Boolean = false, onClick: () -> Unit) {
    val c = Rs.colors
    val bg = when {
        filled -> ink.primary
        accent && c.isDark -> c.accentSoft
        else -> ink.pill
    }
    val fg = when {
        filled -> ink.primaryText
        accent && c.isDark -> c.accent
        else -> ink.pillText
    }
    Box(Modifier.size(44.dp).springClick(onClick).semanticsLabel(label), contentAlignment = Alignment.Center) {
        Box(Modifier.size(34.dp).clip(CircleShape).background(bg), contentAlignment = Alignment.Center) {
            androidx.compose.foundation.Canvas(Modifier.size(12.dp)) {
                val w = size.width; val h = size.height
                when (glyph) {
                    Glyph.Pause -> {
                        drawRoundRect(fg, Offset(w * 0.12f, 0f), androidx.compose.ui.geometry.Size(w * 0.26f, h), androidx.compose.ui.geometry.CornerRadius(w * 0.08f))
                        drawRoundRect(fg, Offset(w * 0.62f, 0f), androidx.compose.ui.geometry.Size(w * 0.26f, h), androidx.compose.ui.geometry.CornerRadius(w * 0.08f))
                    }
                    Glyph.Play -> drawPath(androidx.compose.ui.graphics.Path().apply {
                        moveTo(w * 0.2f, 0f); lineTo(w, h / 2); lineTo(w * 0.2f, h); close()
                    }, fg)
                    Glyph.Star -> {
                        val path = androidx.compose.ui.graphics.Path()
                        for (i in 0 until 10) {
                            val r = if (i % 2 == 0) w * 0.55f else w * 0.24f
                            val a = Math.toRadians(-90.0 + i * 36.0)
                            val x = w / 2 + (r * kotlin.math.cos(a)).toFloat(); val y = h / 2 + (r * kotlin.math.sin(a)).toFloat()
                            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                        }
                        path.close(); drawPath(path, fg)
                    }
                }
            }
        }
    }
}

@Composable
private fun CardPill(text: String, ink: CardInk, filled: Boolean = false, danger: Boolean = false, onClick: () -> Unit) {
    val c = Rs.colors
    val bg by animateColorAsState(when { danger -> c.critical; filled -> ink.primary; else -> ink.pill }, label = "cardPill")
    Box(Modifier.heightIn(min = 44.dp).springClick(onClick), contentAlignment = Alignment.Center) {
        Text(text, style = RsType.subhead.copy(fontWeight = FontWeight.Medium),
            color = when { danger -> Color.White; filled -> ink.primaryText; else -> ink.pillText }, maxLines = 1,
            modifier = Modifier.clip(CircleShape).background(bg).padding(horizontal = 12.dp, vertical = 7.dp))
    }
}

private fun Modifier.semanticsLabel(label: String): Modifier = semantics { contentDescription = label }
