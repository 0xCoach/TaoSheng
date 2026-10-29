package app.resound.ui.transcript

import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import app.resound.digest.MemoryEvent
import app.resound.transcribe.ExportEstimate
import app.resound.transcribe.ExportProgress
import app.resound.transcribe.ExportRequest
import app.resound.ui.skills.Toggle
import app.resound.ui.theme.Rs
import app.resound.ui.theme.RsType
import app.resound.ui.verify.ActionButton
import app.resound.ui.verify.ActionKind
import app.resound.ui.verify.RsSheet
import app.resound.ui.verify.SheetInput
import app.resound.ui.verify.formatBytes
import app.resound.ui.verify.formatClock
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 录音导出的自动标题：
 * 一件事 → “10月29日 14:05 项目进度讨论”；多件事 → 占时最长那件 + “等 N 件事”；没整理出事件 → “10月29日 18:30–18:45 录音”。
 */
internal fun exportTitle(events: List<MemoryEvent>, startAt: Long, endAt: Long): String {
    fun day(at: Long) = Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("M月d日"))
    val hit = events.filter { it.startAt < endAt && it.endAt > startAt }
    val main = hit.maxByOrNull { minOf(it.endAt, endAt) - maxOf(it.startAt, startAt) }
    return when {
        main == null -> "${day(startAt)} ${formatClock(startAt)}–${formatClock(endAt)} 录音"
        hit.size == 1 -> "${day(main.startAt)} ${formatClock(main.startAt)} ${main.title}"
        else -> "${day(main.startAt)} ${formatClock(main.startAt)} ${main.title} 等 ${hit.size} 件事"
    }.replace(Regex("[/\\\\:*?\"<>|]"), " ").take(60)
}

private fun prefs(context: android.content.Context) = context.getSharedPreferences("ui", android.content.Context.MODE_PRIVATE)

/**
 * 导出面板：合成 1 个音频 · 时长 · 大小；附原话文字 / 字幕 / 跳过媒体（记住上次的选择）；标题可改；
 * 导出时显示进度、可取消，完成后用系统分享发出去。
 */
@Composable
fun ExportSheet(segmentIds: List<Long>, suggestedTitle: String, onDismiss: () -> Unit) {
    val c = Rs.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val p = remember { prefs(context) }
    var title by remember { mutableStateOf(suggestedTitle) }
    var withText by remember { mutableStateOf(p.getBoolean("exportText", true)) }
    var withSrt by remember { mutableStateOf(p.getBoolean("exportSrt", true)) }
    var skipMedia by remember { mutableStateOf(p.getBoolean("exportSkipMedia", true)) }
    var progress by remember { mutableStateOf<Float?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    val exporter = if (app.resound.AppGraph.isAudioExporterReady) app.resound.AppGraph.audioExporter else null
    fun request() = ExportRequest(segmentIds, title.trim().ifBlank { suggestedTitle }, withText, withSrt, skipMedia)
    val estimate by produceState<ExportEstimate?>(null, skipMedia, segmentIds) {
        value = exporter?.let { runCatching { it.estimate(request()) }.getOrNull() }
    }
    fun close() { job?.cancel(); onDismiss() }
    RsSheet("导出 ${segmentIds.size} 段录音", estimateLine(estimate), onDismiss = { close() }) {
        if (exporter == null) { Text("导出功能还没准备好", style = RsType.subhead, color = c.textSecondary); return@RsSheet }
        Label2("文件名")
        SheetInput(title, "录音标题") { title = it.take(60) }
        OptionRow("同时附上原话文字", "每句带在录音里的位置和当时时刻", withText) { withText = it; p.edit().putBoolean("exportText", it).apply() }
        OptionRow("字幕文件（.srt）", "和录音放一起，播放时原话跟着滚动", withSrt) { withSrt = it; p.edit().putBoolean("exportSrt", it).apply() }
        OptionRow("跳过媒体片段", "电视、视频的声音不导出", skipMedia) { skipMedia = it; p.edit().putBoolean("exportSkipMedia", it).apply() }
        val running = progress
        if (running != null) {
            LinearProgressIndicator(progress = { running }, color = c.accent, trackColor = c.accentSoft,
                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp).clip(CircleShape))
            Text("正在合成 ${(running * 100).toInt()}%", style = RsType.subhead, color = c.textSecondary)
            ActionButton("取消", ActionKind.Secondary) { job?.cancel(); job = null; progress = null }
        } else {
            val nothing = estimate?.let { it.audioSegments == 0 && !withText && !withSrt } == true
            ActionButton(if (estimate?.audioSegments == 0) "只导出文字并分享" else "导出并分享", ActionKind.Primary, enabled = !nothing) {
                exporter.cleanup()
                progress = 0f
                job = scope.launch {
                    exporter.export(request()).collect { step ->
                        when (step) {
                            is ExportProgress.Working -> progress = step.fraction
                            is ExportProgress.Failed -> { progress = null; LinePlayer.notice = step.message }
                            is ExportProgress.Done -> {
                                progress = null
                                share(context, step.files)
                                onDismiss()
                            }
                        }
                    }
                }
            }
        }
        Text("录音将离开涛声，请注意他人隐私", style = RsType.caption, color = c.textTertiary,
            modifier = Modifier.fillMaxWidth(), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

private fun estimateLine(e: ExportEstimate?): String? = e?.let {
    buildList {
        if (it.audioSegments > 0) {
            val min = it.durationMillis / 60_000; val sec = it.durationMillis / 1000 % 60
            add("合成 1 个音频 · 约 ${if (min > 0) "$min 分 " else ""}$sec 秒 · 约 ${formatBytes(it.bytes)}")
            // 大文件提前说一声要等多久（按约每分钟 0.15 秒估）
            if (it.durationMillis > 60 * 60_000) add("合成大约需要 ${(it.durationMillis / 60_000 * 0.15).toInt().coerceAtLeast(5)} 秒")
        } else add("选中的内容没有录音，只导出文字")
        if (it.textOnly > 0) add("${it.textOnly} 段语音输入只有文字")
        if (it.expired > 0) add("${it.expired} 段录音已过保留期，只导出文字")
        if (it.mediaSkipped > 0) add("跳过 ${it.mediaSkipped} 段媒体")
    }.joinToString("\n")
}

private fun share(context: android.content.Context, files: List<java.io.File>) {
    val uris = ArrayList(files.map { FileProvider.getUriForFile(context, "${context.packageName}.files", it) })
    val send = if (uris.size == 1) Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris.first()).setType(mime(files.first()))
    else Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris).setType("*/*")
    send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(send, "分享录音").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

private fun mime(f: java.io.File) = when (f.extension.lowercase()) { "m4a" -> "audio/mp4"; "txt" -> "text/plain"; "srt" -> "application/x-subrip"; else -> "*/*" }

@Composable
private fun Label2(text: String) = Text(text, style = RsType.caption, color = Rs.colors.textSecondary, modifier = Modifier.padding(top = 4.dp))

@Composable
private fun OptionRow(title: String, sub: String, on: Boolean, onChange: (Boolean) -> Unit) {
    val c = Rs.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = RsType.body, color = c.text)
            Text(sub, style = RsType.caption, color = c.textSecondary)
        }
        Spacer(Modifier.width(12.dp))
        Toggle(on, onChange)
    }
}
