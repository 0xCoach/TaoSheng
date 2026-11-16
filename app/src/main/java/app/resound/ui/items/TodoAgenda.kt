package app.resound.ui.items

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.resound.digest.MemoryItem
import app.resound.todo.TodoAgendaRules
import app.resound.ui.theme.Rs
import app.resound.ui.theme.RsType
import app.resound.ui.verify.RsCard
import app.resound.ui.verify.springClick
import kotlinx.datetime.toJavaLocalDate
import kotlinx.datetime.toKotlinLocalDate
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters

private val WEEK = listOf("一", "二", "三", "四", "五", "六", "日")
private typealias Marks = Map<kotlinx.datetime.LocalDate, TodoAgendaRules.Mark>

/**
 * 待办日历条：一周七天，选中的日子是滑动的橙色胶囊，今天是橙色数字；
 * 日期下 1～3 个小点表示事情多少，红点是有过期，空心圈是有待确认。点月份展开整月（过去、将来都能点）。
 */
@Composable
internal fun TodoCalendarStrip(selected: LocalDate, marks: Marks, onPick: (LocalDate) -> Unit) {
    val c = Rs.colors
    val today = LocalDate.now()
    var monthOpen by remember { mutableStateOf(false) }
    var shownMonth by remember(selected) { mutableStateOf(YearMonth.from(selected)) }
    val weekStart = selected.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    RsCard(padding = PaddingValues(horizontal = 8.dp, vertical = 10.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            val arrow by animateFloatAsState(if (monthOpen) 180f else 0f, spring(stiffness = Spring.StiffnessMediumLow), label = "todoArrow")
            Row(Modifier.springClick { monthOpen = !monthOpen; shownMonth = YearMonth.from(selected) }, verticalAlignment = Alignment.CenterVertically) {
                Text((if (monthOpen) shownMonth.atDay(1) else selected).format(DateTimeFormatter.ofPattern("yyyy年M月")), style = RsType.headline, color = c.text)
                Spacer(Modifier.width(4.dp))
                Text("⌄", style = RsType.headline, color = c.textTertiary, modifier = Modifier.rotate(arrow).padding(bottom = 4.dp))
            }
            Spacer(Modifier.weight(1f))
            if (selected != today) Text("回到今天", style = RsType.subhead.copy(fontWeight = FontWeight.Medium), color = c.accent,
                modifier = Modifier.springClick { monthOpen = false; onPick(today) }.padding(horizontal = 8.dp, vertical = 4.dp))
            Arrow("‹") { if (monthOpen) shownMonth = shownMonth.minusMonths(1) else onPick(selected.minusWeeks(1)) }
            Arrow("›") { if (monthOpen) shownMonth = shownMonth.plusMonths(1) else onPick(selected.plusWeeks(1)) }
        }
        Spacer(Modifier.height(6.dp))
        AnimatedVisibility(!monthOpen, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
            // 选中胶囊在七格之间滑动，不是一闪就换
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val cell = maxWidth / 7
                val index = (selected.dayOfWeek.value - 1)
                val x by animateDpAsState(cell * index, spring(dampingRatio = 0.78f, stiffness = Spring.StiffnessMediumLow), label = "todoPill")
                Box(Modifier.offset(x = x).width(cell).height(64.dp).padding(horizontal = 3.dp)
                    .shadow(10.dp, RoundedCornerShape(18.dp), ambientColor = c.accent, spotColor = c.accent).clip(RoundedCornerShape(18.dp)).background(c.accent))
                Row(Modifier.fillMaxWidth()) {
                    for (i in 0..6) {
                        val d = weekStart.plusDays(i.toLong())
                        DayCell(d, WEEK[i], d == selected, d == today, i >= 5, marks[d.toKotlinLocalDate()], Modifier.weight(1f)) { onPick(d) }
                    }
                }
            }
        }
        AnimatedVisibility(monthOpen, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
            Column {
                Row(Modifier.fillMaxWidth()) {
                    WEEK.forEach { Text(it, style = RsType.caption, color = c.textTertiary, textAlign = TextAlign.Center, modifier = Modifier.weight(1f)) }
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
                                val on = d == selected
                                Box(Modifier.weight(1f).height(52.dp).padding(2.dp).clip(RoundedCornerShape(14.dp))
                                    .background(if (on) c.accent else Color.Transparent)) {
                                    DayCell(d, null, on, d == today, col >= 5, marks[d.toKotlinLocalDate()], Modifier.fillMaxWidth(), dimPast = true) {
                                        monthOpen = false; onPick(d)
                                    }
                                }
                            }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    Legend(c.accent, false, "有安排"); Spacer(Modifier.width(14.dp))
                    Legend(c.critical, false, "有过期"); Spacer(Modifier.width(14.dp))
                    Legend(c.accent, true, "待确认")
                }
            }
        }
    }
}

@Composable
private fun Legend(color: Color, hollow: Boolean, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Dot(color, hollow, 6)
        Spacer(Modifier.width(4.dp))
        Text(text, style = RsType.caption, color = Rs.colors.textTertiary)
    }
}

@Composable
private fun Dot(color: Color, hollow: Boolean, size: Int = 4) {
    Box(Modifier.size(size.dp).clip(CircleShape).then(if (hollow) Modifier.border(1.2.dp, color, CircleShape) else Modifier.background(color)))
}

@Composable
private fun DayCell(d: LocalDate, weekday: String?, selected: Boolean, today: Boolean, weekend: Boolean, mark: TodoAgendaRules.Mark?,
                    modifier: Modifier, dimPast: Boolean = false, onClick: () -> Unit) {
    val c = Rs.colors
    val past = dimPast && d.isBefore(LocalDate.now())
    val fg by animateColorAsState(when {
        selected -> c.onAccent
        today -> c.accent
        weekend || past -> c.textTertiary
        else -> c.text
    }, label = "todoDay")
    Column(modifier.height(if (weekday != null) 64.dp else 48.dp).clip(RoundedCornerShape(16.dp)).springClick(onClick),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        weekday?.let { Text(it, style = RsType.caption, color = if (selected) c.onAccent.copy(alpha = 0.85f) else c.textTertiary) }
        Text("${d.dayOfMonth}", style = RsType.headline.copy(fontWeight = if (selected || today) FontWeight.Bold else FontWeight.Medium), color = fg)
        Row(Modifier.height(7.dp).padding(top = 3.dp), horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
            if (mark != null) {
                if (mark.pending) Dot(if (selected) c.onAccent else c.accent, hollow = true, size = 5)
                val dot = when { selected -> c.onAccent; mark.overdue -> c.critical; else -> c.accent }
                repeat(mark.count.coerceAtMost(3)) { Dot(dot.copy(alpha = if (past && !mark.overdue) 0.45f else 1f), false) }
            }
        }
    }
}

@Composable
private fun Arrow(text: String, onClick: () -> Unit) {
    Box(Modifier.size(34.dp).clip(CircleShape).springClick(onClick), contentAlignment = Alignment.Center) {
        Text(text, style = RsType.title, color = Rs.colors.textSecondary)
    }
}

/** 日期标题：大字“今天 / 明天 / 19”，旁边小字“11月19日 周三 · 3 天后” */
@Composable
internal fun AgendaDayHeader(d: LocalDate, trailing: String? = null) {
    val c = Rs.colors
    val today = LocalDate.now()
    val title = TodoAgendaRules.dayTitle(d.toKotlinLocalDate(), today.toKotlinLocalDate())
    Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 6.dp, bottom = 8.dp), verticalAlignment = Alignment.Bottom) {
        Text(title, style = RsType.title.copy(fontWeight = FontWeight.Bold), color = if (d == today) c.accent else c.text)
        Spacer(Modifier.width(8.dp))
        Text(TodoAgendaRules.dayCaption(d.toKotlinLocalDate(), today.toKotlinLocalDate()) + (trailing?.let { " · $it" } ?: ""),
            style = RsType.subhead, color = c.textSecondary, modifier = Modifier.padding(bottom = 2.dp))
    }
}

/** 没安排的日子：一行，不画空卡；右边“＋ 加一条”直接说一句、日子已填好 */
@Composable
internal fun EmptyDayRow(d: LocalDate, onAdd: () -> Unit) {
    val c = Rs.colors
    val today = LocalDate.now()
    val title = TodoAgendaRules.dayTitle(d.toKotlinLocalDate(), today.toKotlinLocalDate()).let { if (it.all(Char::isDigit)) "${d.monthValue}月${d.dayOfMonth}日" else it }
    Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 0.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = RsType.subhead.copy(fontWeight = FontWeight.SemiBold), color = if (d == today) c.accent else c.textSecondary)
        Spacer(Modifier.width(6.dp))
        Text(TodoAgendaRules.weekday(d.toKotlinLocalDate()) + " · 没有安排", style = RsType.subhead, color = c.textTertiary, modifier = Modifier.weight(1f))
        Text("＋ 加一条", style = RsType.subhead.copy(fontWeight = FontWeight.SemiBold), color = c.accent,
            modifier = Modifier.clip(CircleShape).springClick(onAdd).padding(horizontal = 10.dp, vertical = 6.dp))
    }
}

/** 已过期：浅红小卡，“全部改到今天”；点卡片展开看是哪几件 */
@Composable
internal fun OverdueCard(items: List<MemoryItem>, open: Boolean, onToggle: () -> Unit, onMoveAll: () -> Unit) {
    val c = Rs.colors
    val shape = RoundedCornerShape(18.dp)
    Row(Modifier.fillMaxWidth().clip(shape).background(c.critical.copy(alpha = if (c.isDark) 0.10f else 0.08f))
        .then(if (c.isDark) Modifier.border(1.dp, c.critical.copy(alpha = 0.35f), shape) else Modifier)
        .springClick(onToggle).padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(c.critical))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text("已过期 ${items.size} 件" + if (open) "" else " ›", style = RsType.headline, color = c.critical)
            if (!open) Text(items.take(3).joinToString(" · ") { it.content }, style = RsType.caption, color = c.textSecondary, maxLines = 1)
        }
        Spacer(Modifier.width(8.dp))
        Text("全部改到今天", style = RsType.subhead.copy(fontWeight = FontWeight.SemiBold), color = c.critical,
            modifier = Modifier.clip(CircleShape).background(c.card).springClick(onMoveAll).padding(horizontal = 14.dp, vertical = 8.dp))
    }
}

/** 待确认但定了日子的：虚线框、右边“确认 / 不要” */
@Composable
internal fun PendingAgendaRow(item: MemoryItem, onAccept: () -> Unit, onIgnore: () -> Unit, onOpen: () -> Unit) {
    val c = Rs.colors
    val dash = c.accent.copy(alpha = 0.8f)
    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp).drawBehind {
        drawRoundRect(dash, cornerRadius = CornerRadius(14.dp.toPx()),
            style = Stroke(1.3.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(7.dp.toPx(), 5.dp.toPx()))))
    }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { ItemRow(item, checkable = false, onToggle = {}, onOpen = onOpen) }
            Column(Modifier.padding(end = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp), horizontalAlignment = Alignment.End) {
                Text("确认", style = RsType.subhead.copy(fontWeight = FontWeight.SemiBold), color = c.onAccent,
                    modifier = Modifier.clip(CircleShape).background(c.accent).springClick(onAccept).padding(horizontal = 12.dp, vertical = 5.dp))
                Text("不要", style = RsType.subhead, color = c.textSecondary,
                    modifier = Modifier.clip(CircleShape).background(c.fill).springClick(onIgnore).padding(horizontal = 12.dp, vertical = 5.dp))
            }
        }
    }
}

/** 一周以后的合并组：“下周 · 11月26日—12月2日 · 4 件”，点开再看 */
@Composable
internal fun LaterHeader(group: TodoAgendaRules.Later, open: Boolean, onToggle: () -> Unit) {
    val c = Rs.colors
    val arrow by animateFloatAsState(if (open) 180f else 0f, label = "laterArrow")
    val n = group.items.size + group.pending.size
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(c.card).springClick(onToggle).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(group.title, style = RsType.headline, color = c.text)
        Spacer(Modifier.width(8.dp))
        Text(listOfNotNull(group.range, "$n 件").joinToString(" · "), style = RsType.subhead, color = c.textSecondary, modifier = Modifier.weight(1f))
        Text("⌄", style = RsType.headline, color = c.textTertiary, modifier = Modifier.rotate(arrow))
    }
}

/** 这张卡里的几条：和原来的列表行一样（左滑完成、右滑删除、铃铛、点开看详情） */
@Composable
internal fun AgendaCard(items: List<MemoryItem>, pending: List<MemoryItem>, row: @Composable (MemoryItem) -> Unit,
                        pendingRow: @Composable (MemoryItem) -> Unit) {
    val c = Rs.colors
    RsCard(padding = PaddingValues(vertical = 2.dp)) {
        items.forEachIndexed { i, item ->
            row(item)
            if (i != items.lastIndex || pending.isNotEmpty()) Box(Modifier.padding(start = 52.dp).fillMaxWidth().height(0.5.dp).background(c.separator))
        }
        pending.forEach { pendingRow(it) }
    }
}

internal fun kotlinx.datetime.LocalDate.java(): LocalDate = toJavaLocalDate()
