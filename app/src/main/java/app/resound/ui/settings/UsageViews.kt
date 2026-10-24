package app.resound.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.resound.usage.LlmUsageRow
import app.resound.usage.UsagePrice
import app.resound.usage.UsagePurpose
import app.resound.usage.UsageRepository
import app.resound.ui.theme.Rs
import app.resound.ui.theme.RsType
import app.resound.ui.verify.ActionButton
import app.resound.ui.verify.ActionKind
import app.resound.ui.verify.RsCard
import app.resound.ui.verify.RsSheet
import app.resound.ui.verify.SheetInput
import app.resound.ui.verify.springClick
import java.time.LocalDate

/** 1.2 万 / 3456 这样的简写 */
internal fun tokensText(n: Long): String = when {
    n >= 100_000_000 -> "%.1f 亿".format(n / 100_000_000.0)
    n >= 10_000 -> "%.1f 万".format(n / 10_000.0)
    else -> n.toString()
}

private fun cost(rows: List<LlmUsageRow>, p: UsagePrice): Double? {
    val inP = p.promptPerMillion ?: return null
    val outP = p.completionPerMillion ?: return null
    return rows.sumOf { it.promptTokens } / 1e6 * inP + rows.sumOf { it.completionTokens } / 1e6 * outP
}

private fun money(v: Double) = if (v < 0.01) "不到 1 分钱" else "约 ¥%.2f".format(v)

/** 用途配色 */
private fun purposeColor(p: UsagePurpose): Color = when (p) {
    UsagePurpose.Digest -> Color(0xFFF07B2E)
    UsagePurpose.Ask -> Color(0xFF4F86C6)
    UsagePurpose.Review -> Color(0xFFC8624A)
    UsagePurpose.Skill -> Color(0xFF6E5FA6)
    UsagePurpose.Draft -> Color(0xFF3F8F6F)
    UsagePurpose.Other -> Color(0xFFB0A89E)
}

/** 设置首页“整理模型”一行右侧的小字：今天用了多少 */
@Composable
internal fun todayTokensText(): String? {
    if (!app.resound.AppGraph.isUsageReady) return null
    val today = remember { LocalDate.now() }
    val rows by remember { app.resound.AppGraph.usage.llm(today, today) }.collectAsState(initial = emptyList())
    val total = rows.sumOf { it.promptTokens + it.completionTokens }
    return if (total == 0L) null else "今天 ${tokensText(total)}"
}

/**
 * 整理模型页底部的“用量”卡片：今天 / 本周 / 本月合计、估算花费、近 30 天柱状图、按用途拆分。
 */
@Composable
fun LlmUsageCard(repo: UsageRepository) {
    val c = Rs.colors
    val today = remember { LocalDate.now() }
    val rows by remember { repo.llm(today.minusDays(29), today) }.collectAsState(initial = emptyList())
    val price by repo.price.collectAsState()
    var range by remember { mutableStateOf(0) } // 0 今天 1 本周 2 本月(30 天)
    var editing by remember { mutableStateOf(false) }
    val from = when (range) { 0 -> today; 1 -> today.minusDays((today.dayOfWeek.value - 1).toLong()); else -> today.minusDays(29) }
    val picked = rows.filter { !it.date.isBefore(from) }
    val prompt = picked.sumOf { it.promptTokens }
    val completion = picked.sumOf { it.completionTokens }

    RsCard(padding = PaddingValues(horizontal = 18.dp, vertical = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("用量", style = RsType.body.copy(fontWeight = FontWeight.SemiBold), color = c.text, modifier = Modifier.weight(1f))
            app.resound.ui.skills.Segments(listOf("今天", "本周", "30 天"), range) { range = it }
        }
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(tokensText(prompt + completion), style = RsType.number, color = c.text)
            Text("  token · ${picked.sumOf { it.calls }} 次", style = RsType.caption, color = c.textSecondary, modifier = Modifier.padding(bottom = 5.dp))
        }
        Text("输入 ${tokensText(prompt)} · 输出 ${tokensText(completion)}", style = RsType.caption, color = c.textTertiary)
        val spent = cost(picked, price)
        Text(spent?.let { "花费 ${money(it)}" } ?: "设置单价后显示估算花费 ›", style = RsType.subhead,
            color = if (spent == null) c.accent else c.text, modifier = Modifier.padding(top = 4.dp).springClick { editing = true })

        // 按用途拆分：一条比例条 + 图例
        val byPurpose = picked.groupBy { it.purpose }.mapValues { (_, l) -> l.sumOf { it.promptTokens + it.completionTokens } }
            .entries.sortedByDescending { it.value }
        val sum = byPurpose.sumOf { it.value }.coerceAtLeast(1)
        if (byPurpose.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth().height(10.dp).clip(CircleShape)) {
                byPurpose.forEach { (p, v) -> Box(Modifier.weight(v.toFloat().coerceAtLeast(1f)).fillMaxHeight().background(purposeColor(p))) }
            }
            Spacer(Modifier.height(8.dp))
            byPurpose.forEach { (p, v) ->
                Row(Modifier.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(purposeColor(p)))
                    Text("  ${p.title}", style = RsType.subhead, color = c.text, modifier = Modifier.weight(1f))
                    Text("${tokensText(v)} · ${v * 100 / sum}%", style = RsType.subhead, color = c.textSecondary)
                }
            }
        } else {
            Spacer(Modifier.height(10.dp))
            Text("这段时间还没有用量", style = RsType.subhead, color = c.textTertiary)
        }

        // 近 30 天每天一根柱子，今天用主色
        Spacer(Modifier.height(14.dp))
        Text("近 30 天", style = RsType.caption, color = c.textTertiary)
        val perDay = (0..29).map { d -> today.minusDays(29L - d) }.map { day -> rows.filter { it.date == day }.sumOf { it.promptTokens + it.completionTokens } }
        val max = perDay.maxOrNull()?.coerceAtLeast(1) ?: 1
        Row(Modifier.fillMaxWidth().height(48.dp).padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.Bottom) {
            perDay.forEachIndexed { i, v ->
                Box(Modifier.weight(1f).fillMaxHeight(if (v == 0L) 0.05f else (v.toFloat() / max).coerceIn(0.08f, 1f))
                    .clip(RoundedCornerShape(2.dp)).background(if (v == 0L) c.separator else if (i == 29) c.accent else c.accent.copy(alpha = 0.35f)))
            }
        }
        Text("用途从这一版开始分开记，以前的用量都算在“其他”里。", style = RsType.caption, color = c.textTertiary, modifier = Modifier.padding(top = 8.dp))
    }
    if (editing) PriceSheet(repo, price) { editing = false }
}

/** 原话与转写页：云端转写用了多久、估算花费 */
@Composable
fun AsrUsageCard(repo: UsageRepository) {
    val c = Rs.colors
    val today = remember { LocalDate.now() }
    val rows by remember { repo.asr(today.minusDays(29), today) }.collectAsState(initial = emptyList())
    val price by repo.price.collectAsState()
    var editing by remember { mutableStateOf(false) }
    fun minutes(ms: Long) = (ms + 59_999) / 60_000
    val todayMs = rows.filter { it.date == today }.sumOf { it.cloudMillis }
    val monthMs = rows.sumOf { it.cloudMillis }
    RsCard(padding = PaddingValues(horizontal = 18.dp, vertical = 16.dp)) {
        Text("云端转写用量", style = RsType.body.copy(fontWeight = FontWeight.SemiBold), color = c.text)
        Spacer(Modifier.height(8.dp))
        Row {
            Column(Modifier.weight(1f)) {
                Text("${minutes(todayMs)} 分钟", style = RsType.headline, color = c.text)
                Text("今天", style = RsType.caption, color = c.textTertiary)
            }
            Column(Modifier.weight(1f)) {
                Text("${minutes(monthMs)} 分钟", style = RsType.headline, color = c.text)
                Text("近 30 天", style = RsType.caption, color = c.textTertiary)
            }
        }
        val perHour = price.asrPerHour
        Text(perHour?.let { "近 30 天花费 ${money(monthMs / 3_600_000.0 * it)}" } ?: "设置单价后显示估算花费 ›", style = RsType.subhead,
            color = if (perHour == null) c.accent else c.text, modifier = Modifier.padding(top = 6.dp).springClick { editing = true })
        Text("用本地转写不花钱，不计入这里。", style = RsType.caption, color = c.textTertiary, modifier = Modifier.padding(top = 4.dp))
    }
    if (editing) PriceSheet(repo, price) { editing = false }
}

@Composable
private fun PriceSheet(repo: UsageRepository, price: UsagePrice, onDismiss: () -> Unit) {
    val c = Rs.colors
    fun fmt(v: Double?) = v?.let { if (it % 1.0 == 0.0) it.toLong().toString() else it.toString() } ?: ""
    var p by remember { mutableStateOf(fmt(price.promptPerMillion)) }
    var o by remember { mutableStateOf(fmt(price.completionPerMillion)) }
    var a by remember { mutableStateOf(fmt(price.asrPerHour)) }
    RsSheet("单价", "按你用的服务商官网价格填，只用来估算花费。留空就不显示花费。", onDismiss = onDismiss) {
        Text("大模型 输入（元 / 百万 token）", style = RsType.caption, color = c.textSecondary)
        SheetInput(p, "例如 1") { p = it.filter { ch -> ch.isDigit() || ch == '.' } }
        Text("大模型 输出（元 / 百万 token）", style = RsType.caption, color = c.textSecondary)
        SheetInput(o, "例如 2") { o = it.filter { ch -> ch.isDigit() || ch == '.' } }
        Text("云端转写（元 / 小时）", style = RsType.caption, color = c.textSecondary)
        SheetInput(a, "例如 0.8") { a = it.filter { ch -> ch.isDigit() || ch == '.' } }
        ActionButton("保存", ActionKind.Primary) {
            repo.setPrice(UsagePrice(p.toDoubleOrNull(), o.toDoubleOrNull(), a.toDoubleOrNull())); onDismiss()
        }
    }
}
