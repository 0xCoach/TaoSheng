package app.resound.ui.transcript

import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.animation.togetherWith
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

/** 保存到手机的总文件夹：下载/Resound/ */
private const val EXPORT_DIR = "Resound"

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
    var progressJob by remember { mutableStateOf<Job?>(null) }
    // 后台导出任务的 id：开始后面板可以关掉，导出照常进行
    var jobId by remember { mutableStateOf<String?>(null) }
    // 合成好的文件：有音频时先在面板里试听，确认后再分享
    var done by remember { mutableStateOf<List<java.io.File>?>(null) }
    val exporter = if (app.resound.AppGraph.isAudioExporterReady) app.resound.AppGraph.audioExporter else null
    fun request() = ExportRequest(segmentIds, title.trim().ifBlank { suggestedTitle }, withText, withSrt, skipMedia)
    val estimate by produceState<ExportEstimate?>(null, skipMedia, segmentIds) {
        value = exporter?.let { runCatching { it.estimate(request()) }.getOrNull() }
    }
    // 关面板不取消后台任务；只有前台退回的导出才随面板取消
    fun close() { progressJob?.cancel(); onDismiss() }
    RsSheet("导出 ${segmentIds.size} 段录音", estimateLine(estimate), onDismiss = { close() }) {
        if (exporter == null) { Text("导出功能还没准备好", style = RsType.subhead, color = c.textSecondary); return@RsSheet }
        Label2("文件名")
        SheetInput(title, "录音标题") { title = it.take(60) }
        OptionRow("同时附上原话文字", "每句带在录音里的位置和当时时刻", withText) { withText = it; p.edit().putBoolean("exportText", it).apply() }
        OptionRow("字幕文件（.srt）", "和录音放一起，播放时原话跟着滚动", withSrt) { withSrt = it; p.edit().putBoolean("exportSrt", it).apply() }
        OptionRow("跳过媒体片段", "电视、视频的声音不导出", skipMedia) { skipMedia = it; p.edit().putBoolean("exportSkipMedia", it).apply() }
        val jobs = if (app.resound.AppGraph.isExportJobsReady) app.resound.AppGraph.exportJobs else null
        val job = jobId?.let { id -> jobs?.jobs?.collectAsState()?.value?.firstOrNull { it.id == id } }
        val running = progress
        val ready = done
        when {
            // 后台任务：进度、剩余时间、后台继续 / 取消；完成后就地试听、保存、分享
            job != null -> JobBody(job, onClose = onDismiss)
            ready != null -> ExportResult(ready, safeName(title.ifBlank { suggestedTitle }))
            running != null -> {
                LinearProgressIndicator(progress = { running }, color = c.accent, trackColor = c.accentSoft,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp).clip(CircleShape))
                Text("正在合成 ${(running * 100).toInt()}%", style = RsType.subhead, color = c.textSecondary)
                ActionButton("取消", ActionKind.Secondary) { progressJob?.cancel(); progressJob = null; progress = null }
            }
            else -> {
                val nothing = estimate?.let { it.audioSegments == 0 && !withText && !withSrt } == true
                ActionButton(if (estimate?.audioSegments == 0) "只导出文字" else "生成录音", ActionKind.Primary, enabled = !nothing) {
                    exporter.cleanup()
                    if (jobs != null) jobId = jobs.start(request())
                    else {
                        // 后台任务还没装配时退回前台导出
                        progress = 0f
                        progressJob = scope.launch {
                            exporter.export(request()).collect { step ->
                                when (step) {
                                    is ExportProgress.Working -> progress = step.fraction
                                    is ExportProgress.Failed -> { progress = null; LinePlayer.notice = step.message }
                                    is ExportProgress.Done -> { progress = null; done = step.files }
                                }
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
            // 按本机最近几次实测速度估的合成耗时（没实测时底层给保守值）
            if (it.etaMillis >= 3_000) add("合成大约需要 ${durationWords(it.etaMillis)}")
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

private fun mime(f: java.io.File) = when (f.extension.lowercase()) { "m4a" -> "audio/mp4"; "txt" -> "text/plain"; "zip" -> "application/zip"; "srt" -> "application/x-subrip"; else -> "*/*" }

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

/** 一句字幕：在合成录音里的起止（毫秒）和文字 */
private data class Cue(val start: Int, val end: Int, val text: String)

/** 读 .srt：序号 / 时间轴 / 文字（可多行），空行分隔 */
private fun parseSrt(file: java.io.File?): List<Cue> {
    if (file == null || !file.exists()) return emptyList()
    fun ms(t: String): Int { val (h, m, rest) = t.trim().split(":"); val (sec, milli) = rest.split(","); return ((h.toInt() * 60 + m.toInt()) * 60 + sec.toInt()) * 1000 + milli.toInt() }
    return runCatching {
        file.readText().replace("\r", "").split(Regex("\n\\s*\n")).mapNotNull { block ->
            val lines = block.trim().lines()
            val timeLine = lines.firstOrNull { "-->" in it } ?: return@mapNotNull null
            val (a, b) = timeLine.split("-->")
            Cue(ms(a), ms(b), lines.dropWhile { "-->" !in it }.drop(1).joinToString(" "))
        }
    }.getOrDefault(emptyList())
}

private val PREVIEW_SPEEDS = listOf(1f, 1.25f, 1.5f, 2f)

/**
 * 合成录音的试听：上方实时字幕（当前一句 + 淡灰的下一句），细进度条可点可拖，
 * 下方 后退 15 秒 · 播放/暂停 · 前进 15 秒，右侧倍速（1×/1.25×/1.5×/2×，只改快慢不改音调）。
 * 试听时暂停日常收音，离开面板自动停。
 */
@Composable
private fun PreviewPlayer(file: java.io.File, srt: java.io.File?) {
    val c = Rs.colors
    val player = remember(file) { android.media.MediaPlayer().apply { setDataSource(file.absolutePath); prepare() } }
    val cues = remember(srt) { parseSrt(srt) }
    var playing by remember { mutableStateOf(false) }
    var pos by remember { mutableStateOf(0) }
    var dragging by remember { mutableStateOf<Float?>(null) }
    var speed by remember { mutableStateOf(1f) }
    val total = remember(player) { player.duration.coerceAtLeast(1) }
    fun hold(on: Boolean) {
        if (!app.resound.AppGraph.isOriginalAudioReady) return
        if (on) app.resound.AppGraph.originalAudio.beginPlayback() else app.resound.AppGraph.originalAudio.endPlayback()
    }
    fun applySpeed() { if (player.isPlaying) runCatching { player.playbackParams = android.media.PlaybackParams().setSpeed(speed).setPitch(1f) } }
    fun seek(to: Int) { val t = to.coerceIn(0, total); player.seekTo(t); pos = t }
    androidx.compose.runtime.DisposableEffect(player) {
        player.setOnCompletionListener { playing = false; hold(false) }
        onDispose { if (playing) hold(false); player.release() }
    }
    LaunchedEffect(playing) {
        while (playing) { if (dragging == null) pos = runCatching { player.currentPosition }.getOrDefault(pos); kotlinx.coroutines.delay(120) }
    }
    fun mmss(ms: Int) = "%d:%02d".format(ms / 60_000, ms / 1000 % 60)
    val shown = dragging?.let { (it * total).toInt() } ?: pos
    // 当前句：开始时间不晚于播放位置的最后一句（停顿时仍停在上一句，像歌词一样）
    val playingIdx = cues.indexOfLast { it.start <= shown }.coerceAtLeast(0)
    // 上下滑字幕调进度：每滑过一行换一句，松手跳到那句开头
    var scrollIdx by remember { mutableStateOf<Int?>(null) }
    val center = scrollIdx ?: playingIdx
    val rowPx = with(androidx.compose.ui.platform.LocalDensity.current) { 30.dp.toPx() }
    Column(Modifier.fillMaxWidth().clip(androidx.compose.foundation.shape.RoundedCornerShape(22.dp)).background(c.background)
        .padding(horizontal = 16.dp, vertical = 14.dp)) {
        if (cues.isNotEmpty()) {
            var acc by remember { mutableStateOf(0f) }
            Column(Modifier.fillMaxWidth().height(100.dp).pointerInput(cues) {
                detectVerticalDragGestures(
                    onDragStart = { acc = 0f; scrollIdx = playingIdx },
                    onDragEnd = { scrollIdx?.let { seek(cues[it].start) }; scrollIdx = null },
                    onDragCancel = { scrollIdx = null },
                ) { change, dy ->
                    change.consume(); acc += dy
                    // 往上滑看后面的句子，往下滑回到前面
                    scrollIdx = (playingIdx - (acc / rowPx).toInt()).coerceIn(0, cues.lastIndex)
                }
            }, verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center) {
                listOf(center - 1, center, center + 1).forEach { k ->
                    val cue = cues.getOrNull(k)
                    val main = k == center
                    androidx.compose.animation.AnimatedContent(cue, label = "cue$k", transitionSpec = {
                        androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(180)) togetherWith androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(120))
                    }) { q ->
                        Text(q?.text ?: "", maxLines = if (main) 2 else 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            style = if (main) RsType.body.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold) else RsType.caption,
                            color = when { main && scrollIdx != null -> c.accent; main -> c.text; else -> c.textTertiary },
                            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp))
                    }
                }
            }
            // 滑动时提示松手会跳到哪
            Text(scrollIdx?.let { "松手跳到 ${mmss(cues[it].start)}" } ?: "上下滑动字幕可以调进度", style = RsType.caption, color = if (scrollIdx != null) c.accent else c.textTertiary.copy(alpha = 0.7f),
                modifier = Modifier.fillMaxWidth().padding(top = 2.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            Spacer(Modifier.height(18.dp))
        }
        // 细进度条：点哪儿跳哪儿，按住拖动预览
        SeekBar(shown / total.toFloat(), onDrag = { dragging = it }, onDone = { f -> seek((f * total).toInt()); dragging = null })
        Row(Modifier.padding(top = 4.dp)) {
            Text(mmss(shown), style = RsType.caption, color = c.textTertiary, modifier = Modifier.weight(1f))
            Text(mmss(total), style = RsType.caption, color = c.textTertiary)
        }
        Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.weight(1f))
            Text("−15", style = RsType.subhead.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold), color = c.textSecondary,
                modifier = Modifier.clip(CircleShape).springClick { seek(pos - 15_000) }.padding(12.dp))
            Spacer(Modifier.width(14.dp))
            androidx.compose.foundation.layout.Box(Modifier.size(52.dp).clip(CircleShape).background(c.accent).springClick {
                if (playing) { player.pause(); playing = false; hold(false) } else { hold(true); player.start(); playing = true; applySpeed() }
            }, contentAlignment = Alignment.Center) {
                Text(if (playing) "❚❚" else "▶", style = RsType.headline, color = androidx.compose.ui.graphics.Color.White)
            }
            Spacer(Modifier.width(14.dp))
            Text("+15", style = RsType.subhead.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold), color = c.textSecondary,
                modifier = Modifier.clip(CircleShape).springClick { seek(pos + 15_000) }.padding(12.dp))
            Spacer(Modifier.weight(1f))
        }
        // 倍速放在右下角一颗小胶囊里，点一下换一档
        Row(Modifier.fillMaxWidth(), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.End) {
            Text(if (speed == 1f) "1×" else "${speed.toString().trimEnd('0').trimEnd('.')}×", style = RsType.caption.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold),
                color = c.accent, modifier = Modifier.clip(CircleShape).background(c.accentSoft).springClick {
                    speed = PREVIEW_SPEEDS[(PREVIEW_SPEEDS.indexOf(speed) + 1) % PREVIEW_SPEEDS.size]; applySpeed()
                }.padding(horizontal = 12.dp, vertical = 5.dp))
        }
    }
}

/** 4dp 细轨 + 14dp 圆点；点任意位置跳过去，拖动时圆点放大 */
@Composable
private fun SeekBar(fraction: Float, onDrag: (Float) -> Unit, onDone: (Float) -> Unit) {
    val c = Rs.colors
    var width by remember { mutableStateOf(1) }
    var active by remember { mutableStateOf(false) }
    val knob by androidx.compose.animation.core.animateDpAsState(if (active) 18.dp else 14.dp, label = "knob")
    fun at(x: Float) = (x / width).coerceIn(0f, 1f)
    androidx.compose.foundation.layout.Box(Modifier.fillMaxWidth().height(24.dp)
        .onSizeChanged { width = it.width.coerceAtLeast(1) }
        .pointerInput(Unit) { detectTapGestures { o -> onDone(at(o.x)) } }
        .pointerInput(Unit) {
            var last = 0f
            detectHorizontalDragGestures(onDragStart = { active = true; last = at(it.x); onDrag(last) },
                onDragEnd = { active = false; onDone(last) }, onDragCancel = { active = false; onDone(last) }) { change, _ -> last = at(change.position.x); onDrag(last) }
        }, contentAlignment = Alignment.CenterStart) {
        androidx.compose.foundation.layout.Box(Modifier.fillMaxWidth().height(4.dp).clip(CircleShape).background(c.accentSoft))
        androidx.compose.foundation.layout.Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(4.dp).clip(CircleShape).background(c.accent))
        androidx.compose.foundation.layout.Box(Modifier.offset { androidx.compose.ui.unit.IntOffset(((width * fraction) - knob.toPx() / 2).toInt(), 0) }
            .size(knob).clip(CircleShape).background(c.accent))
    }
}

/**
 * 保存到“下载/Resound/<名字>/”，每次一个文件夹，里面的文件同名（.m4a/.txt/.srt），播放器能自动认字幕。
 * 同名文件夹已存在时加“（2）”“（3）”。安卓 10+ 用 MediaStore，不需要存储权限。返回实际的文件夹名。
 */
private suspend fun saveToDownloads(context: android.content.Context, files: List<java.io.File>, name: String): String =
    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val resolver = context.contentResolver
        val base = android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI
        fun taken(folder: String): Boolean = resolver.query(base, arrayOf(android.provider.MediaStore.MediaColumns._ID),
            "${android.provider.MediaStore.MediaColumns.RELATIVE_PATH} = ?", arrayOf("Download/$EXPORT_DIR/$folder/"), null)?.use { it.count > 0 } ?: false
        var folder = name; var n = 2
        while (taken(folder)) folder = "$name（${n++}）"
        files.forEach { f ->
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, "$folder.${f.extension}")
                put(android.provider.MediaStore.MediaColumns.MIME_TYPE, mime(f))
                put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, "Download/$EXPORT_DIR/$folder/")
                put(android.provider.MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = requireNotNull(resolver.insert(base, values)) { "无法创建文件" }
            resolver.openOutputStream(uri)!!.use { out -> f.inputStream().use { it.copyTo(out) } }
            resolver.update(uri, android.content.ContentValues().apply { put(android.provider.MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        }
        folder
    }

/** 分享哪一个：录音 / 原话文字 / 全部打包成 zip；记住上次的选择并排在最前 */
private enum class ShareKind(val title: String, val sub: String, val icon: String) {
    Audio("录音", ".m4a · 微信里可直接播放", "🎵"),
    Text("原话文字", ".txt · 对方点开就能看", "📝"),
    Zip("全部打包", ".zip · 录音 + 原话 + 字幕，适合发到电脑", "📦"),
}

@Composable
private fun ShareChooser(files: List<java.io.File>, name: String, onDismiss: () -> Unit) {
    val c = Rs.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val p = remember { prefs(context) }
    val last = remember { runCatching { ShareKind.valueOf(p.getString("shareKind", "Audio")!!) }.getOrDefault(ShareKind.Audio) }
    fun file(ext: String) = files.firstOrNull { it.extension.equals(ext, true) }
    val options = (listOf(last) + ShareKind.entries.filter { it != last }).filter {
        when (it) { ShareKind.Audio -> file("m4a") != null; ShareKind.Text -> file("txt") != null; ShareKind.Zip -> files.size > 1 }
    }
    RsSheet("分享什么？", "微信等应用一次只能收一个文件", onDismiss = onDismiss) {
        options.forEach { kind ->
            val on = kind == last
            Row(Modifier.fillMaxWidth().clip(androidx.compose.foundation.shape.RoundedCornerShape(16.dp))
                .background(if (on) c.accentSoft else c.background).springClick {
                    p.edit().putString("shareKind", kind.name).apply()
                    scope.launch {
                        val target = when (kind) {
                            ShareKind.Audio -> file("m4a")!!
                            ShareKind.Text -> file("txt")!!
                            ShareKind.Zip -> zip(files, name)
                        }
                        share(context, listOf(target)); onDismiss()
                    }
                }.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(kind.icon, style = RsType.headline)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(kind.title, style = RsType.body.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Medium), color = if (on) c.accent else c.text)
                    Text(kind.sub, style = RsType.caption, color = c.textTertiary)
                }
                if (on) Text("上次", style = RsType.caption, color = c.accent)
            }
        }
    }
}

/** 把几个文件打成一个 zip（放在同一缓存目录，随导出文件一起 1 天后清理），zip 里的文件名不变 */
private suspend fun zip(files: List<java.io.File>, name: String): java.io.File = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
    val out = java.io.File(files.first().parentFile, "$name.zip")
    java.util.zip.ZipOutputStream(out.outputStream().buffered()).use { z ->
        files.forEach { f -> z.putNextEntry(java.util.zip.ZipEntry(f.name)); f.inputStream().use { it.copyTo(z) }; z.closeEntry() }
    }
    out
}

/** “约 2 分 10 秒” / “约 40 秒” */
internal fun durationWords(ms: Long): String {
    val sec = ((ms + 999) / 1000).coerceAtLeast(1)
    return if (sec >= 60) "${sec / 60} 分 ${sec % 60} 秒" else "$sec 秒"
}

/** 合成好的结果：试听、保存到 下载/Resound/、分享（选一个文件） */
@Composable
internal fun ExportResult(files: List<java.io.File>, name: String) {
    val c = Rs.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var savedTo by remember { mutableStateOf<String?>(null) }
    var picking by remember { mutableStateOf(false) }
    files.firstOrNull { it.extension.equals("m4a", true) }?.let { PreviewPlayer(it, files.firstOrNull { f -> f.extension.equals("srt", true) }) }
    Text("已生成：" + files.joinToString("、") { it.name }, style = RsType.caption, color = c.textTertiary)
    Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp)) {
        ActionButton(if (savedTo != null) "已保存" else "保存", ActionKind.Primary, Modifier.weight(1f), enabled = savedTo == null) {
            scope.launch {
                savedTo = runCatching { saveToDownloads(context, files, name) }.getOrNull()
                LinePlayer.notice = savedTo?.let { "已保存到 下载 › $EXPORT_DIR › $it" } ?: "保存失败，请稍后再试"
            }
        }
        // 微信等一次只收一个文件：只有一个文件时直接分享，否则先选分享什么
        ActionButton("分享", ActionKind.Secondary, Modifier.weight(1f)) { if (files.size == 1) share(context, files) else picking = true }
    }
    savedTo?.let { Text("在 文件管理 › 下载 › $EXPORT_DIR › $it 里", style = RsType.caption, color = c.textTertiary) }
    if (picking) ShareChooser(files, name) { picking = false }
}

/** 一个后台导出任务在面板里的样子：进行中 / 完成 / 失败 / 已取消 */
@Composable
internal fun JobBody(job: app.resound.transcribe.ExportJob, onClose: () -> Unit) {
    val c = Rs.colors
    val jobs = app.resound.AppGraph.exportJobs
    when (val st = job.state) {
        is app.resound.transcribe.ExportJobState.Running -> {
            LinearProgressIndicator(progress = { st.fraction }, color = c.accent, trackColor = c.accentSoft,
                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp).clip(CircleShape))
            // 剩余时间按实际进度推算，越往后越准
            val elapsed = System.currentTimeMillis() - job.startedAt
            val left = if (st.fraction > 0.05f) (elapsed / st.fraction * (1 - st.fraction)).toLong() else null
            Text("正在合成 ${(st.fraction * 100).toInt()}%" + (left?.let { " · 大约还要 ${durationWords(it)}" } ?: ""), style = RsType.subhead, color = c.textSecondary)
            Text("可以关掉面板去做别的，导出会在后台继续，完成后通知你", style = RsType.caption, color = c.textTertiary)
            Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp)) {
                ActionButton("后台继续", ActionKind.Primary, Modifier.weight(1f)) { onClose() }
                ActionButton("取消导出", ActionKind.Secondary, Modifier.weight(1f)) { jobs.cancel(job.id) }
            }
        }
        is app.resound.transcribe.ExportJobState.Done -> ExportResult(st.files, safeName(job.title))
        is app.resound.transcribe.ExportJobState.Failed -> {
            Text(st.message, style = RsType.subhead, color = c.critical)
            ActionButton("知道了", ActionKind.Secondary) { jobs.dismiss(job.id); onClose() }
        }
        app.resound.transcribe.ExportJobState.Cancelled -> {
            Text("已取消导出", style = RsType.subhead, color = c.textSecondary)
            ActionButton("知道了", ActionKind.Secondary) { jobs.dismiss(job.id); onClose() }
        }
    }
}

/** 从底部小胶囊或“导出完成”通知打开的面板：只看这个任务 */
@Composable
fun ExportJobSheet(jobId: String, onDismiss: () -> Unit) {
    if (!app.resound.AppGraph.isExportJobsReady) { onDismiss(); return }
    val job = app.resound.AppGraph.exportJobs.jobs.collectAsState().value.firstOrNull { it.id == jobId }
    if (job == null) { LaunchedEffect(Unit) { LinePlayer.notice = "这次导出的文件已清理"; onDismiss() }; return }
    RsSheet(job.title, null, onDismiss = onDismiss) {
        JobBody(job, onClose = onDismiss)
        Text("录音将离开涛声，请注意他人隐私", style = RsType.caption, color = Rs.colors.textTertiary,
            modifier = Modifier.fillMaxWidth(), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

/** 全局：要打开哪个导出任务的面板（点通知或底部胶囊时设置） */
object ExportUi {
    var openJob by androidx.compose.runtime.mutableStateOf<String?>(null)
}

/** 有导出在进行时，底部浮一个小胶囊“正在导出 35% ›”，点开看进度；完成后变成“导出完成 ›” */
@Composable
fun ExportCapsule(bottomPadding: androidx.compose.ui.unit.Dp) {
    if (!app.resound.AppGraph.isExportJobsReady) return
    val c = Rs.colors
    val jobs by app.resound.AppGraph.exportJobs.jobs.collectAsState()
    val job = jobs.lastOrNull { it.state is app.resound.transcribe.ExportJobState.Running } ?: jobs.lastOrNull { it.state is app.resound.transcribe.ExportJobState.Done }
    val text = when (val st = job?.state) {
        is app.resound.transcribe.ExportJobState.Running -> "正在导出 ${(st.fraction * 100).toInt()}% ›"
        is app.resound.transcribe.ExportJobState.Done -> "导出完成 · 点此保存或分享 ›"
        else -> null
    }
    androidx.compose.animation.AnimatedVisibility(text != null && ExportUi.openJob == null,
        enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.slideInVertically { it },
        exit = androidx.compose.animation.fadeOut()) {
        androidx.compose.foundation.layout.Box(Modifier.fillMaxWidth().padding(bottom = bottomPadding), contentAlignment = Alignment.BottomCenter) {
            Text(text ?: "", style = RsType.subhead.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold), color = androidx.compose.ui.graphics.Color.White,
                modifier = Modifier.clip(CircleShape).background(c.accent).springClick { job?.let { ExportUi.openJob = it.id } }
                    .padding(horizontal = 18.dp, vertical = 10.dp))
        }
    }
}
