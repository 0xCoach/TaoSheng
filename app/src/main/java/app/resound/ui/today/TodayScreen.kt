package app.resound.ui.today

import app.resound.ui.ScrollTopButton
import app.resound.ui.swipeTabs
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.offset
import kotlin.math.roundToInt
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.withFrameMillis
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material3.CircularProgressIndicator
import android.widget.Toast
import app.resound.capture.CaptureStatsRepository
import app.resound.transcribe.TranscriptRepository
import app.resound.PipelineProgress.Stage
import androidx.compose.ui.unit.sp
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.resound.capture.CaptureController
import app.resound.capture.CaptureState
import app.resound.digest.DigestSettings
import app.resound.digest.ItemStatus
import app.resound.digest.hasTime
import app.resound.digest.MemoryEvent
import app.resound.digest.MemoryItem
import app.resound.digest.MemoryRepository
import app.resound.ui.theme.Rs
import app.resound.ui.theme.RsType
import app.resound.ui.transcript.SpeakerLines
import app.resound.ui.transcript.ProgressRing
import app.resound.ui.transcript.runLabel
import app.resound.ui.verify.BreathingWave
import app.resound.ui.verify.DaySwitcher
import app.resound.ui.verify.Dot
import app.resound.ui.verify.RsCard
import app.resound.ui.settings.CustomModelForm
import app.resound.ui.settings.SettingsInput
import app.resound.digest.AndroidDigestSettings
import app.resound.ui.verify.Pill
import app.resound.ui.verify.ActionButton
import app.resound.ui.verify.ActionKind
import app.resound.ui.verify.Section
import app.resound.ui.verify.formatClock
import app.resound.ui.verify.springClick
import app.resound.ui.verify.ThemeModePill
import app.resound.ui.theme.ThemeMode
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/** 今天：录音小状态 → 待确认 → 一天的时间线（事件卡片）。点卡片看详情。 */
@Composable
fun TodayScreen(
    capture: CaptureController,
    stats: CaptureStatsRepository,
    transcripts: TranscriptRepository,
    memory: MemoryRepository,
    settings: DigestSettings,
    themeMode: ThemeMode,
    onThemeMode: (ThemeMode) -> Unit,
    bottomPadding: Dp,
) {
    val c = Rs.colors
    var dayOffset by remember { mutableIntStateOf(0) }
    val date = remember(dayOffset) { LocalDate.now().minusDays(dayOffset.toLong()) }
    val events by remember(date) { memory.events(date) }.collectAsState(initial = null)
    val pending by remember { memory.pendingItems() }.collectAsState(initial = emptyList())
    val hasKey by settings.hasKey.collectAsState()
    val day by remember(date) { stats.dayStats(date) }.collectAsState(initial = null)
    var settingsOpen by remember { mutableStateOf(false) }
    var opened by remember { mutableStateOf<MemoryEvent?>(null) }
    var openedItem by remember { mutableStateOf<MemoryItem?>(null) }
    val skills = remember { app.resound.ui.skills.skillRepo() }
    val cards by remember(date) { skills.cards(date) }.collectAsState(initial = emptyList())
    var openedCard by remember { mutableStateOf<app.resound.skills.SkillCard?>(null) }
    var deleting by remember { mutableStateOf<MemoryEvent?>(null) }
    val deleteNow = rememberDeleteEvent(memory)
    val segments by remember(date) { transcripts.day(date) }.collectAsState(initial = emptyList())
    val dayLines = remember(segments) { segments.flatMap { it.lines }.sortedBy { it.startAt } }
    val importing = if (app.resound.AppGraph.isImporterReady)
        app.resound.AppGraph.importer.progress.collectAsState().value.let { it.running || it.error != null } else false

    val list = events.orEmpty()
    // 筛选：全部 / 重点 / 有待办 / 会议 / 媒体 / 提到的人；换一天回到全部。筛选时技能卡不显示
    var filter by remember(date) { mutableStateOf<TimelineFilter>(TimelineFilter.All) }
    val filterOptions = remember(list, filter, cards) { timelineFilters(list, filter, cards.count { it.skillId == "meeting" }) }
    var peopleOpen by remember { mutableStateOf(false) }
    var meetingList by remember { mutableStateOf<List<app.resound.skills.SkillCard>?>(null) }
    LaunchedEffect(filterOptions) { if (filterOptions.none { it.first == filter }) filter = TimelineFilter.All }
    val shownEvents = if (filter == TimelineFilter.All) list else list.filter(filter::matches)
    // 时间线按时段分组；技能卡（会议、收工打卡等）按时间插进对应时段，只占一行提示
    val newestFirst = list.size > 1 && list.first().startAt > list.last().startAt
    // 筛选时技能卡不显示；筛“会议”时把会议纪要卡一起显示
    val shownCards = when (filter) { TimelineFilter.All -> cards; TimelineFilter.Meeting -> cards.filter { it.skillId == "meeting" }; else -> emptyList() }
    val entries = (shownEvents.map { TimelineEntry.Event(it) } + shownCards.map { TimelineEntry.Card(it) })
        .let { l -> if (newestFirst) l.sortedByDescending { it.at } else l.sortedBy { it.at } }
    val groups = entries.groupBy { partOfDay(it.at) }.toList()

    // 多选：换一天就重置；按显示顺序排好的事件 id 用来算“选到这儿”和“全选”的范围（只算筛选出来的）
    val selection = remember(date) { TimelineSelection() }
    val order = remember(entries) { entries.mapNotNull { (it as? TimelineEntry.Event)?.event?.id } }
    // 换筛选时已选的保留，只去掉当天已不存在的
    val allIds = remember(list) { list.map { it.id } }
    LaunchedEffect(allIds) { selection.retain(allIds); if (allIds.isEmpty() && selection.active) selection.exit() }
    val transcribeQueue by transcripts.queue.collectAsState()
    val rhythmScope = rememberCoroutineScope()
    androidx.activity.compose.BackHandler(selection.active) { selection.exit() }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    var viewport by remember { mutableStateOf(androidx.compose.ui.geometry.Rect.Zero) }
    val topEdge = 0f
    var bottomEdge by remember { mutableStateOf(Float.MAX_VALUE) }
    // 勾选有变化后又滑动过、且已停下，才浮出“选到这儿”
    var scrolledAt by remember { mutableIntStateOf(-1) }
    LaunchedEffect(listState.isScrollInProgress) { if (listState.isScrollInProgress) scrolledAt = selection.version }
    val reach = if (selection.active && !listState.isScrollInProgress && scrolledAt == selection.version)
        selection.reachTarget(order, maxOf(viewport.top, topEdge), minOf(viewport.bottom, bottomEdge)) else null
    val batchScope = rememberCoroutineScope()
    var batchDeleting by remember { mutableStateOf<List<Long>?>(null) }
    var batchCopying by remember { mutableStateOf<List<MemoryEvent>?>(null) }
    var homeExport by remember { mutableStateOf<Pair<List<Long>, String>?>(null) }
    var copyNote by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(copyNote) { if (copyNote != null) { delay(1800); copyNote = null } }
    var deleteProgress by remember { mutableStateOf<Int?>(null) }

    var boxTop by remember { mutableStateOf(0f) }
    val rhythmShown = remember(list, dayLines) { dayRhythm(list, dayLines) != null }
    // 时间线标题那一格在列表里的位置（前面几格按是否显示来数），节奏卡片点数字/柱子时滚到这里
    fun timelineIndex() = 2 + (if (settingsOpen || !hasKey) 1 else 0) + (if (rhythmShown) 1 else 0) + (if (importing) 1 else 0) + (if (pending.isNotEmpty()) 1 else 0)
    Box(Modifier.fillMaxSize().background(c.background).onGloballyPositioned { boxTop = it.boundsInRoot().top }) {
    LazyColumn(
        Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)
            .onGloballyPositioned { viewport = it.boundsInRoot() },
        state = listState,
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = bottomPadding + 24.dp + if (selection.active) 120.dp else 0.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        // 闪退提示、上手清单和标题放在同一格：不显示时不占位，录音卡紧挨着日期
        item {
            Column {
                Header(date, themeMode, onThemeMode)
                if (dayOffset == 0) {
                    app.resound.ui.settings.CrashNotice(topGap = 20.dp)
                    // 首次使用：几步设置的清单，做完或关掉就不再出现；只在看今天时显示
                    StartChecklist(capture, hasKey, topGap = 20.dp)
                }
            }
        }
        item {
            OverviewCard(capture, memory, transcripts, hasKey, speechMillis = day?.speechMillis, eventCount = events?.size, pendingCount = pending.size,
                settingsShown = settingsOpen || !hasKey) { settingsOpen = !settingsOpen }
        }
        if (settingsOpen || !hasKey) item(key = "model") { ModelCard(settings) { settingsOpen = false } }
        if (rhythmShown) item(key = "rhythm") {
            RhythmCard(list, dayLines, date, transcribeQueue.pending,
                onFilter = { f -> filter = f; rhythmScope.launch { listState.animateScrollToItem(timelineIndex()) } },
                onHour = { h ->
                    // 跳到这个小时所在的时段：先回到全部，再滚到那一组
                    filter = TimelineFilter.All
                    val part = partOfDay(date.atTime(h, 30).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli())
                    val gi = groups.indexOfFirst { it.first == part }
                    rhythmScope.launch { listState.animateScrollToItem(timelineIndex() + maxOf(gi, 0)) }
                },
                onOpen = { opened = it }, onPeople = { peopleOpen = true },
                meetings = cards.filter { it.skillId == "meeting" },
                onMeetings = { m -> if (m.size == 1) openedCard = m.first() else meetingList = m })
        }
        if (importing) item(key = "import") { app.resound.ui.importer.ImportBanner() }
        if (pending.isNotEmpty()) {
            item {
                Section("待确认", trailing = { Text("${pending.size} 条", style = RsType.caption, color = c.textTertiary) }) {
                    // 小卡片：一屏并排两张（只有 1 张时占半宽），超过两张左右滑
                    BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val cardWidth = (maxWidth - 12.dp) / 2
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(pending, key = { it.id }) { item ->
                            PendingCard(item, Modifier.width(cardWidth).animateItem(), onOpen = { openedItem = item },
                                onAccept = { memory.setItemStatus(item.id, ItemStatus.Accepted) },
                                onIgnore = { memory.setItemStatus(item.id, ItemStatus.Ignored) })
                        }
                    }
                    }
                }
            }
        }
        item {
            // 标题和第一组放在同一行，间距与“待确认”一致
            Section(when { list.isEmpty() -> "时间线"; filter == TimelineFilter.All -> "时间线 · ${list.size} 件事"; else -> "时间线 · ${shownEvents.size}/${list.size} 件" }, trailing = {
                DaySwitcher(dayOffset, compact = true) { dayOffset = it }
            }) {
                FilterChips(filterOptions, filter, Modifier.padding(bottom = 12.dp)) { filter = it }
                if (filter != TimelineFilter.All && shownEvents.isEmpty()) Text("这一天没有“${filter.label}”的事", style = RsType.subhead, color = c.textTertiary,
                    modifier = Modifier.padding(start = 4.dp, bottom = 8.dp))
                groups.firstOrNull()?.let { (part, evs) -> TimelineGroup(part, evs, onOpenCard = { openedCard = it },
                    onStar = { memory.setStarred(it.id, !it.starred) }, onDelete = { deleteNow(it) }, selection = selection, order = order) { opened = it } }
                if (events != null && list.isEmpty()) EmptyTimeline(memory)
            }
        }
        items(groups.drop(1), key = { it.first }) { (part, evs) ->
            TimelineGroup(part, evs, Modifier.animateItem(), onOpenCard = { openedCard = it },
                onStar = { memory.setStarred(it.id, !it.starred) }, onDelete = { deleteNow(it) }, selection = selection, order = order) { opened = it }
        }
    }
    val chosen = list.filter { it.id in selection.ids }
    val allStarred = chosen.isNotEmpty() && chosen.all { it.starred }
    app.resound.ui.ScrollToTopOnReselect(listState)
    ScrollTopButton(listState, bottomPadding, lift = if (selection.active) 120.dp else 0.dp)
    SelectBottomBar(selection, order, allStarred, bottomPadding, onTop = { bottomEdge = it },
        onStar = { chosen.forEach { memory.setStarred(it.id, !allStarred) }; selection.exit() },
        onCopy = { batchCopying = list.filter { it.id in selection.ids } },
        onExport = {
            // 选中几件事的录音合成一个文件：先查出它们的录音段，再打开导出面板
            val evs = list.filter { it.id in selection.ids }
            batchScope.launch {
                val ids = evs.flatMap { e -> runCatching { memory.eventLines(e.id).first() }.getOrDefault(emptyList()).map { it.segmentId } }.filter { it > 0 }.distinct()
                homeExport = ids to app.resound.ui.transcript.exportTitle(evs, evs.minOf { it.startAt }, evs.maxOf { it.endAt })
            }
        },
        onDelete = { batchDeleting = list.map { it.id }.filter { it in selection.ids } })
    // 复制完的小提示：底部浮一下就消失
    androidx.compose.animation.AnimatedVisibility(copyNote != null, Modifier.align(Alignment.BottomCenter).padding(bottom = bottomPadding + 24.dp),
        enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.scaleIn(initialScale = 0.8f),
        exit = androidx.compose.animation.fadeOut()) {
        Text(copyNote ?: "", style = RsType.subhead.copy(fontWeight = FontWeight.Medium), color = Color.White,
            modifier = Modifier.clip(CircleShape).background(c.accent).padding(horizontal = 18.dp, vertical = 10.dp))
    }
    ReachHereChip(reach, selection, boxTop,
        upward = reach != null && order.indexOf(reach) < order.indexOf(selection.anchor)) { selection.rangeTo(it, order) }
    }

    meetingList?.let { m -> MeetingsSheet(m, onOpen = { openedCard = it }) { meetingList = null } }
    if (peopleOpen) PeopleSheet(list, onPick = { name ->
        filter = TimelineFilter.Person(name); rhythmScope.launch { listState.animateScrollToItem(timelineIndex()) }
    }) { peopleOpen = false }
    homeExport?.let { (ids, title) -> app.resound.ui.transcript.ExportSheet(ids, title) { homeExport = null; selection.exit() } }
    batchCopying?.let { evs -> CopySheet(evs, memory, onDone = { copyNote = it; selection.exit() }) { batchCopying = null } }
    batchDeleting?.let { ids ->
        BatchDeleteSheet(ids.size, deleteProgress, onConfirm = {
            deleteProgress = 0
            batchScope.launch {
                // 逐件删除，与单条删除走同一条路径（有隐私接口时连录音和原话一起删）
                ids.forEachIndexed { i, id ->
                    if (app.resound.AppGraph.isPrivacyReady) runCatching { app.resound.AppGraph.privacy.forgetEvent(id) } else memory.deleteEvent(id)
                    deleteProgress = i + 1
                }
                batchDeleting = null; deleteProgress = null; selection.exit()
            }
        }) { batchDeleting = null }
    }

    opened?.let { event -> EventSheet(event, memory) { opened = null } }
    openedCard?.let { card -> app.resound.ui.skills.SkillCardSheet(card, skills) { openedCard = null } }
    deleting?.let { e -> DeleteEventSheet(e, { memory.deleteEvent(e.id) }) { deleting = null } }
    openedItem?.let { item ->
        ItemSheet(
            item, memory,
            onOpenEvent = events.orEmpty().firstOrNull { it.id == item.eventId }?.let { e -> { openedItem = null; opened = e } },
        ) { openedItem = null }
    }
}

@Composable
private fun Header(date: LocalDate, themeMode: ThemeMode, onThemeMode: (ThemeMode) -> Unit) {
    val c = Rs.colors
    val today = LocalDate.now()
    val label = when (ChronoUnit.DAYS.between(date, today)) {
        0L -> "今天"
        1L -> "昨天"
        2L -> "前天"
        else -> ""
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.Bottom) {
        Column(Modifier.weight(1f)) {
            Text("$label · ${date.format(DateTimeFormatter.ofPattern("EEEE", Locale.CHINA))}", style = RsType.subhead, color = c.textSecondary)
            Spacer(Modifier.height(2.dp))
            Text(date.format(DateTimeFormatter.ofPattern("M月d日")), style = RsType.largeTitle, color = c.text)
        }
        ThemeModePill(themeMode, onThemeMode)
    }
}

/** 今日概览主卡：暖橙渐变，状态+白色波形，三个大数字，整理状态与按钮。页面上最重的一块。 */
@Composable
private fun OverviewCard(
    capture: CaptureController,
    memory: MemoryRepository,
    transcripts: TranscriptRepository,
    hasKey: Boolean,
    speechMillis: Long?,
    eventCount: Int?,
    pendingCount: Int,
    settingsShown: Boolean,
    onToggle: () -> Unit,
) {
    val status by capture.status.collectAsState()
    val digest by memory.state.collectAsState()
    val queue by transcripts.queue.collectAsState()
    val digestProgress by (if (app.resound.AppGraph.isDigestProgressReady) app.resound.AppGraph.digestProgress
        else remember { kotlinx.coroutines.flow.MutableStateFlow(app.resound.digest.DigestProgress()) }).collectAsState()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(digest.running) { while (true) { now = System.currentTimeMillis(); delay(if (digest.running) 1_000 else 30_000) } }
    val active = status.state == CaptureState.Listening || status.state == CaptureState.Recording
    val recording = status.state == CaptureState.Recording
    // 还有录音没转写：按钮叫“立即转写”（同一个按钮会先转写、再整理）
    val toTranscribe = queue.pending + queue.failed
    // 按时间点整理：两个点之间不出新内容，告诉用户下次几点
    val timing = if (app.resound.AppGraph.isDigestTimingReady) app.resound.AppGraph.digestTiming else null
    val timedMode = timing?.mode?.collectAsState()?.value == app.resound.digest.DigestMode.TimePoints
    val nextAt = timing?.nextDigestAt?.collectAsState()?.value
    val nextDigest = if (!timedMode) null else nextAt?.let { at ->
        val day = java.time.Instant.ofEpochMilli(at).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
        "下次整理 " + (if (day != java.time.LocalDate.now()) "明天 " else "") + formatClock(at)
    } ?: "未设整理时间点"
    val pipeline by app.resound.PipelineProgress.state.collectAsState()
    val stage = pipeline.stage
    val busy = digest.running || queue.running || stage != null
    val stateText = when (status.state) {
        CaptureState.Recording -> "正在录音"
        CaptureState.Listening -> "正在聆听"
        CaptureState.Paused -> (status.quietName?.let { "${it}中" } ?: "已暂停") + (app.resound.ui.verify.resumeAt(status.pausedUntil)?.let { " · ${app.resound.ui.verify.formatUpcoming(it)} 恢复" } ?: "")
        CaptureState.MicBusy -> if (status.busyDetail == app.resound.audio.MicYield.IN_CALL) "通话中，暂停收音" else "麦克风让给别的应用了"
        CaptureState.Stopped -> "没在聆听"
        CaptureState.NoPermission -> "缺少权限"
    }
    val white = Color.White
    val soft = Color.White.copy(alpha = 0.8f)
    val shape = RoundedCornerShape(26.dp)
    var listenOpen by remember { mutableStateOf(false) }
    val forgetRequested by app.resound.ui.ShellNav.forgetRecent.collectAsState()
    if (listenOpen || forgetRequested) ListenSheet(capture, startOnForget = forgetRequested) {
        listenOpen = false; app.resound.ui.ShellNav.forgetRecent.value = false
    }
    Column(
        Modifier
            .fillMaxWidth()
            // 明亮暖橙（与图标同色）；卡片压得紧凑，避免过于抢眼
            .shadow(12.dp, shape, ambientColor = Color(0xFFEA5A2A), spotColor = Color(0xFFEA5A2A))
            .clip(shape)
            .background(Brush.linearGradient(listOf(Color(0xFFFFAE6A), Color(0xFFF07B2E), Color(0xFFE8582A))))
            .padding(start = 18.dp, end = 18.dp, top = 14.dp, bottom = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // 点状态打开聆听面板：暂停、恢复、删除最近一段
            Row(Modifier.springClick { listenOpen = true }, verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(if (active) white else white.copy(alpha = 0.5f)))
                Spacer(Modifier.width(8.dp))
                Text(stateText, style = RsType.headline, color = white)
                Text(" ›", style = RsType.headline, color = soft)
            }
            Spacer(Modifier.width(12.dp))
            BreathingWave(status.level, active, recording, Modifier.weight(1f).height(22.dp), tint = white)
        }
        // 麦克风被占时补一句是谁（通话中 / 可能是哪个应用）
        if (status.state == CaptureState.MicBusy) status.busyDetail?.let {
            Text(it, style = RsType.caption, color = soft, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 16.dp, top = 2.dp))
        }
        Spacer(Modifier.height(10.dp))
        Row {
            BigStat(speechMillis?.let { formatSpeech(it) } ?: "—", "录到人声", Modifier.weight(1.3f))
            BigStat(eventCount?.toString() ?: "—", "件事", Modifier.weight(1f))
            BigStat(pendingCount.toString(), "待确认", Modifier.weight(1f))
        }
        Spacer(Modifier.height(10.dp))
        Box(Modifier.fillMaxWidth().height(0.5.dp).background(white.copy(alpha = 0.3f)))
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                when {
                    stage != null -> stageLine(pipeline, queue.runDone, queue.runTotal)
                    digest.running -> "按已保存的原话计数"
                    queue.running -> "正在转写录音…"
                    toTranscribe > 0 -> "$toTranscribe 段录音待转写" + (queue.lastError?.let { " · $it" } ?: "")
                    else -> digestLine(digest.running, digest.pendingLines, digest.lastRunAt, digest.lastError, hasKey, now, nextDigest)
                },
                style = RsType.caption,
                color = soft,
                modifier = Modifier.weight(1f),
                // 整理状态加原因可能较长，放不下就折到第二行，不截断
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (hasKey) {
                Spacer(Modifier.width(8.dp))
                val context = LocalContext.current
                // 点了先转写没转完的录音、再整理；处理中显示转圈，再点只提示，不会像卡死
                Row(
                    Modifier.clip(CircleShape).background(white).springClick {
                        if (busy) Toast.makeText(context, "正在后台处理，请稍等", Toast.LENGTH_SHORT).show() else memory.runNow()
                    }.padding(horizontal = 12.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (busy) {
                        // 转写、认人有真实进度（第几段/共几段），整理按批次，排队和生成卡片转圈
                        val (done, total) = when {
                            stage == Stage.Digest || (stage == null && digest.running) -> digestProgress.done to digestProgress.total
                            stage == Stage.Transcribe && pipeline.total == 0 -> queue.runDone to queue.runTotal
                            stage == Stage.Queued || stage == Stage.Skills -> 0 to 0
                            stage != null -> pipeline.done to pipeline.total
                            else -> queue.runDone to queue.runTotal
                        }
                        ProgressRing(done, total, Color(0xFFE8582A), Modifier.size(12.dp))
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
                        style = RsType.caption.copy(fontWeight = FontWeight.SemiBold), color = Color(0xFFE8582A),
                    )
                }
            }
            Spacer(Modifier.width(6.dp))
            Text(
                if (settingsShown) "收起" else "模型",
                style = RsType.caption.copy(fontWeight = FontWeight.Medium),
                color = white,
                modifier = Modifier.clip(CircleShape).background(white.copy(alpha = 0.2f)).springClick(onToggle)
                    .padding(horizontal = 12.dp, vertical = 5.dp),
            )
        }
        if (digest.running) {
            Spacer(Modifier.height(6.dp))
            DigestElapsedText(digestProgress, soft)
        } else if (stage != null) {
            Spacer(Modifier.height(6.dp))
            StageElapsedText(pipeline, soft)
        } else if (digestProgress.completion != null) {
            Spacer(Modifier.height(6.dp))
            Text(digestProgress.completion!!, style = RsType.caption.copy(fontFeatureSettings = "tnum"), color = soft)
        }
    }
}

/** 这一轮走到哪一步：第几步、做什么、做到第几段。转写段数在 runner 刚开始时可能还没报，先用转写队列的数 */
private fun stageLine(p: app.resound.PipelineProgress.State, runDone: Int, runTotal: Int): String {
    val steps = Stage.Digest.step
    return when (val stage = p.stage) {
        null -> ""
        Stage.Queued -> "已排队 · ${p.detail ?: "马上开始…"}"
        Stage.Transcribe -> {
            val total = if (p.total > 0) p.total else runTotal
            val done = if (p.total > 0) p.done else runDone
            "第 1/$steps 步 · 转写录音" + if (total > 0) " ${minOf(done + 1, total)}/$total 段" else ""
        }
        Stage.Voiceprint -> "第 2/$steps 步 · 识别说话人" + if (p.total > 0) " ${minOf(p.done + 1, p.total)}/${p.total} 段" else ""
        Stage.Digest -> "第 3/$steps 步 · ${stage.title}"
        Stage.Skills -> "收尾 · ${stage.title}"
    }
}

/** 本步已用多久（精确到 0.01 秒，跟随屏幕帧刷新），外加步骤里的细节（如本地转写正在“区分说话人 45%”）；离开前台停止 */
@Composable
private fun StageElapsedText(p: app.resound.PipelineProgress.State, color: Color) {
    var elapsed by remember { mutableLongStateOf(android.os.SystemClock.elapsedRealtime()) }
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    LaunchedEffect(owner) {
        owner.lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
            while (true) withFrameMillis { elapsed = android.os.SystemClock.elapsedRealtime() }
        }
    }
    val used = app.resound.formatElapsed(elapsed - p.since)
    val detail = p.detail?.takeIf { p.stage != Stage.Queued }
    Text((detail?.let { "$it · " } ?: "") + "这一步已用 $used",
        style = RsType.caption.copy(fontFeatureSettings = "tnum"), color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

/** 仅让这一行跟随屏幕帧刷新；离开前台立即停止，后台任务不需要高频计时。 */
@Composable
private fun DigestElapsedText(progress: app.resound.digest.DigestProgress, color: Color) {
    var elapsed by remember { mutableLongStateOf(android.os.SystemClock.elapsedRealtime()) }
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    LaunchedEffect(owner) {
        owner.lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
            while (true) withFrameMillis { elapsed = android.os.SystemClock.elapsedRealtime() }
        }
    }
    Text(progress.detail(elapsed), style = RsType.caption.copy(fontFeatureSettings = "tnum"), color = color)
}

@Composable
private fun BigStat(value: String, label: String, modifier: Modifier) {
    Column(modifier.padding(end = 8.dp)) {
        // 字体调大或屏幕窄时自动缩小，不截断“2小时14分”
        Text(value, style = RsType.number.copy(fontSize = 24.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold), color = Color.White, maxLines = 1,
            autoSize = TextAutoSize.StepBased(minFontSize = 11.sp, maxFontSize = 24.sp))
        Text(label, style = RsType.caption, color = Color.White.copy(alpha = 0.8f), maxLines = 1, autoSize = TextAutoSize.StepBased(minFontSize = 10.sp, maxFontSize = RsType.caption.fontSize))
    }
}

/** “2小时14分”“38分钟”“45秒” */
private fun formatSpeech(ms: Long): String {
    val m = ms / 60_000
    return when {
        m >= 60 -> "${m / 60}小时${m % 60}分"
        m >= 1 -> "${m}分钟"
        else -> "${ms / 1000}秒"
    }
}

/** 只说结果：上次什么时候整理的；刚说的还没整理时加半句“刚说的稍后整理”，不讲句数和等待机制 */
/** [next] 是按时间点整理时的“下次整理 18:00”；按间隔时为空 */
private fun digestLine(running: Boolean, pending: Int, lastRunAt: Long?, error: String?, hasKey: Boolean, now: Long, next: String? = null): String = when {
    !hasKey -> "填好大模型密钥后，转写出的原话会自动整理成一件件事"
    running -> "正在把原话整理成事件…"
    error != null -> error
    else -> buildString {
        lastRunAt?.let {
            val mins = (now - it) / 60_000
            append(if (mins < 1) "刚整理过" else if (mins < 60) "$mins 分钟前整理过" else "上次整理 ${formatClock(it)}")
        }
        if (next != null) {
            if (isNotEmpty()) append(" · ")
            append(next)
        } else if (pending > 0) {
            if (isNotEmpty()) append(" · ")
            append("刚说的稍后整理")
        }
        if (isEmpty()) append("还没整理过")
    }
}

// ---------- 模型设置 ----------

@Composable
fun ModelCard(settings: DigestSettings, onSaved: () -> Unit) {
    val c = Rs.colors
    val scope = rememberCoroutineScope()
    val presetId by settings.presetId.collectAsState()
    val hasKey by settings.hasKey.collectAsState()
    var editing by remember(presetId) { mutableStateOf(false) }
    var input by remember(presetId) { mutableStateOf("") }
    var testing by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<Result<List<app.resound.digest.ModelCheckItem>>?>(null) }

    RsCard(Modifier.animateContentSize(), padding = PaddingValues(horizontal = 20.dp, vertical = 18.dp)) {
        Text("整理用的大模型", style = RsType.body, color = c.text)
        Spacer(Modifier.height(10.dp))
        Row(Modifier.clip(CircleShape).background(c.separator.copy(alpha = if (c.isDark) 1f else 0.7f)).padding(3.dp)) {
            settings.presets.forEach { p ->
                val on = p.id == presetId
                val bg by animateColorAsState(if (on) c.card else c.card.copy(alpha = 0f), spring(stiffness = Spring.StiffnessMediumLow), label = "preset")
                Box(Modifier.clip(CircleShape).background(bg).springClick { settings.setPreset(p.id); result = null }.padding(horizontal = 14.dp, vertical = 7.dp)) {
                    Text(p.name, style = RsType.subhead, color = if (on) c.text else c.textSecondary)
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        val advanced=settings as? AndroidDigestSettings
        if(presetId=="custom" && advanced!=null) {
            CustomModelForm(advanced.customEndpoint,advanced.customModel,hasKey,
                "自定义（OpenAI 兼容聊天接口）",advanced::fetchModels,advanced::saveCustomAll)
            if(hasKey) {
                Spacer(Modifier.height(14.dp))
                Pill(if(testing) "检测中" else "检测模型",filled=false) {
                    if(!testing){testing=true;result=null;scope.launch{result=settings.check();testing=false}}
                }
            }
        } else if (hasKey && !editing) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Dot(c.good, 7.dp)
                Spacer(Modifier.width(8.dp))
                Text("密钥已填写", style = RsType.subhead, color = c.textSecondary, modifier = Modifier.weight(1f))
                Pill(if (testing) "检测中" else "检测", filled = false) {
                    if (testing) return@Pill
                    testing = true; result = null
                    scope.launch { result = settings.check(); testing = false }
                }
                Spacer(Modifier.width(8.dp))
                Pill("更换", filled = false) { editing = true }
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SettingsInput(input,{input=it.trim()},"粘贴 API Key",Modifier.weight(1f),secret=true)
                Spacer(Modifier.width(10.dp))
                Pill("保存", filled = input.isNotEmpty()) {
                    if (input.isEmpty()) return@Pill
                    settings.saveKey(input)
                    input = ""; editing = false; result = null
                    onSaved()
                }
            }
        }
        if (testing) {
            Spacer(Modifier.height(8.dp))
            Text("正在逐项检测各功能，约需半分钟…", style = RsType.caption, color = c.textSecondary)
        }
        result?.let { r ->
            Spacer(Modifier.height(10.dp))
            val items = r.getOrNull()
            if (items == null) Text(r.exceptionOrNull()?.message ?: "检测没通过", style = RsType.caption, color = c.critical)
            else Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { items.forEach { ModelCheckRow(it) } }
        }
        Spacer(Modifier.height(6.dp))
        if(presetId!="custom") Text("每种服务的密钥分开保存在手机安全区里，不会显示。", style = RsType.caption, color = c.textTertiary)
    }
}

/** 检测结果的一行：圆点（绿=正常，红=有问题，灰=没测）+ 功能名 + 结果 */
@Composable
private fun ModelCheckRow(item: app.resound.digest.ModelCheckItem) {
    val c = Rs.colors
    val color = when (item.ok) { true -> c.good; false -> c.critical; null -> c.textTertiary }
    Row(verticalAlignment = Alignment.Top) {
        Box(Modifier.padding(top = 5.dp)) { Dot(color, 7.dp) }
        Spacer(Modifier.width(8.dp))
        Column {
            Text(item.feature, style = RsType.subhead, color = c.text)
            Text(item.detail, style = RsType.caption, color = if (item.ok == false) c.critical else c.textSecondary)
        }
    }
}

// ---------- 待确认 ----------

@Composable
private fun PendingCard(item: MemoryItem, modifier: Modifier, onOpen: () -> Unit, onAccept: () -> Unit, onIgnore: () -> Unit) {
    val c = Rs.colors
    // 浅橙底：表示“需要你处理”，和下面的记录区分开
    RsCard(modifier.springClick(onOpen), padding = PaddingValues(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 12.dp), color = c.accentSoft) {
        // 放不下时日期换到下一行，不会被挤成一字一行
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, itemVerticalAlignment = Alignment.CenterVertically) {
            CategoryChip(item.category, item.kind)
            item.dueAt?.let { Text(friendlyDue(it, item.dueText), style = RsType.caption, color = c.accent, maxLines = 1, softWrap = false) }
        }
        Spacer(Modifier.height(8.dp))
        // 固定两行高度，并排的两张卡底部按钮对齐
        Text(item.content, style = RsType.subhead.copy(lineHeight = 19.sp), color = c.text, maxLines = 2, minLines = 2, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SmallPill("忽略", filled = false, modifier = Modifier.weight(1f), onClick = onIgnore)
            SmallPill(if (item.category == "约定") "记下" else "接受", filled = true, modifier = Modifier.weight(1f), onClick = onAccept)
        }
    }
}

@Composable
private fun SmallPill(text: String, filled: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val c = Rs.colors
    Box(
        modifier.springClick(onClick).clip(CircleShape).background(if (filled) c.accent else c.card)
            .padding(horizontal = 16.dp, vertical = 5.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, style = RsType.subhead.copy(fontWeight = FontWeight.Medium), color = if (filled) Color.White else c.accent) }
}

/** 类别小标签；颜色按类别区分 */
@Composable
fun CategoryChip(category: String, kind: String? = null) {
    val color = categoryColor(category)
    Box(Modifier.clip(CircleShape).background(color.copy(alpha = 0.13f)).padding(horizontal = 9.dp, vertical = 3.dp)) {
        Text(if (kind != null && category == "待办") "待办 · $kind" else category, style = RsType.caption.copy(fontWeight = FontWeight.Medium), color = color,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun categoryColor(category: String): Color {
    val c = Rs.colors
    val dark = c.isDark
    return when (category) {
        "待办" -> c.accent
        "约定" -> if (dark) Color(0xFF6CC3BB) else Color(0xFF2E9C93)
        "备忘" -> if (dark) Color(0xFF8DB8E8) else Color(0xFF4F86C6)
        "灵感" -> if (dark) Color(0xFFA897D8) else Color(0xFF7E68C2)
        "关键信息" -> if (dark) Color(0xFFE48FA8) else Color(0xFFCC5E82)
        "决定" -> c.good
        else -> if (dark) Color(0xFFD9B67A) else Color(0xFFA9803C)
    }
}

/** “明天 14:00”“周三 14:00”“10月30日” */
internal fun friendlyDue(at: Long, text: String?): String {
    val zone = ZoneId.systemDefault()
    val dt = Instant.ofEpochMilli(at).atZone(zone)
    val days = ChronoUnit.DAYS.between(LocalDate.now(zone), dt.toLocalDate())
    val day = when {
        days == 0L -> "今天"
        days == 1L -> "明天"
        days == 2L -> "后天"
        days in 3..6 -> dt.format(DateTimeFormatter.ofPattern("EEE", Locale.CHINA))
        days < 0 -> "已过 ${dt.format(DateTimeFormatter.ofPattern("M月d日"))}"
        else -> dt.format(DateTimeFormatter.ofPattern("M月d日"))
    }
    val hasTime = (text?.length ?: 0) > 10
    return if (hasTime) "$day ${dt.format(DateTimeFormatter.ofPattern("HH:mm"))}" else day
}

// ---------- 时间线 ----------

@Composable
private fun EmptyTimeline(memory: MemoryRepository) {
    val c = Rs.colors
    val digest by memory.state.collectAsState()
    RsCard(padding = PaddingValues(vertical = 32.dp, horizontal = 20.dp)) {
        Text(
            if (digest.pendingLines > 0) "有 ${digest.pendingLines} 句原话等着整理" else "这一天还没有整理出事件",
            style = RsType.subhead,
            color = c.textSecondary,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "录到人声 → 转写成原话 → 说完约 10 分钟后整理成一件件事",
            style = RsType.caption,
            color = c.textTertiary,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
        )
    }
}


internal fun partOfDay(at: Long): String = when (Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).hour) {
    in 0..5 -> "凌晨"
    in 6..11 -> "上午"
    in 12..17 -> "下午"
    else -> "晚上"
}

/** 时间线上的一行：一件事，或一张技能卡的提示 */
internal sealed interface TimelineEntry {
    val at: Long
    data class Event(val event: MemoryEvent) : TimelineEntry { override val at get() = event.startAt }
    data class Card(val card: app.resound.skills.SkillCard) : TimelineEntry { override val at get() = card.at }
}

/** 一个时段一张卡，组内每件事一行，细线分隔：比一事一卡更轻、更紧凑 */
@Composable
internal fun TimelineGroup(part: String, entries: List<TimelineEntry>, modifier: Modifier = Modifier,
                           onOpenCard: (app.resound.skills.SkillCard) -> Unit = {},
                           onStar: (MemoryEvent) -> Unit = {}, onDelete: (MemoryEvent) -> Unit = {},
                           /** 首页传入：长按进入多选；其他页不传 */
                           selection: TimelineSelection? = null, order: List<Long> = emptyList(),
                           onOpen: (MemoryEvent) -> Unit) {
    val c = Rs.colors
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    val count = entries.count { it is TimelineEntry.Event }
    Column(modifier) {
        Text(if (count > 0) "$part · $count 件" else part, style = RsType.caption.copy(fontWeight = FontWeight.Medium), color = c.textTertiary,
            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp))
        RsCard(padding = PaddingValues(vertical = 4.dp)) {
            entries.forEachIndexed { i, e ->
                when (e) {
                    // 多选中：点一下勾选/取消，长按 = 选到这条；左右滑暂停，避免误删
                    is TimelineEntry.Event -> if (selection?.active == true) {
                        val id = e.event.id
                        TimelineLine(e.event, onLongPress = { haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress); selection.rangeTo(id, order) },
                            modifier = Modifier.trackRow(selection, id), selected = id in selection.ids) { selection.toggle(id, order) }
                    } else SwipeEvent(e.event, { onStar(e.event) }, { onDelete(e.event) }) { lp ->
                        val start = selection?.let { s -> { haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress); s.start(e.event.id) } }
                        TimelineLine(e.event, start ?: lp) { onOpen(e.event) }
                    }
                    is TimelineEntry.Card -> CardHintLine(e.card) { onOpenCard(e.card) }
                }
                if (i != entries.lastIndex) Box(Modifier.padding(start = 76.dp, end = 18.dp).fillMaxWidth().height(0.5.dp).background(c.separator))
            }
        }
    }
}

/** 技能卡在时间线上只占一行：“18:00 · 收工打卡已生成 ›”，下面一行标题 */
@Composable
private fun CardHintLine(card: app.resound.skills.SkillCard, onOpen: () -> Unit) {
    val c = Rs.colors
    Row(Modifier.fillMaxWidth().springClick(onOpen).padding(start = 18.dp, end = 18.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(formatClock(card.at), style = RsType.subhead.copy(fontWeight = FontWeight.SemiBold), color = c.accent, modifier = Modifier.widthIn(min = 58.dp))
        app.resound.ui.skills.SkillIcon(card.icon, 28.dp)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text("${card.skillName}已生成", style = RsType.subhead.copy(fontWeight = FontWeight.SemiBold), color = app.resound.ui.skills.skillInk(card.icon))
            Text(card.title, style = RsType.caption, color = c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text("›", style = RsType.title, color = c.textTertiary)
    }
}

@Composable
internal fun TimelineLine(event: MemoryEvent, onLongPress: (() -> Unit)? = null, modifier: Modifier = Modifier,
                          /** 多选模式下为 true/false 表示是否选中；不在多选为 null */
                          selected: Boolean? = null, onOpen: () -> Unit) {
    val c = Rs.colors
    val quiet = event.isMedia()
    val selBg by androidx.compose.animation.animateColorAsState(if (selected == true) c.accentSoft.copy(alpha = 0.6f) else Color.Transparent, label = "rowSel")
    // 媒体的事一直保持灰色（多选时也是）；勾选圈不变灰
    val dim = if (quiet) 0.55f else 1f
    Row(
        modifier.fillMaxWidth().background(selBg)
            .then(if (onLongPress != null) Modifier.combinedClickable(onLongClick = onLongPress, onClick = onOpen) else Modifier.springClick(onOpen))
            .padding(start = 18.dp, end = 18.dp, top = 12.dp, bottom = 12.dp),
    ) {
        androidx.compose.animation.AnimatedVisibility(selected != null,
            enter = androidx.compose.animation.expandHorizontally() + androidx.compose.animation.fadeIn(),
            exit = androidx.compose.animation.shrinkHorizontally() + androidx.compose.animation.fadeOut()) {
            Box(Modifier.padding(end = 12.dp, top = 1.dp)) { SelectCircle(selected == true) }
        }
        Text(
            formatClock(event.startAt),
            style = RsType.subhead.copy(fontWeight = FontWeight.SemiBold),
            color = if (quiet) c.textTertiary else c.accent,
            modifier = Modifier.widthIn(min = 58.dp).padding(top = 1.dp).alpha(dim),
        )
        Column(Modifier.weight(1f).alpha(dim)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(event.title, style = RsType.body.copy(fontWeight = FontWeight.SemiBold), color = c.text, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                // ★ 只给你亲手标的重点；大模型判断的“要紧”用浅色小字，只作参考
                if (event.starred) {
                    Spacer(Modifier.width(6.dp))
                    Text("★", style = RsType.caption, color = c.accent)
                } else if (event.important) {
                    Spacer(Modifier.width(6.dp))
                    Text("要紧", style = RsType.caption, color = c.textTertiary)
                }
            }
            Spacer(Modifier.height(2.dp))
            Text(event.summary, style = RsType.caption.copy(lineHeight = 18.sp), color = c.textSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val counts = event.items.groupingBy { it.category }.eachCount()
            if (counts.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    counts.forEach { (cat, n) -> CategoryChip(if (n > 1) "$cat $n" else cat) }
                }
            }
        }
    }
}

private fun sceneLine(event: MemoryEvent): String = buildList {
    add(event.scene)
    val mins = ((event.endAt - event.startAt) / 60_000).toInt()
    add(if (mins >= 1) "$mins 分钟" else "不到 1 分钟")
    if (event.people.isNotEmpty()) add(event.people.joinToString("、"))
    event.place?.let { add(it) }
    event.emotion?.takeIf { it != "平静" }?.let { add(it) }
}.joinToString(" · ")

// ---------- 详情 ----------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EventSheet(event: MemoryEvent, memory: MemoryRepository, onClose: () -> Unit) {
    val c = Rs.colors
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val lines by remember(event.id) { memory.eventLines(event.id) }.collectAsState(initial = emptyList())
    var important by remember(event.id) { mutableStateOf(event.starred) }
    var confirmDelete by remember { mutableStateOf(false) }
    // 编辑后就地显示新内容（打开时拿到的是快照）；恢复原样也就地切回
    var shown by remember(event.id) { mutableStateOf(event) }
    var editing by remember { mutableStateOf(false) }
    var showOriginal by remember { mutableStateOf(false) }
    var exportingEvent by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current
    if (editing) EditEventSheet(shown, onSave = { t, s, pts ->
        memory.editEvent(shown.id, t, s, pts)
        shown = shown.copy(title = t, summary = s, points = pts, editedAt = System.currentTimeMillis(),
            original = shown.original ?: app.resound.digest.EventOriginal(shown.title, shown.summary, shown.points))
    }) { editing = false }
    if (exportingEvent) app.resound.ui.transcript.ExportSheet(lines.map { it.segmentId }.filter { it > 0 }.distinct(),
        app.resound.ui.transcript.exportTitle(listOf(shown), shown.startAt, shown.endAt)) { exportingEvent = false }
    ModalBottomSheet(onDismissRequest = onClose, sheetState = sheet, containerColor = c.background, dragHandle = {
        Box(Modifier.padding(vertical = 10.dp).size(width = 36.dp, height = 5.dp).clip(CircleShape).background(c.separator))
    }) {
        var copied by remember { mutableStateOf(false) }
        var choosingCopy by remember { mutableStateOf(false) }
        if (choosingCopy) CopySheet(listOf(shown), memory, onDone = { copied = true }) { choosingCopy = false }
        // 文字可长按选中复制
        SelectionContainer {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 36.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${relativeDate(event.startAt)} ${formatClock(event.startAt)} – ${formatClock(event.endAt)} · ${sceneLine(event)}", style = RsType.caption,
                    color = c.textSecondary, modifier = Modifier.weight(1f))
                // 整理错了可以自己改标题、摘要、要点；原话不动
                androidx.compose.foundation.text.selection.DisableSelection {
                    Text("编辑", style = RsType.subhead.copy(fontWeight = FontWeight.Medium), color = c.accent,
                        modifier = Modifier.clip(CircleShape).springClick { editing = true }.padding(horizontal = 10.dp, vertical = 4.dp))
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(shown.title, style = RsType.title, color = c.text)
            shown.original?.takeIf { shown.editedAt != null }?.let { orig ->
                androidx.compose.foundation.text.selection.DisableSelection {
                    Text(if (showOriginal) "已编辑 · 收起原样" else "已编辑 · 查看原样", style = RsType.caption, color = c.textTertiary,
                        modifier = Modifier.padding(top = 4.dp).springClick { showOriginal = !showOriginal })
                }
                androidx.compose.animation.AnimatedVisibility(showOriginal) {
                    RsCard(Modifier.padding(top = 8.dp), padding = PaddingValues(14.dp), color = c.background) {
                        Text("整理时原来写的", style = RsType.caption.copy(fontWeight = FontWeight.Medium), color = c.textTertiary)
                        Spacer(Modifier.height(4.dp))
                        Text(orig.title, style = RsType.subhead.copy(fontWeight = FontWeight.SemiBold), color = c.textSecondary)
                        Text(orig.summary, style = RsType.caption, color = c.textSecondary, modifier = Modifier.padding(top = 2.dp))
                        orig.points.forEach { Text("· $it", style = RsType.caption, color = c.textSecondary) }
                        androidx.compose.foundation.text.selection.DisableSelection {
                            Text("恢复原样", style = RsType.subhead.copy(fontWeight = FontWeight.Medium), color = c.accent,
                                modifier = Modifier.padding(top = 8.dp).springClick {
                                    memory.restoreEvent(shown.id)
                                    shown = shown.copy(title = orig.title, summary = orig.summary, points = orig.points, editedAt = null, original = null)
                                    showOriginal = false
                                })
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(shown.summary, style = RsType.body, color = c.text)

            if (shown.points.isNotEmpty()) {
                SheetSection("要点")
                shown.points.forEach { p ->
                    Row(Modifier.padding(vertical = 3.dp)) {
                        Box(Modifier.padding(top = 8.dp, end = 10.dp).size(5.dp).clip(CircleShape).background(c.accent))
                        Text(p, style = RsType.body, color = c.text)
                    }
                }
            }

            if (event.items.isNotEmpty()) {
                SheetSection("提取出的内容")
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    event.items.forEach { item ->
                        RsCard(padding = PaddingValues(14.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CategoryChip(item.category, item.kind)
                                Spacer(Modifier.weight(1f))
                                item.dueAt?.let { Text(friendlyDue(it, item.dueText), style = RsType.caption, color = c.accent) }
                            }
                            Spacer(Modifier.height(6.dp))
                            Text(item.content, style = RsType.body, color = c.text)
                            val status = when (item.status) {
                                ItemStatus.Pending -> null
                                ItemStatus.Accepted -> "已接受"
                                ItemStatus.Ignored -> "已忽略"
                                ItemStatus.Done -> "已完成"
                            }
                            status?.let { Text(it, style = RsType.caption, color = c.textTertiary, modifier = Modifier.padding(top = 4.dp)) }
                        }
                    }
                }
            }

            Spacer(Modifier.height(22.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("原话", style = RsType.section, color = c.textSecondary, modifier = Modifier.weight(1f))
                // 从这件事的第一句一直播到最后一句
                if (lines.isNotEmpty()) androidx.compose.foundation.text.selection.DisableSelection {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("▶ 播放这件事", style = RsType.subhead.copy(fontWeight = FontWeight.Medium), color = c.accent,
                            modifier = Modifier.clip(CircleShape).background(c.accentSoft).springClick {
                                app.resound.ui.transcript.LinePlayer.play(context, lines, 0, app.resound.ui.transcript.PlaySource.Event(shown))
                            }.padding(horizontal = 12.dp, vertical = 6.dp))
                        // 导出这件事的录音：和原话页多选用同一个导出面板
                        Text("导出", style = RsType.subhead.copy(fontWeight = FontWeight.Medium), color = c.accent,
                            modifier = Modifier.clip(CircleShape).background(c.accentSoft).springClick { exportingEvent = true }
                                .padding(horizontal = 12.dp, vertical = 6.dp))
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            RsCard(padding = PaddingValues(16.dp)) {
                if (lines.isEmpty()) Text("原话加载中…", style = RsType.subhead, color = c.textTertiary)
                else androidx.compose.runtime.CompositionLocalProvider(app.resound.ui.transcript.LocalPlaySource provides app.resound.ui.transcript.PlaySource.Event(shown)) { SpeakerLines(lines) }
            }

            Spacer(Modifier.height(20.dp))
            // 有隐私接口时删除会连录音和原话一起删；否则只从时间线删掉这件事
            val forget = app.resound.AppGraph.isPrivacyReady
            val scope = rememberCoroutineScope()
            // 四个按钮一行等宽：重点 / 复制 / 问问 / 删除。删除点一下原地变红“确认删除”，再点才删
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionChip(if (important) "取消重点" else "★ 重点", if (important) ChipTone.Accent else ChipTone.AccentSoft, Modifier.weight(1f)) {
                    important = !important
                    memory.setStarred(event.id, important)
                }
                ActionChip(if (copied) "已复制" else "复制", ChipTone.Plain, Modifier.weight(1f)) { choosingCopy = true }
                if (app.resound.AppGraph.isAskReady) ActionChip("问问", ChipTone.Plain, Modifier.weight(1f)) {
                    app.resound.AppGraph.ask.askAbout(
                        app.resound.ask.AskSource("event", event.id, event.startAt, event.title, event.summary),
                        "关于“${event.title}”，还有哪些细节？",
                    )
                    onClose(); app.resound.ui.ShellNav.go(1)
                }
                ActionChip(if (confirmDelete) "确认删除" else "删除", if (confirmDelete) ChipTone.Danger else ChipTone.DangerSoft, Modifier.weight(1f)) {
                    if (!confirmDelete) { confirmDelete = true; return@ActionChip }
                    if (forget) scope.launch { runCatching { app.resound.AppGraph.privacy.forgetEvent(event.id) } } else memory.deleteEvent(event.id)
                    onClose()
                }
            }
            if (confirmDelete) Text(if (forget) "会把这件事的录音、原话和提取出的内容一起删掉，无法恢复。" else "会从时间线里删掉这件事。",
                style = RsType.caption, color = c.critical, modifier = Modifier.padding(top = 6.dp))
        }
        }
    }
}

/** 复制用的纯文本：标题、时间、摘要、要点、提取项、原话 */

/** 一条提取项的详情：完整内容、时间、谁来做、依据的原话；可接受/忽略、编辑、删除、跳到所属那件事 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ItemSheet(opened: MemoryItem, memory: MemoryRepository, onOpenEvent: (() -> Unit)?, onClose: () -> Unit) {
    val c = Rs.colors
    // 跟着数据库走：编辑保存、改状态后，详情里马上是新的时间和内容
    val latest by remember(opened.id) { memory.item(opened.id) }.collectAsState(initial = opened)
    val item = latest ?: opened
    val lines by remember(item.eventId) { item.eventId?.let(memory::eventLines) ?: kotlinx.coroutines.flow.flowOf(emptyList()) }.collectAsState(initial = emptyList())
    val source = lines.firstOrNull { it.id == item.sourceLineId }
    val task = item.category in setOf("待办", "约定", "备忘")
    var editing by remember { mutableStateOf(false) }
    // 没定时间时打开强提醒：先进编辑页选时间，强提醒已帮你打开
    var editingStrong by remember { mutableStateOf(false) }
    var deleteArmed by remember(item.id) { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(deleteArmed) { if (deleteArmed) { kotlinx.coroutines.delay(3000); deleteArmed = false } }
    if (editing || editingStrong) app.resound.ui.items.EditItemSheet(item, memory, strongOn = editingStrong) { editing = false; editingStrong = false }
    ModalBottomSheet(onDismissRequest = onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = c.background, dragHandle = {
        Box(Modifier.padding(vertical = 10.dp).size(width = 36.dp, height = 5.dp).clip(CircleShape).background(c.separator))
    }) {
        SelectionContainer {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 36.dp)) {
            CategoryChip(item.category, item.kind)
            Spacer(Modifier.height(12.dp))
            Text(item.content, style = RsType.title, color = c.text)
            // 整理时听到后来有句话说明已经做了：给出那句话，让用户一键确认
            item.doneHint?.takeIf { item.status == ItemStatus.Accepted }?.let { hint ->
                Spacer(Modifier.height(16.dp))
                RsCard(padding = PaddingValues(16.dp)) {
                    Text("可能已经做完了", style = RsType.subhead.copy(fontWeight = FontWeight.SemiBold), color = c.good)
                    Spacer(Modifier.height(6.dp))
                    Text("${friendlyDate(hint.at)} 你说：", style = RsType.caption, color = c.textTertiary)
                    Text("“${hint.text}”", style = RsType.body, color = c.text)
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        ActionChip("是，做完了", ChipTone.Accent, Modifier.weight(1f)) { memory.setItemStatus(item.id, ItemStatus.Done); onClose() }
                        ActionChip("还没", ChipTone.Plain, Modifier.weight(1f)) { memory.dismissDoneHint(item.id) }
                    }
                }
            }
            val facts = buildList {
                item.dueAt?.let { add("时间" to friendlyDue(it, item.dueText)) }
                item.who?.let { add("谁" to it) }
            }
            if (facts.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                RsCard(padding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)) {
                    facts.forEachIndexed { i, (k, v) ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
                            Text(k, style = RsType.subhead, color = c.textSecondary, modifier = Modifier.widthIn(min = 56.dp))
                            Text(v, style = RsType.body, color = c.text)
                        }
                        if (i != facts.lastIndex) Box(Modifier.fillMaxWidth().height(0.5.dp).background(c.separator))
                    }
                }
            }
            if (task && item.status != ItemStatus.Ignored && item.status != ItemStatus.Done) {
                Spacer(Modifier.height(12.dp))
                app.resound.ui.items.StrongReminderRow(item.strongReminder && item.hasTime) { on ->
                    when {
                        // 强提醒要具体几点：没定到几点的先去编辑里选
                        on && !item.hasTime -> editingStrong = true
                        else -> {
                            // 要强提醒说明这条要留：待确认的顺手接受（只有接受的才会到点响）
                            if (on && item.status == ItemStatus.Pending) memory.setItemStatus(item.id, ItemStatus.Accepted)
                            memory.setStrongReminder(item.id, on)
                        }
                    }
                }
            }
            if (item.origin != app.resound.digest.ItemOrigin.Digest) {
                // 手动或语音添加的没有对应的事和原话，显示当时写的、说的那句
                SheetSection(if (item.origin == app.resound.digest.ItemOrigin.Voice) "语音添加" else "手动添加")
                RsCard(padding = PaddingValues(16.dp)) {
                    Text(friendlyDate(item.at), style = RsType.caption, color = c.textTertiary)
                    item.sourceText?.let {
                        Spacer(Modifier.height(4.dp))
                        Text("“$it”", style = RsType.body, color = c.text)
                    }
                }
            } else {
            SheetSection("依据的原话")
            RsCard(padding = PaddingValues(16.dp)) {
                if (source != null) {
                    Text(formatClock(source.startAt), style = RsType.caption, color = c.textTertiary)
                    Spacer(Modifier.height(4.dp))
                    Text("“${source.text}”", style = RsType.body, color = c.text)
                } else Text(if (lines.isEmpty()) "原话加载中…" else "没有找到对应的原话", style = RsType.subhead, color = c.textTertiary)
            }
            }
            // 相似的想法被并进来的那几次：各自的时间和内容，可以拆出来
            val merged by remember(item.id) { memory.mergedItems(item.id) }.collectAsState(initial = emptyList())
            if (merged.isNotEmpty()) {
                SheetSection("还说过 ${merged.size} 次")
                RsCard(padding = PaddingValues(vertical = 4.dp)) {
                    merged.forEachIndexed { i, m ->
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(friendlyDate(m.at), style = RsType.caption, color = c.textTertiary)
                                Text(m.content, style = RsType.subhead, color = c.text)
                            }
                            Text("拆开", style = RsType.caption, color = c.accent,
                                modifier = Modifier.clip(CircleShape).background(c.accentSoft).springClick { memory.unmerge(m.id) }.padding(horizontal = 10.dp, vertical = 4.dp))
                        }
                        if (i != merged.lastIndex) Box(Modifier.padding(start = 16.dp).fillMaxWidth().height(0.5.dp).background(c.separator))
                    }
                }
                Text("意思很像的想法会自动并成一条。并错了就点“拆开”，以后不会再并。", style = RsType.caption, color = c.textTertiary,
                    modifier = Modifier.padding(top = 8.dp, start = 4.dp))
            }
            Spacer(Modifier.height(22.dp))
            if (item.status == ItemStatus.Pending) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ActionChip("接受", ChipTone.Accent, Modifier.weight(1f)) { memory.setItemStatus(item.id, ItemStatus.Accepted); onClose() }
                    ActionChip("忽略", ChipTone.Plain, Modifier.weight(1f)) { memory.setItemStatus(item.id, ItemStatus.Ignored); onClose() }
                }
                Spacer(Modifier.height(10.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (task) ActionChip("编辑", ChipTone.Plain, Modifier.weight(1f)) { editing = true }
                onOpenEvent?.let { ActionChip("查看来源", ChipTone.Plain, Modifier.weight(1f), onClick = it) }
                // 删除：点一下原地变红“确认删除”，3 秒内再点才删
                if (task) ActionChip(if (deleteArmed) "确认删除" else "删除", if (deleteArmed) ChipTone.Danger else ChipTone.DangerSoft, Modifier.weight(1f)) {
                    if (deleteArmed) { memory.deleteItem(item.id); onClose() } else deleteArmed = true
                }
            }
            if (task) Text("删除只删这一条，那件事和原话都还在。", style = RsType.caption, color = c.textTertiary, modifier = Modifier.padding(top = 8.dp, start = 4.dp))
        }
        }
    }
}

private fun friendlyDate(at: Long): String {
    val t = java.time.Instant.ofEpochMilli(at).atZone(java.time.ZoneId.systemDefault())
    return t.format(java.time.format.DateTimeFormatter.ofPattern("M月d日 HH:mm"))
}

@Composable
private fun SheetSection(title: String) {
    Spacer(Modifier.height(22.dp))
    Text(title, style = RsType.section, color = Rs.colors.textSecondary)
    Spacer(Modifier.height(10.dp))
}

/** 时间线上的一件事：左滑标重点 / 再左滑取消；右滑两次删除（见 [SwipeRow]） */
@Composable
internal fun SwipeEvent(event: MemoryEvent, onStar: () -> Unit, onDelete: () -> Unit, content: @Composable (onLongPress: (() -> Unit)?) -> Unit) =
    SwipeRow(event.id, if (event.starred) "取消重点" else "★ 标重点", onStar, onDelete) { content(null) }

/**
 * 可滑动的一行：左滑做 [leftLabel] 那件事；右滑删除要滑两次：第一次过线行变红、提示“再右滑一次删除”，3 秒内再右滑才删。
 * 拖过 72dp 就算过线，过线那一刻轻震。自己处理拖动（系统 SwipeToDismissBox 在“触发后弹回”时隔一次才生效）。
 */
@Composable
internal fun SwipeRow(key: Any, leftLabel: String, onLeft: () -> Unit, onDelete: () -> Unit, content: @Composable () -> Unit) {
    val c = Rs.colors
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    val density = androidx.compose.ui.platform.LocalDensity.current
    val left by androidx.compose.runtime.rememberUpdatedState(onLeft)
    val delete by androidx.compose.runtime.rememberUpdatedState(onDelete)
    val threshold = with(density) { 72.dp.toPx() }
    val offset = remember { androidx.compose.animation.core.Animatable(0f) }
    val scope = rememberCoroutineScope()
    var armed by remember { mutableStateOf(false) }
    // 第一次右滑过线后进入“待删除”，3 秒内再右滑一次才删
    var deleteArmed by remember(key) { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(deleteArmed) { if (deleteArmed) { kotlinx.coroutines.delay(3000); deleteArmed = false } }
    Box(Modifier.fillMaxWidth()) {
        val dx = offset.value
        if (dx != 0f) Box(
            Modifier.matchParentSize()
                .background(if (dx < 0f) (if (armed) c.accent else c.accent.copy(alpha = 0.55f)) else (if (armed) c.critical else c.critical.copy(alpha = 0.55f)))
                .padding(horizontal = 24.dp),
            contentAlignment = if (dx < 0f) Alignment.CenterEnd else Alignment.CenterStart,
        ) {
            val label = if (dx < 0f) leftLabel else if (deleteArmed) "删除" else "标记删除"
            Text(if (armed) "松手$label" else label, style = RsType.subhead.copy(fontWeight = FontWeight.SemiBold), color = Color.White)
        }
        Box(
            Modifier.offset { androidx.compose.ui.unit.IntOffset(offset.value.roundToInt(), 0) }
                .background(if (deleteArmed) androidx.compose.ui.graphics.lerp(c.card, c.critical, 0.12f) else c.card)
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            val fire = armed
                            val dir = offset.value
                            armed = false
                            scope.launch { offset.animateTo(0f, spring(dampingRatio = 0.7f)) }
                            if (fire && dir < 0f) left()
                            if (fire && dir > 0f) { if (deleteArmed) { deleteArmed = false; delete() } else deleteArmed = true }
                        },
                        onDragCancel = { armed = false; scope.launch { offset.animateTo(0f) } },
                    ) { change, drag ->
                        change.consume()
                        val next = (offset.value + drag).coerceIn(-size.width * 0.6f, size.width * 0.6f)
                        scope.launch { offset.snapTo(next) }
                        val now = kotlin.math.abs(next) >= threshold
                        if (now && !armed) haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                        armed = now
                    }
                },
        ) {
            content()
            if (deleteArmed) Text("再右滑一次删除", style = RsType.caption.copy(fontWeight = FontWeight.Medium), color = c.critical,
                modifier = Modifier.align(Alignment.TopEnd).padding(top = 8.dp, end = 16.dp))
        }
    }
}

/** 删除一件事：有隐私接口时连录音和原话一起删（和详情里的“删除”一致） */
@Composable
internal fun rememberDeleteEvent(memory: MemoryRepository): (MemoryEvent) -> Unit {
    val scope = rememberCoroutineScope()
    return remember(memory) {
        { e: MemoryEvent ->
            if (app.resound.AppGraph.isPrivacyReady) scope.launch { runCatching { app.resound.AppGraph.privacy.forgetEvent(e.id) } }
            else memory.deleteEvent(e.id)
        }
    }
}

private enum class ChipTone { Accent, AccentSoft, Plain, Danger, DangerSoft }

/** 详情里一行等宽的小按钮 */
@Composable
private fun ActionChip(text: String, tone: ChipTone, modifier: Modifier, onClick: () -> Unit) {
    val c = Rs.colors
    val (bg, fg) = when (tone) {
        ChipTone.Accent -> c.accent to Color.White
        ChipTone.AccentSoft -> c.accentSoft to c.accent
        ChipTone.Plain -> c.card to c.text
        ChipTone.Danger -> c.critical to Color.White
        ChipTone.DangerSoft -> c.critical.copy(alpha = 0.08f) to c.critical
    }
    val animated by androidx.compose.animation.animateColorAsState(bg, label = "chip")
    Box(modifier.springClick(onClick).clip(CircleShape).background(animated).padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
        Text(text, style = RsType.subhead.copy(fontWeight = FontWeight.Medium), color = fg, maxLines = 1)
    }
}

/** 删除一件事前确认 */
@Composable
internal fun DeleteEventSheet(event: MemoryEvent, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    app.resound.ui.verify.RsSheet("删除“${event.title}”？", "只删这件事和从中提取的条目，原话还在原话页里。", onDismiss = onDismiss) {
        app.resound.ui.verify.ActionButton("删除", app.resound.ui.verify.ActionKind.Danger) { onConfirm(); onDismiss() }
        app.resound.ui.verify.ActionButton("取消", app.resound.ui.verify.ActionKind.Secondary) { onDismiss() }
    }
}
