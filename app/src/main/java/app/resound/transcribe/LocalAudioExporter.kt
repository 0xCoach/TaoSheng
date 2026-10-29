package app.resound.transcribe

import app.resound.data.*
import app.resound.voiceprint.VoiceprintThresholds
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.channels.Channel
import org.json.JSONArray

internal data class ExportSource(val row: SegmentWithLines, val events: List<MemoryEventEntity>) {
    val audio get() = File(row.segment.path).isFile
    val voice get() = row.lines.any { !it.voiceInputJson.isNullOrBlank() } && !audio
    val media get() = events.any { it.scene == "媒体" && it.startAt < row.segment.endAt && it.endAt > row.segment.startAt }
}

/** 两条 IO 协程通过有界通道合成；坏段中途失败时重建输出，避免部分音频污染时间轴。 */
internal class LocalAudioExporter(
    private val directory: File,
    private val load: suspend (List<Long>) -> List<ExportSource>,
    private val decode: suspend (File, suspend (ByteArray) -> Unit) -> Unit,
    private val sink: (File) -> ExportPcmSink = ::ExportAacEncoder,
    private val now: () -> Long = System::currentTimeMillis,
    private val speeds: ExportSpeeds = ExportSpeeds(),
    private val record: (String) -> Unit = {},
) : AudioExporter {
    constructor(cacheDir: File, dao: TranscribeDao, speeds: ExportSpeeds = ExportSpeeds(), record: (String) -> Unit = {}) : this(File(cacheDir, "exports"), { ids ->
        ids.distinct().chunked(500).flatMap { dao.exportSegments(it) }.map { row ->
            val events = dao.exportEvents(row.segment.id, row.segment.startAt, row.segment.endAt)
            ExportSource(row, events)
        }
    }, ExportPcmDecoder::decode, speeds = speeds, record = record)

    private val active = mutableSetOf<File>()
    private suspend fun sources(request: ExportRequest) = load(request.segmentIds.distinct())
        .distinctBy { it.row.segment.id }.sortedWith(compareBy({ it.row.segment.startAt }, { it.row.segment.id }))
    override suspend fun estimate(request: ExportRequest): ExportEstimate = withContext(Dispatchers.IO) {
        val all = sources(request)
        val selected = all.filterNot { request.skipMedia && it.media }
        val audio = selected.filter { it.audio }
        val duration = audio.sumOf { (it.row.segment.endAt - it.row.segment.startAt).coerceAtLeast(0) } + (audio.size - 1).coerceAtLeast(0) * 500L
        ExportEstimate(audio.size, selected.count { it.voice }, selected.count { !it.audio && !it.voice },
            all.size - selected.size, duration, duration * 8, speeds.estimate(duration)) // 64 kbps
    }

    override fun export(request: ExportRequest): Flow<ExportProgress> = exportIn(request, UUID.randomUUID().toString())

    internal fun removeJobFiles(id: String) = synchronized(active) {
        val folder = File(directory, id)
        if (folder !in active) folder.deleteRecursively()
        Unit
    }

    internal fun exportIn(request: ExportRequest, id: String): Flow<ExportProgress> = flow {
        cleanup()
        val folder = synchronized(active) {
            File(directory, id).also { check(it !in active); it.deleteRecursively(); check(it.mkdirs()) { "无法创建导出目录" }; active += it }
        }
        var delivered = false
        var encoder: ExportPcmSink? = null
        val started = System.nanoTime()
        var decodeNanos = 0L
        var encodeNanos = 0L
        var duration = 0L
        var attempted = 0
        val excluded = mutableSetOf<Long>()
        try {
            emit(ExportProgress.Working(0f))
            val selected = sources(request).filterNot { request.skipMedia && it.media }
            check(selected.isNotEmpty()) { "没有可导出的录音段或文字" }
            val base = safeTitle(request.title)
            val audio = File(folder, "$base.m4a")
            val positions = mutableMapOf<Long, Pair<Long, Long>>()
            attempted = selected.count { it.audio }
            val estimatedDuration = selected.filter { it.audio }.sumOf {
                (it.row.segment.endAt - it.row.segment.startAt).coerceAtLeast(0)
            } + (attempted - 1).coerceAtLeast(0) * 500L
            var lastUpdate = 0L
            while (true) {
                positions.clear()
                var samples = 0L
                try {
                    coroutineScope {
                        val chunks = Channel<ExportChunk>(4)
                        val producer = launch(Dispatchers.IO) {
                            try {
                                for ((index, source) in selected.withIndex()) {
                                    val id = source.row.segment.id
                                    if (source.audio && id !in excluded) {
                                        val begin = System.nanoTime()
                                        var blocked = 0L
                                        try {
                                            var count = 0L
                                            decode(File(source.row.segment.path)) { bytes ->
                                                require(bytes.isNotEmpty() && bytes.size % 2 == 0)
                                                // 对自定义解码器也施加块大小限制，发送独立数组。
                                                for (offset in bytes.indices step 8192) {
                                                    val block = bytes.copyOfRange(offset, minOf(offset + 8192, bytes.size))
                                                    val wait = System.nanoTime()
                                                    chunks.send(ExportChunk.Pcm(id, block))
                                                    blocked += System.nanoTime() - wait
                                                }
                                                count += bytes.size
                                            }
                                            check(count > 0)
                                        } catch (e: CancellationException) { throw e }
                                        catch (_: Exception) {
                                            chunks.send(ExportChunk.Bad(id))
                                            return@launch
                                        } finally { decodeNanos += System.nanoTime() - begin - blocked }
                                    }
                                    chunks.send(ExportChunk.Progress((index + 1f) / (selected.size + 1f)))
                                }
                            } finally { chunks.close() }
                        }
                        try {
                            for (chunk in chunks) when (chunk) {
                                is ExportChunk.Pcm -> {
                                    val begin = System.nanoTime()
                                    if (encoder == null) encoder = sink(audio)
                                    if (chunk.id !in positions) {
                                        if (positions.isNotEmpty()) { encoder!!.silence(); samples += 8000 }
                                        positions[chunk.id] = samples * 1000 / 16000 to 0L
                                    }
                                    encoder!!.append(chunk.bytes)
                                    samples += chunk.bytes.size / 2
                                    val start = positions.getValue(chunk.id).first
                                    positions[chunk.id] = start to (samples * 1000 / 16000 - start)
                                    encodeNanos += System.nanoTime() - begin
                                    val at = System.nanoTime()
                                    if (at - lastUpdate >= 500_000_000) {
                                        emit(ExportProgress.Working((samples * 1000.0 / 16000 / estimatedDuration.coerceAtLeast(1)).toFloat().coerceIn(0f, .99f)))
                                        lastUpdate = at
                                    }
                                }
                                is ExportChunk.Bad -> throw UnreadableExportSegment(chunk.id)
                                is ExportChunk.Progress -> {
                                    val at = System.nanoTime()
                                    if (at - lastUpdate >= 500_000_000) {
                                        emit(ExportProgress.Working(chunk.fraction))
                                        lastUpdate = at
                                    }
                                }
                            }
                        } finally { producer.cancel(); chunks.cancel() }
                    }
                    duration = samples * 1000 / 16000
                    break
                } catch (e: UnreadableExportSegment) {
                    excluded += e.segmentId
                    encoder?.close(); encoder = null
                    check(!audio.exists() || audio.delete()) { "无法清理导出半成品" }
                }
            }
            val failed = excluded.size
            check(attempted == 0 || positions.isNotEmpty()) { "所选的 $failed 段录音均无法读取，请检查原声文件" }
            val finishing = System.nanoTime()
            encoder?.finish(); encoder?.close(); encoder = null
            encodeNanos += System.nanoTime() - finishing
            val files = mutableListOf<File>()
            if (positions.isNotEmpty()) files += audio
            val rendered = ExportText.render(base, selected, positions, duration, failed)
            if (request.includeText) files += File(folder, "$base.txt").apply { writeText(rendered.first) }
            if (request.includeSrt) files += File(folder, "$base.srt").apply { writeText(rendered.second) }
            check(files.isNotEmpty()) { "没有可合成的录音，请勾选原话文字" }
            currentCoroutineContext().ensureActive()
            // Contract 的 Working 无消息字段；跳过数量写入 txt，不伪造 Failed 终态。
            val elapsed = (System.nanoTime() - started) / 1_000_000
            emit(ExportProgress.Working(1f))
            emit(ExportProgress.Done(files))
            delivered = true
            if (duration > 0) speeds.record(duration, elapsed)
        } finally {
            record("${now()} 录音导出 总时长=${duration}ms 段数=$attempted 合成=${(System.nanoTime() - started) / 1_000_000}ms 解码=${decodeNanos / 1_000_000}ms 编码=${encodeNanos / 1_000_000}ms 跳过=${excluded.size} 完成=$delivered")
            encoder?.close()
            synchronized(active) {
                if (!delivered) folder.deleteRecursively()
                active -= folder
            }
        }
    }.catch { e ->
        emit(ExportProgress.Failed(when (e) {
            is IllegalStateException -> e.message ?: "导出失败，请重试"
            is java.io.IOException -> "导出文件读写失败，请检查剩余空间"
            else -> "音频导出失败，请检查录音文件后重试"
        }))
    }.flowOn(Dispatchers.IO).buffer(0)

    override fun cleanup() = synchronized(active) {
        directory.listFiles()?.filter { it !in active && it.lastModified() < now() - 86_400_000L }
            ?.forEach { it.deleteRecursively() }
        Unit
    }
    companion object {
        internal fun safeTitle(title: String): String = title.filter { it !in "/\\:*?\"<>|" && !it.isISOControl() }
            .trim().take(60).trimEnd('.', ' ').ifBlank { "涛声录音" }
    }
}

private sealed interface ExportChunk {
    data class Pcm(val id: Long, val bytes: ByteArray) : ExportChunk
    data class Bad(val id: Long) : ExportChunk
    data class Progress(val fraction: Float) : ExportChunk
}
private class UnreadableExportSegment(val segmentId: Long) : Exception()

internal object ExportText {
    private fun clock(at: Long, pattern: String) = Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern(pattern))
    private fun position(ms: Long) = String.format(Locale.ROOT, "%02d:%02d", ms / 60000, ms / 1000 % 60)
    private fun subtitle(ms: Long) = String.format(Locale.ROOT, "%02d:%02d:%02d,%03d", ms / 3600000, ms / 60000 % 60, ms / 1000 % 60, ms % 1000)
    private fun speaker(line: TranscriptLineEntity) = line.speakerName
        ?: if ((line.speakerScore ?: 0f) >= VoiceprintThresholds.MAYBE) "可能是我？"
        else line.speakerTag?.let { if (it.startsWith("uncertain:")) "待确认" else "说话人 ${it.toIntOrNull()?.plus(1) ?: it}" } ?: "未知"
    fun render(title: String, sources: List<ExportSource>, positions: Map<Long, Pair<Long, Long>>, duration: Long, failed: Int): Pair<String, String> {
        val events = sources.flatMap { it.events }.distinctBy { it.id }
        val people = events.flatMap { event -> runCatching {
            val array = JSONArray(event.peopleJson)
            (0 until array.length()).map { array.getString(it) }
        }.getOrDefault(emptyList()) }.distinct()
        val txt = StringBuilder("$title\n日期：${sources.joinToString("、") { clock(it.row.segment.startAt, "yyyy-MM-dd") }.split("、").distinct().joinToString("、")}\n总时长：${position(duration)}\n事件：${events.joinToString("、") { it.title }}\n人名：${people.joinToString("、")}\n")
        if (failed > 0) txt.append("有 $failed 段录音读不出来，已跳过音频，原话保留\n")
        val srt = StringBuilder()
        var number = 0
        for (source in sources) for (line in source.row.lines.sortedWith(compareBy({ it.startAt }, { it.id }))) {
            val pos = positions[source.row.segment.id]?.takeIf { line.voiceInputJson.isNullOrBlank() }
            val start = pos?.let { it.first + (line.startAt - source.row.segment.startAt).coerceIn(0, it.second) }
            val end = pos?.let { it.first + (line.endAt - source.row.segment.startAt).coerceIn(0, it.second) }
            val voice = if (!line.voiceInputJson.isNullOrBlank()) "⌨ 语音输入 · ${VoiceInputMetadata.read(line.voiceInputJson)?.app ?: "未知应用"}（无录音） " else ""
            val text = line.text.replace(Regex("[\\r\\n]+"), " ")
            txt.append("[${start?.let(::position) ?: "—"}] ${clock(line.startAt, "HH:mm:ss")} $voice${speaker(line)}：$text\n")
            if (start != null && end != null && end > start) srt.append("${++number}\n${subtitle(start)} --> ${subtitle(end)}\n${speaker(line)}：$text\n\n")
        }
        return txt.toString() to srt.toString()
    }
}
