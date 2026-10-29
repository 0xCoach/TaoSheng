package app.resound.ui.verify

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.resound.capture.QuietRule
import app.resound.capture.QuietSchedule
import app.resound.ui.items.Choice
import app.resound.ui.items.Label
import app.resound.ui.skills.Toggle
import app.resound.ui.theme.Rs
import app.resound.ui.theme.RsType
import java.time.DayOfWeek

/** 录音与后台页的“停录时段”：每条一行（名字、时间、重复、开关），点开编辑；底部添加 */
@Composable
internal fun QuietSection(schedule: QuietSchedule) {
    val c = Rs.colors
    val rules by schedule.rules.collectAsState()
    val active by schedule.active.collectAsState()
    var editing by remember { mutableStateOf<QuietRule?>(null) }
    var adding by remember { mutableStateOf(false) }
    Section("停录时段", trailing = {
        val on = rules.count { it.enabled }
        Text(active?.let { a -> "${a.rule.name}中" + (resumeAt(a.endsAt)?.let { " · ${formatUpcoming(it)} 恢复" } ?: "") } ?: if (on > 0) "$on 条已开启" else "未开启",
            style = RsType.caption, color = if (active != null) c.accent else c.textTertiary)
    }) {
        RsCard(Modifier.animateContentSize(), padding = PaddingValues(horizontal = 18.dp, vertical = 6.dp)) {
            rules.forEach { r ->
                QuietRow(r, active?.rule?.id == r.id, onToggle = { schedule.save(r.copy(enabled = it)) }) { editing = r }
                Box(Modifier.fillMaxWidth().height(0.5.dp).background(c.separator))
            }
            Row(Modifier.fillMaxWidth().springClick { adding = true }.padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(26.dp).clip(RoundedCornerShape(8.dp)).background(c.accentSoft), contentAlignment = Alignment.Center) {
                    Text("+", style = RsType.headline, color = c.accent)
                }
                Spacer(Modifier.width(12.dp))
                Text("添加时段", style = RsType.body, color = c.accent)
            }
        }
        Spacer(Modifier.height(8.dp))
        Text("时段内不录音，到点自动恢复；时段内点“现在恢复”只对这一次有效。", style = RsType.caption, color = c.textTertiary,
            modifier = Modifier.padding(horizontal = 4.dp))
    }
    editing?.let { r -> QuietRuleSheet(r, isNew = false, onSave = { schedule.save(it) }, onDelete = { schedule.delete(r.id) }) { editing = null } }
    if (adding) QuietRuleSheet(null, isNew = true, onSave = { schedule.save(it) }, onDelete = {}) { adding = false }
}

@Composable
private fun QuietRow(rule: QuietRule, now: Boolean, onToggle: (Boolean) -> Unit, onOpen: () -> Unit) {
    val c = Rs.colors
    Row(Modifier.fillMaxWidth().springClick(onOpen).padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(rule.name, style = RsType.body.copy(fontWeight = FontWeight.Medium), color = if (rule.enabled) c.text else c.textSecondary)
                if (now) {
                    Spacer(Modifier.width(8.dp))
                    Text("进行中", style = RsType.caption.copy(fontWeight = FontWeight.Medium), color = c.accent,
                        modifier = Modifier.clip(CircleShape).background(c.accentSoft).padding(horizontal = 8.dp, vertical = 2.dp))
                }
            }
            Spacer(Modifier.height(2.dp))
            Text("${spanText(rule.startMinute, rule.endMinute)} · ${daysText(rule.days)}", style = RsType.caption, color = c.textSecondary)
        }
        Spacer(Modifier.width(12.dp))
        Toggle(rule.enabled, onToggle)
    }
}

/** 新增可从模板一键填好；编辑时可删除 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QuietRuleSheet(rule: QuietRule?, isNew: Boolean, onSave: (QuietRule) -> Unit, onDelete: () -> Unit, onDismiss: () -> Unit) {
    val c = Rs.colors
    var name by remember { mutableStateOf(rule?.name ?: "") }
    var start by remember { mutableIntStateOf(rule?.startMinute ?: 23 * 60) }
    var end by remember { mutableIntStateOf(rule?.endMinute ?: 7 * 60) }
    var days by remember { mutableStateOf(rule?.days ?: DayOfWeek.entries.toSet()) }
    // 换模板时让两个滚轮按新值重建
    var wheelKey by remember { mutableIntStateOf(0) }
    var pickingEnd by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    RsSheet(if (isNew) "添加停录时段" else "编辑停录时段", spanText(start, end), onDismiss = onDismiss) {
        if (isNew) {
            Label("常用")
            Row(Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TEMPLATES.forEach { t ->
                    Choice(t.name, name == t.name && start == t.startMinute && end == t.endMinute && days == t.days) {
                        name = t.name; start = t.startMinute; end = t.endMinute; days = t.days; wheelKey++
                    }
                }
            }
        }
        Label("名字")
        SheetInput(name, "如 睡眠、午休、上课") { name = it.take(8) }
        Label("时间")
        // 两张等宽时间卡：开始 → 结束，选中的那张亮起；下面一条居中的滚轮调它，中间写总时长
        RsCard(padding = PaddingValues(12.dp), color = c.background) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TimeCard("开始", start, null, !pickingEnd, Modifier.weight(1f)) { pickingEnd = false }
                Column(Modifier.padding(horizontal = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("→", style = RsType.headline, color = c.textTertiary)
                    Text(durationText(start, end), style = RsType.caption, color = c.textTertiary)
                }
                TimeCard("结束", end, if (end <= start) "次日" else null, pickingEnd, Modifier.weight(1f)) { pickingEnd = true }
            }
            Spacer(Modifier.height(8.dp))
            androidx.compose.runtime.key(wheelKey, pickingEnd) {
                val v = if (pickingEnd) end else start
                WheelTimePicker(v / 60, v % 60, { h, m -> if (pickingEnd) end = h * 60 + m else start = h * 60 + m })
            }
        }
        Label("重复")
        // 常用组合用分段胶囊，下面七个圆点逐天点选，都撑满整行
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            val preset = PRESET_DAYS.indexOfFirst { it.second == days }
            app.resound.ui.skills.Segments(PRESET_DAYS.map { it.first } + "自选", if (preset >= 0) preset else PRESET_DAYS.size) { i ->
                if (i < PRESET_DAYS.size) days = PRESET_DAYS[i].second
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            DayOfWeek.entries.forEach { d -> DayDot(DAY_NAMES[d.value - 1], d in days) { days = if (d in days) days - d else days + d } }
        }
        if (end <= start && days != DayOfWeek.entries.toSet()) Text("跨到第二天的时段，按开始那天算重复。", style = RsType.caption, color = c.textTertiary)
        Spacer(Modifier.height(4.dp))
        ActionButton("保存", ActionKind.Primary, enabled = days.isNotEmpty()) {
            onSave(QuietRule(rule?.id ?: "q" + System.currentTimeMillis(), name.trim().ifBlank { "停录" }, start, end, days, rule?.enabled ?: true))
            onDismiss()
        }
        if (!isNew) ActionButton(if (confirmDelete) "确认删除" else "删除这个时段", ActionKind.Danger) {
            if (confirmDelete) { onDelete(); onDismiss() } else confirmDelete = true
        }
    }
}

private val DAY_NAMES = listOf("一", "二", "三", "四", "五", "六", "日")
private val WORKDAYS = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)
private val WEEKEND = setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)
private val PRESET_DAYS = listOf("每天" to DayOfWeek.entries.toSet(), "工作日" to WORKDAYS, "周末" to WEEKEND)
private val TEMPLATES = listOf(
    QuietRule("", "睡眠", 23 * 60, 7 * 60),
    QuietRule("", "午休", 12 * 60 + 30, 14 * 60, WORKDAYS),
    QuietRule("", "下班后", 18 * 60, 9 * 60, WORKDAYS),
    QuietRule("", "周末", 0, 0, WEEKEND),
)

/** “23:00 – 次日 07:00”；开始等于结束 = 全天 */
internal fun spanText(start: Int, end: Int): String {
    if (start == end) return "全天"
    val s = "%02d:%02d".format(start / 60, start % 60)
    val e = "%02d:%02d".format(end / 60, end % 60)
    return if (end < start) "$s – 次日 $e" else "$s – $e"
}

internal fun daysText(days: Set<DayOfWeek>): String = when (days) {
    DayOfWeek.entries.toSet() -> "每天"
    WORKDAYS -> "工作日"
    WEEKEND -> "周末"
    else -> "周" + days.sortedBy { it.value }.joinToString("、") { DAY_NAMES[it.value - 1] }
}

/** 一张时间卡：上面小字“开始/结束”，下面大号时刻；选中时浅橙底、细描边 */
@Composable
private fun TimeCard(label: String, minutes: Int, tag: String?, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val c = Rs.colors
    val bg by androidx.compose.animation.animateColorAsState(if (selected) c.accentSoft else c.card, label = "timeCard")
    val edge by androidx.compose.animation.animateColorAsState(if (selected) c.accent else c.separator, label = "timeEdge")
    Column(modifier.clip(RoundedCornerShape(16.dp)).background(bg).border(1.dp, edge, RoundedCornerShape(16.dp))
        .springClick(onClick).padding(vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = RsType.caption, color = if (selected) c.accent else c.textTertiary)
            tag?.let {
                Spacer(Modifier.width(4.dp))
                Text(it, style = RsType.caption.copy(fontWeight = FontWeight.Medium), color = c.accent,
                    modifier = Modifier.clip(CircleShape).background(c.card).padding(horizontal = 6.dp))
            }
        }
        Text("%02d:%02d".format(minutes / 60, minutes % 60), style = RsType.title.copy(fontWeight = FontWeight.SemiBold),
            color = if (selected) c.accent else c.text)
    }
}

/** 一周里的一天：圆形小钮，选中实心主色 */
@Composable
private fun DayDot(label: String, on: Boolean, onClick: () -> Unit) {
    val c = Rs.colors
    val bg by androidx.compose.animation.animateColorAsState(if (on) c.accent else c.card, label = "day")
    Box(Modifier.size(38.dp).clip(CircleShape).background(bg).springClick(onClick), contentAlignment = Alignment.Center) {
        Text(label, style = RsType.subhead.copy(fontWeight = FontWeight.Medium), color = if (on) androidx.compose.ui.graphics.Color.White else c.textSecondary)
    }
}

/** “8 小时”“7 小时 30 分”；开始等于结束 = 全天 */
private fun durationText(start: Int, end: Int): String {
    val m = ((end - start) % 1440 + 1440) % 1440
    if (m == 0) return "全天"
    return if (m % 60 == 0) "${m / 60} 小时" else if (m < 60) "$m 分" else "${m / 60} 小时 ${m % 60} 分"
}
