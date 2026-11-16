package app.resound.ui.today

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.resound.AppGraph
import app.resound.digest.MemoryItem
import app.resound.search.ExpandedSearchHit
import app.resound.search.ExpandedSearchRequest
import app.resound.search.RecordSearch
import app.resound.search.RecordSearchRules
import app.resound.search.SearchKind
import app.resound.search.SearchSource
import app.resound.things.ThingRecord
import app.resound.ui.ShellNav
import app.resound.ui.settings.SettingsPage
import app.resound.ui.theme.Rs
import app.resound.ui.theme.RsType
import app.resound.ui.transcript.PlayerNav
import app.resound.ui.verify.RsSheet
import app.resound.ui.verify.SheetInput
import app.resound.ui.verify.springClick
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** 首页日期旁的放大镜：和日夜切换按钮同一套样式 */
@Composable
internal fun SearchButton(onClick: () -> Unit) {
    val c = Rs.colors
    Box(Modifier.size(40.dp).springClick(onClick).clip(CircleShape).background(c.accentSoft), contentAlignment = Alignment.Center) {
        MagnifierIcon(c.accent, Modifier.size(18.dp))
    }
}

@Composable
private fun MagnifierIcon(ink: Color, modifier: Modifier) {
    Canvas(modifier) {
        val stroke = size.minDimension * 0.13f
        val r = size.minDimension * 0.33f
        val center = Offset(r + stroke, r + stroke)
        drawCircle(ink, r, center, style = Stroke(stroke))
        val d = r * 0.72f
        drawLine(ink, Offset(center.x + d, center.y + d), Offset(size.width - stroke / 2, size.height - stroke / 2), stroke, StrokeCap.Round)
    }
}

/** 在任何页面拿到搜索入口；数据库未就绪（预览、测试）时为 null，不显示放大镜 */
@Composable
internal fun rememberRecordSearch(): RecordSearch? {
    val app = LocalContext.current.applicationContext as? app.resound.ResoundApp
    return remember(app) { app?.let { RecordSearch(it.database) } }
}

private val KIND_LABELS = listOf(null to "全部", SearchKind.Line to "对话", SearchKind.Event to "事件",
    SearchKind.Todo to "待办", SearchKind.Memory to "记忆", SearchKind.Other to "其他")

/** 时间筛选：全部 / 最近 7 天 / 30 天 / 自选；自选为 [from, to] 两天（含） */
internal sealed interface SearchRange {
    data object All : SearchRange
    data class Recent(val days: Int) : SearchRange
    data class Custom(val from: LocalDate, val to: LocalDate) : SearchRange
}
private fun SearchRange.bounds(zone: ZoneId): Pair<Long?, Long?> = when (this) {
    SearchRange.All -> null to null
    is SearchRange.Recent -> LocalDate.now().minusDays(days - 1L).atStartOfDay(zone).toInstant().toEpochMilli() to null
    is SearchRange.Custom -> from.atStartOfDay(zone).toInstant().toEpochMilli() to to.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
}

/** 点开一条结果后在面板里就地显示的详情；关掉回到搜索结果 */
private sealed interface SearchDetail {
    data class Item(val item: MemoryItem) : SearchDetail
    data class ThingCard(val thingId: Long) : SearchDetail
    data class ThingPending(val record: ThingRecord) : SearchDetail
}

private const val OVERVIEW_ROWS = 3

/**
 * 卡 194：搜对话、事件、待办、记忆和其他（寻物、专题、每日回顾、问问）。
 * “全部”按类别分块各给几条，点“查看全部”切到那一类；单类按天分组，滑到底接着加载更早的。
 * 待办、记忆在面板里就地打开，其余跳到各自页面。
 */
@Composable
fun RecordSearchSheet(search: RecordSearch, initialKind: SearchKind? = null, onDismiss: () -> Unit) {
    val c = Rs.colors
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val zone = remember { ZoneId.systemDefault() }
    var query by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf(initialKind) }
    var range by remember { mutableStateOf<SearchRange>(SearchRange.All) }
    // 各类的命中数（最多 101 = 100+），来自同一关键词和时间的“全部”查询
    var counts by remember { mutableStateOf<Map<SearchKind, Int>>(emptyMap()) }
    var overview by remember { mutableStateOf<Map<SearchKind, List<ExpandedSearchHit>>>(emptyMap()) }
    var hits by remember { mutableStateOf<List<ExpandedSearchHit>?>(emptyList()) }
    var next by remember { mutableStateOf<Int?>(null) }
    var loadingMore by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var detail by remember { mutableStateOf<SearchDetail?>(null) }
    val scroll = rememberScrollState()

    LaunchedEffect(query, kind, range) {
        failed = false; next = null; loadingMore = false
        if (query.isBlank()) { hits = emptyList(); overview = emptyMap(); counts = emptyMap(); return@LaunchedEffect }
        delay(250) // 打字停一下再查，避免每个字都查一次
        hits = null
        val (from, to) = range.bounds(zone)
        try {
            val all = search.searchAll(ExpandedSearchRequest(query, null, from, to, pageSize = OVERVIEW_ROWS))
            counts = all.categories.mapValues { it.value.count }
            overview = all.categories.mapValues { it.value.hits }
            val k = kind
            if (k == null) { hits = overview.values.flatten(); next = null }
            else {
                val page = search.searchAll(ExpandedSearchRequest(query, k, from, to)).categories[k]
                hits = page?.hits.orEmpty(); next = page?.nextOffset
            }
            scroll.scrollTo(0)
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) {
            app.resound.diagnostics.FailureNotes.record("ui/today/RecordSearchSheet.kt:search", e)
            failed = true; hits = emptyList()
        }
    }
    // 单类结果滑到底：带上已加载条数再取一页
    val atBottom by remember { derivedStateOf { scroll.maxValue > 0 && scroll.value >= scroll.maxValue - 600 } }
    LaunchedEffect(atBottom, next, kind) {
        val k = kind ?: return@LaunchedEffect
        val offset = next ?: return@LaunchedEffect
        if (!atBottom || loadingMore) return@LaunchedEffect
        loadingMore = true
        val (from, to) = range.bounds(zone)
        try {
            val page = search.searchAll(ExpandedSearchRequest(query, k, from, to, offsets = mapOf(k to offset))).categories[k]
            hits = hits.orEmpty() + page?.hits.orEmpty(); next = page?.nextOffset
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) {
            app.resound.diagnostics.FailureNotes.record("ui/today/RecordSearchSheet.kt:more", e); next = null
        } finally { loadingMore = false }
    }
    val words = remember(query) { RecordSearchRules.words(query) }

    val open: (ExpandedSearchHit) -> Unit = { hit -> scope.launch { openHit(hit, zone, onDismiss) { detail = it } } }

    when (val d = detail) {
        is SearchDetail.Item -> if (AppGraph.isMemoryReady) ItemSheet(d.item, AppGraph.memory,
            onOpenEvent = d.item.eventId?.let { id -> { scope.launch {
                val event = runCatching { AppGraph.memory.eventById(id) }.getOrNull()
                if (event != null) { detail = null; onDismiss(); PlayerNav.openEvent = event }
            } } }) { detail = null }
        is SearchDetail.ThingCard -> app.resound.ui.things.ThingSheet(d.thingId, app.resound.ui.things.thingRepo()) { detail = null }
        is SearchDetail.ThingPending -> app.resound.ui.things.ThingConfirmSheet(d.record, app.resound.ui.things.thingRepo()) { detail = null }
        null -> RsSheet("搜索记录", onDismiss = onDismiss, fixedHeight = true, scrollState = scroll) {
            SheetInput(query, "搜原话、事件、待办、记忆，空格分开多个词") { query = it }
            KindCapsules(kind, counts.takeIf { query.isNotBlank() }) { kind = it }
            RangeCapsules(range) { picked ->
                if (picked !is SearchRange.Custom) range = picked
                else pickRange(context, (range as? SearchRange.Custom)) { range = it }
            }
            val list = hits
            when {
                query.isBlank() -> Hint("例如：合同、老王 周五、报销。所有日期都会搜到")
                failed -> Hint("搜索没有完成，请稍后再试")
                list == null -> Hint("正在查…")
                list.isEmpty() -> Hint("没有找到“${query.trim()}”" + if (range != SearchRange.All) "，可以把时间放宽到“全部”" else "")
                kind == null -> KIND_LABELS.mapNotNull { it.first }.forEach { k ->
                    val rows = overview[k].orEmpty()
                    if (rows.isEmpty()) return@forEach
                    SectionHeader(KIND_LABELS.first { it.first == k }.second, counts[k] ?: rows.size) { kind = k }
                    HitCard(rows, words, zone, showDay = true, onOpen = open)
                }
                else -> {
                    list.groupBy { Instant.ofEpochMilli(it.at).atZone(zone).toLocalDate() }.forEach { (day, items) ->
                        Text(dayLabel(day), style = RsType.subhead.copy(fontWeight = FontWeight.Medium), color = c.textSecondary,
                            modifier = Modifier.padding(start = 4.dp, top = 8.dp))
                        HitCard(items, words, zone, showDay = false, onOpen = open)
                    }
                    when {
                        next != null -> Hint("正在加载更早的…")
                        list.size >= 20 -> Hint("已经到底了")
                    }
                }
            }
        }
    }
}

/** 跳转：原话、事件、专题、回顾、问问去各自页面；待办、记忆、寻物在搜索面板里就地打开 */
private suspend fun openHit(hit: ExpandedSearchHit, zone: ZoneId, dismiss: () -> Unit, showDetail: (SearchDetail) -> Unit) {
    when (hit.source) {
        SearchSource.Line -> {
            PlayerNav.transcriptFocus = Instant.ofEpochMilli(hit.at).atZone(zone).toLocalDate() to (hit.segmentId ?: 0L)
            dismiss(); ShellNav.openSettings(SettingsPage.Transcript)
        }
        SearchSource.Event -> {
            val event = if (AppGraph.isMemoryReady) runCatching { AppGraph.memory.eventById(hit.id) }.getOrNull() else null
            if (event != null) { dismiss(); PlayerNav.openEvent = event }
        }
        SearchSource.Todo, SearchSource.Memory -> {
            val item = if (AppGraph.isMemoryReady) runCatching { AppGraph.memory.item(hit.id).first() }.getOrNull() else null
            if (item != null) showDetail(SearchDetail.Item(item))
        }
        SearchSource.Thing -> showDetail(SearchDetail.ThingCard(hit.thingId ?: hit.id))
        SearchSource.ThingRecord -> {
            val pending = if (hit.status == "Pending")
                runCatching { app.resound.ui.things.thingRepo().pending().first().firstOrNull { it.id == hit.id } }.getOrNull() else null
            when {
                pending != null -> showDetail(SearchDetail.ThingPending(pending))
                else -> hit.thingId?.let { showDetail(SearchDetail.ThingCard(it)) }
            }
        }
        SearchSource.Topic -> { dismiss(); app.resound.ui.topic.TopicUi.openTopic = hit.id }
        SearchSource.Review -> {
            val day = hit.reviewDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
                ?: Instant.ofEpochMilli(hit.at).atZone(zone).toLocalDate()
            dismiss(); ShellNav.openReview(day)
        }
        SearchSource.AskMessage, SearchSource.AskConversation -> {
            val id = hit.conversationId ?: return
            if (AppGraph.isAskReady) { AppGraph.ask.openConversation(id); dismiss(); ShellNav.go(1) }
        }
    }
}

@Composable
internal fun KindCapsules(selected: SearchKind?, counts: Map<SearchKind, Int>?, onSelect: (SearchKind?) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        KIND_LABELS.forEach { (k, label) ->
            val n = k?.let { counts?.get(it) }
            if (k != null && counts != null && n == 0 && selected != k) return@forEach // 没命中的类别先藏起来，少点干扰
            Capsule(label, n?.let(::countText), selected == k) { onSelect(k) }
        }
    }
}

@Composable
internal fun RangeCapsules(selected: SearchRange, onSelect: (SearchRange) -> Unit) {
    val custom = selected as? SearchRange.Custom
    val fmt = DateTimeFormatter.ofPattern("M/d")
    val options = listOf<Pair<SearchRange, String>>(SearchRange.All to "全部时间", SearchRange.Recent(7) to "最近 7 天",
        SearchRange.Recent(30) to "最近 30 天",
        (custom ?: SearchRange.Custom(LocalDate.now(), LocalDate.now())) to (custom?.let { "${it.from.format(fmt)}–${it.to.format(fmt)}" } ?: "自选日期"))
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (r, label) ->
            val on = if (r is SearchRange.Custom) custom != null else r == selected
            Capsule(label, null, on, small = true) { onSelect(r) }
        }
    }
}

private fun countText(n: Int) = if (n > 100) "100+" else "$n"

@Composable
private fun Capsule(label: String, count: String?, on: Boolean, small: Boolean = false, onClick: () -> Unit) {
    val c = Rs.colors
    val bg by animateColorAsState(if (on) c.selected else if (small) c.elevated else c.well(), spring(stiffness = Spring.StiffnessMediumLow), label = "capsule")
    Row(Modifier.clip(CircleShape).background(bg).then(if (small && !on) Modifier.border(0.5.dp, c.separator, CircleShape) else Modifier)
        .springClick(onClick).padding(horizontal = 12.dp, vertical = if (small) 5.dp else 7.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = (if (small) RsType.caption else RsType.subhead).copy(fontWeight = FontWeight.Medium),
            color = if (on) c.onSelected else c.textSecondary, maxLines = 1)
        if (count != null) {
            Spacer(Modifier.width(4.dp))
            Text(count, style = RsType.caption, color = if (on) c.onSelected.copy(alpha = 0.85f) else c.textTertiary)
        }
    }
}

/** 自选日期：先选开始日，再选结束日；结束早于开始时自动对调 */
private fun pickRange(context: android.content.Context, current: SearchRange.Custom?, onPicked: (SearchRange.Custom) -> Unit) {
    val today = LocalDate.now()
    val start = current?.from ?: today.minusDays(6)
    android.app.DatePickerDialog(context, { _, y, m, d ->
        val from = LocalDate.of(y, m + 1, d)
        val end = current?.to?.takeIf { !it.isBefore(from) } ?: today
        android.app.DatePickerDialog(context, { _, y2, m2, d2 ->
            val to = LocalDate.of(y2, m2 + 1, d2)
            onPicked(if (to.isBefore(from)) SearchRange.Custom(to, from) else SearchRange.Custom(from, to))
        }, end.year, end.monthValue - 1, end.dayOfMonth).apply { setTitle("到哪一天"); datePicker.maxDate = System.currentTimeMillis() }.show()
    }, start.year, start.monthValue - 1, start.dayOfMonth).apply { setTitle("从哪一天"); datePicker.maxDate = System.currentTimeMillis() }.show()
}

@Composable
internal fun SectionHeader(title: String, count: Int, onMore: () -> Unit) {
    val c = Rs.colors
    Row(Modifier.fillMaxWidth().padding(start = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = RsType.subhead.copy(fontWeight = FontWeight.SemiBold), color = c.text, modifier = Modifier.weight(1f))
        if (count > OVERVIEW_ROWS) Text("查看全部 ${countText(count)} 条", style = RsType.subhead.copy(fontWeight = FontWeight.Medium),
            color = c.accent, modifier = Modifier.clip(CircleShape).springClick(onMore).padding(horizontal = 8.dp, vertical = 4.dp))
    }
}

@Composable
internal fun HitCard(rows: List<ExpandedSearchHit>, words: List<String>, zone: ZoneId, showDay: Boolean, onOpen: (ExpandedSearchHit) -> Unit) {
    val c = Rs.colors
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(c.well()).animateContentSize()) {
        rows.forEachIndexed { i, hit ->
            HitRow(hit, words, zone, showDay) { onOpen(hit) }
            if (i != rows.lastIndex) Box(Modifier.fillMaxWidth().padding(start = 64.dp).height(0.5.dp).background(c.separator))
        }
    }
}

/** 面板里的卡片底色：浅色时面板本身是白的，卡片用页面底色才分得开；深色沿用卡片色 */
private fun app.resound.ui.theme.RsColors.well() = if (isDark) card else background

@Composable
private fun Hint(text: String) {
    Text(text, style = RsType.subhead, color = Rs.colors.textTertiary, modifier = Modifier.padding(start = 4.dp, top = 8.dp))
}

/** 结果一行的小标签：对话写说话人，其余写类别；待办完成了加“已完成” */
private fun ExpandedSearchHit.tag(): String? = when (source) {
    SearchSource.Line -> speaker
    SearchSource.Event -> "事件"
    SearchSource.Todo, SearchSource.Memory -> itemCategory ?: if (source == SearchSource.Todo) "待办" else "记忆"
    SearchSource.Thing, SearchSource.ThingRecord -> "寻物"
    SearchSource.Topic -> "专题"
    SearchSource.Review -> "每日回顾"
    SearchSource.AskMessage, SearchSource.AskConversation -> "问问"
}

@Composable
private fun HitRow(hit: ExpandedSearchHit, words: List<String>, zone: ZoneId, showDay: Boolean, onClick: () -> Unit) {
    val c = Rs.colors
    val time = Instant.ofEpochMilli(hit.at).atZone(zone)
    val today = LocalDate.now()
    val stamp = when {
        !showDay -> time.toLocalTime().format(DateTimeFormatter.ofPattern("HH:mm"))
        time.toLocalDate() == today -> time.toLocalTime().format(DateTimeFormatter.ofPattern("HH:mm"))
        time.year == today.year -> time.format(DateTimeFormatter.ofPattern("M/d"))
        else -> time.format(DateTimeFormatter.ofPattern("yy/M/d"))
    }
    Row(Modifier.fillMaxWidth().springClick(onClick).padding(PaddingValues(horizontal = 16.dp, vertical = 12.dp)),
        verticalAlignment = Alignment.Top) {
        Text(stamp, style = RsType.subhead, color = c.textTertiary, modifier = Modifier.width(48.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                hit.tag()?.let { Text(it, style = RsType.caption.copy(fontWeight = FontWeight.Medium),
                    color = if (hit.source == SearchSource.Line) c.textSecondary else c.accent) }
                if (hit.completed) Text("已完成", style = RsType.caption, color = c.textTertiary,
                    modifier = Modifier.clip(CircleShape).background(if (c.isDark) c.background else c.elevated).padding(horizontal = 6.dp, vertical = 1.dp))
            }
            val title = hit.title.trim()
            if (title.isNotEmpty() && hit.source != SearchSource.Line && !hit.text.startsWith(title))
                Text(highlight(title, words, c.accent), style = RsType.body.copy(fontWeight = FontWeight.Medium), color = c.text, maxLines = 1)
            Text(highlight(hit.text, words, c.accent), style = RsType.body,
                color = if (hit.completed) c.textSecondary else c.text, maxLines = 3)
        }
    }
}

/** 关键词用主题色加粗，不分大小写 */
internal fun highlight(text: String, words: List<String>, color: Color): AnnotatedString = buildAnnotatedString {
    append(text)
    for (word in words) {
        var from = 0
        while (true) {
            val at = text.indexOf(word, from, ignoreCase = true)
            if (at < 0) break
            addStyle(SpanStyle(color = color, fontWeight = FontWeight.SemiBold), at, at + word.length)
            from = at + word.length
        }
    }
}

private fun dayLabel(day: LocalDate): String {
    val today = LocalDate.now()
    val prefix = when (day) { today -> "今天 · "; today.minusDays(1) -> "昨天 · "; else -> "" }
    val pattern = if (day.year == today.year) "M月d日 EEEE" else "yyyy年M月d日 EEEE"
    return prefix + day.format(DateTimeFormatter.ofPattern(pattern, Locale.CHINA))
}
