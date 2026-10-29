package app.resound.ui.redo

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.resound.AppGraph
import app.resound.redo.AsrChooser
import app.resound.redo.AsrEstimate
import app.resound.redo.RedoEstimate
import app.resound.redo.RedoJob
import app.resound.redo.RedoJobState
import app.resound.redo.RedoRequest
import app.resound.redo.RedoScope
import app.resound.redo.RedoService
import app.resound.transcribe.TranscribeSource
import app.resound.ui.theme.Rs
import app.resound.ui.theme.RsType
import app.resound.ui.verify.ActionButton
import app.resound.ui.verify.ActionKind
import app.resound.ui.verify.RsSheet
import app.resound.ui.verify.springClick
import kotlinx.coroutines.launch

/** 全局：要打开哪个重做任务的面板（点完成通知时设置） */
object RedoUi {
    var openJob by mutableStateOf<String?>(null)
}

internal fun etaText(ms: Long?): String? = ms?.let {
    val s = it / 1000
    when { s < 60 -> "约 ${s.coerceAtLeast(1)} 秒"; s < 3600 -> "约 ${(s + 30) / 60} 分钟"; else -> "约 ${s / 3600} 小时 ${s % 3600 / 60} 分" }
}

internal fun costText(yuan: Double?): String? = yuan?.let { if (it < 0.005) "免费" else "约 ¥%.2f".format(it) }

/** 选转写方式：只列能用的，每个下面一行“约 2 分钟 · 约 ¥0.80”；重做和导入共用 */
@Composable
fun AsrChoice(chooser: AsrChooser, durationMs: Long, selected: TranscribeSource, onSelect: (TranscribeSource) -> Unit) {
    val c = Rs.colors
    val options by chooser.options.collectAsState()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { o ->
            val on = o.source == selected && o.ready
            val est: AsrEstimate? = remember(o.source, durationMs) { if (o.ready) chooser.estimate(durationMs, o.source) else null }
            val bg by animateColorAsState(if (on) c.accentSoft else c.background, spring(stiffness = Spring.StiffnessMediumLow), label = "asr")
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(bg)
                .then(if (o.ready) Modifier.springClick { onSelect(o.source) } else Modifier)
                .padding(horizontal = 14.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(18.dp).clip(CircleShape).background(if (on) c.accent else c.separator), contentAlignment = Alignment.Center) {
                    if (on) Box(Modifier.size(7.dp).clip(CircleShape).background(Color.White))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(o.title, style = RsType.body.copy(fontWeight = FontWeight.Medium), color = if (o.ready) c.text else c.textTertiary)
                    val line = if (!o.ready) o.note ?: "还没配好" else listOfNotNull(etaText(est?.etaMillis), costText(est?.costYuan)).joinToString(" · ")
                    if (line.isNotBlank()) Text(line, style = RsType.caption, color = c.textTertiary)
                }
            }
        }
    }
}

@Composable
private fun Segmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    val c = Rs.colors
    Row(Modifier.fillMaxWidth().clip(CircleShape).background(c.separator.copy(alpha = if (c.isDark) 1f else 0.7f)).padding(3.dp)) {
        options.forEachIndexed { i, label ->
            val on = i == selected
            val bg by animateColorAsState(if (on) c.card else c.card.copy(alpha = 0f), label = "seg")
            Box(Modifier.weight(1f).clip(CircleShape).background(bg).springClick { onSelect(i) }.padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                Text(label, style = RsType.subhead, color = if (on) c.text else c.textSecondary, maxLines = 1)
            }
        }
    }
}

@Composable
private fun Label(text: String) =
    Text(text, style = RsType.caption.copy(fontWeight = FontWeight.Medium), color = Rs.colors.textSecondary, modifier = Modifier.padding(top = 4.dp))

/**
 * 重做面板。首页选事件：可选“只重新整理 / 重新转写并整理”；原话页选录音段：只重新转写。
 * 开始后面板变成进度，关掉不影响，做完有通知。
 */
@Composable
fun RedoSheet(eventIds: List<Long> = emptyList(), segmentIds: List<Long> = emptyList(), onDismiss: () -> Unit) {
    if (!AppGraph.isRedoReady) { onDismiss(); return }
    val repo = AppGraph.redo
    val c = Rs.colors
    val segmentsOnly = eventIds.isEmpty()
    var scope by remember { mutableStateOf(if (segmentsOnly) RedoScope.TranscribeOnly else RedoScope.DigestOnly) }
    val last by repo.asr.lastChoice.collectAsState()
    var asr by remember { mutableStateOf(last) }
    val llms by repo.llmOptions.collectAsState()
    var llm by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(llms) { if (llm == null || llms.none { it.presetId == llm && it.ready }) llm = llms.firstOrNull { it.ready }?.presetId }
    val needsAsr = scope != RedoScope.DigestOnly
    fun request() = RedoRequest(eventIds, segmentIds, scope, if (needsAsr) asr else null, if (scope != RedoScope.TranscribeOnly) llm else null)
    val estimate by produceState<RedoEstimate?>(null, scope, asr, llm) { value = runCatching { repo.estimate(request()) }.getOrNull() }
    var jobId by remember { mutableStateOf<String?>(null) }
    val jobs by repo.jobs.collectAsState()
    val job = jobId?.let { id -> jobs.firstOrNull { it.id == id } }
    val title = if (segmentsOnly) "重新转写 ${segmentIds.size} 段录音" else "重做 ${eventIds.size} 件事"
    RsSheet(title, null, onDismiss = onDismiss, fixedHeight = job == null) {
        if (job != null) { JobBody(repo, job, onDismiss); return@RsSheet }
        if (!segmentsOnly) {
            Segmented(listOf("只重新整理", "重新转写并整理"), if (scope == RedoScope.DigestOnly) 0 else 1) {
                scope = if (it == 0) RedoScope.DigestOnly else RedoScope.TranscribeAndDigest
            }
            Text(if (scope == RedoScope.DigestOnly) "原话不动，用选的模型把这段时间重新整理成事件" else "先用选的方式把录音重新转成文字，再重新整理",
                style = RsType.caption, color = c.textSecondary)
        } else Text("只换原话，事件不动；转好后可以再去首页重新整理", style = RsType.caption, color = c.textSecondary)
        if (needsAsr) {
            Label("用哪个转写")
            AsrChoice(repo.asr, estimate?.audioMs ?: 0L, asr) { asr = it }
        }
        if (scope != RedoScope.TranscribeOnly) {
            Label("用哪个模型整理")
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                llms.forEach { o ->
                    val on = o.presetId == llm
                    val bg by animateColorAsState(if (on) c.accentSoft else c.background, label = "llm")
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(bg)
                        .then(if (o.ready) Modifier.springClick { llm = o.presetId } else Modifier)
                        .padding(horizontal = 14.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(18.dp).clip(CircleShape).background(if (on) c.accent else c.separator), contentAlignment = Alignment.Center) {
                            if (on) Box(Modifier.size(7.dp).clip(CircleShape).background(Color.White))
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(o.title, style = RsType.body.copy(fontWeight = FontWeight.Medium), color = if (o.ready) c.text else c.textTertiary)
                            if (!o.ready) Text(o.note ?: "未填密钥", style = RsType.caption, color = c.textTertiary)
                        }
                    }
                }
            }
        }
        // 预计和提醒
        estimate?.let { e ->
            val warn = buildList {
                if (needsAsr && e.expiredSegments > 0) add(if (segmentsOnly) "其中 ${e.expiredSegments} 段原声已过期，只能保留原来的文字"
                    else "其中 ${e.expiredEvents} 件事的原声已过期，只能重新整理")
                if (scope != RedoScope.TranscribeOnly && e.editedEvents > 0) add("${e.editedEvents} 件事你手动改过，重做会覆盖你的修改")
            }
            val line = listOfNotNull(
                if (needsAsr && e.audioMs > 0) "录音 ${etaText(e.audioMs)?.removePrefix("约 ")}" else null,
                etaText(e.etaMillis)?.let { "预计 ${it.removePrefix("约 ")}" },
                costText(e.costYuan)?.let { "转写 $it" },
            ).joinToString(" · ")
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.background).padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (line.isNotBlank()) Text(line, style = RsType.subhead, color = c.textSecondary)
                warn.forEach { Text("· $it", style = RsType.caption, color = c.warning) }
                if (scope != RedoScope.TranscribeOnly) Text("重点和已确认、已完成的待办会保留；做完可以撤销", style = RsType.caption, color = c.textTertiary)
            }
        }
        val allExpired = needsAsr && estimate?.let { it.audioMs == 0L && (it.expiredSegments > 0) } == true
        val canStart = estimate != null && (!needsAsr || asr in repo.asr.options.value.filter { it.ready }.map { it.source }) &&
            (scope == RedoScope.TranscribeOnly || llm != null) && !allExpired
        ActionButton(if (allExpired) "原声都已过期，无法重新转写" else "开始", ActionKind.Primary, enabled = canStart) {
            if (needsAsr) repo.asr.remember(asr)
            jobId = repo.start(request())
        }
    }
}

/** 点“重做完成”通知打开的面板 */
@Composable
fun RedoJobSheet(jobId: String, onDismiss: () -> Unit) {
    if (!AppGraph.isRedoReady) { onDismiss(); return }
    val repo = AppGraph.redo
    val job = repo.jobs.collectAsState().value.firstOrNull { it.id == jobId }
    if (job == null) { LaunchedEffect(Unit) { onDismiss() }; return }
    RsSheet(job.title, null, onDismiss = onDismiss) { JobBody(repo, job, onDismiss) }
}

@Composable
private fun JobBody(repo: RedoService, job: RedoJob, onClose: () -> Unit) {
    val c = Rs.colors
    val scope = rememberCoroutineScope()
    var undoMsg by remember { mutableStateOf<String?>(null) }
    when (val st = job.state) {
        is RedoJobState.Running -> {
            Text(st.stage, style = RsType.body, color = c.text)
            Box(Modifier.fillMaxWidth().height(6.dp).clip(CircleShape).background(c.accentSoft)) {
                Box(Modifier.fillMaxWidth(st.fraction.coerceIn(0.02f, 1f)).height(6.dp).clip(CircleShape).background(c.accent))
            }
            Text("可以关掉面板去做别的，做完会通知你", style = RsType.caption, color = c.textTertiary)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ActionButton("后台继续", ActionKind.Secondary, Modifier.weight(1f)) { onClose() }
                ActionButton("取消", ActionKind.Danger, Modifier.weight(1f)) { repo.cancel(job.id) }
            }
        }
        is RedoJobState.Done -> {
            Text("做好了", style = RsType.title, color = c.text)
            Text(st.summary, style = RsType.body, color = c.textSecondary)
            undoMsg?.let { Text(it, style = RsType.caption, color = c.warning) }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (st.undoable) ActionButton("撤销重做", ActionKind.Secondary, Modifier.weight(1f)) {
                    scope.launch { repo.undo(job.id).onFailure { undoMsg = it.message ?: "撤销失败" } }
                }
                ActionButton("好", ActionKind.Primary, Modifier.weight(1f)) { repo.dismiss(job.id); onClose() }
            }
        }
        is RedoJobState.Failed -> {
            Text("没做成", style = RsType.title, color = c.text)
            Text(st.message, style = RsType.body, color = c.textSecondary)
            Text("原来的事件和原话都没动", style = RsType.caption, color = c.textTertiary)
            ActionButton("知道了", ActionKind.Primary) { repo.dismiss(job.id); onClose() }
        }
        RedoJobState.Cancelled -> {
            Text("已取消", style = RsType.title, color = c.text)
            Text("已经重新转好的原话保留；事件没有替换", style = RsType.body, color = c.textSecondary)
            ActionButton("知道了", ActionKind.Primary) { repo.dismiss(job.id); onClose() }
        }
        RedoJobState.Undone -> {
            Text("已撤销", style = RsType.title, color = c.text)
            Text("已恢复到重做前", style = RsType.body, color = c.textSecondary)
            ActionButton("好", ActionKind.Primary) { repo.dismiss(job.id); onClose() }
        }
    }
}
