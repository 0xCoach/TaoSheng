package app.resound.ui.transcript

import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import app.resound.ui.verify.springClick
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
    // 文件和文件夹用：开头完整日期便于按名排序；时间用“.”代替“:”（电脑上冒号不能做文件名）
    fun day(at: Long) = Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))
    fun hm(at: Long) = formatClock(at).replace(':', '.')
    val hit = events.filter { it.startAt < endAt && it.endAt > startAt }
    val main = hit.maxByOrNull { minOf(it.endAt, endAt) - maxOf(it.startAt, startAt) }
    return safeName(when {
        main == null -> "${day(startAt)} ${hm(startAt)}-${hm(endAt)} 录音"
        hit.size == 1 -> "${day(main.startAt)} ${hm(main.startAt)} ${main.title}"
        else -> "${day(main.startAt)} ${hm(main.startAt)} ${main.title} 等 ${hit.size} 件事"
    })
}

/** 去掉不能做文件名的字符，压掉多余空格，截到 60 字 */
internal fun safeName(name: String) = name.replace(Regex("[/\\\\:*?\"<>|\\n\\r\\t]"), " ").replace(Regex(" +"), " ").trim().take(60)

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
    // 合成好的文件：有音频时先在面板里试听，确认后再分享
    var done by remember { mutableStateOf<List<java.io.File>?>(null) }
    var savedTo by remember { mutableStateOf<String?>(null) }
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
        val ready = done
        if (ready != null) {
            // 试听：播放/暂停 + 可拖动的进度条，拖到哪儿就从哪儿播
            ready.firstOrNull { it.extension.equals("m4a", true) }?.let { PreviewPlayer(it) }
            Text("已生成：" + ready.joinToString("、") { it.name }, style = RsType.caption, color = c.textTertiary)
            Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp)) {
                ActionButton(if (savedTo != null) "已保存" else "保存", ActionKind.Primary, Modifier.weight(1f), enabled = savedTo == null) {
                    scope.launch {
                        savedTo = runCatching { saveToDownloads(context, ready, safeName(title.ifBlank { suggestedTitle })) }.getOrNull()
                        LinePlayer.notice = savedTo?.let { "已保存到 下载 › 涛声 › $it" } ?: "保存失败，请稍后再试"
                    }
                }
                ActionButton("分享", ActionKind.Secondary, Modifier.weight(1f)) { share(context, ready) }
            }
            savedTo?.let { Text("在 文件管理 › 下载 › 涛声 › $it 里", style = RsType.caption, color = c.textTertiary) }
        } else if (running != null) {
            LinearProgressIndicator(progress = { running }, color = c.accent, trackColor = c.accentSoft,
                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp).clip(CircleShape))
            Text("正在合成 ${(running * 100).toInt()}%", style = RsType.subhead, color = c.textSecondary)
            ActionButton("取消", ActionKind.Secondary) { job?.cancel(); job = null; progress = null }
        } else {
            val nothing = estimate?.let { it.audioSegments == 0 && !withText && !withSrt } == true
            ActionButton(if (estimate?.audioSegments == 0) "只导出文字" else "生成录音", ActionKind.Primary, enabled = !nothing) {
                exporter.cleanup()
                progress = 0f
                job = scope.launch {
                    exporter.export(request()).collect { step ->
                        when (step) {
                            is ExportProgress.Working -> progress = step.fraction
                            is ExportProgress.Failed -> { progress = null; LinePlayer.notice = step.message }
                            is ExportProgress.Done -> {
                                progress = null
                                done = step.files
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

/** 合成录音的试听：▶/❚❚、可拖动进度条、当前/总时长；试听时暂停日常收音，离开面板自动停 */
@Composable
private fun PreviewPlayer(file: java.io.File) {
    val c = Rs.colors
    val player = remember(file) { android.media.MediaPlayer().apply { setDataSource(file.absolutePath); prepare() } }
    var playing by remember { mutableStateOf(false) }
    var pos by remember { mutableStateOf(0) }
    var dragging by remember { mutableStateOf<Float?>(null) }
    val total = remember(player) { player.duration.coerceAtLeast(1) }
    fun hold(on: Boolean) {
        if (!app.resound.AppGraph.isOriginalAudioReady) return
        if (on) app.resound.AppGraph.originalAudio.beginPlayback() else app.resound.AppGraph.originalAudio.endPlayback()
    }
    androidx.compose.runtime.DisposableEffect(player) {
        player.setOnCompletionListener { playing = false; hold(false) }
        onDispose { if (playing) hold(false); player.release() }
    }
    LaunchedEffect(playing) {
        while (playing) { if (dragging == null) pos = runCatching { player.currentPosition }.getOrDefault(pos); kotlinx.coroutines.delay(200) }
    }
    fun mmss(ms: Int) = "%d:%02d".format(ms / 60_000, ms / 1000 % 60)
    androidx.compose.foundation.layout.Column(Modifier.fillMaxWidth().clip(androidx.compose.foundation.shape.RoundedCornerShape(18.dp))
        .background(c.background).padding(horizontal = 14.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.foundation.layout.Box(Modifier.padding(end = 10.dp).size(40.dp).clip(CircleShape).background(c.accent)
                .springClick {
                    if (playing) { player.pause(); playing = false; hold(false) } else { hold(true); player.start(); playing = true }
                }, contentAlignment = Alignment.Center) {
                Text(if (playing) "❚❚" else "▶", style = RsType.subhead, color = androidx.compose.ui.graphics.Color.White)
            }
            androidx.compose.foundation.layout.Column(Modifier.weight(1f)) {
                androidx.compose.material3.Slider(
                    value = dragging ?: (pos / total.toFloat()),
                    onValueChange = { dragging = it },
                    onValueChangeFinished = { dragging?.let { f -> val to = (f * total).toInt(); player.seekTo(to); pos = to }; dragging = null },
                    colors = androidx.compose.material3.SliderDefaults.colors(thumbColor = c.accent, activeTrackColor = c.accent, inactiveTrackColor = c.accentSoft),
                )
                Row {
                    Text(mmss(((dragging ?: (pos / total.toFloat())) * total).toInt()), style = RsType.caption, color = c.textTertiary, modifier = Modifier.weight(1f))
                    Text(mmss(total), style = RsType.caption, color = c.textTertiary)
                }
            }
        }
    }
}

/**
 * 保存到“下载/涛声/<名字>/”，每次一个文件夹，里面的文件同名（.m4a/.txt/.srt），播放器能自动认字幕。
 * 同名文件夹已存在时加“（2）”“（3）”。安卓 10+ 用 MediaStore，不需要存储权限。返回实际的文件夹名。
 */
private suspend fun saveToDownloads(context: android.content.Context, files: List<java.io.File>, name: String): String =
    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val resolver = context.contentResolver
        val base = android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI
        fun taken(folder: String): Boolean = resolver.query(base, arrayOf(android.provider.MediaStore.MediaColumns._ID),
            "${android.provider.MediaStore.MediaColumns.RELATIVE_PATH} = ?", arrayOf("Download/涛声/$folder/"), null)?.use { it.count > 0 } ?: false
        var folder = name; var n = 2
        while (taken(folder)) folder = "$name（${n++}）"
        files.forEach { f ->
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, "$folder.${f.extension}")
                put(android.provider.MediaStore.MediaColumns.MIME_TYPE, mime(f))
                put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, "Download/涛声/$folder/")
                put(android.provider.MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = requireNotNull(resolver.insert(base, values)) { "无法创建文件" }
            resolver.openOutputStream(uri)!!.use { out -> f.inputStream().use { it.copyTo(out) } }
            resolver.update(uri, android.content.ContentValues().apply { put(android.provider.MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        }
        folder
    }
