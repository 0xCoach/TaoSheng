package app.resound.ui.screentime

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.resound.digest.MemoryEvent
import app.resound.digest.MemoryRepository
import app.resound.screentime.AppMinutes
import app.resound.screentime.AppSession
import app.resound.screentime.GapCode
import app.resound.screentime.ScreenTimeRules
import app.resound.screentime.ScreenTimeView
import app.resound.ui.theme.Rs
import app.resound.ui.theme.RsType
import app.resound.ui.today.phoneTint
import app.resound.ui.verify.RsSheet
import app.resound.ui.verify.formatClock
import app.resound.ui.verify.springClick
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toKotlinLocalDate

/** 一周的屏幕时间：周一到周日每天分钟数、有数据那几天的日均、上周日均、本周各 App 用时。 */
private data class ScreenWeek(val start: LocalDate, val days: List<Int>, val average: Int?, val lastAverage: Int?, val apps: List<AppMinutes>)

/** 首页点“屏幕”打开：按日 / 按周看屏幕时间。数据只在手机里读。 */
@Composable
internal fun ScreenTimeSheet(initial: LocalDate, memory: MemoryRepository, onOpenEvent: (MemoryEvent) -> Unit = {}, onDismiss: () -> Unit) {
    val c = Rs.colors
    var week by remember { mutableStateOf(false) }
    var date by remember { mutableStateOf(initial) }
    val today = LocalDate.now()
    RsSheet("屏幕时间", subtitle = "数据只存在手机里", onDismiss = onDismiss, fixedHeight = true) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            app.resound.ui.skills.Segments(listOf("日", "周"), if (week) 1 else 0) { week = it == 1 }
        }
        val weekStart = date.with(DayOfWeek.MONDAY)
        val canNext = if (week) weekStart.plusWeeks(1) <= today else date < today
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("‹", style = RsType.title, color = c.textSecondary,
                modifier = Modifier.springClick { date = if (week) date.minusWeeks(1) else date.minusDays(1) }.padding(horizontal = 12.dp))
            Text(if (week) weekTitle(weekStart, today) else dayTitle(date, today), style = RsType.subhead.copy(fontWeight = FontWeight.Medium),
                color = c.text, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
            Text("›", style = RsType.title, color = if (canNext) c.textSecondary else c.separator,
                modifier = Modifier.springClick { if (canNext) date = if (week) minOf(date.plusWeeks(1), today) else date.plusDays(1) }.padding(horizontal = 12.dp))
        }
        PermissionHint()
        if (week) WeekView(weekStart) { picked -> date = picked; week = false }
        else DayView(date, memory, onOpenEvent)
    }
}

@Composable
private fun PermissionHint() {
    val (usage, _) = rememberScreenPermissions()
    if (usage) return
    val c = Rs.colors
    val context = LocalContext.current
    Row(Modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(14.dp)).background(c.fill)
        .springClick { ScreenPermissions.openUsageAccess(context) }.padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text("要开启“使用情况访问”，才能记下新的使用", style = RsType.subhead, color = c.text, modifier = Modifier.weight(1f))
        Text("去开启 ›", style = RsType.subhead.copy(fontWeight = FontWeight.SemiBold), color = c.accent)
    }
}

// ---------- 按日 ----------

@Composable
private fun DayView(date: LocalDate, memory: MemoryRepository, onOpenEvent: (MemoryEvent) -> Unit) {
    val c = Rs.colors
    var day by remember(date) { mutableStateOf<ScreenDay?>(null) }
    var loaded by remember(date) { mutableStateOf(false) }
    var average by remember(date) { mutableStateOf<Int?>(null) }
    LaunchedEffect(date) {
        day = loadScreenDay(date); loaded = true
        // 近 7 天（不含当天）有数据那几天的平均
        val zone = ZoneId.systemDefault()
        val from = date.minusDays(7).atStartOfDay(zone).toInstant().toEpochMilli()
        val until = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val result = readScreenTime(from, until)
        average = result?.let { r -> ScreenTimeView.averageMinutes(dayMinutes(r.sessions.map { AppSession(it.pkg, it.from, it.until) }, zone, date.minusDays(7), 7)) }
    }
    val events = remember(date) { memory.events(date.toKotlinLocalDate()) }.collectAsStateWithLifecycle(initialValue = emptyList()).value
    val shown = day
    if (shown == null) {
        if (loaded) Text("这一天没有屏幕时间记录", style = RsType.subhead, color = c.textTertiary, modifier = Modifier.padding(top = 24.dp))
        return
    }
    Hero(shown.totalMinutes, ScreenTimeView.compare(shown.totalMinutes, average, "近 7 天平均"), average)
    val now = java.time.LocalTime.now()
    val peak = shown.byHour.indices.maxByOrNull { shown.byHour[it] } ?: 0
    var hour by remember(date) { mutableStateOf(if (date == LocalDate.now() && shown.byHour[now.hour] > 0) now.hour else peak) }
    Block {
        HourChart(shown.byHour, hour) { hour = it }
        val bounds = remember(date) { dayBounds(date) }
        val apps = remember(shown, hour) { ScreenTimeView.appsDuring(shown.sessions, bounds[hour], bounds[hour + 1]) }
        val inHour = events.filter { it.startAt < bounds[hour + 1] && it.endAt > bounds[hour] }
        Spacer(Modifier.height(12.dp))
        Box(Modifier.fillMaxWidth().height(0.5.dp).background(c.separator))
        Spacer(Modifier.height(10.dp))
        Text("%02d:00–%02d:00".format(hour, (hour + 1) % 24), style = RsType.subhead.copy(fontWeight = FontWeight.SemiBold), color = c.text)
        if (apps.isEmpty()) Text("这一小时没怎么用手机", style = RsType.caption, color = c.textTertiary, modifier = Modifier.padding(top = 4.dp))
        else androidx.compose.foundation.layout.FlowRow(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            apps.take(4).forEach { app ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AppIcon(app.pkg, app.name, 20)
                    Text("  ${app.name} ${app.minutes} 分", style = RsType.subhead, color = c.textSecondary)
                }
            }
        }
        inHour.take(3).forEach { e ->
            Row(Modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(12.dp)).background(c.card)
                .springClick { onOpenEvent(e) }.padding(horizontal = 12.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(formatClock(e.startAt), style = RsType.subhead.copy(fontWeight = FontWeight.SemiBold), color = c.textSecondary)
                Text("  ${e.title}", style = RsType.subhead, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text("›", style = RsType.subhead, color = c.textTertiary)
            }
        }
    }
    AppList("各 App 用时", shown.report.apps)
    Timeline(shown)
    Playback(shown)
    GapLine(shown)
}

@Composable
private fun Hero(minutes: Int, compare: ScreenTimeView.Compare?, average: Int?, averageLabel: String = "") {
    val c = Rs.colors
    Column(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 6.dp)) {
        Text(screenDuration(minutes), style = RsType.number.copy(fontSize = 38.sp, lineHeight = 44.sp), color = c.text)
        if (compare != null && average != null) Row(verticalAlignment = Alignment.CenterVertically) {
            if (compare.diffMinutes != 0) Text((if (compare.diffMinutes > 0) "↑ " else "↓ ") + screenDuration(kotlin.math.abs(compare.diffMinutes)) + "  ",
                style = RsType.subhead.copy(fontWeight = FontWeight.SemiBold), color = if (compare.diffMinutes > 0) c.accent else c.good)
            Text("${compare.text}（${averageLabel.ifEmpty { "" }}${screenDuration(average)}）", style = RsType.subhead, color = c.textSecondary)
        }
    }
}

@Composable
private fun Block(content: @Composable () -> Unit) {
    val c = Rs.colors
    Column(Modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(20.dp)).background(c.fill).padding(16.dp).animateContentSize()) { content() }
}

@Composable
private fun Label(text: String, trailing: String? = null, onTrailing: () -> Unit = {}) {
    val c = Rs.colors
    Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 18.dp, bottom = 2.dp)) {
        Text(text, style = RsType.caption.copy(fontWeight = FontWeight.Medium), color = c.textTertiary, modifier = Modifier.weight(1f))
        trailing?.let { Text(it, style = RsType.caption, color = c.accent, modifier = Modifier.springClick(onTrailing)) }
    }
}

/** 24 小时柱状图：点柱子选中那一小时，选中的柱子上方浮出“15 点 · 22 分”。 */
@Composable
private fun HourChart(byHour: List<Int>, selected: Int, onPick: (Int) -> Unit) {
    val c = Rs.colors
    val max = (byHour.maxOrNull() ?: 0).coerceAtLeast(1)
    Box(Modifier.fillMaxWidth().padding(top = 28.dp)) {
        Column {
            Row(Modifier.fillMaxWidth().height(110.dp), horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.Bottom) {
                byHour.forEachIndexed { h, m ->
                    Box(Modifier.weight(1f).fillMaxHeight().springClick { onPick(h) }, contentAlignment = Alignment.BottomCenter) {
                        Box(Modifier.fillMaxWidth().fillMaxHeight(if (m == 0) 0.03f else (m / max.toFloat()).coerceAtLeast(0.06f))
                            .clip(RoundedCornerShape(3.dp))
                            .background(if (m == 0) c.separator else if (h == selected) phoneTint(c) else phoneTint(c).copy(alpha = 0.35f)))
                    }
                }
            }
            Box(Modifier.fillMaxWidth().height(18.dp)) {
                listOf(0, 6, 12, 18, 24).forEach { t ->
                    Text("$t", style = RsType.caption.copy(fontSize = 11.sp), color = c.textTertiary,
                        modifier = Modifier.align(BiasAlignment((t / 24f) * 2f - 1f, 0f)))
                }
            }
        }
        Box(Modifier.align(BiasAlignment((((selected + 0.5f) / 24f) * 2f - 1f).coerceIn(-0.8f, 0.8f), -1f)).offset(y = (-28).dp)
            .clip(CircleShape).background(c.accent).padding(horizontal = 10.dp, vertical = 3.dp)) {
            Text("$selected 点 · ${byHour[selected]} 分", style = RsType.caption.copy(fontWeight = FontWeight.SemiBold), color = c.onAccent)
        }
    }
}

@Composable
private fun AppList(title: String, apps: List<AppMinutes>) {
    if (apps.isEmpty()) return
    val c = Rs.colors
    var all by remember(apps) { mutableStateOf(false) }
    Label(title, if (apps.size > 8 && !all) "全部 ${apps.size} 个 ›" else null) { all = true }
    Block {
        val max = apps.first().minutes.coerceAtLeast(1)
        (if (all) apps else apps.take(8)).forEach { app ->
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                AppIcon(app.pkg, app.name, 30)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Row {
                        Text(app.name, style = RsType.body, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        Text(screenDuration(app.minutes), style = RsType.subhead, color = c.textSecondary)
                    }
                    Spacer(Modifier.height(5.dp))
                    Box(Modifier.fillMaxWidth((app.minutes / max.toFloat()).coerceIn(0.02f, 1f)).height(4.dp).clip(CircleShape).background(phoneTint(c).copy(alpha = 0.7f)))
                }
            }
        }
    }
}

/** 时间线：同一个 App 挨着的合成一段、不到 1 分钟的不列，按小时分组，新的在前；先显示最近三个小时。 */
@Composable
private fun Timeline(day: ScreenDay) {
    val rows = remember(day) { ScreenTimeView.timeline(day.sessions) }
    if (rows.isEmpty()) return
    val c = Rs.colors
    val zone = ZoneId.systemDefault()
    val groups = rows.groupBy { java.time.Instant.ofEpochMilli(it.from).atZone(zone).hour }.toList()
    var more by remember(day.date) { mutableStateOf(false) }
    Label("时间线")
    Block {
        (if (more) groups else groups.take(3)).forEachIndexed { gi, (hour, list) ->
            if (gi > 0) Spacer(Modifier.height(10.dp))
            Text(hourLabel(hour), style = RsType.caption.copy(fontWeight = FontWeight.Medium), color = c.textTertiary)
            list.forEach { row ->
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    AppIcon(row.pkg, row.name, 24)
                    Spacer(Modifier.width(10.dp))
                    Text(row.name, style = RsType.body, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Text("${formatClock(row.from)}–${formatClock(row.until)} · ${row.minutes} 分", style = RsType.subhead, color = c.textSecondary)
                }
            }
        }
        if (!more && groups.size > 3) Text("展开更早的 ›", style = RsType.subhead, color = c.accent,
            modifier = Modifier.padding(top = 12.dp).springClick { more = true })
    }
}

@Composable
private fun Playback(day: ScreenDay) {
    val list = day.report.result.devicePlayback
    if (list.isEmpty()) return
    val c = Rs.colors
    Label("设备播放记录")
    Block {
        list.sortedByDescending { it.from }.forEachIndexed { i, p ->
            if (i > 0) Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppIcon(p.pkg, p.name, 36)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(p.title ?: p.name, style = RsType.body, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val mins = ((p.until - p.from).coerceAtLeast(0) + 30_000) / 60_000
                    Text(listOfNotNull(p.name, p.subtitle?.takeIf { it.isNotBlank() }).joinToString(" · ") + " · ${formatClock(p.from)}–${formatClock(p.until)} · $mins 分",
                        style = RsType.caption, color = c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text("只表示手机在播放，不代表你在听", style = RsType.caption, color = c.textTertiary)
    }
}

/** 缺口只留一行灰字；“等下一次采集”这种不用用户管的不说。 */
@Composable
private fun GapLine(day: ScreenDay) {
    val gaps = day.report.result.gaps.filter { it.code != GapCode.COVERAGE_LAG && it.code != GapCode.USAGE_ACCESS_MISSING }
    if (gaps.isEmpty()) return
    val c = Rs.colors
    val text = gaps.groupBy { it.code }.map { (_, g) ->
        val first = g.minBy { it.from }
        "${formatClock(first.from)}–${formatClock(first.until)}" + (if (g.size > 1) " 等 ${g.size} 段" else "") + "：${first.fix}"
    }.joinToString("；")
    Text("部分时段没有数据 · $text", style = RsType.caption, color = c.textTertiary, modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 14.dp))
}

// ---------- 按周 ----------

@Composable
private fun WeekView(start: LocalDate, onPickDay: (LocalDate) -> Unit) {
    val c = Rs.colors
    var data by remember(start) { mutableStateOf<ScreenWeek?>(null) }
    LaunchedEffect(start) { data = loadWeek(start) }
    val w = data ?: return
    val compare = ScreenTimeView.compare(w.average ?: 0, w.lastAverage, "上周")
    Hero(w.average ?: 0, compare.takeIf { w.average != null }, w.lastAverage, "日均 ")
    Block {
        val max = (w.days.maxOrNull() ?: 0).coerceAtLeast(1)
        val today = LocalDate.now()
        Box(Modifier.fillMaxWidth().height(130.dp)) {
            Row(Modifier.fillMaxWidth().fillMaxHeight(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Bottom) {
                w.days.forEachIndexed { i, m ->
                    val d = start.plusDays(i.toLong())
                    Box(Modifier.weight(1f).fillMaxHeight().springClick { if (d <= today) onPickDay(d) }, contentAlignment = Alignment.BottomCenter) {
                        Box(Modifier.fillMaxWidth().fillMaxHeight(if (m == 0) 0.03f else (m / max.toFloat()).coerceAtLeast(0.06f))
                            .clip(RoundedCornerShape(4.dp)).background(if (m == 0) c.separator else if (d == today) phoneTint(c) else phoneTint(c).copy(alpha = 0.35f)))
                    }
                }
            }
            w.average?.let { avg ->
                Box(Modifier.fillMaxWidth().align(Alignment.BottomStart).padding(bottom = (130f * avg / max).dp.coerceAtMost(129.dp))
                    .height(1.dp).background(c.textTertiary.copy(alpha = 0.6f)))
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            listOf("一", "二", "三", "四", "五", "六", "日").forEach { d ->
                Text(d, style = RsType.caption.copy(fontSize = 11.sp), color = c.textTertiary, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
            }
        }
        Text("横线是本周日均 · 点某天看那天的明细", style = RsType.caption, color = c.textTertiary, modifier = Modifier.padding(top = 6.dp))
    }
    AppList("本周用得最多", w.apps)
}

private suspend fun loadWeek(start: LocalDate): ScreenWeek? {
    val zone = ZoneId.systemDefault()
    val from = start.minusWeeks(1).atStartOfDay(zone).toInstant().toEpochMilli()
    val until = minOf(start.plusWeeks(1).atStartOfDay(zone).toInstant().toEpochMilli(), System.currentTimeMillis())
    val result = readScreenTime(from, until) ?: return null
    val sessions = result.sessions.map { AppSession(it.pkg, it.from, it.until) }
    val days = dayMinutes(sessions, zone, start, 7)
    val last = dayMinutes(sessions, zone, start.minusWeeks(1), 7)
    val weekFrom = start.atStartOfDay(zone).toInstant().toEpochMilli()
    val apps = withContext(Dispatchers.Default) { ScreenTimeView.appsDuring(result.sessions, weekFrom, until) }
    return ScreenWeek(start, days, ScreenTimeView.averageMinutes(days), ScreenTimeView.averageMinutes(last), apps)
}

/** 从 [start] 起 [count] 天，每天用手机的分钟数。 */
private fun dayMinutes(sessions: List<AppSession>, zone: ZoneId, start: LocalDate, count: Int): List<Int> {
    val totals = ScreenTimeRules.dayTotals(sessions, TimeZone.of(zone.id))
    return (0 until count).map { i ->
        val d = start.plusDays(i.toLong()).toKotlinLocalDate()
        ((totals[d]?.sumOf { it.millis } ?: 0L) + 30_000L).div(60_000L).toInt()
    }
}

// ---------- 小件 ----------

private val iconCache = HashMap<String, ImageBitmap?>()

/** App 图标：用手机上真实的应用图标；拿不到时用首字色块。 */
@Composable
internal fun AppIcon(pkg: String, name: String, size: Int) {
    val context = LocalContext.current
    var icon by remember(pkg) { mutableStateOf(iconCache[pkg]) }
    LaunchedEffect(pkg) {
        if (!iconCache.containsKey(pkg)) {
            icon = withContext(Dispatchers.IO) {
                try { context.packageManager.getApplicationIcon(pkg).toBitmap(96, 96).asImageBitmap() } catch (e: Exception) { null }
            }
            iconCache[pkg] = icon
        }
    }
    val shape = RoundedCornerShape((size * 0.26f).dp)
    val shown = icon
    if (shown != null) Image(shown, contentDescription = name, modifier = Modifier.size(size.dp).clip(shape))
    else Box(Modifier.size(size.dp).clip(shape).background(letterTint(pkg)), contentAlignment = Alignment.Center) {
        Text(name.take(1), style = RsType.caption.copy(fontWeight = FontWeight.Bold, fontSize = (size * 0.45f).sp), color = Color.White)
    }
}

private fun letterTint(pkg: String): Color {
    val palette = listOf(0xFF6B8AC9, 0xFF5BA37A, 0xFFD9825B, 0xFF9B7BD0, 0xFFC9708A, 0xFF4F9FB0)
    return Color(palette[(pkg.hashCode() and 0x7fffffff) % palette.size])
}

private fun hourLabel(h: Int): String {
    val part = when { h < 6 -> "凌晨"; h < 12 -> "上午"; h == 12 -> "中午"; h < 18 -> "下午"; else -> "晚上" }
    val shown = if (h == 0 || h == 12) 12 else h % 12
    return "$part $shown 点"
}

private val md = DateTimeFormatter.ofPattern("M月d日", Locale.CHINA)

private fun dayTitle(d: LocalDate, today: LocalDate) = when (d) {
    today -> "今天 · ${d.format(md)}"
    today.minusDays(1) -> "昨天 · ${d.format(md)}"
    else -> d.format(DateTimeFormatter.ofPattern("M月d日 EEEE", Locale.CHINA))
}

private fun weekTitle(start: LocalDate, today: LocalDate): String {
    val end = start.plusDays(6)
    val range = "${start.format(md)}–${if (end.month == start.month) "${end.dayOfMonth}日" else end.format(md)}"
    return if (today >= start && today <= end) "本周 · $range" else range
}

