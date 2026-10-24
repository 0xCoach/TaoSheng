package app.resound.ui.items

import android.content.Intent
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.resound.diary.DiaryRepository
import app.resound.digest.DayItems
import app.resound.digest.ItemStatus
import app.resound.digest.MemoryEvent
import app.resound.digest.MemoryItem
import app.resound.digest.MemoryRepository
import app.resound.review.DailyReview
import app.resound.transcribe.TranscriptLine
import app.resound.ui.theme.Rs
import app.resound.ui.theme.RsType
import app.resound.ui.today.TimelineLine
import app.resound.ui.today.partOfDay
import app.resound.ui.verify.Pill
import app.resound.ui.verify.RsCard
import app.resound.ui.verify.formatClock
import app.resound.ui.verify.springClick
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.Locale

private val WEEKDAYS = listOf("一", "二", "三", "四", "五", "六", "日")

/** 日期条：一周七天，有内容的日子下面打点；点月份展开整月 */
@Composable
internal fun DatePickerStrip(memory: MemoryRepository, date: LocalDate, onPick: (LocalDate) -> Unit) {
    val c = Rs.colors
    val today = LocalDate.now()
    var monthOpen by remember { mutableStateOf(false) }
    var shownMonth by remember(date) { mutableStateOf(YearMonth.from(date)) }
    val weekStart = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    // 一周可能跨两个月，两个月的打点都要
    val active1 by remember(YearMonth.from(weekStart)) { memory.activeDays(YearMonth.from(weekStart)) }.collectAsState(initial = emptySet())
    val active2 by remember(YearMonth.from(weekStart.plusDays(6))) { memory.activeDays(YearMonth.from(weekStart.plusDays(6))) }.collectAsState(initial = emptySet())
    val monthActive by remember(shownMonth) { memory.activeDays(shownMonth) }.collectAsState(initial = emptySet())

    RsCard(padding = PaddingValues(horizontal = 10.dp, vertical = 12.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            val arrow by animateFloatAsState(if (monthOpen) 180f else 0f, spring(stiffness = Spring.StiffnessMediumLow), label = "arrow")
            Row(Modifier.springClick { monthOpen = !monthOpen; shownMonth = YearMonth.from(date) }, verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (monthOpen) shownMonth.format(DateTimeFormatter.ofPattern("yyyy年M月")) else date.format(DateTimeFormatter.ofPattern("yyyy年M月")),
                    style = RsType.headline, color = c.text,
                )
                Spacer(Modifier.width(4.dp))
                Text("⌄", style = RsType.headline, color = c.textTertiary, modifier = Modifier.rotate(arrow).padding(bottom = 4.dp))
            }
            Spacer(Modifier.weight(1f))
            if (date != today) {
                Text("回到今天", style = RsType.subhead.copy(fontWeight = FontWeight.Medium), color = c.accent,
                    modifier = Modifier.springClick { monthOpen = false; onPick(today) }.padding(horizontal = 8.dp, vertical = 4.dp))
            }
            NavArrow("‹") { if (monthOpen) shownMonth = shownMonth.minusMonths(1) else onPick(date.minusWeeks(1)) }
            NavArrow("›", enabled = if (monthOpen) shownMonth < YearMonth.from(today) else weekStart.plusDays(7) <= today) {
                if (monthOpen) shownMonth = shownMonth.plusMonths(1) else onPick(minOf(date.plusWeeks(1), today))
            }
        }
        Spacer(Modifier.height(8.dp))
        AnimatedVisibility(!monthOpen, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
            Row(Modifier.fillMaxWidth()) {
                for (i in 0..6) {
                    val d = weekStart.plusDays(i.toLong())
                    DayCell(d, WEEKDAYS[i], d == date, d == today, d in active1 || d in active2, enabled = d <= today, Modifier.weight(1f)) {
                        onPick(d)
                    }
                }
            }
        }
        AnimatedVisibility(monthOpen, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
            Column {
                Row(Modifier.fillMaxWidth()) {
                    WEEKDAYS.forEach { Text(it, style = RsType.caption, color = c.textTertiary, textAlign = TextAlign.Center, modifier = Modifier.weight(1f)) }
                }
                Spacer(Modifier.height(4.dp))
                val first = shownMonth.atDay(1)
                val lead = first.dayOfWeek.value - 1
                val cells = lead + shownMonth.lengthOfMonth()
                for (row in 0 until (cells + 6) / 7) {
                    Row(Modifier.fillMaxWidth()) {
                        for (col in 0..6) {
                            val n = row * 7 + col - lead + 1
                            if (n < 1 || n > shownMonth.lengthOfMonth()) Spacer(Modifier.weight(1f))
                            else {
                                val d = shownMonth.atDay(n)
                                DayCell(d, null, d == date, d == today, d in monthActive, enabled = d <= today, Modifier.weight(1f)) {
                                    monthOpen = false; onPick(d)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NavArrow(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    val c = Rs.colors
    Box(Modifier.size(34.dp).clip(CircleShape).springClick { if (enabled) onClick() }, contentAlignment = Alignment.Center) {
        Text(text, style = RsType.title, color = if (enabled) c.textSecondary else c.separator)
    }
}

@Composable
private fun DayCell(d: LocalDate, weekday: String?, selected: Boolean, today: Boolean, active: Boolean, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val c = Rs.colors
    val bg by animateColorAsState(if (selected) c.accent else c.accent.copy(alpha = 0f), spring(stiffness = Spring.StiffnessMediumLow), label = "dayBg")
    val fg = when {
        selected -> Color.White
        !enabled -> c.separator
        today -> c.accent
        else -> c.text
    }
    Column(modifier.alpha(if (enabled) 1f else 0.6f).springClick { if (enabled) onClick() }.padding(vertical = 3.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        weekday?.let {
            Text(it, style = RsType.caption, color = if (today && !selected) c.accent else c.textTertiary)
            Spacer(Modifier.height(4.dp))
        }
        Box(Modifier.size(36.dp).clip(CircleShape).background(bg), contentAlignment = Alignment.Center) {
            Text("${d.dayOfMonth}", style = RsType.body.copy(fontWeight = if (selected || today) FontWeight.SemiBold else FontWeight.Normal), color = fg)
        }
        Spacer(Modifier.height(3.dp))
        Box(Modifier.size(4.dp).clip(CircleShape).background(if (active && !selected) c.accent.copy(alpha = 0.7f) else Color.Transparent))
    }
}

/** 当天日记的内容：回顾 → 时间线 → 待办 → 灵感等 → 原话（折叠） */
internal fun LazyListScope.dayDiary(
    date: LocalDate,
    review: DailyReview?,
    events: List<MemoryEvent>?,
    day: DayItems?,
    lines: List<TranscriptLine>,
    linesOpen: Boolean,
    onToggleLines: () -> Unit,
    onOpenReview: () -> Unit,
    onOpenEvent: (MemoryEvent) -> Unit,
    onOpenItem: (MemoryItem) -> Unit,
    onToggleDone: (MemoryItem) -> Unit,
    /** 手动生成 / 重新生成这天的回顾；没有回顾功能时为 null */
    onGenerate: (() -> Unit)? = null,
    generating: Boolean = false,
    /** 时间线倒序（新的在上） */
    newestFirst: Boolean = false,
    onToggleOrder: () -> Unit = {},
    /** 按出现顺序记下每个列表项的 key，给小时刻度算跳转位置 */
    keysOut: MutableList<Any>? = null,
    /** 当天的技能卡 */
    cards: List<app.resound.skills.SkillCard> = emptyList(),
    onOpenCard: (app.resound.skills.SkillCard) -> Unit = {},
    /** 手动给这天生成卡片；没有技能功能时为 null */
    onMakeCard: (() -> Unit)? = null,
    /** 时间线左滑标重点、右滑删除 */
    onStar: (MemoryEvent) -> Unit = {},
    onDelete: (MemoryEvent) -> Unit = {},
) {
    fun item(key: Any, content: @Composable androidx.compose.foundation.lazy.LazyItemScope.() -> Unit) {
        keysOut?.add(key); this@dayDiary.item(key = key, content = content)
    }
    // 列表本身不留间距（时间线一件事一行要拼成整卡），各块自己在下方留 16dp
    val gap = Modifier.padding(bottom = 16.dp)
    val evs = events.orEmpty()
    val todo = day?.let { it.mentioned.isNotEmpty() || it.due.isNotEmpty() || it.done.isNotEmpty() } == true
    val notes = day?.notes.orEmpty()
    if (events != null && day != null && review == null && evs.isEmpty() && !todo && notes.isEmpty() && lines.isEmpty() && cards.isEmpty()) {
        item(key = "empty") { Box(gap) { EmptyDay(date) } }
        return
    }
    if (review != null) item(key = "review") { Box(gap) { ReviewLead(review, generating, onOpenReview, onGenerate) } }
    else if (evs.isNotEmpty() && onGenerate != null) item(key = "reviewMake") { Box(gap) { MakeReview(date, generating, onGenerate) } }

    if (cards.isNotEmpty() || (evs.isNotEmpty() && onMakeCard != null)) item(key = "cards") { Box(gap) { DayCards(cards, onOpenCard, onMakeCard) } }

    if (evs.isNotEmpty()) {
        item(key = "tlTitle") {
            Row(Modifier.fillMaxWidth().padding(start = 4.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("时间线", style = RsType.headline, color = Rs.colors.text)
                Text("  ${evs.size} 件事", style = RsType.caption, color = Rs.colors.textTertiary, modifier = Modifier.weight(1f))
                OrderToggle(newestFirst, onToggleOrder)
            }
        }
        // 一件事一个列表项（拖动条才能精确定位），按位置画成整卡：首行圆上角、末行圆下角
        val sorted = if (newestFirst) evs.sortedByDescending { it.startAt } else evs.sortedBy { it.startAt }
        sorted.groupBy { partOfDay(it.startAt) }.forEach { (part, group) ->
            item(key = "tl$part") {
                Text("$part · ${group.size} 件", style = RsType.caption.copy(fontWeight = FontWeight.Medium), color = Rs.colors.textTertiary,
                    modifier = Modifier.padding(start = 4.dp, top = 4.dp, bottom = 8.dp))
            }
            keysOut?.addAll(group.map { "ev${it.id}" })
            items(group, key = { "ev${it.id}" }) { e ->
                val i = group.indexOf(e)
                val last = i == group.lastIndex
                val shape = androidx.compose.foundation.shape.RoundedCornerShape(
                    topStart = if (i == 0) 22.dp else 0.dp, topEnd = if (i == 0) 22.dp else 0.dp,
                    bottomStart = if (last) 22.dp else 0.dp, bottomEnd = if (last) 22.dp else 0.dp)
                Column(Modifier.padding(bottom = if (last) 16.dp else 0.dp).clip(shape).background(Rs.colors.card)
                    .padding(top = if (i == 0) 4.dp else 0.dp, bottom = if (last) 4.dp else 0.dp)) {
                    app.resound.ui.today.SwipeEvent(e, { onStar(e) }, { onDelete(e) }) { lp -> TimelineLine(e, lp) { onOpenEvent(e) } }
                    if (!last) Box(Modifier.padding(start = 76.dp, end = 18.dp).fillMaxWidth().height(0.5.dp).background(Rs.colors.separator))
                }
            }
        }
    }
    if (todo) item(key = "todo") {
        Column(gap) {
            SectionTitle("待办", null)
            RsCard(padding = PaddingValues(vertical = 2.dp)) {
                var first = true
                listOf("当天提到" to day!!.mentioned, "当天到期" to day.due, "当天完成" to day.done).forEach { (name, list) ->
                    if (list.isEmpty()) return@forEach
                    if (!first) Box(Modifier.fillMaxWidth().height(0.5.dp).background(Rs.colors.separator))
                    first = false
                    GroupLabel(name, list.size)
                    list.forEachIndexed { i, item ->
                        ItemRow(item, checkable = item.category != "约定" || item.status == ItemStatus.Done, onToggle = { onToggleDone(item) }) { onOpenItem(item) }
                        if (i != list.lastIndex) Box(Modifier.padding(start = 52.dp).fillMaxWidth().height(0.5.dp).background(Rs.colors.separator))
                    }
                }
            }
        }
    }
    notes.groupBy { it.category }.toList().sortedBy { NOTE_ORDER.indexOf(it.first) }.forEach { (cat, list) ->
        item(key = "note$cat") {
            Column(gap) {
                SectionTitle(cat, "${list.size} 条")
                RsCard(padding = PaddingValues(vertical = 2.dp)) {
                    list.forEachIndexed { i, item ->
                        ItemRow(item, checkable = false, onToggle = {}) { onOpenItem(item) }
                        if (i != list.lastIndex) Box(Modifier.padding(start = 18.dp).fillMaxWidth().height(0.5.dp).background(Rs.colors.separator))
                    }
                }
            }
        }
    }
    if (lines.isNotEmpty()) {
        item(key = "linesHead") { Box(gap) { LinesHeader(lines.size, linesOpen, onToggleLines) } }
        if (linesOpen) keysOut?.addAll(lines.map { "line${it.id}" })
        if (linesOpen) items(lines, key = { "line${it.id}" }) { Box(Modifier.padding(bottom = 12.dp)) { LineRow(it) } }
    }
}

private val NOTE_ORDER = listOf("灵感", "关键信息", "决定", "知识")

/** 当天的技能卡：标题行带“＋ 生成”，下面一张白卡逐张列出；还没有卡时只留一行入口 */
@Composable
private fun DayCards(cards: List<app.resound.skills.SkillCard>, onOpen: (app.resound.skills.SkillCard) -> Unit, onMake: (() -> Unit)?) {
    val c = Rs.colors
    Column {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("卡片", style = RsType.headline, color = c.text)
            Text(if (cards.isEmpty()) "" else "  ${cards.size} 张", style = RsType.caption, color = c.textTertiary, modifier = Modifier.weight(1f))
            if (onMake != null) Text("＋ 生成", style = RsType.subhead.copy(fontWeight = FontWeight.Medium), color = c.accent,
                modifier = Modifier.springClick(onMake).clip(CircleShape).background(c.accentSoft).padding(horizontal = 12.dp, vertical = 5.dp))
        }
        if (cards.isEmpty()) {
            Text("会议、收工打卡这些技能卡会出现在这里，也可以点“＋ 生成”手动给这天做一张。", style = RsType.caption, color = c.textTertiary,
                modifier = Modifier.padding(horizontal = 4.dp))
        } else RsCard(padding = PaddingValues(vertical = 2.dp)) {
            cards.forEachIndexed { i, card ->
                app.resound.ui.skills.SkillCardRow(card, Modifier.springClick { onOpen(card) })
                if (i != cards.lastIndex) Box(Modifier.padding(start = 64.dp).fillMaxWidth().height(0.5.dp).background(c.separator))
            }
        }
    }
}

/** 正序 / 倒序 小胶囊，箭头随顺序翻转 */
@Composable
private fun OrderToggle(newestFirst: Boolean, onToggle: () -> Unit) {
    val c = Rs.colors
    val turn by animateFloatAsState(if (newestFirst) 180f else 0f, spring(stiffness = Spring.StiffnessMediumLow), label = "order")
    Row(Modifier.clip(CircleShape).background(c.card).springClick(onToggle).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text("↓", style = RsType.subhead.copy(fontWeight = FontWeight.SemiBold), color = c.accent, modifier = Modifier.rotate(turn))
        Spacer(Modifier.width(4.dp))
        Text(if (newestFirst) "新的在上" else "早的在上", style = RsType.caption.copy(fontWeight = FontWeight.Medium), color = c.textSecondary)
    }
}



@Composable
private fun SectionTitle(title: String, trailing: String?) {
    val c = Rs.colors
    Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, bottom = 8.dp), verticalAlignment = Alignment.Bottom) {
        Text(title, style = RsType.headline, color = c.text)
        Spacer(Modifier.weight(1f))
        trailing?.let { Text(it, style = RsType.caption, color = c.textTertiary) }
    }
}

@Composable
private fun GroupLabel(name: String, n: Int) {
    val c = Rs.colors
    Text("$name · $n", style = RsType.caption.copy(fontWeight = FontWeight.Medium), color = c.textTertiary,
        modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 2.dp))
}

/** 回顾导语：浅橙底，一句话 + 查看完整 */
@Composable
private fun ReviewLead(review: DailyReview, generating: Boolean, onOpen: () -> Unit, onGenerate: (() -> Unit)?) {
    val c = Rs.colors
    val lead = review.sections.firstOrNull { it.title == "一句话" }?.lines?.firstOrNull() ?: review.brief
    var confirm by remember(review.generatedAt) { mutableStateOf(false) }
    RsCard(Modifier.springClick(onOpen), padding = PaddingValues(horizontal = 18.dp, vertical = 16.dp), color = c.accentSoft) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("当天回顾", style = RsType.caption.copy(fontWeight = FontWeight.Medium), color = c.accent)
            Text(" · ${formatClock(review.generatedAt)} 生成", style = RsType.caption, color = c.textTertiary)
        }
        Spacer(Modifier.height(6.dp))
        Text(lead, style = RsType.headline.copy(fontWeight = FontWeight.Medium), color = c.text)
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("查看完整回顾 ›", style = RsType.subhead.copy(fontWeight = FontWeight.Medium), color = c.accent, modifier = Modifier.weight(1f))
            // 重新生成会花一次大模型费用，点两次才生效
            if (onGenerate != null) {
                Text(
                    when { generating -> "正在生成…"; confirm -> "确定重新生成？"; else -> "重新生成" },
                    style = RsType.caption.copy(fontWeight = FontWeight.Medium), color = c.accent,
                    modifier = Modifier.clip(CircleShape).background(c.card).springClick {
                        if (generating) return@springClick
                        if (confirm) { onGenerate(); confirm = false } else confirm = true
                    }.padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }
    }
}

/** 这天有事件但还没有回顾：给一个生成按钮 */
@Composable
private fun MakeReview(date: LocalDate, generating: Boolean, onGenerate: () -> Unit) {
    val c = Rs.colors
    RsCard(padding = PaddingValues(horizontal = 18.dp, vertical = 16.dp), color = c.accentSoft) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("这天还没有回顾", style = RsType.body.copy(fontWeight = FontWeight.SemiBold), color = c.text)
                Text(
                    if (date == LocalDate.now()) "每晚会自动生成，也可以现在先生成一版" else "可以现在补生成",
                    style = RsType.caption, color = c.textSecondary,
                )
            }
            Spacer(Modifier.width(10.dp))
            Pill(if (generating) "生成中…" else "生成回顾", filled = !generating) { if (!generating) onGenerate() }
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, style = RsType.caption, color = Rs.colors.textTertiary, modifier = Modifier.padding(start = 4.dp))
}

@Composable
private fun EmptyDay(date: LocalDate) {
    val c = Rs.colors
    Text(if (date == LocalDate.now()) "今天还没有整理出内容" else "这天没有记录",
        style = RsType.subhead, color = c.textTertiary, textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(top = 60.dp))
}

@Composable
private fun LinesHeader(n: Int, open: Boolean, onToggle: () -> Unit) {
    val c = Rs.colors
    val arrow by animateFloatAsState(if (open) 90f else 0f, spring(stiffness = Spring.StiffnessMediumLow), label = "lines")
    RsCard(Modifier.springClick(onToggle), padding = PaddingValues(horizontal = 18.dp, vertical = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("原话", style = RsType.body.copy(fontWeight = FontWeight.SemiBold), color = c.text)
            Spacer(Modifier.width(8.dp))
            Text("$n 句", style = RsType.subhead, color = c.textTertiary)
            Spacer(Modifier.weight(1f))
            Text("›", style = RsType.title, color = c.textTertiary, modifier = Modifier.rotate(arrow))
        }
    }
}

@Composable
private fun LineRow(line: TranscriptLine) {
    val c = Rs.colors
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
        Text(formatClock(line.startAt), style = RsType.caption, color = c.textTertiary, modifier = Modifier.width(44.dp).padding(top = 2.dp))
        Column(Modifier.weight(1f)) {
            line.speakerName?.let { Text(it, style = RsType.caption.copy(fontWeight = FontWeight.Medium), color = if (it == "我") c.accent else c.textSecondary) }
            Text(line.text, style = RsType.body, color = c.text)
        }
    }
}

// ---------- 导出 ----------

private enum class Range(val label: String) { Day("这一天"), Week("这一周"), Month("这个月"), Custom("自选") }

/** 导出：选范围、要不要原话，走系统分享；下面是每晚同步到坚果云的开关 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ExportSheet(diary: DiaryRepository, date: LocalDate, onClose: () -> Unit) {
    val c = Rs.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sync by diary.cloudSync.collectAsState()
    var range by remember { mutableStateOf(Range.Day) }
    var withLines by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf(false) }
    var custom by remember { mutableStateOf(date.minusDays(6) to date) }
    val today = LocalDate.now()
    val (from, to) = when (range) {
        Range.Day -> date to date
        Range.Week -> date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).let { it to minOf(it.plusDays(6), today) }
        Range.Month -> date.withDayOfMonth(1).let { it to minOf(date.with(TemporalAdjusters.lastDayOfMonth()), today) }
        Range.Custom -> custom
    }

    ModalBottomSheet(
        onDismissRequest = onClose,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = c.background,
        dragHandle = { Box(Modifier.padding(vertical = 10.dp).size(width = 36.dp, height = 5.dp).clip(CircleShape).background(c.separator)) },
    ) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 36.dp)) {
            Text("导出日记", style = RsType.title, color = c.text)
            Spacer(Modifier.height(4.dp))
            Text("生成 Markdown 文件，可以发到微信、存进笔记软件", style = RsType.subhead, color = c.textSecondary)
            Spacer(Modifier.height(18.dp))
            RsCard(padding = PaddingValues(horizontal = 18.dp, vertical = 16.dp)) {
                Text("范围", style = RsType.caption.copy(fontWeight = FontWeight.Medium), color = c.textTertiary)
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Range.entries.forEach { r -> Choice(r.label, r == range, Modifier.weight(1f)) { range = r; if (r == Range.Custom) picking = true } }
                }
                Spacer(Modifier.height(12.dp))
                Text(rangeText(from, to), style = RsType.body.copy(fontWeight = FontWeight.Medium), color = c.text,
                    modifier = if (range == Range.Custom) Modifier.springClick { picking = true } else Modifier)
                Spacer(Modifier.height(14.dp))
                Box(Modifier.fillMaxWidth().height(0.5.dp).background(c.separator))
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("附上原话", style = RsType.body, color = c.text)
                        Text("每天末尾附当天全部原话，文件会长很多", style = RsType.caption, color = c.textSecondary)
                    }
                    Switch(withLines) { withLines = it }
                }
            }
            Spacer(Modifier.height(16.dp))
            Pill(if (busy) "正在生成…" else "分享", filled = !busy, modifier = Modifier.fillMaxWidth()) {
                if (busy) return@Pill
                busy = true
                scope.launch {
                    runCatching {
                        if (diary.markdown(from, to, includeLines = false).isBlank() && !withLines) null
                        else diary.exportFile(from, to, withLines)
                    }.onSuccess { uri ->
                        if (uri == null) Toast.makeText(context, "这段时间没有内容", Toast.LENGTH_SHORT).show()
                        else {
                            val send = Intent(Intent.ACTION_SEND).setType("text/markdown").putExtra(Intent.EXTRA_STREAM, uri)
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            context.startActivity(Intent.createChooser(send, "导出日记"))
                        }
                    }.onFailure { Toast.makeText(context, "导出没成功：${it.message ?: "未知原因"}", Toast.LENGTH_SHORT).show() }
                    busy = false
                }
            }
            Spacer(Modifier.height(22.dp))
            RsCard(padding = PaddingValues(horizontal = 18.dp, vertical = 16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("每晚同步到坚果云", style = RsType.body, color = c.text)
                        Text("备份时顺带上传到坚果云“Resound-Diary”文件夹，一天一个文件，不含原话", style = RsType.caption, color = c.textSecondary)
                    }
                    Switch(sync) { diary.setCloudSync(it) }
                }
            }
        }
    }

    if (picking) RangePickerSheet(custom, onPick = { custom = it; picking = false }) { picking = false }
}

@Composable
private fun Choice(text: String, on: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val c = Rs.colors
    val bg by animateColorAsState(if (on) c.accent else c.background, spring(stiffness = Spring.StiffnessMediumLow), label = "choice")
    val fg by animateColorAsState(if (on) Color.White else c.textSecondary, label = "choiceFg")
    Box(modifier.clip(CircleShape).background(bg).springClick(onClick).padding(vertical = 9.dp), contentAlignment = Alignment.Center) {
        Text(text, style = RsType.subhead.copy(fontWeight = FontWeight.Medium), color = fg)
    }
}

@Composable
private fun Switch(on: Boolean, onChange: (Boolean) -> Unit) {
    val c = Rs.colors
    val track by animateColorAsState(if (on) c.accent else c.separator, label = "track")
    val x by androidx.compose.animation.core.animateDpAsState(if (on) 20.dp else 0.dp,
        spring(dampingRatio = 0.65f, stiffness = Spring.StiffnessMedium), label = "knob")
    Box(Modifier.size(width = 50.dp, height = 30.dp).clip(CircleShape).background(track).springClick { onChange(!on) }.padding(2.dp)) {
        Box(Modifier.offset(x = x).size(26.dp).clip(CircleShape).background(Color.White))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RangePickerSheet(initial: Pair<LocalDate, LocalDate>, onPick: (Pair<LocalDate, LocalDate>) -> Unit, onClose: () -> Unit) {
    val c = Rs.colors
    fun millis(d: LocalDate) = d.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    fun day(ms: Long) = Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate()
    val todayMs = millis(LocalDate.now())
    val state = rememberDateRangePickerState(
        initialSelectedStartDateMillis = millis(initial.first),
        initialSelectedEndDateMillis = millis(initial.second),
        selectableDates = object : androidx.compose.material3.SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis <= todayMs
        },
    )
    ModalBottomSheet(
        onDismissRequest = onClose,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = c.card,
        dragHandle = { Box(Modifier.padding(vertical = 10.dp).size(width = 36.dp, height = 5.dp).clip(CircleShape).background(c.separator)) },
    ) {
        Column(Modifier.padding(bottom = 24.dp)) {
            val colors = DatePickerDefaults.colors(
                containerColor = c.card,
                selectedDayContainerColor = c.accent,
                todayDateBorderColor = c.accent,
                todayContentColor = c.accent,
                dayInSelectionRangeContainerColor = c.accentSoft,
            )
            DateRangePicker(state, Modifier.fillMaxWidth().height(460.dp), colors = colors, showModeToggle = false,
                title = null, headline = null)
            val s = state.selectedStartDateMillis
            val e = state.selectedEndDateMillis
            Pill(if (s != null && e != null) "选好了 · ${rangeText(day(s), day(e))}" else "请选开始和结束日期", filled = s != null && e != null,
                modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth()) {
                if (s != null && e != null) onPick(day(s) to day(e))
            }
        }
    }
}

private fun rangeText(from: LocalDate, to: LocalDate): String {
    val f = DateTimeFormatter.ofPattern("M月d日", Locale.CHINA)
    return if (from == to) from.format(DateTimeFormatter.ofPattern("M月d日 EEEE", Locale.CHINA))
    else "${from.format(f)} 至 ${to.format(f)}"
}

/** 一天里全部已转写的原话，按时间先后 */
internal fun dayLines(segments: List<app.resound.transcribe.SegmentTranscript>): List<TranscriptLine> =
    segments.flatMap { it.lines }.sortedBy { it.startAt }

internal fun dateOf(at: Long): LocalDate = Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).toLocalDate()
