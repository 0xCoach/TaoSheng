package app.resound.recordings

import app.resound.data.CaptureDatabase
import app.resound.data.SegmentWithLines
import java.io.File
import java.io.OutputStream
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.zip.*
import kotlinx.coroutines.*

internal data class RecordingSource(val row: SegmentWithLines, val excluded: Boolean = false, val eventTitle: String? = null) {
    val id get() = row.segment.id
    val file get() = File(row.segment.path)
    val day get() = Instant.ofEpochMilli(row.segment.startAt).atZone(ZoneId.systemDefault()).toLocalDate()
    val duration get() = (row.segment.endAt - row.segment.startAt).coerceAtLeast(0)
    val durationText get() = "${duration / 60000}分${duration / 1000 % 60}秒"
    /** 文件名主体：“2026-10-30 09.15 项目周会”——日期时刻 + 所属事件标题（没整理的用原话首句，没转写写“未转写”） */
    val stem get() = Instant.ofEpochMilli(row.segment.startAt).atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH.mm")) + " " + label
    private val label get() = (eventTitle?.let { safe(it).take(16) }
        ?: row.lines.sortedBy { it.startAt }.firstOrNull { it.text.isNotBlank() }?.text?.let { safe(it).take(12) })
        ?.trim()?.ifBlank { null } ?: "未转写"
    private fun safe(t: String) = t.filter { it !in "/\\:*?\"<>|" && !it.isISOControl() }.replace(Regex("[\\s，。！？、；：,.!?;]+$"), "").replace(Regex("\\s+"), " ")
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
            val events = if (text || exclusions) dao.exportEvents(s.id, s.startAt, s.endAt) else emptyList()
            RecordingSource(row, (exclusions && events.any { it.scene == "媒体" }) || s.status == "SkippedEnv" ||
                (env != null && !env.reportedWrong && (env.verdict == "Media" || (env.skipped && !env.restored))),
                // 一段跨两件事时取开始得早的那件
                events.minByOrNull { it.startAt }?.title)
        }.sortedWith(compareBy({ it.row.segment.startAt }, { it.id }))
    }
}

internal object RecordingZip {
    fun select(all: List<RecordingSource>, range: RecordingRange, options: RecordingPackOptions): List<RecordingSource> {
        require(range.startMillis() <= range.endMillis()) { "开始时间不能晚于结束时间" }
        val start = range.startMillis(); val end = range.endMillis()
        return all.filter { it.row.segment.startAt in start..end && it.file.isFile && !(options.skipMedia && it.excluded) }
            .sortedWith(compareBy({ it.row.segment.startAt }, { it.id }))
    }
    fun estimate(rows: List<RecordingSource>) = RecordingPackEstimate(rows.map { it.day }.distinct().size,
        rows.size, rows.sumOf { it.file.length() }, rows.sumOf { it.duration })
    fun name(range: RecordingRange) = if (range.wholeDays) "涛声录音 ${range.from}" + (if (range.from == range.to) "" else " 至 ${range.to}") + ".zip"
        else "涛声录音 ${range.from} ${clock(range.fromTime)} 至 " + (if (range.from == range.to) "" else "${range.to} ") + clock(range.toTime) + ".zip"
    private fun clock(t: LocalTime) = "%02d.%02d".format(t.hour, t.minute)
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
                    // 同一件事、同一分钟的几段：加序号“… 2”“… 3”
                    val base = "${row.day}/${row.stem}"; var path = "$base.ogg"; var suffix = 2
                    while (!used.add(path)) path = "$base ${suffix++}.ogg"
                    entry(path, row.file)
                    if (withText) {
                        textEntry(path.removeSuffix(".ogg") + ".txt", row.text)
                        table.appendLine("${row.stem.substring(0, 16).replace('.', ':')}\t${row.durationText}\t$path\t${row.text.replace('\n', ' ')}")
                    }
                    progress(i + 1)
                } }
                if (withText) entry("原话.txt", index)
            }
        } finally { index.delete() }
    }
}
