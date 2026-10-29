package app.resound.recordings

import app.resound.data.CaptureDatabase
import app.resound.data.SegmentWithLines
import java.io.File
import java.io.OutputStream
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.zip.*
import kotlinx.coroutines.*

internal data class RecordingSource(val row: SegmentWithLines, val excluded: Boolean = false) {
    val id get() = row.segment.id
    val file get() = File(row.segment.path)
    val day get() = Instant.ofEpochMilli(row.segment.startAt).atZone(ZoneId.systemDefault()).toLocalDate()
    val duration get() = (row.segment.endAt - row.segment.startAt).coerceAtLeast(0)
    val durationText get() = "${duration / 60000}分${duration / 1000 % 60}秒"
    val stem get() = Instant.ofEpochMilli(row.segment.startAt).atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("HH-mm-ss")) + " $durationText"
    val completed get() = row.segment.status in setOf("Done", "Empty")
    val text get() = row.lines.filter { it.text.isNotBlank() }.sortedWith(compareBy({ it.startAt }, { it.id }))
        .joinToString("\n") { "${it.speakerName ?: it.speakerTag ?: "未知说话人"}：${it.text}" }.ifBlank { "（未转写）" }
    val speech get() = completed && !excluded && row.lines.any { it.text.isNotBlank() }
}

internal class RecordingSources(private val database: CaptureDatabase, private val root: File) {
    suspend fun load(): List<RecordingSource> = loadRows(true, true)
    suspend fun metadata(exclusions: Boolean): List<RecordingSource> = loadRows(false, exclusions)
    private suspend fun loadRows(text: Boolean, exclusions: Boolean): List<RecordingSource> {
        val dao = database.transcribe()
        val base = root.canonicalPath + File.separator
        val segments = dao.allAudio().filter { File(it.path).extension.equals("ogg", true) &&
            File(it.path).canonicalPath.startsWith(base) }
        val rows = if (text) segments.map { it.id }.chunked(400).flatMap { dao.exportSegments(it) }
            else segments.map { SegmentWithLines(it, emptyList()) }
        return rows.map { row ->
            val s = row.segment
            val env = if (exclusions) database.envSound().get(s.id) else null
            val media = exclusions && dao.exportEvents(s.id, s.startAt, s.endAt).any { it.scene == "媒体" }
            RecordingSource(row, media || s.status == "SkippedEnv" ||
                (env != null && !env.reportedWrong && (env.verdict == "Media" || (env.skipped && !env.restored))))
        }.sortedWith(compareBy({ it.row.segment.startAt }, { it.id }))
    }
}

internal object RecordingZip {
    fun select(all: List<RecordingSource>, range: RecordingRange, options: RecordingPackOptions): List<RecordingSource> {
        require(range.from <= range.to) { "开始日期不能晚于结束日期" }
        return all.filter { it.day >= range.from && it.day <= range.to && it.file.isFile && !(options.skipMedia && it.excluded) }
            .sortedWith(compareBy({ it.row.segment.startAt }, { it.id }))
    }
    fun estimate(rows: List<RecordingSource>) = RecordingPackEstimate(rows.map { it.day }.distinct().size,
        rows.size, rows.sumOf { it.file.length() }, rows.sumOf { it.duration })
    fun name(range: RecordingRange) = "涛声录音 ${range.from}" + (if (range.from == range.to) "" else " 至 ${range.to}") + ".zip"
    suspend fun write(output: OutputStream, rows: List<RecordingSource>, withText: Boolean, progress: suspend (Int) -> Unit) {
        val context = currentCoroutineContext()
        val used = mutableSetOf<String>()
        // 总表用临时文件，避免将整个包/全部原话攒进内存。
        val index = File.createTempFile("recording-index-", ".txt")
        try {
            ZipOutputStream(output).use { zip ->
                suspend fun entry(name: String, file: File) {
                    val crc = CRC32(); var size = 0L
                    file.inputStream().use { input -> val buffer = ByteArray(64 * 1024)
                        while (true) { context.ensureActive(); val n = input.read(buffer); if (n < 0) break; crc.update(buffer, 0, n); size += n }
                    }
                    zip.putNextEntry(ZipEntry(name).apply { method = ZipEntry.STORED; this.size = size; compressedSize = size; this.crc = crc.value })
                    file.inputStream().use { input -> val buffer = ByteArray(64 * 1024)
                        while (true) { context.ensureActive(); val n = input.read(buffer); if (n < 0) break; zip.write(buffer, 0, n) }
                    }; zip.closeEntry()
                }
                fun textEntry(name: String, text: String) {
                    val bytes = text.toByteArray(Charsets.UTF_8)
                    zip.putNextEntry(ZipEntry(name).apply { method = ZipEntry.STORED; size = bytes.size.toLong(); compressedSize = size; crc = CRC32().apply { update(bytes) }.value })
                    zip.write(bytes); zip.closeEntry()
                }
                index.bufferedWriter().use { table -> rows.forEachIndexed { i, row ->
                    context.ensureActive()
                    val base = "${row.day}/${row.stem}"; var path = "$base.ogg"; var suffix = 2
                    while (!used.add(path)) path = "$base（${suffix++}）.ogg"
                    entry(path, row.file)
                    if (withText) {
                        textEntry(path.removeSuffix(".ogg") + ".txt", row.text)
                        table.appendLine("${row.day} ${row.stem.substringBefore(' ').replace('-', ':')}\t${row.durationText}\t$path\t${row.text.replace('\n', ' ')}")
                    }
                    progress(i + 1)
                } }
                if (withText) entry("原话.txt", index)
            }
        } finally { index.delete() }
    }
}
