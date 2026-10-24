package app.resound.ui.voiceprint

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.resound.AppGraph
import app.resound.transcribe.local.LocalModels
import app.resound.ui.theme.Rs
import app.resound.ui.theme.RsType
import app.resound.ui.verify.ActionButton
import app.resound.ui.verify.ActionKind
import app.resound.ui.verify.Pill
import app.resound.ui.verify.RsCard
import app.resound.ui.verify.RsSheet
import app.resound.ui.verify.SheetInput
import app.resound.ui.verify.springClick
import app.resound.voiceprint.*
import kotlinx.coroutines.*

private const val RECORD_SECONDS = 15

/** 一个人的详情：头像、学习进度、录入声音、改名、删除（“我”可清空声纹） */
@Composable
fun PersonDialog(repo: VoiceprintRepository, person: VoiceprintPerson, onDismiss: () -> Unit) {
    val c = Rs.colors
    val people by repo.people.collectAsState()
    val voiceState by repo.state.collectAsState()
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(person.name) }
    var editing by remember { mutableStateOf(false) }
    var confirmDanger by remember { mutableStateOf(false) }
    var recording by remember { mutableStateOf<Job?>(null) }
    var seconds by remember { mutableIntStateOf(0) }
    var note by remember { mutableStateOf<String?>(null) }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        // 离开前台就停止录入，日常录音随之恢复
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP) recording?.cancel()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); recording?.cancel() }
    }
    fun dismiss() { recording?.cancel(); onDismiss() }
    val me = person.id == 0L

    RsSheet(person.name, onDismiss = { dismiss() }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Avatar(person, 56.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(if (person.ready) "已能认出声音" else if (person.samples > 0) "正在学习声音" else "还没有声音", style = RsType.headline, color = c.text)
                Text("${person.samples} 段 · ${minutes(person.seconds)}", style = RsType.subhead, color = c.textSecondary)
            }
        }
        LearnBar(person)
        Text(
            if (person.ready) "拿不准时会显示“可能是${person.name}？”，等你确认。"
            else "至少需要 3 段、累计 30 秒说话。在原话里长按指定为${person.name}，就会自动补上。",
            style = RsType.caption, color = c.textTertiary,
        )

        // 录入
        val job = recording
        if (job != null) {
            RsCard(color = c.accentSoft, padding = PaddingValues(16.dp)) {
                Text("正在录入 $seconds / $RECORD_SECONDS 秒", style = RsType.headline, color = c.accent)
                Spacer(Modifier.height(4.dp))
                Text("请只让${person.name}说话。日常录音暂停中，结束后自动恢复。", style = RsType.caption, color = c.textSecondary)
                Spacer(Modifier.height(10.dp))
                Box(Modifier.fillMaxWidth().height(6.dp).clip(CircleShape).background(c.card)) {
                    Box(Modifier.fillMaxWidth(seconds / RECORD_SECONDS.toFloat()).height(6.dp).clip(CircleShape).background(c.accent))
                }
            }
            ActionButton("取消录入", ActionKind.Secondary) { job.cancel() }
        } else {
            ActionButton("录入 $RECORD_SECONDS 秒声音", ActionKind.Primary) {
                if (editing) return@ActionButton
                seconds = 0; note = null
                recording = scope.launch {
                    try {
                        val pcm = VoiceprintRecorder.record(AppGraph.capture) { seconds = it }
                        note = repo.enrollPerson(person.id, pcm).fold({ "已保存${person.name}的声音" }, { "没录到足够的说话声，再试一次" })
                    } catch (e: CancellationException) { throw e }
                    catch (_: Exception) { note = "录音没完成，请检查麦克风是否被占用" }
                    finally { recording = null }
                }
            }
        }
        note?.let { Text(it, style = RsType.subhead, color = c.accent) }

        // 改名
        if (!me) {
            if (editing) {
                SheetInput(name, "名字或称呼") { name = it.take(30) }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ActionButton("取消", ActionKind.Secondary, Modifier.weight(1f)) { editing = false; note = null }
                    ActionButton("保存名字", ActionKind.Primary, Modifier.weight(1f), enabled = name.isNotBlank()) {
                        val value = name.trim()
                        if (people.any { it.id != person.id && it.name == value }) note = "已经有叫“$value”的人了"
                        else { repo.renamePerson(person.id, value); editing = false; note = null }
                    }
                }
            } else if (job == null) ActionButton("改名", ActionKind.Secondary) { editing = true; name = person.name; note = null }
        }

        // 危险操作：点两次才生效
        if (job == null && !editing && (!me || person.samples > 0)) {
            Spacer(Modifier.height(4.dp))
            val label = when {
                me && confirmDanger -> "确定清空？再点一次"
                me -> "清空我的声纹"
                confirmDanger -> "确定删除？再点一次"
                else -> "删除这个人"
            }
            ActionButton(label, ActionKind.Danger) {
                if (!confirmDanger) { confirmDanger = true; return@ActionButton }
                if (me) repo.reset() else repo.deletePerson(person.id)
                dismiss()
            }
            Text(
                if (me) "只清空声音样本，原话和记忆都保留，之后会重新学习。"
                else "删除此人的声音样本，原话里的名字改回“未知”。录音、文字和记忆都保留。",
                style = RsType.caption, color = c.textTertiary,
            )
        }
        voiceState.lastError?.let { Text(it, style = RsType.caption, color = c.critical) }
    }
}

/** 学习进度：段数和时长两项都够才算能认 */
@Composable
private fun LearnBar(person: VoiceprintPerson) {
    val c = Rs.colors
    val f = minOf(person.samples / 3f, person.seconds / 30f).coerceIn(0f, 1f)
    Box(Modifier.fillMaxWidth().height(6.dp).clip(CircleShape).background(c.background)) {
        Box(Modifier.fillMaxWidth(f).height(6.dp).clip(CircleShape).background(if (f >= 1f) c.good else c.accent))
    }
}

@Composable
fun AddPersonDialog(repo: VoiceprintRepository, onDismiss: () -> Unit) {
    val c = Rs.colors
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    RsSheet("添加人物", "先起个名字，之后在原话里长按指定此人，就会学习声音。", onDismiss = { if (!saving) onDismiss() }) {
        SheetInput(name, "名字或称呼，例如：老王") { if (!saving) name = it.take(30) }
        error?.let { Text(it, style = RsType.caption, color = c.critical) }
        ActionButton(if (saving) "正在添加…" else "添加", ActionKind.Primary, enabled = name.isNotBlank() && !saving) {
            saving = true; error = null
            scope.launch {
                try { repo.addPerson(name.trim()).fold({ onDismiss() }, { error = "没添加成功：名字可能重复，或超过 30 个字" }) }
                finally { saving = false }
            }
        }
    }
}

/** 本地多人分段：一张卡，开关 + 一句说明；模型没下时给下载按钮 */
@Composable
fun SpeakerSegmentationSettings() {
    val context = LocalContext.current
    val c = Rs.colors
    val prefs = remember { context.getSharedPreferences("local-asr", 0) }
    var enabled by remember { mutableStateOf(prefs.getBoolean("speakers-enabled", true)) }
    var downloaded by remember { mutableStateOf(LocalModels.ready(context, LocalModels.speakers)) }
    var downloadState by remember { mutableStateOf("") }
    LaunchedEffect(downloaded) {
        while (!downloaded) {
            downloaded = LocalModels.ready(context, LocalModels.speakers)
            downloadState = prefs.getString("download-Speakers", "") ?: ""
            delay(1000)
        }
    }
    RsCard(padding = PaddingValues(horizontal = 18.dp, vertical = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("本地多人分段", style = RsType.body, color = c.text)
                    Spacer(Modifier.width(6.dp))
                    Text("试用", style = RsType.caption, color = c.accent,
                        modifier = Modifier.clip(CircleShape).background(c.accentSoft).padding(horizontal = 7.dp, vertical = 1.dp))
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    if (downloaded) "本地转写时先分出不同的人。人多嘈杂时可能分错，长按原话可纠正。"
                    else "需要先下载约 7 MB 的模型。",
                    style = RsType.caption, color = c.textSecondary,
                )
            }
            if (downloaded) {
                Spacer(Modifier.width(12.dp))
                Switch(enabled) { enabled = it; prefs.edit().putBoolean("speakers-enabled", it).apply() }
            }
        }
        if (!downloaded) {
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Pill("下载模型", filled = true) { LocalModels.enqueueSpeakers(context) }
                Spacer(Modifier.width(10.dp))
                if (downloadState.isNotBlank()) Text(downloadState, style = RsType.caption, color = c.textTertiary)
            }
        }
    }
}

@Composable
private fun Switch(on: Boolean, onChange: (Boolean) -> Unit) {
    val c = Rs.colors
    val track by animateColorAsState(if (on) c.accent else c.separator, label = "track")
    val x by animateDpAsState(if (on) 20.dp else 0.dp, spring(dampingRatio = 0.65f, stiffness = Spring.StiffnessMedium), label = "knob")
    Box(Modifier.size(width = 50.dp, height = 30.dp).clip(CircleShape).background(track).springClick { onChange(!on) }.padding(2.dp)) {
        Box(Modifier.padding(start = x).size(26.dp).clip(CircleShape).background(Color.White))
    }
}

/** 原话长按“这是谁”：选已有的人，或输入新名字 */
@Composable
fun AssignPersonDialog(repo: VoiceprintRepository, lineId: Long, onDismiss: () -> Unit) {
    val people by repo.people.collectAsState()
    val c = Rs.colors
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    RsSheet("这是谁说的？", "选一个人，或输入新名字。会从还在的录音里学习这个人的声音。", onDismiss = { if (!saving) onDismiss() }) {
        if (people.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                people.forEach { person ->
                    val on = name.trim() == person.name
                    val bg by animateColorAsState(if (on) personColor(person) else c.background, label = "who")
                    Row(
                        Modifier.clip(CircleShape).background(bg).springClick { if (!saving) name = person.name }.padding(start = 4.dp, end = 14.dp, top = 4.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Avatar(person, 28.dp)
                        Spacer(Modifier.width(6.dp))
                        Text(person.name, style = RsType.subhead.copy(fontWeight = FontWeight.Medium), color = if (on) Color.White else c.text)
                    }
                }
            }
        }
        SheetInput(name, "或输入新名字") { if (!saving) name = it.take(30) }
        error?.let { Text(it, style = RsType.caption, color = c.critical) }
        ActionButton(if (saving) "正在保存…" else "就是${name.trim().ifEmpty { "他/她" }}", ActionKind.Primary, enabled = name.isNotBlank() && !saving) {
            saving = true; error = null
            scope.launch {
                try { repo.nameAndAssign(lineId, name.trim()).fold({ onDismiss() }, { error = "没保存成功，再试一次" }) }
                finally { saving = false }
            }
        }
        ActionButton("不确定，先标成未知", ActionKind.Secondary) { if (!saving) { repo.clearIdentity(lineId); onDismiss() } }
    }
}
