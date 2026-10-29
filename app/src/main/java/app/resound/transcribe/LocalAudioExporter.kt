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
import org.json.JSONArray

internal data class ExportSource(val row: SegmentWithLines, val events: List<MemoryEventEntity>) {
    val audio get() = File(row.segment.path).isFile
    val voice get() = row.lines.any { !it.voiceInputJson.isNullOrBlank() } && !audio
    val media get() = events.any { it.scene == "媒体" && it.startAt < row.segment.endAt && it.endAt > row.segment.startAt }
}

/** 仅音频块驻留内存；每段解码成功后才接入编码器，坏段不会污染后续时间轴。 */
internal class LocalAudioExporter(
    private val directory: File,
    private val load: suspend (List<Long>) -> List<ExportSource>,
    private val decode: suspend (File, File) -> Unit,
    private val sink: (File) -> ExportPcmSink = ::ExportAacEncoder,
    private val now: () -> Long = System::currentTimeMillis,
) : AudioExporter {
    constructor(cacheDir: File, dao: TranscribeDao) : this(File(cacheDir, "exports"), { ids ->
        ids.distinct().chunked(500).flatMap { dao.exportSegments(it) }.map { row ->
            val events = dao.exportEvents(row.segment.id, row.segment.startAt, row.segment.endAt)
            ExportSource(row, events)
        }
    }, AsrAudioReader(cacheDir)::decodeForExport)

    private val active = mutableSetOf<File>()
    private suspend fun sources(request: ExportRequest) = load(request.segmentIds.distinct())
        .distinctBy { it.row.segment.id }.sortedWith(compareBy({ it.row.segment.startAt }, { it.row.segment.id }))
    override suspend fun estimate(request: ExportRequest): ExportEstimate = withContext(Dispatchers.IO) {
        val all = sources(request)
        val selected = all.filterNot { request.skipMedia && it.media }
        val audio = selected.filter { it.audio }
        val duration = audio.sumOf { (it.row.segment.endAt - it.row.segment.startAt).coerceAtLeast(0) } + (audio.size - 1).coerceAtLeast(0) * 500L
        ExportEstimate(audio.size, selected.count { it.voice }, selected.count { !it.audio && !it.voice },
            all.size - selected.size, duration, duration * 8) // 64 kbps
    }

    override fun export(request: ExportRequest): Flow<ExportProgress> = flow {
        cleanup()
        val folder = synchronized(active) {
            File(directory, UUID.randomUUID().toString()).also { check(it.mkdirs()) { "无法创建导出目录" }; active += it }
        }
        var delivered = false
        var encoder: ExportPcmSink? = null
        try {
            emit(ExportProgress.Working(0f))
            val selected = sources(request).filterNot { request.skipMedia && it.media }
            check(selected.isNotEmpty()) { "没有可导出的录音段或文字" }
            val base = safeTitle(request.title)
            val audio = File(folder, "$base.m4a")
            val positions = mutableMapOf<Long, Pair<Long, Long>>()
            var duration = 0L
            var samples = 0L
            var failed = 0
            var attempted = 0
            for ((index, source) in selected.withIndex()) {
                currentCoroutineContext().ensureActive()
                if (source.audio) {
                    attempted++
                    val pcm = File(folder, "segment.pcm")
                    try {
                        val decoded = try {
                            decode(File(source.row.segment.path), pcm)
                            check(pcm.length() > 0 && pcm.length() % 2 == 0L)
                            true
                        } catch (e: CancellationException) { throw e }
                        catch (_: Exception) { failed++; false }
                        if (decoded) {
                            if (encoder == null) encoder = sink(audio)
                            if (positions.isNotEmpty()) { encoder.silence(); samples += 8000 }
                            val start = samples * 1000 / 16000
                            val length = pcm.length() / 2
                            positions[source.row.segment.id] = start to (length * 1000 / 16000)
                            encoder.append(pcm)
                            samples += length
                            duration = samples * 1000 / 16000
                        }
                    } finally { pcm.delete() }
                }
                emit(ExportProgress.Working((index + 1f) / (selected.size + 1f)))
            }
            check(attempted == 0 || positions.isNotEmpty()) { "所选的 $failed 段录音均无法读取，请检查原声文件" }
            encoder?.finish(); encoder?.close(); encoder = null
            val files = mutableListOf<File>()
            if (positions.isNotEmpty()) files += audio
            val rendered = ExportText.render(base, selected, positions, duration, failed)
            if (request.includeText) files += File(folder, "$base.txt").apply { writeText(rendered.first) }
            if (request.includeSrt) files += File(folder, "$base.srt").apply { writeText(rendered.second) }
            check(files.isNotEmpty()) { "没有可合成的录音，请勾选原话文字" }
            currentCoroutineContext().ensureActive()
            // Contract 的 Working 无消息字段；跳过数量写入 txt，不伪造 Failed 终态。
            emit(ExportProgress.Working(1f))
            emit(ExportProgress.Done(files))
            delivered = true
        } finally {
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
