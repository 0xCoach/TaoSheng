package app.resound.ui.voiceprint

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.resound.ui.theme.Rs
import app.resound.ui.theme.RsType
import app.resound.ui.verify.springClick
import app.resound.voiceprint.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val FOLDED = 5

private val stampFormat = java.text.SimpleDateFormat("M月d日 HH:mm", java.util.Locale.CHINA)
private fun stamp(at: Long) = if (at <= 0L) null else stampFormat.format(java.util.Date(at))
private fun secondsText(ms: Long) = if (ms <= 0L) null else "%.1f 秒".format(ms / 1000f)

/** 人物详情里的两块：此人的声纹样本（可听、可删），以及被认成此人的原话（可加入声纹、可移除） */
@Composable
internal fun PersonSamples(repo: VoiceprintRepository, person: VoiceprintPerson) {
    val samples by remember(person.id) { repo.samples(person.id) }.collectAsState(null)
    val lines by remember(person.id) { repo.lines(person.id) }.collectAsState(null)
    DisposableEffect(Unit) { onDispose { SamplePlayer.stop() } }
    SampleSection(repo, person, samples)
    LineSection(repo, person, lines)
}

@Composable
private fun SampleSection(repo: VoiceprintRepository, person: VoiceprintPerson, samples: List<VoiceprintSample>?) {
    val c = Rs.colors
    val scope = rememberCoroutineScope()
    var expanded by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<String?>(null) }
    var note by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(confirm) { if (confirm != null) { delay(3_000); confirm = null } }

    SectionHeader("声纹样本", samples?.size)
    when {
        samples == null -> Unit
        samples.isEmpty() -> Hint(if (person.id == 0L) "还没有样本。朗读上面的短文，或在原话里指定是你说的。"
            else "还没有样本。录入一段声音，或在下面的原话里点“加入声纹”。")
        else -> Column(Modifier.animateContentSize(spring(stiffness = Spring.StiffnessMediumLow)), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            (if (expanded) samples else samples.take(FOLDED)).forEach { sample ->
                key(sample.key) {
                    SampleRow(sample, person, confirm == sample.key,
                        onPlay = {
                            if (SamplePlayer.playingKey == sample.key) SamplePlayer.stop()
                            else scope.launch {
                                note = null
                                try { repo.sampleAudio(sample.key)?.let { SamplePlayer.play(sample.key, it) } ?: run { note = "这段声音已经不在了" } }
                                catch (e: CancellationException) { throw e }
                                catch (_: Exception) { note = "没能播放，请稍后再试" }
                            }
                        },
                        onDelete = {
                            if (confirm != sample.key) confirm = sample.key
                            else { if (SamplePlayer.playingKey == sample.key) SamplePlayer.stop(); repo.deleteSample(sample.key); confirm = null }
                        })
                }
            }
            if (samples.size > FOLDED) MoreToggle(expanded, samples.size) { expanded = !expanded }
        }
    }
    note?.let { Text(it, style = RsType.caption, color = c.critical) }
}

@Composable
private fun SampleRow(sample: VoiceprintSample, person: VoiceprintPerson, confirming: Boolean, onPlay: () -> Unit, onDelete: () -> Unit) {
    val c = Rs.colors
    val title = when (sample.origin) {
        SampleOrigin.Line -> sample.text?.let { "“$it”" } ?: "原话已删除"
        SampleOrigin.Recorded -> "录入的声音"
        SampleOrigin.Auto -> sample.text?.let { "“$it”" } ?: "自动学到的声音"
    }
    val tag = when (sample.state) {
        SampleState.Active -> if (sample.origin == SampleOrigin.Auto) "自动学习" else null
        SampleState.Pending -> "处理中"
        SampleState.Failed -> "未生效"
    }
    val meta = listOfNotNull(stamp(sample.at), secondsText(sample.millis)).joinToString(" · ")
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.background).padding(start = 10.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        PlayButton(sample, personColor(person), onPlay)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = RsType.body, color = if (sample.state == SampleState.Failed) c.textSecondary else c.text,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(3.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                tag?.let { Tag(it, if (sample.state == SampleState.Failed) c.warning else c.textSecondary); Spacer(Modifier.width(6.dp)) }
                Text(meta, style = RsType.caption, color = c.textTertiary)
            }
            sample.note?.let { Text(it, style = RsType.caption, color = c.textTertiary, modifier = Modifier.padding(top = 2.dp)) }
        }
        Spacer(Modifier.width(8.dp))
        ConfirmPill(if (confirming) "确定删除" else "删除", confirming, onDelete)
    }
}

/** 圆形播放键：播放中外圈走进度环、图标轻轻呼吸；没有声音时置灰 */
@Composable
private fun PlayButton(sample: VoiceprintSample, tint: Color, onClick: () -> Unit) {
    val c = Rs.colors
    val playing = SamplePlayer.playingKey == sample.key
    val progress by animateFloatAsState(if (playing) SamplePlayer.progress else 0f, tween(80, easing = LinearEasing), label = "progress")
    val pulse = if (playing) rememberInfiniteTransition(label = "pulse").animateFloat(1f, 1.08f,
        infiniteRepeatable(tween(520, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "scale").value else 1f
    val bg by animateColorAsState(if (playing) tint else tint.copy(alpha = 0.14f), label = "bg")
    Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
        if (!sample.playable) {
            Box(Modifier.size(34.dp).clip(CircleShape).background(c.separator.copy(alpha = 0.5f)), contentAlignment = Alignment.Center) {
                Text("—", style = RsType.subhead, color = c.textTertiary)
            }
        } else {
            Canvas(Modifier.size(40.dp)) {
                if (playing) drawArc(tint, -90f, 360f * progress, false, style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round))
            }
            Box(Modifier.size(32.dp).scale(pulse).clip(CircleShape).background(bg).springClick(onClick), contentAlignment = Alignment.Center) {
                Text(if (playing) "■" else "▶", style = RsType.caption.copy(fontWeight = FontWeight.Bold), color = if (playing) Color.White else tint)
            }
        }
    }
}

@Composable
private fun LineSection(repo: VoiceprintRepository, person: VoiceprintPerson, lines: List<PersonLine>?) {
    val c = Rs.colors
    var expanded by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(confirm) { if (confirm != null) { delay(3_000); confirm = null } }
    val who = if (person.id == 0L) "我" else person.name
    SectionHeader("${who}说的话", lines?.size, more = (lines?.size ?: 0) >= 100)
    when {
        lines == null -> Unit
        lines.isEmpty() -> Hint("还没有。在原话里长按一句，选“这是谁”就会出现在这里。")
        else -> Column(Modifier.animateContentSize(spring(stiffness = Spring.StiffnessMediumLow)), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            (if (expanded) lines else lines.take(FOLDED)).forEach { line ->
                key(line.lineId) {
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.background).padding(horizontal = 14.dp, vertical = 10.dp)) {
                        Text("“${line.text}”", style = RsType.body, color = c.text, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(6.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(listOfNotNull(stamp(line.startAt), if (line.manual) "手动指定" else "自动认出").joinToString(" · "),
                                style = RsType.caption, color = c.textTertiary, modifier = Modifier.weight(1f))
                            AnimatedContent(line.inVoiceprint, transitionSpec = { (fadeIn() + scaleIn(initialScale = 0.85f)) togetherWith fadeOut() }, label = "in") { inside ->
                                if (inside) Tag("已在声纹", c.good)
                                else ConfirmPill("加入声纹", false) { repo.assignPerson(line.lineId, person.id) }
                            }
                            Spacer(Modifier.width(6.dp))
                            ConfirmPill(if (confirm == line.lineId) "确定移除" else "移除", confirm == line.lineId) {
                                if (confirm != line.lineId) confirm = line.lineId else { repo.clearIdentity(line.lineId); confirm = null }
                            }
                        }
                    }
                }
            }
            if (lines.size > FOLDED) MoreToggle(expanded, lines.size, recent = lines.size >= 100) { expanded = !expanded }
        }
    }
    if (!lines.isNullOrEmpty()) Hint("移除后这句改回未知，录音和文字都保留。")
}

@Composable
private fun SectionHeader(title: String, count: Int?, more: Boolean = false) {
    val c = Rs.colors
    Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = RsType.headline, color = c.text)
        if (count != null && count > 0) {
            Spacer(Modifier.width(8.dp))
            Text("$count${if (more) "+" else ""}", style = RsType.caption.copy(fontWeight = FontWeight.SemiBold), color = c.accent,
                modifier = Modifier.clip(CircleShape).background(c.accentSoft).padding(horizontal = 8.dp, vertical = 1.dp))
        }
    }
}

@Composable
private fun Hint(text: String) = Text(text, style = RsType.caption, color = Rs.colors.textTertiary)

@Composable
private fun Tag(text: String, color: Color) = Text(text, style = RsType.caption, color = color,
    modifier = Modifier.clip(CircleShape).background(color.copy(alpha = 0.12f)).padding(horizontal = 7.dp, vertical = 1.dp))

/** 小胶囊按钮；确认态变红，三秒不点自动复原 */
@Composable
private fun ConfirmPill(text: String, danger: Boolean, onClick: () -> Unit) {
    val c = Rs.colors
    val bg by animateColorAsState(if (danger) c.critical else c.card, label = "pill")
    val fg by animateColorAsState(if (danger) Color.White else c.textSecondary, label = "pillText")
    Text(text, style = RsType.caption.copy(fontWeight = FontWeight.Medium), color = fg,
        modifier = Modifier.springClick(onClick).clip(CircleShape).background(bg).padding(horizontal = 11.dp, vertical = 5.dp))
}

@Composable
private fun MoreToggle(expanded: Boolean, total: Int, recent: Boolean = false, onClick: () -> Unit) {
    val c = Rs.colors
    Text(if (expanded) "收起" else if (recent) "显示最近 $total 条" else "显示全部 $total 条", style = RsType.subhead, color = c.accent,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).springClick(onClick).padding(vertical = 8.dp),
        textAlign = androidx.compose.ui.text.style.TextAlign.Center)
}
