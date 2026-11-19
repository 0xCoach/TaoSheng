package app.resound.ui.night

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.resound.AppGraph
import app.resound.night.Feedback
import app.resound.night.NightDetail
import app.resound.night.NightEvent
import app.resound.night.NightEventKind
import app.resound.night.NightState
import app.resound.ui.theme.Rs
import app.resound.ui.theme.RsType
import app.resound.ui.verify.Pill
import app.resound.ui.verify.RsSheet
import app.resound.ui.verify.springClick
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.time.LocalDate

/**
 * “昨晚”详情：数字格 → 三个时间（点了原地改）→ 声音地图（点一下回听）→ 事件列表（是 / 不是）→
 * 试试看标签 → 梦 → 分享最响 15 秒 → 可信度原因 → 删除这一夜。
 */
@Composable
internal fun NightSheet(date: LocalDate, onDismiss: () -> Unit) {
    val detail by remember(date) { AppGraph.night.night(date.toKotlin()) }.collectAsStateWithLifecycle(initialValue = null)
    val settings by AppGraph.night.settings.collectAsStateWithLifecycle()
    RsSheet("昨晚", subtitle = "${date.monthValue}月${date.dayOfMonth}日早上醒来", onDismiss = {
        app.resound.ui.voiceprint.SamplePlayer.stop(); onDismiss()
    }, fixedHeight = true, trailing = {
        Text("趋势", style = RsType.subhead, color = Rs.colors.accent, modifier = Modifier.springClick { NightUi.trendOpen = true }.padding(8.dp))
    }) {
        val d = detail
        if (d == null) {
            Text("这一夜没有记录", style = RsType.body, color = Rs.colors.textSecondary, modifier = Modifier.padding(vertical = 24.dp))
            return@RsSheet
        }
        NightBody(d, settings.sharedRoom, onDeleted = onDismiss)
    }
}

/** 卡 208：午睡详情，和“昨晚”同样的内容；顶上一个“不是午睡”直接删掉 */
@Composable
internal fun NapSheet(id: Long, onDismiss: () -> Unit) {
    val detail by remember(id) { AppGraph.night.nightById(id) }.collectAsStateWithLifecycle(initialValue = null)
    val settings by AppGraph.night.settings.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val d = detail
    val t = d?.summary?.times
    RsSheet("午睡", subtitle = t?.let { "${clock(it.onset)}–${clock(it.wake)} · ${span(it.wake - it.onset)}" }, onDismiss = {
        app.resound.ui.voiceprint.SamplePlayer.stop(); onDismiss()
    }, fixedHeight = true, trailing = {
        Text("不是午睡", style = RsType.subhead, color = Rs.colors.warning, modifier = Modifier.springClick {
            scope.launch { AppGraph.night.deleteNight(id); onDismiss() }
        }.padding(8.dp))
    }) {
        if (d == null) {
            Text("这次午睡没有记录", style = RsType.body, color = Rs.colors.textSecondary, modifier = Modifier.padding(vertical = 24.dp))
            return@RsSheet
        }
        NightBody(d, settings.sharedRoom, onDeleted = onDismiss)
    }
}

@Composable
internal fun NightBody(d: NightDetail, sharedRoom: Boolean, onDeleted: () -> Unit) {
    val c = Rs.colors
    val s = d.summary
    val scope = rememberCoroutineScope()
    var selected by remember { mutableStateOf<Long?>(null) }
    var playError by remember { mutableStateOf<String?>(null) }
    fun play(e: NightEvent) {
        val clip = e.clipId ?: return
        selected = e.id
        if (app.resound.ui.voiceprint.SamplePlayer.playingKey == clipKey(clip)) { app.resound.ui.voiceprint.SamplePlayer.stop(); return }
        scope.launch { playError = if (runCatching { playClip(clip) }.getOrDefault(false)) null else "这段录音已经删除" }
    }

    if (s.state == NightState.Processing) Text("正在整理昨晚的声音，充电时会更快。", style = RsType.subhead, color = c.textSecondary)
    // 首页只留一行提醒，确认、标签建议、中断提示都放在这里最上面
    BreakNotice(d)
    s.sentence?.let { Text(it, style = RsType.title.copy(fontWeight = FontWeight.SemiBold), color = c.text) }
    Questions(d)
    Suggestions(d)
    Spacer(Modifier.height(14.dp))
    Stats(d)
    // 卡 210：声音详情图可放大；列表点哪一条，图就移到哪
    val zoom = s.times?.let { t -> rememberNightZoom(s.id, t.bedtime, t.wake) }
    s.times?.let { t ->
        Spacer(Modifier.height(16.dp))
        TimesEditor(s.id, t)
        Spacer(Modifier.height(12.dp))
        NightSoundDetail(s.id, zoom!!, d.envelope, d.events, selected, onPlay = ::play)
        Spacer(Modifier.height(6.dp))
        MapLegend(d.events, sharedRoom)
        Text("点圆点回听 · 双指或双击放大 · 长按看几点几分多响", style = RsType.caption, color = c.textTertiary, modifier = Modifier.padding(top = 4.dp))
        playError?.let { Text(it, style = RsType.caption, color = c.warning) }
    }
    Spacer(Modifier.height(18.dp))
    Events(d, sharedRoom, selected, ::play) { e -> selected = e.id; zoom?.focus(e) }
    Spacer(Modifier.height(18.dp))
    Tags(d)
    Spacer(Modifier.height(18.dp))
    Dreams(d)
    Spacer(Modifier.height(18.dp))
    ShareLoudest(s.id)
    if (s.reasons.isNotEmpty() || s.confidence != null) {
        Spacer(Modifier.height(18.dp))
        Head("可信度：${s.confidence?.title ?: "—"}")
        s.reasons.forEach { Text("· $it", style = RsType.subhead, color = c.textSecondary, modifier = Modifier.padding(top = 2.dp)) }
        if (s.modes.size > 1) Text("· " + s.modes.joinToString("，") { "${clock(it.start)} 起${it.mode.title}" },
            style = RsType.subhead, color = c.textSecondary, modifier = Modifier.padding(top = 2.dp))
    }
    Spacer(Modifier.height(24.dp))
    DeleteNight(s.id, onDeleted)
    Text("由手机录音自动统计，仅供参考，不是医疗检查。", style = RsType.caption, color = c.textTertiary, modifier = Modifier.padding(top = 12.dp, bottom = 12.dp))
}

@Composable
private fun Stats(d: NightDetail) {
    val s = d.summary
    val cells = buildList {
        add("睡了" to (s.sleepMillis?.let(::hours) ?: "—"))
        add("醒了" to if (s.awakeCount > 0) "${s.awakeCount} 次" else "没醒")
        add("鼾声" to if (s.snoreMillis > 0) hours(s.snoreMillis) else "没听到")
        // 咳嗽、梦话固定显示，每晚格局一样；没有就写“没听到”
        add("咳嗽" to if (s.coughCount > 0) "${s.coughCount} 次" else "没听到")
        add("梦话" to if (s.talkCount > 0) "${s.talkCount} 段" else "没听到")
        // 中断只在出现时显示：检测会漏，不写“0 次”免得让人以为没有问题
        if (s.snoreBreaks > 0) add("鼾声中断" to "${s.snoreBreaks} 次")
    }
    val c = Rs.colors
    cells.chunked(3).forEach { row ->
        Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            row.forEach { (label, value) ->
                Column(Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).background(c.fill).padding(horizontal = 12.dp, vertical = 10.dp)) {
                    Text(label, style = RsType.caption, color = c.textTertiary)
                    Text(value, style = RsType.headline, color = c.text)
                }
            }
            repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
        }
    }
}

/** 三个时间并排；点一个原地展开 ±5/±15 分钟，改完存 */
@Composable
private fun TimesEditor(nightId: Long, t: app.resound.night.NightTimes) {
    val c = Rs.colors
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf<Int?>(null) }
    var draft by remember(t) { mutableStateOf(listOf(t.bedtime, t.onset, t.wake)) }
    val labels = listOf("上床", if (t.onsetEstimated && !t.editedOnset) "约入睡" else "入睡", "起床")
    val edited = listOf(t.editedBedtime, t.editedOnset, t.editedWake)
    Column(Modifier.animateContentSize()) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            labels.forEachIndexed { i, label ->
                val on = editing == i
                Column(Modifier.weight(1f).clip(RoundedCornerShape(14.dp))
                    .background(if (on) c.accentSoft else c.fill)
                    .border(1.5.dp, if (on) c.accent else Color.Transparent, RoundedCornerShape(14.dp))
                    .springClick { editing = if (on) null else i; draft = listOf(t.bedtime, t.onset, t.wake) }
                    .padding(horizontal = 12.dp, vertical = 10.dp)) {
                    Text(label + if (edited[i]) " · 改过" else "", style = RsType.caption, color = c.textTertiary)
                    Text(clock(draft[i]), style = RsType.headline, color = c.text)
                }
            }
        }
        editing?.let { i ->
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                listOf(-15, -5, 5, 15).forEach { m ->
                    Pill(if (m > 0) "+$m 分" else "$m 分", filled = false) { draft = draft.toMutableList().also { it[i] = it[i] + m * 60_000L } }
                }
                Spacer(Modifier.weight(1f))
                Pill("保存", filled = true) {
                    val (b, o, w) = draft
                    if (b <= o && o < w) {
                        scope.launch {
                            AppGraph.night.editTimes(nightId, b.takeIf { i == 0 }, o.takeIf { i == 1 }, w.takeIf { i == 2 })
                            editing = null
                        }
                    }
                }
            }
            val (b, o, w) = draft
            if (!(b <= o && o < w)) Text("顺序要是 上床 ≤ 入睡 < 起床", style = RsType.caption, color = c.warning, modifier = Modifier.padding(top = 6.dp))
            else Text("改过的以你为准，以后估入睡时间会参考", style = RsType.caption, color = c.textTertiary, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun Events(d: NightDetail, sharedRoom: Boolean, selected: Long?, onPlay: (NightEvent) -> Unit, onFocus: (NightEvent) -> Unit) {
    val c = Rs.colors
    val all = d.events.filter { it.kind != NightEventKind.Turn }
    // 卡 205：标了“不是”的不再消失，收在最下面一行，点开原地展开，能回听、能改回
    val shown = all.filter { it.feedback != Feedback.No }
    val denied = all.filter { it.feedback == Feedback.No }
    if (shown.isEmpty() && denied.isEmpty()) return
    Head("夜里的声音和动静")
    // 卡 210：要你确认的放最上面、默认展开；其余按类别一行，点开原地展开
    val pending = shown.filter { askable(it) && it.feedback == Feedback.None && it.id !in d.vetoed }
    if (pending.isNotEmpty()) Group(d.summary.id, "pending", c.accent, "待确认", "${pending.size} 条", openAtFirst = true) {
        pending.forEach { e -> EventRow(e, d, sharedRoom, e.id == selected, onPlay, onFocus) }
    }
    val order = listOf(NightEventKind.Snore, NightEventKind.SnoreBreak, NightEventKind.Cough, NightEventKind.SleepTalk,
        NightEventKind.Loud, NightEventKind.Awake, NightEventKind.Playback, NightEventKind.Gap)
    for (kind in order) {
        val list = shown.filter { it.kind == kind && it !in pending }
        if (list.isEmpty()) continue
        Group(d.summary.id, kind.name, kindColor(kind), groupTitle(kind, sharedRoom), groupSummary(kind, list, d)) {
            if (kind == NightEventKind.SnoreBreak) FlowRow(Modifier.padding(start = 8.dp, top = 4.dp, bottom = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                // 中断很多时一条一行太长：排成时间小胶囊，点了图移过去
                list.forEach { e ->
                    val on = e.id == selected
                    Text(clock(e.start), style = RsType.subhead, color = if (on) c.onAccent else c.text,
                        modifier = Modifier.clip(CircleShape).springClick { onFocus(e) }.background(if (on) c.critical else c.fill)
                            .padding(horizontal = 12.dp, vertical = 7.dp))
                }
            } else list.forEach { e -> EventRow(e, d, sharedRoom, e.id == selected, onPlay, onFocus) }
        }
    }
    if (denied.isNotEmpty()) Group(d.summary.id, "denied", c.textTertiary, "标了不是的", "${denied.size} 条") {
        denied.forEach { e -> EventRow(e, d, sharedRoom, e.id == selected, onPlay, onFocus, dim = true) }
    }
}

/** 一类一行：色点、名称、次数/时长，点了原地展开 */
@Composable
private fun Group(night: Long, key: String, color: Color, title: String, summary: String, openAtFirst: Boolean = false, content: @Composable () -> Unit) {
    val c = Rs.colors
    var open by remember(night, key) { mutableStateOf(openAtFirst) }
    val turn by androidx.compose.animation.core.animateFloatAsState(if (open) 180f else 0f, label = "group")
    Column(Modifier.fillMaxWidth().animateContentSize()) {
        Row(Modifier.fillMaxWidth().padding(top = 4.dp).clip(RoundedCornerShape(12.dp)).springClick { open = !open }
            .padding(horizontal = 8.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
            app.resound.ui.verify.Dot(color, 8.dp)
            Text("  $title", style = RsType.body.copy(fontWeight = FontWeight.SemiBold), color = c.text)
            Text("  $summary", style = RsType.subhead, color = c.textSecondary, modifier = Modifier.weight(1f), maxLines = 1)
            Text("⌄", style = RsType.headline, color = c.textTertiary, modifier = Modifier.graphicsLayer { rotationZ = turn })
        }
        AnimatedVisibility(open) { Column { content() } }
    }
}

private fun groupTitle(kind: NightEventKind, sharedRoom: Boolean) = when (kind) {
    NightEventKind.Snore -> if (sharedRoom) "卧室里的鼾声" else "鼾声"
    NightEventKind.Cough -> "咳嗽/清嗓"
    NightEventKind.SleepTalk -> "梦话和人声"
    NightEventKind.Loud -> "最响的几下"
    NightEventKind.Awake -> "醒来"
    NightEventKind.Playback -> "手机在放声音"
    NightEventKind.Gap -> "没有记录"
    NightEventKind.SnoreBreak -> "鼾声中断"
    NightEventKind.Turn -> "翻身"
}

private fun groupSummary(kind: NightEventKind, list: List<NightEvent>, d: NightDetail): String = when (kind) {
    NightEventKind.Snore -> "${list.size} 段 · 共 ${span(app.resound.night.NightRules.merge(list.map { app.resound.night.NightSpan(it.start, it.end) }).sumOf { it.millis })}"
    NightEventKind.SnoreBreak -> "${list.size} 次" + (d.summary.sleepMillis?.takeIf { it > 0 }?.let { " · 每小时 %.1f 次".format(list.size * 3_600_000.0 / it) } ?: "")
    NightEventKind.Cough, NightEventKind.Awake -> "${list.size} 次"
    NightEventKind.Loud -> "${list.size} 下 · 最响比背景高 ${list.maxOf { it.aboveBackgroundDb ?: 0 }} 分贝"
    else -> "${list.size} 段"
}

/** 一条：左边圆形播放键，中间时间和说明，右边“是/不是”——两类按钮左右分开，不会点错 */
@Composable
private fun EventRow(e: NightEvent, d: NightDetail, sharedRoom: Boolean, on: Boolean, onPlay: (NightEvent) -> Unit, onFocus: (NightEvent) -> Unit, dim: Boolean = false) {
    val c = Rs.colors
    val scope = rememberCoroutineScope()
    val playing = e.clipId != null && app.resound.ui.voiceprint.SamplePlayer.playingKey == clipKey(e.clipId!!)
    val tint = if (dim) c.textTertiary else kindColor(e.kind)
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(12.dp))
        .background(if (on) c.accentSoft else Color.Transparent).springClick { onFocus(e) }.padding(horizontal = 6.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        if (e.clipId != null && e.audioAvailable) Box(Modifier.size(40.dp).clip(CircleShape).background(tint.copy(alpha = .16f)).springClick { onPlay(e) },
            contentAlignment = Alignment.Center) {
            Text(if (playing) "■" else "▶", style = RsType.body, color = tint)
        } else Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) { app.resound.ui.verify.Dot(tint, 8.dp) }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(clock(e.start) + if (e.end - e.start >= 60_000) "–${clock(e.end)}" else "", style = RsType.subhead, color = c.textSecondary)
                Text("  " + kindTitle(e, sharedRoom), style = RsType.body, color = if (dim) c.textTertiary else c.text, maxLines = 1)
            }
            val sub = buildList {
                e.aboveBackgroundDb?.takeIf { it > 0 && (askable(e) || e.kind == NightEventKind.Loud) }?.let { add("比背景高 $it 分贝") }
                e.text?.let { add(if (e.kind == NightEventKind.SleepTalk) "“$it”" else it) }
                if (e.kind == NightEventKind.SleepTalk && e.text == null) add("转写中")
                if ((askable(e) || e.kind == NightEventKind.Loud) && e.clipId != null && !e.audioAvailable) add("录音已过期")
                if ((e.feedback == Feedback.No || e.kind == NightEventKind.Loud) && e.audioAvailable) add("录音 7 天后删")
                if (e.id in d.vetoed) add("个人判断认为不是")
            }
            if (sub.isNotEmpty()) Text(sub.joinToString(" · "), style = RsType.caption, color = c.textSecondary, modifier = Modifier.padding(top = 2.dp))
        }
        // 答案随时能改：已确认的点“改成不是”，标了不是的点“改成是”
        if (askable(e)) {
            Spacer(Modifier.width(8.dp))
            when (e.feedback) {
                Feedback.Yes -> Column(horizontalAlignment = Alignment.End) {
                    Text("已确认 ✓", style = RsType.caption, color = c.good)
                    Text("改成不是", style = RsType.caption, color = c.textTertiary,
                        modifier = Modifier.clip(CircleShape).springClick { scope.launch { answerWithUndo(e, Feedback.No) } }.padding(horizontal = 8.dp, vertical = 8.dp))
                }
                Feedback.No -> Pill("改成是", filled = false) { scope.launch { answerWithUndo(e, Feedback.Yes) } }
                else -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Pill("不是", filled = false) { scope.launch { answerWithUndo(e, Feedback.No) } }
                    Pill("是", filled = true) { scope.launch { answerWithUndo(e, Feedback.Yes) } }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Tags(d: NightDetail) {
    val c = Rs.colors
    val scope = rememberCoroutineScope()
    val all by AppGraph.nightInsights.tags.collectAsStateWithLifecycle(initialValue = emptyList())
    val on = d.summary.tags.map { it.id }.toSet()
    val suggested = d.suggestedTags.map { it.id }.toSet()
    var adding by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    Head("试试看", "标上昨晚的情况，几晚后就能对比")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        all.forEach { tag ->
            val active = tag.id in on
            TagChip(tag.name + if (tag.id in suggested && !active) "？" else "", active) {
                scope.launch { AppGraph.nightInsights.setTag(d.summary.id, tag.id, !active) }
            }
        }
        Text(if (adding) "取消" else "+ 自定义", style = RsType.subhead, color = c.accent,
            modifier = Modifier.springClick { adding = !adding; error = null }.padding(horizontal = 6.dp, vertical = 9.dp))
    }
    if (adding) Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        BasicTextField(name, { name = it.take(12) }, singleLine = true, textStyle = RsType.body.copy(color = c.text), cursorBrush = SolidColor(c.accent),
            modifier = Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(c.fill).padding(horizontal = 12.dp, vertical = 10.dp),
            decorationBox = { inner -> if (name.isEmpty()) Text("比如：开了加湿器", style = RsType.body, color = c.textTertiary); inner() })
        Spacer(Modifier.width(8.dp))
        Pill("添加", filled = true) {
            val n = name.trim(); if (n.isEmpty()) return@Pill
            scope.launch {
                AppGraph.nightInsights.createTag(n).onSuccess { tag ->
                    AppGraph.nightInsights.setTag(d.summary.id, tag.id, true); name = ""; adding = false
                }.onFailure { error = it.message ?: "没加上" }
            }
        }
    }
    error?.let { Text(it, style = RsType.caption, color = c.warning, modifier = Modifier.padding(top = 6.dp)) }
}

@Composable
private fun Dreams(d: NightDetail) {
    val c = Rs.colors
    val scope = rememberCoroutineScope()
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Head("梦") }
        Text("+ 记梦", style = RsType.subhead, color = c.accent, modifier = Modifier.springClick { NightUi.dreamNight = d.summary.id }.padding(8.dp))
    }
    if (d.dreams.isEmpty()) Text("还没记", style = RsType.subhead, color = c.textTertiary)
    d.dreams.forEach { dream ->
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.Top) {
            Text(clock(dream.at), style = RsType.subhead, color = c.textTertiary)
            Text("  " + (dream.text ?: if (dream.transcribing) "转写中…" else "（没听到内容）"), style = RsType.body, color = c.text, modifier = Modifier.weight(1f))
            Text("删", style = RsType.caption, color = c.textTertiary, modifier = Modifier.springClick { scope.launch { AppGraph.nightInsights.deleteDream(dream.id) } }.padding(horizontal = 8.dp))
        }
    }
}

/** 最响 15 秒：没确认过的先提醒听一下 */
@Composable
private fun ShareLoudest(nightId: Long) {
    val c = Rs.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<String?>(null) }
    var pending by remember { mutableStateOf<File?>(null) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("最响的 15 秒", style = RsType.body, color = c.text)
            Text("发给自己或家人听听", style = RsType.caption, color = c.textSecondary)
        }
        Pill(if (state == "making") "准备中…" else "分享", filled = false) {
            if (state == "making") return@Pill
            state = "making"
            scope.launch {
                val r = runCatching { AppGraph.nightInsights.loudestClip(nightId) }.getOrNull()
                if (r == null) { state = "none"; return@launch }
                val file = File(r.first.id)
                if (r.second) { pending = file; state = "unconfirmed" } else { state = null; share(context, listOf(file), "audio/mp4", "分享鼾声") }
            }
        }
    }
    when (state) {
        "none" -> Text("这一夜没有可分享的鼾声片段", style = RsType.caption, color = c.textTertiary, modifier = Modifier.padding(top = 6.dp))
        "unconfirmed" -> pending?.let { f ->
            Column(Modifier.padding(top = 8.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.fill).padding(12.dp)) {
                Text("这段还没确认是鼾声，先听一下再分享", style = RsType.subhead, color = c.text)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Pill("听一下", filled = false) { scope.launch { runCatching { app.resound.ui.voiceprint.SamplePlayer.play("night-share", f) } } }
                    Pill("分享", filled = true) { state = null; share(context, listOf(f), "audio/mp4", "分享鼾声") }
                }
            }
        }
    }
}

@Composable
private fun DeleteNight(nightId: Long, onDeleted: () -> Unit) {
    val c = Rs.colors
    val scope = rememberCoroutineScope()
    var armed by remember { mutableStateOf(false) }
    LaunchedEffect(armed) { if (armed) { delay(4000); armed = false } }
    Text(if (armed) "再点一次，删除这一夜的全部记录和录音" else "删除这一夜", style = RsType.subhead.copy(fontWeight = if (armed) FontWeight.SemiBold else FontWeight.Normal),
        color = c.critical, modifier = Modifier.fillMaxWidth().clip(CircleShape).springClick {
            if (!armed) armed = true else scope.launch { app.resound.ui.voiceprint.SamplePlayer.stop(); AppGraph.night.deleteNight(nightId); onDeleted() }
        }.padding(vertical = 10.dp))
}

@Composable
private fun TagChip(text: String, on: Boolean, onClick: () -> Unit) {
    val c = Rs.colors
    Text(text, style = RsType.subhead.copy(fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium), color = if (on) c.text else c.textSecondary,
        modifier = Modifier.clip(CircleShape).background(if (on) c.accentSoft else c.fill)
            .border(1.5.dp, if (on) c.accent else Color.Transparent, CircleShape).springClick(onClick).padding(horizontal = 14.dp, vertical = 8.dp))
}

@Composable
internal fun Head(title: String, sub: String? = null) {
    val c = Rs.colors
    Text(title, style = RsType.headline, color = c.text)
    sub?.let { Text(it, style = RsType.caption, color = c.textSecondary) }
    Spacer(Modifier.height(8.dp))
}

/** 记梦：说出来（之后 3 分钟内录下的话算梦）或写下来 */
@Composable
internal fun DreamSheet(nightId: Long, onDismiss: () -> Unit) {
    val c = Rs.colors
    val scope = rememberCoroutineScope()
    var mode by remember { mutableStateOf<String?>(null) }
    var text by remember { mutableStateOf("") }
    var left by remember { mutableStateOf(180) }
    LaunchedEffect(mode) { if (mode == "speak") { left = 180; while (left > 0) { delay(1000); left-- } } }
    RsSheet("记梦", subtitle = "趁还记得，说几句或写几句", onDismiss = onDismiss) {
        when (mode) {
            null -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Pill("说出来", filled = true, modifier = Modifier.weight(1f)) { mode = "speak"; scope.launch { AppGraph.nightInsights.startDream(nightId) } }
                Pill("写下来", filled = false, modifier = Modifier.weight(1f)) { mode = "write" }
            }
            "speak" -> {
                Text(if (left > 0) "在听… 直接说就好" else "好了", style = RsType.title.copy(fontWeight = FontWeight.SemiBold), color = c.text)
                Spacer(Modifier.height(6.dp))
                Text(if (left > 0) "接下来 %d:%02d 里说的话都会记成梦，转写好后出现在“昨晚”里。".format(left / 60, left % 60)
                    else "转写好后会出现在“昨晚”里。", style = RsType.subhead, color = c.textSecondary)
                Spacer(Modifier.height(16.dp))
                Pill("完成", filled = true, modifier = Modifier.fillMaxWidth()) { onDismiss() }
            }
            else -> {
                BasicTextField(text, { text = it }, textStyle = RsType.body.copy(color = c.text), cursorBrush = SolidColor(c.accent),
                    modifier = Modifier.fillMaxWidth().height(140.dp).clip(RoundedCornerShape(14.dp)).background(c.fill).padding(12.dp),
                    decorationBox = { inner -> if (text.isEmpty()) Text("梦见了什么…", style = RsType.body, color = c.textTertiary); inner() })
                Spacer(Modifier.height(12.dp))
                Pill("保存", filled = true, modifier = Modifier.fillMaxWidth()) {
                    val t = text.trim(); if (t.isEmpty()) return@Pill
                    scope.launch { AppGraph.nightInsights.writeDream(nightId, t); onDismiss() }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}
