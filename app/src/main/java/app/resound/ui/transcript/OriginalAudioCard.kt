package app.resound.ui.transcript

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.resound.transcribe.AudioTrim
import app.resound.transcribe.AudioUsage
import app.resound.transcribe.OriginalAudio
import app.resound.transcribe.RETENTION_DAY_OPTIONS
import app.resound.ui.items.Choice
import app.resound.ui.skills.Toggle
import app.resound.ui.theme.Rs
import app.resound.ui.theme.RsType
import app.resound.ui.verify.ActionButton
import app.resound.ui.verify.ActionKind
import app.resound.ui.verify.RsCard
import app.resound.ui.verify.RsSheet
import app.resound.ui.verify.Section
import app.resound.ui.verify.SheetInput
import app.resound.ui.verify.formatBytes
import app.resound.ui.verify.springClick
import kotlinx.coroutines.launch

/** 状态卡里的一行：保留几天 · 重点是否长期保留 · 占用；点开在面板里改 */
@Composable
internal fun OriginalAudioRow(audio: OriginalAudio) {
    val days by audio.retentionDays.collectAsState()
    val keepStarred by audio.keepStarred.collectAsState()
    val usage by audio.usage().collectAsState(initial = null)
    var open by remember { mutableStateOf(false) }
    SettingLine("原声", "保留 $days 天" + (if (keepStarred) " · 重点长期保留" else "") + (usage?.takeIf { it.bytes > 0 }?.let { " · 占用 ${formatBytes(it.bytes)}" } ?: "")) { open = true }
    if (open) RsSheet("原声", usage?.let(::usageLine), onDismiss = { open = false }) { OriginalAudioSettings(audio) }
}

/** 一行设置：左边固定宽的灰色小标签，右边内容，最后 ›；上方一条细分隔线 */
@Composable
internal fun SettingLine(label: String, value: String, onClick: () -> Unit) {
    val c = Rs.colors
    Spacer(Modifier.height(12.dp))
    androidx.compose.foundation.layout.Box(Modifier.fillMaxWidth().height(0.5.dp).background(c.separator))
    Row(Modifier.fillMaxWidth().springClick(onClick).padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = RsType.caption.copy(fontWeight = FontWeight.Medium), color = c.textTertiary, modifier = Modifier.width(64.dp))
        Text(value, style = RsType.subhead, color = c.textSecondary, maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Text("›", style = RsType.headline, color = c.textTertiary, modifier = Modifier.padding(start = 8.dp))
    }
}

/** 原声面板里的内容：保留几天、重点一直保留、清除 */
@Composable
private fun OriginalAudioSettings(audio: OriginalAudio) {
    val c = Rs.colors
    val scope = rememberCoroutineScope()
    val days by audio.retentionDays.collectAsState()
    val keepStarred by audio.keepStarred.collectAsState()
    var shorten by remember { mutableStateOf<Pair<Int, AudioTrim>?>(null) }
    var clearing by remember { mutableStateOf(false) }
    Text("保留几天", style = RsType.body, color = c.text)
    Text("点原话可以听原声；超过这些天的原声会删掉，原话文字一直保留", style = RsType.caption, color = c.textSecondary)
    // 改短先算一下会删多少，确认后再改；改长直接生效
    fun choose(d: Int) { if (d == days) return; if (d < days) scope.launch { shorten = d to audio.previewTrim(d) } else audio.setRetentionDays(d) }
    val custom = days !in RETENTION_DAY_OPTIONS
    var editing by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        app.resound.ui.skills.Segments(RETENTION_DAY_OPTIONS.map { "$it 天" } + (if (custom) "$days 天" else "自定义"),
            if (editing || custom) RETENTION_DAY_OPTIONS.size else RETENTION_DAY_OPTIONS.indexOf(days)) { i ->
            if (i == RETENTION_DAY_OPTIONS.size) editing = true else { editing = false; choose(RETENTION_DAY_OPTIONS[i]) }
        }
    }
    if (editing) {
        var input by remember { mutableStateOf(if (custom) days.toString() else "") }
        val n = input.toIntOrNull()
        val valid = n != null && n in app.resound.transcribe.RETENTION_DAY_RANGE
        Row(verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.foundation.text.BasicTextField(input, { v -> input = v.filter(Char::isDigit).take(3) }, singleLine = true,
                textStyle = RsType.body.copy(color = c.text),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(c.accent),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                modifier = Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).background(c.background).padding(horizontal = 14.dp, vertical = 11.dp),
                decorationBox = { inner -> Box { if (input.isEmpty()) Text("填天数", style = RsType.body, color = c.textTertiary); inner() } })
            Text(" 天", style = RsType.body, color = c.textSecondary)
            Spacer(Modifier.width(10.dp))
            Text("确定", style = RsType.subhead.copy(fontWeight = FontWeight.Medium), color = if (valid) Color.White else c.textTertiary,
                modifier = Modifier.clip(CircleShape).background(if (valid) c.accent else c.background)
                    .springClick { if (valid) { editing = false; choose(n!!) } }.padding(horizontal = 16.dp, vertical = 9.dp))
        }
        Text(if (input.isEmpty() || valid) "可填 1～365 天；天数越多，占用的手机空间越大" else "请填 1～365 之间的数",
            style = RsType.caption, color = if (input.isEmpty() || valid) c.textTertiary else c.warning)
    }
    Text("已删除的原声无法恢复，改长只对以后的录音有效", style = RsType.caption, color = c.textTertiary)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("重点一直保留", style = RsType.body, color = c.text)
            Text("标了重点的事，原声不随天数删除", style = RsType.caption, color = c.textSecondary)
        }
        Spacer(Modifier.width(12.dp))
        Toggle(keepStarred) { audio.setKeepStarred(it) }
    }
    ActionButton("清除原声", ActionKind.Danger) { clearing = true }
    shorten?.let { (d, trim) ->
        RsSheet("改成保留 $d 天？", if (trim.bytes > 0) "超过 $d 天的原声会删掉，约 ${formatBytes(trim.bytes)}" else "目前没有超过 $d 天的原声", onDismiss = { shorten = null }) {
            if (trim.keptPending > 0) Text("还有 ${trim.keptPending} 段等待转写，暂不删除", style = RsType.caption, color = c.textSecondary)
            ActionButton("改成 $d 天", ActionKind.Danger) { audio.setRetentionDays(d); shorten = null }
            ActionButton("取消", ActionKind.Secondary) { shorten = null }
        }
    }
    if (clearing) ClearSheet(audio) { clearing = false }
}

private fun usageLine(u: AudioUsage) = if (u.bytes <= 0) "没有原声" else "占用 ${formatBytes(u.bytes)} · ${u.days} 天"

@Composable
private fun ClearSheet(audio: OriginalAudio, onDismiss: () -> Unit) {
    val c = Rs.colors
    val scope = rememberCoroutineScope()
    var includeStarred by remember { mutableStateOf(false) }
    val preview by androidx.compose.runtime.produceState<AudioTrim?>(null, includeStarred) { value = audio.previewClear(includeStarred) }
    var busy by remember { mutableStateOf(false) }
    val p = preview
    RsSheet("清除原声？", p?.let { if (it.bytes > 0) "将删除 ${it.days} 天的原声，约 ${formatBytes(it.bytes)}；原话文字都会保留" else "没有可清除的原声" } ?: "正在计算…", onDismiss = { if (!busy) onDismiss() }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("连重点的也删", style = RsType.body, color = c.text, modifier = Modifier.weight(1f))
            Toggle(includeStarred) { includeStarred = it }
        }
        if ((p?.keptPending ?: 0) > 0) Text("还有 ${p!!.keptPending} 段等待转写，暂不删除", style = RsType.caption, color = c.textSecondary)
        ActionButton(if (busy) "正在清除…" else "清除", ActionKind.Danger, enabled = !busy && (p?.bytes ?: 0) > 0) {
            busy = true
            scope.launch {
                LinePlayer.stop()
                val done = runCatching { audio.clear(includeStarred) }.getOrNull()
                busy = false
                LinePlayer.notice = done?.let { "已清除 ${formatBytes(it.bytes)}" } ?: "清除失败，请稍后再试"
                onDismiss()
            }
        }
        ActionButton("取消", ActionKind.Secondary, enabled = !busy) { onDismiss() }
    }
}

/** 把认错的词加进热词：默认填好这句话，删到只剩想加的词 */
@Composable
internal fun AddHotwordSheet(text: String, onDismiss: () -> Unit) {
    val c = Rs.colors
    var word by remember { mutableStateOf(text.take(30)) }
    val hotwords = app.resound.AppGraph.hotwords
    RsSheet("加入热词", "删到只剩想加的词，如人名、地名、专有名词", onDismiss = onDismiss) {
        SheetInput(word, "要加的词") { word = it.take(30) }
        Text("以后转写会优先认这个词，整理时也会按它纠正同音字", style = RsType.caption, color = c.textTertiary)
        val w = word.trim()
        val exists = w in hotwords.words.value
        ActionButton(if (exists) "已经在热词里" else "加入", ActionKind.Primary, enabled = w.length in 2..20 && !exists) {
            hotwords.setWords(hotwords.words.value + w)
            LinePlayer.notice = "已加入热词：$w"
            onDismiss()
        }
    }
}
