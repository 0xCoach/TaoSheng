package app.resound.ui.voiceprint

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.resound.ui.theme.Rs
import app.resound.ui.theme.RsType
import app.resound.ui.verify.Pill
import app.resound.ui.verify.RsCard
import app.resound.ui.verify.springClick
import app.resound.voiceprint.VoiceprintPerson
import app.resound.voiceprint.VoiceprintRepository
import kotlinx.coroutines.launch

/** 声纹服务；底层没装配（界面预览）时为 null，长按纠正等功能自动隐藏 */
val LocalVoiceprint = staticCompositionLocalOf<VoiceprintRepository?> { null }

/** 设置 → 声纹与人物：人物列表（点一行看详情）+ 本地多人分段 */
@Composable
fun VoiceprintCard(repo: VoiceprintRepository) {
    val c = Rs.colors
    val state by repo.state.collectAsState()
    val people by repo.people.collectAsState()
    var selected by remember { mutableStateOf<Long?>(null) }
    var adding by remember { mutableStateOf(false) }
    if (adding) AddPersonDialog(repo) { adding = false }
    people.firstOrNull { it.id == selected }?.let { person ->
        androidx.compose.runtime.key(person.id) { PersonDialog(repo, person) { selected = null } }
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("人物 · ${people.size}", style = RsType.section, color = c.textSecondary, modifier = Modifier.weight(1f))
            Pill("添加人物", filled = false) { adding = true }
        }
        RsCard(padding = PaddingValues(vertical = 4.dp)) {
            people.forEachIndexed { i, person ->
                PersonRow(person) { selected = person.id }
                if (i != people.lastIndex) Box(Modifier.padding(start = 68.dp).fillMaxWidth().height(0.5.dp).background(c.separator))
            }
        }
        Text("在原话里长按一句，选“这是谁”，就能指定人物并学习声音。", style = RsType.caption, color = c.textTertiary,
            modifier = Modifier.padding(horizontal = 4.dp))
        Spacer(Modifier.height(10.dp))
        SpeakerSegmentationSettings()
        SampleAudioCard(repo)
        if (state.ready) RecomputeCard()
        state.lastError?.let { Text(it, style = RsType.caption, color = c.critical, modifier = Modifier.padding(horizontal = 4.dp)) }
        if (state.pendingSegments > 0) Text("还有 ${state.pendingSegments} 段等着匹配声音", style = RsType.caption, color = c.textTertiary,
            modifier = Modifier.padding(horizontal = 4.dp))
    }
}

/** 卡 044：用新判定把最近几天（录音还在的）重新认一遍；人工标过的不动 */
@Composable
private fun RecomputeCard() {
    val c = Rs.colors
    val context = androidx.compose.ui.platform.LocalContext.current
    val infos by remember {
        androidx.work.WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(app.resound.voiceprint.VoiceprintRecomputeWork.NAME)
    }.collectAsState(initial = emptyList())
    val latest = infos.lastOrNull()?.state
    val running = latest == androidx.work.WorkInfo.State.RUNNING || latest == androidx.work.WorkInfo.State.ENQUEUED
    var days by remember { mutableStateOf(1) } // 0=1 天 1=7 天 2=30 天
    RsCard(padding = PaddingValues(horizontal = 18.dp, vertical = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("重新认一遍", style = RsType.body.copy(fontWeight = FontWeight.SemiBold), color = c.text, modifier = Modifier.weight(1f))
            app.resound.ui.skills.Segments(listOf("今天", "7 天", "30 天"), days) { days = it }
        }
        Text("识别方法升级了。用新方法把这段时间里录音还在的话重新判断一遍是谁说的；你亲手标过的不会变。",
            style = RsType.caption, color = c.textTertiary, modifier = Modifier.padding(top = 6.dp, bottom = 12.dp))
        app.resound.ui.verify.ActionButton(if (running) "正在重新判断…" else "开始", app.resound.ui.verify.ActionKind.Secondary, enabled = !running) {
            app.resound.voiceprint.VoiceprintRecomputeWork.enqueue(context, listOf(1, 7, 30)[days])
        }
        when (latest) {
            androidx.work.WorkInfo.State.SUCCEEDED -> "上次已完成"
            androidx.work.WorkInfo.State.FAILED -> "上次没做完，可以再试一次"
            else -> null
        }?.let { Text(it, style = RsType.caption, color = if (latest == androidx.work.WorkInfo.State.FAILED) c.critical else c.textTertiary, modifier = Modifier.padding(top = 8.dp)) }
    }
}

/** 样本声音：占用空间 + 清除（清除后仍能认人，只是样本不能再回放） */
@Composable
private fun SampleAudioCard(repo: VoiceprintRepository) {
    val c = Rs.colors
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val bytes by repo.sampleAudioBytes.collectAsState()
    var confirm by remember { mutableStateOf(false) }
    var clearing by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    androidx.compose.runtime.LaunchedEffect(confirm) { if (confirm) { kotlinx.coroutines.delay(3_000); confirm = false } }
    val shown by androidx.compose.animation.core.animateFloatAsState(bytes.toFloat(), androidx.compose.animation.core.tween(600), label = "bytes")
    RsCard(padding = PaddingValues(horizontal = 18.dp, vertical = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("样本声音", style = RsType.body, color = c.text)
                Spacer(Modifier.height(2.dp))
                Text(if (bytes > 0) "已占用 ${sizeText(shown.toLong())}" else "暂无", style = RsType.caption, color = c.textSecondary)
            }
            if (bytes > 0) Pill(when { clearing -> "清除中…"; confirm -> "确定清除"; else -> "清除" }, filled = confirm) {
                if (clearing) return@Pill
                if (!confirm) { confirm = true; note = null; return@Pill }
                confirm = false; clearing = true
                scope.launch {
                    note = repo.clearSampleAudio().fold({ "已清除，仍能照常认人" to false }, { "没清除成功，请稍后再试" to true })
                    clearing = false
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text("用来在人物详情里回放样本。清除后仍能照常认人，已有样本不能再回放。", style = RsType.caption, color = c.textTertiary)
        note?.let { (text, bad) -> Text(text, style = RsType.caption, color = if (bad) c.critical else c.good, modifier = Modifier.padding(top = 6.dp)) }
    }
}

internal fun sizeText(bytes: Long): String = when {
    bytes < 1024 * 1024 -> "${(bytes / 1024).coerceAtLeast(1)} KB"
    else -> "%.1f MB".format(bytes / 1024f / 1024f)
}

@Composable
private fun PersonRow(person: VoiceprintPerson, onOpen: () -> Unit) {
    val c = Rs.colors
    Row(Modifier.fillMaxWidth().springClick(onOpen).padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Avatar(person, 40.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(person.name, style = RsType.body.copy(fontWeight = FontWeight.SemiBold), color = c.text)
            Spacer(Modifier.height(2.dp))
            Text(personStatus(person), style = RsType.caption, color = if (person.ready) c.textSecondary else c.textTertiary)
        }
        Text("›", style = RsType.title, color = c.textTertiary)
    }
}

/** 头像：名字第一个字，颜色按人物固定；“我”用暖橙 */
@Composable
internal fun Avatar(person: VoiceprintPerson, size: Dp) {
    val color = personColor(person)
    Box(Modifier.size(size).clip(CircleShape).background(color.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
        Text(person.name.take(1), style = (if (size > 48.dp) RsType.title else RsType.headline), color = color)
    }
}

@Composable
internal fun personColor(person: VoiceprintPerson): Color {
    val c = Rs.colors
    if (person.id == 0L) return c.accent
    val palette = if (c.isDark) listOf(Color(0xFF6CC3BB), Color(0xFFA897D8), Color(0xFFE48FA8), Color(0xFF8DB8E8), Color(0xFFD9B86A))
    else listOf(Color(0xFF2E9C93), Color(0xFF7E68C2), Color(0xFFCC5E82), Color(0xFF4F86C6), Color(0xFFB08A2E))
    return palette[(person.id % palette.size).toInt()]
}

internal fun personStatus(p: VoiceprintPerson): String = when {
    p.ready -> "已能认出 · ${p.samples} 段 · ${minutes(p.seconds)}"
    p.samples > 0 -> "学习中 · ${p.samples} 段，还需多一些声音"
    else -> "还没有声音"
}

internal fun minutes(seconds: Int) = if (seconds < 60) "$seconds 秒" else "${seconds / 60} 分钟"
