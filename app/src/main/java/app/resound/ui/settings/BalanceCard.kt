package app.resound.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.resound.digest.AndroidDigestSettings
import app.resound.usage.LlmBalance
import app.resound.usage.LlmBalanceChecker
import app.resound.ui.theme.Rs
import app.resound.ui.theme.RsType
import app.resound.ui.verify.ActionButton
import app.resound.ui.verify.ActionKind
import app.resound.ui.verify.RsCard
import app.resound.ui.verify.RsSheet
import app.resound.ui.verify.SheetInput
import app.resound.ui.verify.springClick
import kotlinx.coroutines.launch

/**
 * 整理模型余额：DeepSeek 自动查；自定义模型填了余额查询地址才查。打开页面时查（最多每小时一次），可手动刷新。
 * 低于提醒线变橙色并提醒一次。
 */
@Composable
fun BalanceCard(checker: LlmBalanceChecker, settings: AndroidDigestSettings) {
    val c = Rs.colors
    val scope = rememberCoroutineScope()
    val state by checker.state.collectAsState()
    val threshold by checker.threshold.collectAsState()
    val presetId by settings.presetId.collectAsState()
    val hasKey by settings.hasKey.collectAsState()
    val custom by checker.custom.collectAsState()
    // 完全自填的服务商可以自己填余额查询；从模板建的，模板没有余额接口就不显示这张卡
    val activeProvider = if (app.resound.AppGraph.isLlmProvidersReady) app.resound.AppGraph.llmProviders.saved.collectAsState().value.firstOrNull { it.id == presetId } else null
    val isCustom = activeProvider?.templateId == null
    var editingLine by remember { mutableStateOf(false) }
    var editingSource by remember { mutableStateOf(false) }
    LaunchedEffect(presetId, hasKey, custom) { checker.refresh() }
    if (!hasKey) return
    // DeepSeek 以外、又没填查询地址的自定义：只留一行入口
    if (!state.supported && !isCustom) return
    RsCard(padding = PaddingValues(horizontal = 18.dp, vertical = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("余额", style = RsType.body.copy(fontWeight = FontWeight.SemiBold), color = c.text, modifier = Modifier.weight(1f))
            if (state.supported) Text(if (state.loading) "查询中…" else "刷新", style = RsType.subhead.copy(fontWeight = FontWeight.Medium), color = c.accent,
                modifier = Modifier.clip(CircleShape).background(c.accentSoft).springClick { if (!state.loading) scope.launch { checker.refresh(force = true) } }
                    .padding(horizontal = 12.dp, vertical = 5.dp))
        }
        val b = state.balance
        when {
            !state.supported -> Text("这个模型服务没有自动查余额。可以填它的余额查询地址，填好后像 DeepSeek 一样显示和提醒。",
                style = RsType.subhead, color = c.textSecondary, modifier = Modifier.padding(top = 8.dp))
            b != null -> {
                val low = threshold > 0 && (b.total < threshold || !b.available)
                Text(if (b.available) money(b.total, b.currency) else "已用完", style = RsType.number, color = if (low) c.warning else c.text, modifier = Modifier.padding(top = 8.dp))
                val parts = listOfNotNull(b.granted?.let { "赠送 " + money(it, b.currency) }, b.toppedUp?.let { "充值 " + money(it, b.currency) }, "更新于 " + ago(b.updatedAt))
                Text(parts.joinToString(" · "), style = RsType.caption, color = c.textTertiary)
                if (low) Text("余额不多了，用完后整理会失败，请及时充值", style = RsType.caption, color = c.warning, modifier = Modifier.padding(top = 2.dp))
            }
            state.loading -> Text("查询中…", style = RsType.subhead, color = c.textTertiary, modifier = Modifier.padding(top = 8.dp))
            else -> {}
        }
        state.error?.let { Text(it, style = RsType.caption, color = c.critical, modifier = Modifier.padding(top = 4.dp)) }
        Column(Modifier.padding(top = 10.dp)) {
            if (state.supported) Line("提醒", if (threshold > 0) "低于 ¥%.0f 时提醒".format(threshold) else "不提醒") { editingLine = true }
            if (isCustom || custom.first.isNotBlank()) Line("查询地址", if (custom.first.isBlank()) "未填写" else custom.first) { editingSource = true }
        }
    }
    if (editingLine) ThresholdSheet(threshold, { checker.setThreshold(it); editingLine = false }) { editingLine = false }
    if (editingSource) SourceSheet(checker, settings.preset.baseUrl, custom) { editingSource = false }
}

@Composable
private fun Line(label: String, value: String, onClick: () -> Unit) {
    val c = Rs.colors
    Row(Modifier.springClick(onClick).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = RsType.subhead, color = c.textSecondary, modifier = Modifier.padding(end = 12.dp))
        Text(value, style = RsType.subhead, color = c.text, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Text(" ›", style = RsType.subhead, color = c.textTertiary)
    }
}

@Composable
private fun ThresholdSheet(current: Double, onSave: (Double) -> Unit, onDismiss: () -> Unit) {
    val c = Rs.colors
    var input by remember { mutableStateOf(if (current > 0) "%.0f".format(current) else "") }
    val v = input.toDoubleOrNull()
    RsSheet("余额低于多少时提醒", "提醒一次；充值回到这个数以上后，下次再低才会再提醒。填 0 不提醒。", onDismiss = onDismiss) {
        SheetInput(input, "如 10（元）") { input = it.filter { ch -> ch.isDigit() || ch == '.' }.take(7) }
        ActionButton("保存", ActionKind.Primary, enabled = v != null && v >= 0) { onSave(v!!) }
    }
}

/** 自定义模型的余额查询：地址 + 余额在返回结果里的位置；认识的服务一键填好；先测试再保存 */
@Composable
private fun SourceSheet(checker: LlmBalanceChecker, base: String, current: Pair<String, String>, onDismiss: () -> Unit) {
    val c = Rs.colors
    val scope = rememberCoroutineScope()
    val suggestion = remember(base) { LlmBalanceChecker.suggest(base) }
    var url by remember { mutableStateOf(current.first) }
    var path by remember { mutableStateOf(current.second) }
    var testing by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<Result<LlmBalance>?>(null) }
    RsSheet("余额查询", "用整理模型的同一把密钥去查。填服务商的余额接口地址，再写余额在返回结果里的位置（用点隔开，如 data.totalBalance）。", onDismiss = onDismiss) {
        if (suggestion != null && (url != suggestion.first || path != suggestion.second))
            Text("按你的模型服务自动填写", style = RsType.subhead.copy(fontWeight = FontWeight.Medium), color = c.accent,
                modifier = Modifier.clip(CircleShape).background(c.accentSoft).springClick { url = suggestion.first; path = suggestion.second; result = null }
                    .padding(horizontal = 12.dp, vertical = 6.dp))
        SheetInput(url, "余额接口地址，如 https://…/user/info") { url = it.trim(); result = null }
        SheetInput(path, "余额字段，如 data.totalBalance") { path = it.trim(); result = null }
        result?.let { r ->
            Text(r.fold({ "查到了：¥%.2f".format(it.total) }, { it.message ?: "没查到" }), style = RsType.subhead,
                color = if (r.isSuccess) c.good else c.critical)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ActionButton(if (testing) "测试中…" else "测试", ActionKind.Secondary, Modifier.weight(1f), enabled = !testing && url.isNotBlank() && path.isNotBlank()) {
                testing = true
                scope.launch { result = checker.test(url, path); testing = false }
            }
            ActionButton("保存", ActionKind.Primary, Modifier.weight(1f), enabled = result?.isSuccess == true || (url.isBlank() && path.isBlank())) {
                checker.setCustom(url, path); onDismiss()
            }
        }
        Text(if (url.isBlank() && path.isBlank()) "两项都清空再保存，就不再查余额。" else "测试成功后才能保存。火山方舟（豆包）等需要另一套密钥的服务查不了。",
            style = RsType.caption, color = c.textTertiary)
    }
}

private fun money(v: Double, currency: String) = (when (currency) { "CNY" -> "¥"; "USD" -> "$"; else -> "$currency " }) + "%.2f".format(v)

private fun ago(at: Long): String {
    val m = (System.currentTimeMillis() - at) / 60_000
    return when { m < 1 -> "刚刚"; m < 60 -> "$m 分钟前"; m < 24 * 60 -> "${m / 60} 小时前"; else -> "${m / 1440} 天前" }
}
