package app.resound.ui.verify

import app.resound.appDisplayName
import android.Manifest
import android.content.Intent
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Offset
import androidx.compose.animation.scaleOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.Canvas
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.compose.ui.res.stringResource
import app.resound.R
import app.resound.capture.CaptureController
import app.resound.capture.CaptureState
import app.resound.capture.CaptureStatsRepository
import app.resound.capture.DayStats
import app.resound.capture.SetupGuide
import app.resound.capture.SetupItem
import app.resound.capture.WatchdogEvent
import app.resound.capture.WatchdogEventType
import app.resound.ui.theme.Rs
import app.resound.ui.theme.RsType
import app.resound.ui.theme.ThemeMode
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** 验证版主页：录音状态、当天统计、守护记录、小米设置引导。 */
@Composable
fun VerifyScreen(
    capture: CaptureController,
    stats: CaptureStatsRepository,
    setup: SetupGuide,
    themeMode: ThemeMode,
    onThemeMode: (ThemeMode) -> Unit,
) {
    val c = Rs.colors
    var dayOffset by remember { mutableIntStateOf(0) }
    val date = remember(dayOffset) { LocalDate.now().minusDays(dayOffset.toLong()) }
    val day by remember(date) { stats.dayStats(date) }.collectAsState(initial = null)
    val events by remember(date) { stats.watchdogEvents(date) }.collectAsState(initial = emptyList())

    LazyColumn(
        Modifier.fillMaxSize().background(c.background).windowInsetsPadding(WindowInsets.safeDrawing),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 40.dp + app.resound.ui.ShellBottomPadding),
        verticalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        item { Header(themeMode, onThemeMode) }
        item { StatusCard(capture) }
        if (app.resound.AppGraph.isInputsReady) item { InputSection(app.resound.AppGraph.inputs) }
        if (app.resound.AppGraph.isVoiceInputReady) item { VoiceInputSection(app.resound.AppGraph.voiceInput) }
        item {
            Section("每日统计") {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    DaySwitcher(dayOffset) { dayOffset = it }
                    StatsGrid(day)
                }
            }
        }
        item { WatchdogSection(remember(events) { compactEvents(events.sortedByDescending { it.at }) }) }
        item { SetupSection(setup) }
        item { MicHolderSection() }
        item { ExportRow(stats) }
    }
}

@Composable
private fun Header(themeMode: ThemeMode, onThemeMode: (ThemeMode) -> Unit) {
    val c = Rs.colors
    val today = remember { LocalDate.now().format(DateTimeFormatter.ofPattern("M月d日 EEEE", Locale.CHINA)) }
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.Bottom) {
        Column(Modifier.weight(1f)) {
            Text("$today · 验证版", style = RsType.subhead, color = c.textSecondary)
            Spacer(Modifier.height(2.dp))
            Text(stringResource(R.string.app_name), style = RsType.largeTitle, color = c.text)
        }
        ThemeModePill(themeMode, onThemeMode)
    }
}

/** 日间 / 夜间 / 跟随系统 切换按钮（今天页和录音页共用）：太阳、月亮、半圆图标，点一下按顺序切换 */
@Composable
fun ThemeModePill(themeMode: ThemeMode, onThemeMode: (ThemeMode) -> Unit) {
    val c = Rs.colors
    Box(
        Modifier.size(40.dp).springClick {
            onThemeMode(
                when (themeMode) {
                    ThemeMode.System -> ThemeMode.Light
                    ThemeMode.Light -> ThemeMode.Dark
                    ThemeMode.Dark -> ThemeMode.System
                },
            )
        }.clip(CircleShape).background(c.accentSoft),
        contentAlignment = Alignment.Center,
    ) {
        AnimatedContent(
            themeMode,
            transitionSpec = { (fadeIn() + scaleIn(initialScale = 0.6f)) togetherWith (fadeOut() + scaleOut(targetScale = 0.6f)) },
            label = "themeIcon",
        ) { mode -> ThemeIcon(mode, c.accent, c.accentSoft, Modifier.size(20.dp)) }
    }
}

@Composable
private fun ThemeIcon(mode: ThemeMode, ink: Color, paper: Color, modifier: Modifier) {
    Canvas(modifier) {
        val r = size.minDimension / 2
        val center = this.center
        when (mode) {
            ThemeMode.Light -> {
                // 太阳：圆心 + 8 道光芒
                drawCircle(ink, r * 0.42f, center)
                repeat(8) { i ->
                    val a = Math.toRadians(i * 45.0)
                    val from = Offset(center.x + (r * 0.66f) * kotlin.math.cos(a).toFloat(), center.y + (r * 0.66f) * kotlin.math.sin(a).toFloat())
                    val to = Offset(center.x + r * 0.95f * kotlin.math.cos(a).toFloat(), center.y + r * 0.95f * kotlin.math.sin(a).toFloat())
                    drawLine(ink, from, to, strokeWidth = r * 0.16f, cap = StrokeCap.Round)
                }
            }
            ThemeMode.Dark -> {
                // 月亮：实心圆被底色圆咬掉一块
                drawCircle(ink, r * 0.85f, center)
                drawCircle(paper, r * 0.72f, Offset(center.x + r * 0.42f, center.y - r * 0.32f))
            }
            ThemeMode.System -> {
                // 跟随系统：左半实心、右半描边
                drawCircle(ink, r * 0.78f, center, style = Stroke(r * 0.16f))
                drawArc(ink, 90f, 180f, true, topLeft = Offset(center.x - r * 0.78f, center.y - r * 0.78f), size = Size(r * 1.56f, r * 1.56f))
            }
        }
    }
}

@Composable
private fun StatusCard(capture: CaptureController) {
    val c = Rs.colors
    val context = LocalContext.current
    val status by capture.status.collectAsState()
    val active = status.state == CaptureState.Listening || status.state == CaptureState.Recording
    val recording = status.state == CaptureState.Recording

    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }

    val permissions = remember {
        buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.toTypedArray()
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result[Manifest.permission.RECORD_AUDIO] == true) capture.start()
    }
    val startWithPermission = { launcher.launch(permissions) }

    RsCard(padding = PaddingValues(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val dotColor by animateColorAsState(
                when (status.state) {
                    CaptureState.Recording -> c.accent
                    CaptureState.Listening -> c.good
                    CaptureState.Paused, CaptureState.MicBusy -> c.warning
                    CaptureState.Stopped -> c.textTertiary
                    CaptureState.NoPermission -> c.critical
                },
                label = "dot",
            )
            Dot(dotColor, 9.dp)
            Spacer(Modifier.width(8.dp))
            AnimatedContent(
                targetState = stateTitle(status.state),
                transitionSpec = {
                    (slideInVertically { it / 2 } + fadeIn()) togetherWith (slideOutVertically { -it / 2 } + fadeOut())
                },
                label = "stateTitle",
            ) { Text(it, style = RsType.headline, color = c.text) }
            Spacer(Modifier.weight(1f))
            Text(status.sourceName, style = RsType.caption, color = c.textTertiary)
        }
        Spacer(Modifier.height(4.dp))
        Text(
            stateDetail(status.state, now - status.since, status.pausedUntil, status.busyDetail),
            style = RsType.subhead,
            color = c.textSecondary,
            modifier = Modifier.padding(start = 17.dp),
        )
        BreathingWave(
            level = status.level,
            active = active,
            recording = recording,
            modifier = Modifier.fillMaxWidth().height(84.dp).padding(vertical = 10.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            when (status.state) {
                CaptureState.Stopped, CaptureState.NoPermission ->
                    Pill("开始聆听", filled = true, onClick = startWithPermission)
                CaptureState.Paused -> {
                    Pill("现在恢复", filled = true) { capture.resume() }
                    Pill("停止", filled = false) { capture.stop() }
                }
                else -> {
                    Pill("暂停 1 小时", filled = false) { capture.pause(60) }
                    Pill("停止", filled = false) { capture.stop() }
                }
            }
        }
    }
}

private fun stateTitle(state: CaptureState) = when (state) {
    CaptureState.Stopped -> "未开启"
    CaptureState.Listening -> "正在聆听"
    CaptureState.Recording -> "正在录音"
    CaptureState.Paused -> "已暂停"
    CaptureState.MicBusy -> "麦克风被占用"
    CaptureState.NoPermission -> "缺少权限"
}

private fun stateDetail(state: CaptureState, elapsed: Long, pausedUntil: Long?, busyDetail: String?) = when (state) {
    CaptureState.Stopped -> "点“开始聆听”后，有人说话才会录"
    CaptureState.Listening -> "安静中 · 已持续 ${formatDuration(elapsed)}"
    CaptureState.Recording -> "检测到人声 · 本段 ${formatDuration(elapsed, seconds = true)}"
    CaptureState.Paused -> pausedUntil?.let { "将在 ${formatClock(it)} 自动恢复" } ?: "手动暂停中"
    CaptureState.MicBusy -> busyDetail?.let { "$it，结束后自动恢复" } ?: "通话或其他 App 在用麦克风，结束后自动恢复"
    CaptureState.NoPermission -> "需要麦克风和通知权限"
}

@Composable
/** compact：放在分区标题右侧时用小一号 */
fun DaySwitcher(selected: Int, compact: Boolean = false, onSelect: (Int) -> Unit) {
    val c = Rs.colors
    Row(
        Modifier.clip(CircleShape).background(c.separator.copy(alpha = if (c.isDark) 1f else 0.7f)).padding(3.dp),
    ) {
        listOf("今天", "昨天", "前天").forEachIndexed { i, label ->
            val on = i == selected
            val bg by animateColorAsState(if (on) c.card else c.card.copy(alpha = 0f), spring(stiffness = Spring.StiffnessMediumLow), label = "seg")
            Box(
                Modifier.clip(CircleShape).background(bg).springClick { onSelect(i) }.padding(horizontal = if (compact) 12.dp else 18.dp, vertical = if (compact) 4.dp else 7.dp),
            ) {
                Text(label, style = if (compact) RsType.caption else RsType.subhead, color = if (on) c.text else c.textSecondary)
            }
        }
    }
}

@Composable
private fun StatsGrid(day: DayStats?) {
    val c = Rs.colors
    val tiles = listOf(
        Triple("人声时长", day?.let { formatDuration(it.speechMillis) } ?: "—", null),
        Triple("录音片段", day?.let { "${it.segmentCount} 段" } ?: "—", null),
        Triple("服务在跑", day?.let { formatDuration(it.runningMillis) } ?: "—", null),
        Triple(
            "被关掉 / 已恢复",
            day?.let { "${it.killedCount} / ${it.recoveredCount}" } ?: "—",
            day?.let { if (it.killedCount > it.recoveredCount) c.critical else if (it.killedCount > 0) c.warning else c.good },
        ),
        Triple("漏录时长", day?.let { formatDuration(it.gapMillis) } ?: "—", day?.let { if (it.gapMillis > 0) c.warning else null }),
        Triple("${appDisplayName}自身耗电（估算）", day?.appPowerPerHour?.let { String.format(Locale.CHINA, "约 %.1f%%/时", it) } ?: "数据不足", null),
    )
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        tiles.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { (label, value, tint) -> StatTile(label, value, tint, Modifier.weight(1f)) }
            }
        }
        Text(
            "录音占用 ${day?.let { formatBytes(it.storageBytes) } ?: "—"}。自身耗电按 CPU 用时、麦克风开启时长和上传流量估算，不充电时段满 30 分钟才显示；同期整机耗电 ${day?.dischargePerHour?.let { String.format(Locale.CHINA, "%.1f%%/时", it) } ?: "—"}（含你用手机的全部消耗）。",
            style = RsType.caption,
            color = c.textTertiary,
            modifier = Modifier.padding(horizontal = 6.dp),
        )
    }
}

@Composable
private fun StatTile(label: String, value: String, tint: Color?, modifier: Modifier) {
    val c = Rs.colors
    RsCard(modifier, padding = PaddingValues(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (tint != null) {
                Dot(tint, 6.dp)
                Spacer(Modifier.width(6.dp))
            }
            Text(label, style = RsType.caption, color = c.textSecondary)
        }
        Spacer(Modifier.height(6.dp))
        AnimatedContent(value, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "value") {
            Text(it, style = RsType.number, color = c.text)
        }
    }
}

@Composable
private fun WatchdogSection(events: List<WatchdogEvent>) {
    val c = Rs.colors
    Section("守护记录") {
        RsCard(padding = PaddingValues(horizontal = 18.dp, vertical = 8.dp)) {
            if (events.isEmpty()) {
                Text(
                    "这一天还没有记录",
                    style = RsType.subhead,
                    color = c.textTertiary,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 18.dp),
                    textAlign = TextAlign.Center,
                )
            }
            // 记录会越积越多：这里只显示最近 8 条；全部记录放在可滑动的面板里，滑到哪里才渲染到哪里
            var sheetOpen by remember { mutableStateOf(false) }
            val shown = events.take(PREVIEW_EVENTS)
            shown.forEachIndexed { i, e -> EventRow(e, last = i == shown.lastIndex && events.size <= PREVIEW_EVENTS) }
            if (events.size > PREVIEW_EVENTS) {
                Text(
                    "查看全部 ${events.size} 条",
                    style = RsType.subhead,
                    color = c.accent,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().springClick { sheetOpen = true }.padding(vertical = 12.dp),
                )
            }
            if (sheetOpen) AllEventsSheet(events) { sheetOpen = false }
        }
    }
}

private const val PREVIEW_EVENTS = 8

/** 把底层记的原始说明翻译成人话；无意义的（如 “Busy: null”）不显示 */
private fun humanDetail(raw: String?): String? {
    if (raw == null || raw.matches(Regex("^\\w+: null$"))) return null
    val route = Regex("(启动|恢复)途径=([\\w-]+)").find(raw)?.groupValues?.get(2)
    if (route != null) return when (route) {
        "controller" -> "在 App 里开启"
        "system-sticky" -> "系统自动重新拉起"
        "notification" -> "从通知栏恢复"
        "pause-timer", "alarm" -> "暂停到点自动恢复"
        "watchdog" -> "定时检查发现后拉起"
        else -> null
    }
    if (raw.contains("PACKAGE_REPLACED") || raw.contains("BOOT_COMPLETED")) return "安卓不允许后台自己开麦克风，打开${appDisplayName}或点通知即可恢复"
    Regex("约 (\\d+)ms").find(raw)?.let { m ->
        val sec = m.groupValues[1].toLong() / 1000
        return "中断约 " + if (sec >= 60) "${sec / 60} 分 ${sec % 60} 秒" else "$sec 秒"
    }
    return raw
}

/**
 * 把连续的“麦克风被占用 / 释放”合并成一条（按时间倒序的列表），
 * 例如“麦克风让给别的应用 · 13:32–14:14 共 9 次”，避免几百条刷屏。
 */
private fun compactEvents(events: List<WatchdogEvent>): List<WatchdogEvent> {
    val micTypes = setOf(WatchdogEventType.MicBusy, WatchdogEventType.MicReleased)
    val out = mutableListOf<WatchdogEvent>()
    var i = 0
    while (i < events.size) {
        val e = events[i]
        if (e.type !in micTypes) { out += e; i++; continue }
        var j = i
        while (j < events.size && events[j].type in micTypes) j++
        val run = events.subList(i, j)
        val times = run.count { it.type == WatchdogEventType.MicBusy }
        if (run.size <= 2) out += run
        else out += run.first().copy(
            type = WatchdogEventType.MicBusy,
            detail = "${formatClock(run.last().at)}–${formatClock(run.first().at)} 共 $times 次（通话、语音输入等占用时自动让出）",
        )
        i = j
    }
    return out
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AllEventsSheet(events: List<WatchdogEvent>, onClose: () -> Unit) {
    val c = Rs.colors
    ModalBottomSheet(
        onDismissRequest = onClose,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = c.background,
        dragHandle = { Box(Modifier.padding(vertical = 10.dp).size(width = 36.dp, height = 5.dp).clip(CircleShape).background(c.separator)) },
    ) {
        Text(
            "守护记录 · ${events.size} 条",
            style = RsType.headline,
            color = c.text,
            modifier = Modifier.padding(start = 24.dp, bottom = 12.dp),
        )
        // 整张圆角卡片里滚动；只渲染屏幕上看得到的几行
        LazyColumn(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp).clip(RoundedCornerShape(22.dp)).background(c.card),
            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 6.dp),
        ) {
            itemsIndexed(events, key = { i, e -> "${e.at}-${e.type}-$i" }) { i, e ->
                EventRow(e, last = i == events.lastIndex)
            }
        }
        Spacer(Modifier.height(28.dp))
    }
}

@Composable
private fun EventRow(e: WatchdogEvent, last: Boolean) {
    val c = Rs.colors
    val (title, color) = when (e.type) {
        WatchdogEventType.ServiceStarted -> "开始聆听" to c.good
        WatchdogEventType.ServiceStopped -> "停止" to c.textTertiary
        WatchdogEventType.Killed -> "被系统关掉" to c.critical
        WatchdogEventType.Recovered -> "自动恢复" to c.good
        WatchdogEventType.Paused -> "暂停" to c.warning
        WatchdogEventType.Resumed -> "恢复" to c.good
        WatchdogEventType.MicBusy -> "麦克风让给别的应用" to c.warning
        WatchdogEventType.MicReleased -> "收回麦克风" to c.good
        WatchdogEventType.InputSwitched -> "录音输入切换" to c.accent
        WatchdogEventType.BootRestart ->
            (if (e.detail?.contains("PACKAGE_REPLACED") == true) "App 升级后待恢复" else "开机后待恢复") to c.warning
    }
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.Top) {
        Text(formatClock(e.at), style = RsType.subhead, color = c.textSecondary, modifier = Modifier.width(48.dp))
        Box(Modifier.padding(top = 5.dp, end = 12.dp)) { Dot(color, 8.dp) }
        Column(Modifier.weight(1f)) {
            Text(title, style = RsType.body, color = c.text)
            // 旧版本留下的 “Busy: null” 这类原始文字不显示
            humanDetail(e.detail)?.let { Text(it, style = RsType.caption, color = c.textSecondary) }
        }
        e.batteryPercent?.let {
            Text("$it%" + if (e.charging == true) " ⚡" else "", style = RsType.caption, color = c.textTertiary)
        }
    }
    if (!last) Box(Modifier.fillMaxWidth().padding(start = 68.dp).height(0.5.dp).background(c.separator))
}

@Composable
private fun SetupSection(setup: SetupGuide) {
    val c = Rs.colors
    var version by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { version++ }
    val items = remember(version) { setup.items() }
    val doneCount = items.count { it.done == true }
    Section("小米后台设置", trailing = {
        Text("$doneCount / ${items.size} 已完成", style = RsType.caption, color = if (doneCount == items.size) c.good else c.accent)
    }) {
        RsCard(padding = PaddingValues(horizontal = 18.dp, vertical = 6.dp)) {
            items.forEachIndexed { i, item ->
                SetupRow(item, onOpen = { setup.open(item.key); version++ }, onDone = { setup.markDone(item.key); version++ })
                if (i != items.lastIndex) Box(Modifier.fillMaxWidth().height(0.5.dp).background(c.separator))
            }
        }
    }
}

@Composable
private fun SetupRow(item: SetupItem, onOpen: () -> Unit, onDone: () -> Unit) {
    val c = Rs.colors
    Row(Modifier.fillMaxWidth().springClick(onOpen).padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        val (mark, color) = when (item.done) {
            true -> "✓" to c.good
            false -> "!" to c.accent
            null -> "?" to c.textTertiary
        }
        Box(
            Modifier.size(26.dp).clip(RoundedCornerShape(8.dp)).background(color.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) { Text(mark, style = RsType.subhead, color = color) }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(item.title, style = RsType.body, color = c.text)
            Text(item.description, style = RsType.caption, color = c.textSecondary)
        }
        if (item.done == null) {
            Spacer(Modifier.width(8.dp))
            Pill("我已设置", filled = false, onClick = onDone)
        } else if (item.done == false) {
            Text("去设置 ›", style = RsType.subhead, color = c.accent)
        }
    }
}

/** 可选权限：打开“使用情况访问”后，麦克风被占时能猜出前台是哪个应用。不计入上面的后台设置完成数。 */
@Composable
private fun MicHolderSection() {
    val c = Rs.colors
    val context = LocalContext.current
    val guess = remember { app.resound.capture.MicHolderGuess(context.applicationContext) }
    var on by remember { mutableStateOf(guess.permitted()) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { on = guess.permitted() }
    Section("可选设置") {
        RsCard(padding = PaddingValues(horizontal = 18.dp, vertical = 6.dp)) {
            SetupRow(
                SetupItem("usage", "看出谁占了麦克风",
                    if (on) "麦克风被占时，会提示可能是哪个应用。"
                    else "打开“使用情况访问”后，麦克风被占时提示可能是哪个应用；不打开也能下拉状态栏点绿点查看。", on),
                onOpen = { if (!on) guess.openSettings() }, onDone = {},
            )
        }
    }
}

@Composable
private fun ExportRow(stats: CaptureStatsRepository) {
    val c = Rs.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Pill(if (busy) "正在导出…" else "导出诊断记录", filled = false) {
            if (busy) return@Pill
            busy = true
            scope.launch {
                runCatching {
                    val file = stats.exportDiagnostics()
                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
                    val send = Intent(Intent.ACTION_SEND)
                        .setType("text/plain")
                        .putExtra(Intent.EXTRA_STREAM, uri)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    context.startActivity(Intent.createChooser(send, "发送诊断记录"))
                }
                busy = false
            }
        }
        Spacer(Modifier.height(8.dp))
        Text("出问题时导出发给开发排查", style = RsType.caption, color = c.textTertiary)
    }
}

fun formatDuration(ms: Long, seconds: Boolean = false): String {
    val totalSec = (ms / 1000).coerceAtLeast(0)
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return when {
        h > 0 -> "${h}小时${m}分"
        m > 0 -> if (seconds) "${m}分${s}秒" else "${m}分钟"
        else -> if (seconds) "${s}秒" else "0分钟"
    }
}

fun formatClock(epochMs: Long): String =
    Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm"))

fun formatBytes(b: Long): String = when {
    b >= 1_000_000_000 -> String.format(Locale.CHINA, "%.1f GB", b / 1e9)
    b >= 1_000_000 -> String.format(Locale.CHINA, "%.1f MB", b / 1e6)
    else -> String.format(Locale.CHINA, "%.0f KB", b / 1e3)
}
