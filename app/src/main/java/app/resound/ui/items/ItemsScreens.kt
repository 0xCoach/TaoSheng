package app.resound.ui.items

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.resound.diary.DiaryRepository
import app.resound.digest.ItemStatus
import app.resound.digest.MemoryEvent
import app.resound.digest.MemoryItem
import app.resound.digest.MemoryRepository
import app.resound.review.DailyReview
import app.resound.review.ReviewRepository
import app.resound.transcribe.TranscriptRepository
import app.resound.ui.review.ReviewRow
import app.resound.ui.review.ReviewSheet
import app.resound.ui.theme.Rs
import app.resound.ui.theme.RsType
import app.resound.ui.today.CategoryChip
import app.resound.ui.today.EventSheet
import app.resound.ui.today.ItemSheet
import app.resound.ui.today.friendlyDue
import app.resound.ui.verify.RsCard
import app.resound.ui.verify.formatClock
import app.resound.ui.verify.springClick
import app.resound.ui.verify.Pill
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/** 列表怎么分组 */
private enum class Grouping {
    /** 按提到的那天 */
    Mentioned,
    /** 按截止/约定那天，没有时间的放最后 */
    Due,
    /** 按完成那天 */
    Done,
    /** 不分组，手动拖动排序 */
    Manual,
}

/** 一个分段：名字 + 取数 + 分组方式 */
private class Tab(val name: String, val grouping: Grouping, val source: (MemoryRepository) -> Flow<List<MemoryItem>>)

private val MEMORY_TABS = listOf(Tab("重点", Grouping.Mentioned) { flowOf(emptyList()) }) + listOf("灵感", "关键信息", "决定", "知识").map { cat -> Tab(cat, Grouping.Mentioned) { it.items(setOf(cat), null) } } +
    Tab("卡片", Grouping.Mentioned) { flowOf(emptyList()) }

private enum class TodoTab(val title: String) { Pending("待确认"), Ongoing("进行中"), Done("已完成") }

private val TASKS = setOf("待办", "约定", "备忘")

/**
 * 待办：待确认（可全部接受/忽略，3 天前的收起）· 进行中（上面有时间的按时间排，下面随时做的长按拖动）· 已完成（默认最近 7 天，可往前翻）。
 * 每行左滑完成（待确认里是接受），右滑两次删除；点圆圈完成，点一行看详情。
 */
@Composable
fun TodoScreen(memory: MemoryRepository, bottomPadding: Dp) {
    val c = Rs.colors
    var tabIndex by rememberSaveable { mutableStateOf(0) }
    val tab = TodoTab.entries[tabIndex.coerceIn(0, TodoTab.entries.lastIndex)]
    val today = remember { LocalDate.now() }
    // 已完成默认看 7 天，“查看更早”每次再往前一个月
    var doneDays by rememberSaveable { mutableStateOf(7) }
    val items by remember(tab, doneDays) {
        when (tab) {
            TodoTab.Pending -> memory.items(TASKS, setOf(ItemStatus.Pending))
            TodoTab.Ongoing -> memory.ongoing()
            TodoTab.Done -> memory.doneItems(today.minusDays(doneDays - 1L))
        }
    }.collectAsState(initial = null)
    var opened by remember { mutableStateOf<MemoryItem?>(null) }
    var reminders by remember { mutableStateOf(false) }
    var olderOpen by rememberSaveable { mutableStateOf(false) }
    var ignoreArmed by remember { mutableStateOf(false) }
    LaunchedEffect(ignoreArmed) { if (ignoreArmed) { kotlinx.coroutines.delay(3000); ignoreArmed = false } }
    val listState = rememberLazyListState()
    // 随时做的：拖动时先在本地挪，松手再存；数据库发回新顺序后以它为准
    var order by remember(items, tab) { mutableStateOf(if (tab == TodoTab.Ongoing) items.orEmpty().filter { it.dueAt == null } else emptyList()) }
    val haptic = LocalHapticFeedback.current
    val reorder = rememberReorderableLazyListState(listState) { from, to ->
        val a = order.indexOfFirst { it.id == from.key }
        val b = order.indexOfFirst { it.id == to.key }
        if (a >= 0 && b >= 0) order = order.toMutableList().apply { add(b, removeAt(a)) }
    }
    val toggle: (MemoryItem) -> Unit = { memory.setItemStatus(it.id, if (it.status == ItemStatus.Done) ItemStatus.Accepted else ItemStatus.Done) }
    val left: (MemoryItem) -> Unit = { if (it.status == ItemStatus.Pending) memory.setItemStatus(it.id, ItemStatus.Accepted) else toggle(it) }
    val leftLabel: (MemoryItem) -> String = { when (it.status) { ItemStatus.Pending -> "接受"; ItemStatus.Done -> "撤回完成"; else -> "完成" } }
    val delete: (MemoryItem) -> Unit = { memory.deleteItem(it.id) }
    val swipe = RowSwipe(leftLabel, left, delete)

    LazyColumn(
        Modifier.fillMaxSize().background(c.background).windowInsetsPadding(WindowInsets.safeDrawing),
        state = listState,
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = bottomPadding + 24.dp),
    ) {
        item { PageTitle("待办"); Spacer(Modifier.height(12.dp)) }
        item { Capsules(TodoTab.entries.map { it.title }, tabIndex) { tabIndex = it }; Spacer(Modifier.height(16.dp)) }
        val list = items
        if (list != null && list.isEmpty() && tab != TodoTab.Done) item { Empty(emptyText(tab.title)) }
        when (tab) {
            TodoTab.Pending -> {
                val cutoff = System.currentTimeMillis() - 3 * 86_400_000L
                val recent = list.orEmpty().filter { it.at >= cutoff }
                val older = list.orEmpty().filter { it.at < cutoff }
                if (list.orEmpty().size > 1) item {
                    Row(Modifier.padding(bottom = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Pill("全部接受 ${list.orEmpty().size} 条", filled = true) { memory.setItemsStatus(list.orEmpty().map { it.id }, ItemStatus.Accepted) }
                        // 全部忽略要点两次：第一次变红提示
                        Text(if (ignoreArmed) "再点一次全部忽略" else "全部忽略", style = RsType.subhead.copy(fontWeight = FontWeight.Medium),
                            color = if (ignoreArmed) Color.White else c.textSecondary,
                            modifier = Modifier.clip(CircleShape).background(if (ignoreArmed) c.critical else c.card).springClick {
                                if (ignoreArmed) { ignoreArmed = false; memory.setItemsStatus(list.orEmpty().map { it.id }, ItemStatus.Ignored) } else ignoreArmed = true
                            }.padding(horizontal = 16.dp, vertical = 8.dp))
                    }
                }
                groupedItems(recent, Grouping.Mentioned, checkable = false, keyPrefix = "p", swipe = swipe, onToggle = toggle) { opened = it }
                if (older.isNotEmpty()) {
                    item(key = "older") {
                        Row(Modifier.fillMaxWidth().springClick { olderOpen = !olderOpen }.padding(start = 4.dp, top = 4.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("更早没确认的 · ${older.size} 条", style = RsType.subhead.copy(fontWeight = FontWeight.Medium), color = c.textSecondary, modifier = Modifier.weight(1f))
                            Text(if (olderOpen) "收起" else "展开", style = RsType.caption, color = c.accent)
                        }
                    }
                    if (olderOpen) groupedItems(older, Grouping.Mentioned, checkable = false, keyPrefix = "o", swipe = swipe, onToggle = toggle) { opened = it }
                }
            }
            TodoTab.Ongoing -> {
                val timed = list.orEmpty().filter { it.dueAt != null }.sortedBy { it.dueAt }
                item(key = "ongoingHead") {
                    Row(Modifier.padding(bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(if (timed.isEmpty()) "随时做的" else "有时间的 · ${timed.size}", style = RsType.section, color = c.textSecondary,
                            modifier = Modifier.weight(1f).padding(start = 4.dp))
                        Pill("提醒", filled = false) { reminders = true }
                    }
                }
                if (timed.isNotEmpty()) {
                    groupedItems(timed, Grouping.Due, checkable = true, keyPrefix = "t", swipe = swipe, onToggle = toggle) { opened = it }
                    if (order.isNotEmpty()) item(key = "freeHead") {
                        Text("随时做的 · ${order.size}", style = RsType.section, color = c.textSecondary, modifier = Modifier.padding(start = 4.dp, top = 6.dp, bottom = 10.dp))
                    }
                }
                if (order.size > 1) item(key = "dragHint") { Hint("长按拖动，调整先后") }
                items(order, key = { it.id }) { item ->
                    ReorderableItem(reorder, key = item.id) { dragging ->
                        val i = order.indexOf(item)
                        val lift by animateDpAsState(if (dragging) 12.dp else 0.dp, label = "lift")
                        val shape = segmentShape(i == 0 || dragging, i == order.lastIndex || dragging)
                        Column(
                            Modifier.shadow(lift, shape, ambientColor = c.shadow, spotColor = c.shadow).clip(shape).background(c.card)
                                .longPressDraggableHandle(
                                    onDragStarted = { haptic.performHapticFeedback(HapticFeedbackType.LongPress) },
                                    onDragStopped = { memory.reorder(order.map { it.id }) },
                                ),
                        ) {
                            swipe.wrap(item) { ItemRow(item, checkable = true, onToggle = { toggle(item) }) { opened = item } }
                            if (i != order.lastIndex && !dragging) Box(Modifier.padding(start = 52.dp).fillMaxWidth().height(0.5.dp).background(c.separator))
                        }
                    }
                }
            }
            TodoTab.Done -> {
                val label = if (doneDays == 7) "最近 7 天" else "最近 ${doneDays} 天"
                item(key = "doneHead") {
                    Text("${label}完成 ${list.orEmpty().size} 件", style = RsType.section, color = c.textSecondary, modifier = Modifier.padding(start = 4.dp, bottom = 12.dp))
                }
                if (list != null && list.isEmpty()) item { Empty(emptyText(tab.title)) }
                groupedItems(list.orEmpty(), Grouping.Done, checkable = true, keyPrefix = "d", swipe = swipe, onToggle = toggle) { opened = it }
                item(key = "doneMore") {
                    Box(Modifier.fillMaxWidth().padding(top = 4.dp), contentAlignment = Alignment.Center) {
                        Pill("查看更早", filled = false) { doneDays += 30 }
                    }
                }
            }
        }
    }
    opened?.let { item -> ItemSheet(item, memory, onOpenEvent = null) { opened = null } }
    if (reminders) ReminderSheet(memory) { reminders = false }
}

/** 一行的滑动动作：左滑 [label] / 右滑两次删除 */
private class RowSwipe(val label: (MemoryItem) -> String, val left: (MemoryItem) -> Unit, val delete: (MemoryItem) -> Unit) {
    @Composable
    fun wrap(item: MemoryItem, content: @Composable () -> Unit) =
        app.resound.ui.today.SwipeRow(item.id, label(item), { left(item) }, { delete(item) }, content)
}

/** 记忆：按天（日期条 + 当天日记，可导出）· 按类（灵感 · 关键信息 · 决定 · 知识 · 回顾）。openReview 不为空时跳到那天并打开回顾 */
@Composable
fun MemoryScreen(
    memory: MemoryRepository,
    reviews: ReviewRepository?,
    transcripts: TranscriptRepository?,
    diary: DiaryRepository?,
    openReview: LocalDate?,
    onReviewShown: () -> Unit,
    bottomPadding: Dp,
    /** false 时只跳到那天，不弹回顾 */
    showReview: Boolean = true,
) {
    val c = Rs.colors
    var byDay by rememberSaveable { mutableStateOf(true) }
    var epochDay by rememberSaveable { mutableLongStateOf(LocalDate.now().toEpochDay()) }
    val date = LocalDate.ofEpochDay(epochDay)
    var tabIndex by rememberSaveable { mutableStateOf(0) }
    val tabs = MEMORY_TABS
    val tab = tabs[tabIndex.coerceIn(0, tabs.lastIndex)]
    var opened by remember { mutableStateOf<MemoryItem?>(null) }
    var openedEvent by remember { mutableStateOf<MemoryEvent?>(null) }
    var openedReview by remember { mutableStateOf<DailyReview?>(null) }
    var exporting by remember { mutableStateOf(false) }
    var linesOpen by remember(epochDay) { mutableStateOf(false) }
    // 时间线顺序：记住上次的选择
    val uiPrefs = androidx.compose.ui.platform.LocalContext.current.getSharedPreferences("ui", android.content.Context.MODE_PRIVATE)
    var newestFirst by remember { mutableStateOf(uiPrefs.getBoolean("timelineNewestFirst", false)) }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val keys = remember { ArrayList<Any>() }
    // 换了日期回到顶部，从回顾开始看
    LaunchedEffect(epochDay) { if (listState.firstVisibleItemIndex > 1) listState.scrollToItem(0) }

    // 按天要的数据
    val review by remember(date, reviews) { reviews?.daily(date) ?: flowOf(null) }.collectAsState(initial = null)
    val events by remember(date) { memory.events(date) }.collectAsState(initial = null)
    val day by remember(date) { memory.dayItems(date) }.collectAsState(initial = null)
    val segments by remember(date, transcripts) { transcripts?.day(date) ?: flowOf(emptyList()) }.collectAsState(initial = emptyList())
    val lines = remember(segments) { dayLines(segments) }
    val skills = remember { app.resound.ui.skills.skillRepo() }
    val dayCards by remember(date) { skills.cards(date) }.collectAsState(initial = emptyList())
    var openedCard by remember { mutableStateOf<app.resound.skills.SkillCard?>(null) }
    var makingCard by remember { mutableStateOf(false) }
    var deletingEvent by remember { mutableStateOf<app.resound.digest.MemoryEvent?>(null) }
    val deleteNow = app.resound.ui.today.rememberDeleteEvent(memory)
    val cardSkillIds by remember { skills.skillsWithCards }.collectAsState(initial = emptyList())
    val skillNames = skills.skills.collectAsState().value.associate { it.id to it.name }
    var chipIndex by rememberSaveable { mutableStateOf(0) }
    val starred by remember { memory.starredEvents() }.collectAsState(initial = null)
    // “卡片”分类的第二排小胶囊：回顾 + 出过卡的技能（按最近出卡排序）
    val chips = (if (reviews != null) listOf("review") else emptyList()) + cardSkillIds.filter { it != "review" }
    val chip = chips.getOrNull(chipIndex.coerceIn(0, (chips.size - 1).coerceAtLeast(0)))
    val history by remember(chip) { if (chip == null || chip == "review") flowOf(emptyList()) else skills.cardsOf(chip) }.collectAsState(initial = emptyList())
    val hourSlots = remember(events, newestFirst) {
        val sorted = if (newestFirst) events.orEmpty().sortedByDescending { it.startAt } else events.orEmpty().sortedBy { it.startAt }
        sorted.groupBy { eventHourOf(it.startAt) }.map { (h, list) ->
            HourSlot(h, list.map { RailEntry("ev${it.id}", "${app.resound.ui.verify.formatClock(it.startAt)}  ${it.title}") })
        }
    }
    // 跨了至少 3 个小时才有跳转的意义；全挤在一两个小时里只会显示孤零零一个数字
    val showIndex = byDay && events.orEmpty().size >= 6 && hourSlots.size >= 3
    // 按类要的数据
    val items by remember(tab) { tab.source(memory) }.collectAsState(initial = null)
    val reviewList by remember(reviews) { reviews?.recent() ?: flowOf(emptyList()) }.collectAsState(initial = emptyList())
    val reviewState = reviews?.state?.collectAsState()?.value

    // 从通知点进来：切到按天、那一天，回顾出来后打开
    LaunchedEffect(openReview) { openReview?.let { byDay = true; epochDay = it.toEpochDay() } }
    LaunchedEffect(openReview, review) {
        if (openReview != null && !showReview) { onReviewShown(); return@LaunchedEffect }
        if (openReview != null && review?.date == openReview) { openedReview = review; onReviewShown() }
    }

    Box(Modifier.fillMaxSize().background(c.background).windowInsetsPadding(WindowInsets.safeDrawing)) {
    LazyColumn(
        Modifier.fillMaxSize(),
        state = listState,
        // 有小时刻度时右边多留出刻度的宽度
        contentPadding = PaddingValues(start = 16.dp, end = if (showIndex) 40.dp else 16.dp, top = 12.dp, bottom = bottomPadding + 24.dp),
        // 按天时各块自己留间距（时间线一件事一行要拼成整卡）
        verticalArrangement = Arrangement.spacedBy(if (byDay) 0.dp else 16.dp),
    ) {
        keys.clear(); keys.add("title"); if (byDay) keys.add("strip")
        item(key = "title") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PageTitle("记忆", Modifier.weight(1f))
                if (byDay && diary != null) {
                    Text("导出", style = RsType.subhead.copy(fontWeight = FontWeight.Medium), color = c.accent,
                        modifier = Modifier.springClick { exporting = true }.clip(CircleShape).background(c.accentSoft)
                            .padding(horizontal = 14.dp, vertical = 7.dp))
                    Spacer(Modifier.width(8.dp))
                }
                ModeSwitch(byDay) { byDay = it }
            }
        }
        if (byDay) {
            item(key = "strip") { Box(Modifier.padding(top = 8.dp, bottom = 16.dp)) { DatePickerStrip(memory, date) { epochDay = it.toEpochDay() } } }
            dayDiary(
                date, review, events, day, lines, linesOpen,
                onToggleLines = { linesOpen = !linesOpen },
                onOpenReview = { openedReview = review },
                onOpenEvent = { openedEvent = it },
                onOpenItem = { opened = it },
                onToggleDone = { memory.setItemStatus(it.id, if (it.status == ItemStatus.Done) ItemStatus.Accepted else ItemStatus.Done) },
                onGenerate = reviews?.let { r -> { r.generateNow(date) } },
                generating = reviewState?.generating == true,
                newestFirst = newestFirst,
                keysOut = keys,
                onToggleOrder = { newestFirst = !newestFirst; uiPrefs.edit().putBoolean("timelineNewestFirst", newestFirst).apply() },
                cards = dayCards,
                onOpenCard = { openedCard = it },
                onMakeCard = { makingCard = true },
                onStar = { memory.setStarred(it.id, !it.starred) },
                onDelete = { deleteNow(it) },
            )
        } else {
            item(key = "caps") { Capsules(tabs.map { it.name }, tabIndex) { tabIndex = it } }
            if (tab.name == "重点") {
                val list = starred
                if (list != null && list.isEmpty()) item { Empty("在时间线上把一件事往左滑，就能标成重点。标过的事会集中放在这里，问问能查，每日和每周回顾也会跟进") }
                list.orEmpty().groupBy { dateOf(it.starredAt ?: it.startAt) }.forEach { (d, evs) ->
                    item(key = "star$d") {
                        Column(Modifier.padding(bottom = 16.dp)) {
                            Text(dayLabel(d), style = RsType.caption.copy(fontWeight = FontWeight.Medium), color = c.textTertiary,
                                modifier = Modifier.padding(start = 4.dp, bottom = 8.dp))
                            RsCard(padding = PaddingValues(vertical = 4.dp)) {
                                evs.forEachIndexed { i, e ->
                                    app.resound.ui.today.SwipeEvent(e, { memory.setStarred(e.id, !e.starred) }, { deleteNow(e) }) {
                                        lp -> app.resound.ui.today.TimelineLine(e, lp) { openedEvent = e }
                                    }
                                    if (i != evs.lastIndex) Box(Modifier.padding(start = 76.dp, end = 18.dp).fillMaxWidth().height(0.5.dp).background(c.separator))
                                }
                            }
                        }
                    }
                }
            } else if (tab.name == "卡片") {
                if (chips.size > 1) item(key = "chips") {
                    Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        chips.forEachIndexed { i, id ->
                            val on = id == chip
                            val name = if (id == "review") "回顾" else skillNames[id] ?: id
                            Text(name, style = RsType.caption.copy(fontWeight = FontWeight.Medium), color = if (on) c.accent else c.textSecondary,
                                modifier = Modifier.springClick { chipIndex = i }.clip(CircleShape).background(if (on) c.accentSoft else Color.Transparent)
                                    .padding(horizontal = 12.dp, vertical = 6.dp))
                        }
                    }
                }
                item(key = "chipGap") { Spacer(Modifier.height(12.dp)) }
                when {
                    chip == null -> item { Empty("开了技能以后，会议、收工打卡这些卡片会出现在这里") }
                    chip == "review" -> if (reviewList.isEmpty()) item { Empty("每天晚上会自动生成当天的回顾") }
                        else item {
                            RsCard(padding = PaddingValues(vertical = 2.dp)) {
                                reviewList.forEachIndexed { i, r ->
                                    ReviewRow(r) { openedReview = r }
                                    if (i != reviewList.lastIndex) Box(Modifier.padding(start = 18.dp).fillMaxWidth().height(0.5.dp).background(c.separator))
                                }
                            }
                        }
                    else -> {
                        history.groupBy { it.date }.forEach { (d, list) ->
                            item(key = "h$chip$d") {
                                Column(Modifier.padding(bottom = 16.dp)) {
                                    Text(dayLabel(d), style = RsType.caption.copy(fontWeight = FontWeight.Medium), color = c.textTertiary,
                                        modifier = Modifier.padding(start = 4.dp, bottom = 8.dp))
                                    RsCard(padding = PaddingValues(vertical = 2.dp)) {
                                        list.forEachIndexed { i, card ->
                                            app.resound.ui.skills.SkillCardRow(card, Modifier.springClick { openedCard = card })
                                            if (i != list.lastIndex) Box(Modifier.padding(start = 64.dp).fillMaxWidth().height(0.5.dp).background(c.separator))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                val list = items
                if (list != null && list.isEmpty()) item { Empty(emptyText(tab.name)) }
                groupedItems(list.orEmpty(), Grouping.Mentioned, checkable = false, keyPrefix = tab.name, onToggle = {}) { opened = it }
            }
        }
    }
    // 右侧时间刻度：点小时跳到那个小时，按住拖在事件之间连续滑并预览
    if (showIndex) {
        val scope = androidx.compose.runtime.rememberCoroutineScope()
        val currentKey by remember {
            androidx.compose.runtime.derivedStateOf {
                listState.layoutInfo.visibleItemsInfo.firstOrNull { (it.key as? String)?.startsWith("ev") == true }?.key
            }
        }
        HourIndex(hourSlots, currentKey, Modifier.align(Alignment.TopEnd).padding(top = 150.dp, bottom = bottomPadding + 20.dp, end = 4.dp)) { key ->
            val index = keys.indexOf(key)
            if (index >= 0) scope.launch { listState.scrollToItem(index) }
        }
    }
    }
    opened?.let { item ->
        ItemSheet(item, memory, onOpenEvent = events.orEmpty().firstOrNull { it.id == item.eventId }?.let { e -> { opened = null; openedEvent = e } }) { opened = null }
    }
    openedEvent?.let { e -> EventSheet(e, memory) { openedEvent = null } }
    openedReview?.let { r -> ReviewSheet(r) { openedReview = null } }
    openedCard?.let { card -> app.resound.ui.skills.SkillCardSheet(card, skills) { openedCard = null } }
    if (makingCard) app.resound.ui.skills.MakeCardSheet(date, skills) { makingCard = false }
    deletingEvent?.let { e -> app.resound.ui.today.DeleteEventSheet(e, { memory.deleteEvent(e.id) }) { deletingEvent = null } }
    if (exporting && diary != null) ExportSheet(diary, date) { exporting = false }
}

/** 分组列表：每组一个小标题 + 一张卡 */
private fun androidx.compose.foundation.lazy.LazyListScope.groupedItems(
    list: List<MemoryItem>, grouping: Grouping, checkable: Boolean, keyPrefix: String,
    swipe: RowSwipe? = null, onToggle: (MemoryItem) -> Unit, onOpen: (MemoryItem) -> Unit,
) {
    val groups: List<Pair<String, List<MemoryItem>>> = when (grouping) {
        Grouping.Due -> list.groupBy { it.dueAt?.let(::dateOf) }.toList()
            .sortedWith(compareBy<Pair<LocalDate?, List<MemoryItem>>> { it.first == null }.thenBy { it.first })
            .map { (d, l) -> (d?.let { dueLabel(it) } ?: "没定时间") to l }
        Grouping.Done -> list.groupBy { dateOf(it.doneAt ?: it.at) }.toList().sortedByDescending { it.first }.map { (d, l) -> "${dayLabel(d)} 完成" to l }
        else -> list.groupBy { dateOf(it.at) }.toList().map { (d, l) -> dayLabel(d) to l }
    }
    items(groups, key = { keyPrefix + it.first }) { (label, dayItems) ->
        val c = Rs.colors
        Column(Modifier.padding(bottom = if (grouping == Grouping.Manual) 0.dp else 16.dp)) {
            Text("$label · ${dayItems.size} 条", style = RsType.caption.copy(fontWeight = FontWeight.Medium),
                color = c.textTertiary, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp))
            RsCard(padding = PaddingValues(vertical = 2.dp)) {
                dayItems.forEachIndexed { i, item ->
                    if (swipe != null) swipe.wrap(item) { ItemRow(item, checkable, onToggle = { onToggle(item) }) { onOpen(item) } }
                    else ItemRow(item, checkable, onToggle = { onToggle(item) }) { onOpen(item) }
                    if (i != dayItems.lastIndex) Box(Modifier.padding(start = if (checkable) 52.dp else 18.dp).fillMaxWidth().height(0.5.dp).background(c.separator))
                }
            }
        }
    }
}

/** 拖动列表里每行自己画卡片：首行圆上角、末行圆下角，拼起来像一张卡；拖起时整行变圆角浮起 */
private fun segmentShape(top: Boolean, bottom: Boolean) =
    RoundedCornerShape(topStart = if (top) 22.dp else 0.dp, topEnd = if (top) 22.dp else 0.dp,
        bottomStart = if (bottom) 22.dp else 0.dp, bottomEnd = if (bottom) 22.dp else 0.dp)

@Composable
private fun PageTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, style = RsType.largeTitle, color = Rs.colors.text, modifier = modifier.padding(horizontal = 4.dp, vertical = 8.dp))
}

@Composable
private fun Hint(text: String) {
    Text(text, style = RsType.caption, color = Rs.colors.textTertiary, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp))
}

@Composable
private fun Empty(text: String) {
    Text(text, style = RsType.subhead, color = Rs.colors.textTertiary, textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(top = 60.dp))
}

/** 按天 / 按类 小切换 */
@Composable
private fun ModeSwitch(byDay: Boolean, onChange: (Boolean) -> Unit) {
    val c = Rs.colors
    Row(Modifier.clip(CircleShape).background(c.card).padding(3.dp)) {
        listOf(true to "按天", false to "按类").forEach { (v, label) ->
            val on = v == byDay
            val bg by animateColorAsState(if (on) c.accentSoft else c.accentSoft.copy(alpha = 0f), spring(stiffness = Spring.StiffnessMediumLow), label = "mode")
            val fg by animateColorAsState(if (on) c.accent else c.textSecondary, label = "modeFg")
            Text(label, style = RsType.subhead.copy(fontWeight = FontWeight.Medium), color = fg,
                modifier = Modifier.clip(CircleShape).background(bg).springClick { onChange(v) }.padding(horizontal = 12.dp, vertical = 6.dp))
        }
    }
}

@Composable
private fun Capsules(names: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    val c = Rs.colors
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        names.forEachIndexed { i, n ->
            val on = i == selected
            val bg by animateColorAsState(if (on) c.accent else c.card, spring(stiffness = Spring.StiffnessMediumLow), label = "cap")
            val fg by animateColorAsState(if (on) Color.White else c.textSecondary, label = "capFg")
            Text(n, style = RsType.subhead.copy(fontWeight = FontWeight.Medium), color = fg,
                modifier = Modifier.springClick { onSelect(i) }.clip(CircleShape).background(bg).padding(horizontal = 16.dp, vertical = 8.dp))
        }
    }
}

@Composable
internal fun ItemRow(item: MemoryItem, checkable: Boolean, onToggle: () -> Unit, onOpen: () -> Unit) {
    val c = Rs.colors
    val done = item.status == ItemStatus.Done
    Row(Modifier.fillMaxWidth().springClick(onOpen).padding(horizontal = 16.dp, vertical = 13.dp), verticalAlignment = Alignment.Top) {
        if (checkable) {
            // 完成圈：点一下完成，再点撤回
            Box(
                Modifier.padding(top = 1.dp).size(22.dp).clip(CircleShape)
                    .then(if (done) Modifier.background(c.good) else Modifier.border(1.5.dp, c.textTertiary, CircleShape))
                    .springClick(onToggle),
                contentAlignment = Alignment.Center,
            ) { if (done) Text("✓", style = RsType.caption.copy(fontWeight = FontWeight.Bold), color = Color.White) }
            Spacer(Modifier.width(14.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                item.content,
                style = RsType.body.copy(textDecoration = if (done) TextDecoration.LineThrough else null),
                color = if (done) c.textTertiary else c.text,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            val meta = buildList {
                item.dueAt?.let { add(friendlyDue(it, item.dueText)) }
                item.who?.takeIf { it != "我" }?.let { add(it) }
                if (item.at > 0) add(formatClock(item.at) + " 提到")
                if (item.repeats > 1) add("说过 ${item.repeats} 次")
            }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                CategoryChip(item.category, item.kind)
                if (item.doneHint != null && item.status == ItemStatus.Accepted) {
                    Spacer(Modifier.width(6.dp))
                    Text("可能做完了", style = RsType.caption.copy(fontWeight = FontWeight.Medium), color = c.good,
                        modifier = Modifier.clip(CircleShape).background(c.good.copy(alpha = 0.12f)).padding(horizontal = 8.dp, vertical = 2.dp))
                }
                if (meta.isNotEmpty()) {
                    Spacer(Modifier.width(8.dp))
                    // 过了时间标红，24 小时内标橙，再远用主色
                    val left = item.dueAt?.let { it - System.currentTimeMillis() }
                    val tint = when {
                        done || left == null -> c.textTertiary
                        left < 0 -> c.critical
                        left < 86_400_000L -> c.warning
                        else -> c.accent
                    }
                    Text(meta.joinToString(" · "), style = RsType.caption, color = tint, maxLines = 1)
                }
            }
        }
    }
}

private fun dayLabel(day: LocalDate): String = when (ChronoUnit.DAYS.between(day, LocalDate.now())) {
    0L -> "今天"
    1L -> "昨天"
    2L -> "前天"
    else -> day.format(DateTimeFormatter.ofPattern("M月d日"))
}

/** 约定按将来的日子说：今天 / 明天 / 后天 / 10月28日 */
private fun dueLabel(day: LocalDate): String = when (val d = ChronoUnit.DAYS.between(LocalDate.now(), day)) {
    in Long.MIN_VALUE..-1L -> "已过 ${-d} 天 · " + day.format(DateTimeFormatter.ofPattern("M月d日"))
    0L -> "今天"
    1L -> "明天"
    2L -> "后天"
    else -> day.format(DateTimeFormatter.ofPattern("M月d日"))
}

private fun emptyText(tab: String) = when (tab) {
    "待确认" -> "没有要确认的了"
    "进行中" -> "手上没有进行中的事"
    "已完成" -> "完成的事会出现在这里"
    else -> "还没有$tab"
}

private fun eventHourOf(at: Long) = java.time.Instant.ofEpochMilli(at).atZone(java.time.ZoneId.systemDefault()).hour
