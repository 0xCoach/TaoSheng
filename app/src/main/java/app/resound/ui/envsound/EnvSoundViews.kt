package app.resound.ui.envsound

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.resound.AppGraph
import app.resound.digest.MemoryEvent
import app.resound.envsound.EnvModelState
import app.resound.envsound.EnvSoundRepository
import app.resound.envsound.EnvSoundSettings
import app.resound.envsound.EventSounds
import app.resound.envsound.MediaMode
import app.resound.envsound.SegmentSound
import app.resound.envsound.SegmentVerdict
import app.resound.envsound.SkipMode
import app.resound.ui.theme.Rs
import app.resound.ui.theme.RsType
import app.resound.ui.verify.RsCard
import app.resound.ui.verify.RsSheet
import app.resound.ui.verify.springClick
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 按天共享的“每件事的环境”，时间线上每张卡不用各查一次 */
private object EnvCache {
    private val scope = MainScope()
    private val days = HashMap<LocalDate, StateFlow<Map<Long, EventSounds>>>()
    fun eventSounds(repo: EnvSoundRepository, date: LocalDate) = days.getOrPut(date) {
        repo.eventSounds(date).stateIn(scope, SharingStarted.WhileSubscribed(5_000), emptyMap())
    }
}

private fun EventSounds.line(): String? {
    val parts = buildList {
        if (tags.isNotEmpty()) add(tags.joinToString("、") { it.title })
        sounds.forEach { add("有${it.title}") }
    }
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
}

private fun eventDate(e: MemoryEvent) = Instant.ofEpochMilli(e.startAt).atZone(ZoneId.systemDefault()).toLocalDate()

/** 时间线卡片上的一行小字：雨、键盘声 · 有敲门 */
@Composable
fun EventEnvLine(event: MemoryEvent, modifier: Modifier = Modifier) {
    if (!AppGraph.isEnvSoundReady) return
    val repo = AppGraph.envSound
    val s by repo.settings.collectAsState()
    if (!s.enabled || !s.showInSummaries) return
    val map by EnvCache.eventSounds(repo, eventDate(event)).collectAsState()
    val line = map[event.id]?.line() ?: return
    Text(line, style = RsType.caption, color = Rs.colors.textTertiary, maxLines = 1, modifier = modifier)
}

/** 事件详情里的“环境”：全部标签和特别声音，有疑似影视时说一句 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EventEnvDetail(event: MemoryEvent, modifier: Modifier = Modifier) {
    if (!AppGraph.isEnvSoundReady) return
    val repo = AppGraph.envSound
    val s by repo.settings.collectAsState()
    if (!s.enabled || !s.showInSummaries) return
    val map by EnvCache.eventSounds(repo, eventDate(event)).collectAsState()
    val es = map[event.id] ?: return
    if (es.tags.isEmpty() && es.sounds.isEmpty() && !es.media) return
    val c = Rs.colors
    Column(modifier) {
        Text("环境", style = RsType.caption.copy(fontWeight = FontWeight.Medium), color = c.textTertiary)
        Spacer(Modifier.height(6.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            es.tags.forEach { Tag(it.title, c.background, c.textSecondary) }
            es.sounds.forEach { Tag("有${it.title}", c.accentSoft, c.accent) }
            if (es.media) Tag("含疑似影视外放", c.background, c.textTertiary)
        }
    }
}

@Composable
private fun Tag(text: String, bg: Color, fg: Color) =
    Text(text, style = RsType.caption.copy(fontWeight = FontWeight.Medium), color = fg,
        modifier = Modifier.clip(CircleShape).background(bg).padding(horizontal = 10.dp, vertical = 4.dp))

/** 原话页日期下面的一行：“已跳过 3 段 · 疑似影视 1 段 ›”，点开逐段看、试听、找回 */
@Composable
fun EnvFlaggedRow(date: LocalDate) {
    if (!AppGraph.isEnvSoundReady) return
    val repo = AppGraph.envSound
    val s by repo.settings.collectAsState()
    if (!s.enabled) return
    val list by remember(date) { repo.flagged(date) }.collectAsState(initial = emptyList())
    if (list.isEmpty()) return
    val c = Rs.colors
    var open by remember { mutableStateOf(false) }
    val skipped = list.count { it.verdict == SegmentVerdict.Skipped && !it.restored }
    val skippable = list.count { it.verdict == SegmentVerdict.Skippable }
    val media = list.count { it.verdict == SegmentVerdict.Media }
    val text = listOfNotNull(
        skipped.takeIf { it > 0 }?.let { "已跳过 $it 段" },
        skippable.takeIf { it > 0 }?.let { "可跳过 $it 段" },
        media.takeIf { it > 0 }?.let { "疑似影视 $it 段" },
    ).ifEmpty { listOf("环境音 ${list.size} 段") }.joinToString(" · ")
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.card).springClick { open = true }
        .padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(c.textTertiary))
        Spacer(Modifier.width(10.dp))
        Text(text, style = RsType.subhead, color = c.textSecondary, modifier = Modifier.weight(1f))
        Text("查看 ›", style = RsType.subhead.copy(fontWeight = FontWeight.Medium), color = c.accent)
    }
    if (open) FlaggedSheet(repo, list) { open = false }
}

@Composable
private fun FlaggedSheet(repo: EnvSoundRepository, list: List<SegmentSound>, onDismiss: () -> Unit) {
    val c = Rs.colors
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    var playing by remember { mutableStateOf<Long?>(null) }
    val player = remember { arrayOfNulls<android.media.MediaPlayer>(1) }
    fun stopPlay() { player[0]?.runCatching { stop(); release() }; player[0] = null; playing = null }
    DisposableEffect(Unit) { onDispose { stopPlay() } }
    RsSheet("没有转写或被标记的录音", "判断错了可以找回；已跳过的录音至少保留 1 天", onDismiss = onDismiss) {
        list.sortedByDescending { it.startAt }.forEach { seg ->
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.background).padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val t = Instant.ofEpochMilli(seg.startAt).atZone(ZoneId.systemDefault())
                    Text("%02d:%02d".format(t.hour, t.minute), style = RsType.subhead.copy(fontWeight = FontWeight.SemiBold), color = c.text)
                    Spacer(Modifier.width(8.dp))
                    Text("${((seg.endAt - seg.startAt) / 1000).coerceAtLeast(1)} 秒", style = RsType.caption, color = c.textTertiary)
                    Spacer(Modifier.weight(1f))
                    Text(when {
                        seg.restored -> "已找回"
                        seg.reportedWrong -> "已标记判断错了"
                        seg.verdict == SegmentVerdict.Skipped -> "已跳过"
                        seg.verdict == SegmentVerdict.Skippable -> "可跳过"
                        else -> "疑似影视"
                    }, style = RsType.caption.copy(fontWeight = FontWeight.Medium), color = if (seg.restored || seg.reportedWrong) c.good else c.textSecondary)
                }
                val why = listOfNotNull(seg.reason, seg.tags.takeIf { it.isNotEmpty() }?.joinToString("、") { it.title }).distinct()
                if (why.isNotEmpty()) Text(why.joinToString(" · "), style = RsType.caption, color = c.textTertiary, modifier = Modifier.padding(top = 2.dp))
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (seg.audioAvailable) Pill(if (playing == seg.segmentId) "停止" else "试听", false) {
                        if (playing == seg.segmentId) stopPlay() else {
                            stopPlay()
                            repo.audio(seg.segmentId)?.let { f ->
                                runCatching {
                                    player[0] = android.media.MediaPlayer().apply {
                                        setDataSource(f.path); setOnCompletionListener { stopPlay() }; prepare(); start()
                                    }
                                    playing = seg.segmentId
                                }
                            }
                        }
                    }
                    val untranscribed = seg.verdict == SegmentVerdict.Skipped || seg.verdict == SegmentVerdict.Media
                    if (untranscribed && !seg.restored) Pill("转写这段", true) {
                        scope.launch {
                            val r = repo.restore(seg.segmentId)
                            android.widget.Toast.makeText(context, if (r.isSuccess) "已送去转写" else (r.exceptionOrNull()?.message ?: "这段录音已过保留期"), android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                    if (!seg.reportedWrong && !seg.restored) Pill("判断错了", false) {
                        repo.reportWrong(seg.segmentId)
                        android.widget.Toast.makeText(context, "已记下，这段按正常对话处理", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
        }
    }
}

@Composable
private fun Pill(text: String, primary: Boolean, onClick: () -> Unit) {
    val c = Rs.colors
    Text(text, style = RsType.caption.copy(fontWeight = FontWeight.SemiBold), color = if (primary) Color.White else c.accent,
        modifier = Modifier.clip(CircleShape).background(if (primary) c.accent else c.accentSoft).springClick(onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp))
}

/** 设置里“环境音识别”一行右侧的说明 */
@Composable
fun envSoundSummary(): String? {
    if (!AppGraph.isEnvSoundReady) return null
    val s by AppGraph.envSound.settings.collectAsState()
    val m by AppGraph.envSound.model.collectAsState()
    return when {
        !s.enabled -> "未开启"
        m is EnvModelState.Downloading -> "下载模型中"
        m is EnvModelState.Failed -> "模型下载失败"
        else -> "跳过：${s.skip.title}"
    }
}

/** 设置 › 环境音识别 */
@Composable
fun EnvSoundPage(repo: EnvSoundRepository, bottomPadding: Dp) {
    val c = Rs.colors
    val s by repo.settings.collectAsState()
    val model by repo.model.collectAsState()
    val stats by remember { repo.stats(7) }.collectAsState(initial = null)
    fun set(v: EnvSoundSettings) = repo.update(v)
    Column(Modifier.verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = bottomPadding + 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("只分析已经录下的人声片段，在转写前判断一次；录音方式不变，旧录音不补算。全部在手机上完成，不上传。",
            style = RsType.subhead, color = c.textSecondary, modifier = Modifier.padding(horizontal = 4.dp))
        RsCard(padding = PaddingValues(horizontal = 20.dp, vertical = 18.dp)) {
            SwitchRow("环境音识别", when (val m = model) {
                EnvModelState.NotDownloaded -> if (s.enabled) "准备下载模型（约 27 MB）" else "打开后下载约 27 MB 的识别模型"
                is EnvModelState.Downloading -> "正在下载模型 ${(m.progress * 100).toInt()}%"
                EnvModelState.Ready -> if (s.enabled) "已开启" else "模型已下载"
                is EnvModelState.Failed -> m.message
            }, s.enabled) { on -> set(s.copy(enabled = on)); if (on) repo.downloadModel() }
            (model as? EnvModelState.Downloading)?.let { d ->
                Spacer(Modifier.height(10.dp))
                Box(Modifier.fillMaxWidth().height(4.dp).clip(CircleShape).background(c.accentSoft)) {
                    Box(Modifier.fillMaxWidth(d.progress.coerceIn(0f, 1f)).height(4.dp).clip(CircleShape).background(c.accent))
                }
            }
            if (model is EnvModelState.Failed && s.enabled) {
                Spacer(Modifier.height(10.dp))
                Pill("重新下载", true) { repo.downloadModel() }
            }
        }
        if (s.enabled) {
            RsCard(padding = PaddingValues(horizontal = 20.dp, vertical = 18.dp)) {
                Text("跳过纯音乐和环境音", style = RsType.body, color = c.text)
                Text(when (s.skip) {
                    SkipMode.Off -> "全部照常转写"
                    SkipMode.MarkOnly -> "照常转写，只在原话页标出“可跳过”，先看看准不准"
                    SkipMode.On -> "没人说话的片段不转写，省钱省电；在原话页可找回"
                }, style = RsType.caption, color = c.textSecondary)
                Spacer(Modifier.height(12.dp))
                Segmented(SkipMode.entries.map { it.title }, SkipMode.entries.indexOf(s.skip)) { set(s.copy(skip = SkipMode.entries[it])) }
                stats?.takeIf { s.skip != SkipMode.Off && it.marked > 0 }?.let { st ->
                    Spacer(Modifier.height(12.dp))
                    Text(buildString {
                        append("过去 ${st.days} 天标记 ${st.marked} 段，你找回 ${st.restored} 段")
                        if (st.reportedWrong > 0) append("，判断错了 ${st.reportedWrong} 次")
                        if (st.savedMinutes > 0) append("，约省 ${st.savedMinutes} 分钟转写")
                    }, style = RsType.caption, color = c.textTertiary)
                }
            }
            RsCard(padding = PaddingValues(horizontal = 20.dp, vertical = 18.dp)) {
                Text("疑似影视外放", style = RsType.body, color = c.text)
                Text("有人声、配乐很强、又没认出是你。没有配乐的新闻、口播识别不了，照常记录", style = RsType.caption, color = c.textSecondary)
                Spacer(Modifier.height(12.dp))
                Segmented(MediaMode.entries.map { it.title }, MediaMode.entries.indexOf(s.media)) { set(s.copy(media = MediaMode.entries[it])) }
            }
            RsCard(padding = PaddingValues(horizontal = 20.dp, vertical = 18.dp)) {
                SwitchRow("记下环境", "雨、键盘声、宠物、流水、家电、脚步、闹钟、音乐、附近有车流", s.tags) { set(s.copy(tags = it)) }
                Spacer(Modifier.height(16.dp))
                SwitchRow("记下对话中的特别声音", "婴儿哭声、敲门、狗叫、警笛；只记录，不提醒", s.specialSounds) { set(s.copy(specialSounds = it)) }
                Spacer(Modifier.height(16.dp))
                SwitchRow("在卡片和回顾里显示", "关掉后照常记录，只是不显示", s.showInSummaries) { set(s.copy(showInSummaries = it)) }
            }
        }
    }
}

@Composable
private fun SwitchRow(title: String, desc: String, on: Boolean, onChange: (Boolean) -> Unit) {
    val c = Rs.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = RsType.body, color = c.text)
            Text(desc, style = RsType.caption, color = c.textSecondary)
        }
        Spacer(Modifier.width(12.dp))
        val track by animateColorAsState(if (on) c.accent else c.separator, label = "track")
        val x by animateDpAsState(if (on) 20.dp else 0.dp, spring(dampingRatio = 0.65f, stiffness = Spring.StiffnessMedium), label = "knob")
        Box(Modifier.size(width = 50.dp, height = 30.dp).clip(CircleShape).background(track).springClick { onChange(!on) }.padding(2.dp)) {
            Box(Modifier.offset(x = x).size(26.dp).clip(CircleShape).background(Color.White))
        }
    }
}

@Composable
private fun Segmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    val c = Rs.colors
    Row(Modifier.clip(CircleShape).background(c.separator.copy(alpha = if (c.isDark) 1f else 0.7f)).padding(3.dp)) {
        options.forEachIndexed { i, label ->
            val on = i == selected
            val bg by animateColorAsState(if (on) c.card else c.card.copy(alpha = 0f), label = "seg")
            Box(Modifier.clip(CircleShape).background(bg).springClick { onSelect(i) }.padding(horizontal = 16.dp, vertical = 7.dp)) {
                Text(label, style = RsType.subhead, color = if (on) c.text else c.textSecondary)
            }
        }
    }
}

/** 某天每小时的环境（hour → HourSound）；没开环境音识别或不显示时为空 */
@Composable
fun rememberHourSounds(date: LocalDate): Map<Int, app.resound.envsound.HourSound> {
    if (!AppGraph.isEnvSoundReady) return emptyMap()
    val repo = AppGraph.envSound
    val s by repo.settings.collectAsState()
    val list by remember(date) { repo.hourly(date) }.collectAsState(initial = emptyList())
    if (!s.enabled || !s.showInSummaries) return emptyMap()
    return remember(list) { list.associateBy { it.hour } }
}

/** 节奏卡分布条下面一行：“环境：键盘声 5 小时 · 雨 2 小时 · 有敲门” */
@Composable
fun EnvDayLine(hours: Map<Int, app.resound.envsound.HourSound>, modifier: Modifier = Modifier) {
    if (hours.isEmpty()) return
    val tags = hours.values.mapNotNull { it.tag }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.take(3)
    val sounds = hours.values.flatMap { it.sounds }.distinct()
    if (tags.isEmpty() && sounds.isEmpty()) return
    val text = (tags.map { "${it.key.title} ${it.value} 小时" } + sounds.map { "有${it.title}" }).joinToString(" · ")
    Text("环境：$text", style = RsType.caption, color = Rs.colors.textTertiary, maxLines = 1,
        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = modifier)
}
