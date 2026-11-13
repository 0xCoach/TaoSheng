package app.resound.recordings

import app.resound.data.CaptureDatabase
import app.resound.data.SegmentWithLines
import java.io.File
import java.io.OutputStream
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.zip.*
import kotlinx.coroutines.*

internal data class RecordingSource(override val row: SegmentWithLines, override val excluded: Boolean = false, override val eventTitle: String? = null) : RecordingSourceFields {
    val id get() = row.segment.id
    val file get() = File(row.segment.path)
    val day get() = Instant.ofEpochMilli(row.segment.startAt).atZone(ZoneId.systemDefault()).toLocalDate()
    val duration get() = RecordingTextRules.duration(this)
    val durationText get() = RecordingTextRules.durationText(this)
    val stem get() = Instant.ofEpochMilli(row.segment.startAt).atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH.mm")) + " " + RecordingTextRules.label(this)
    val completed get() = RecordingTextRules.completed(this)
    val text get() = RecordingTextRules.text(this)
    val speech get() = RecordingTextRules.speech(this)
}

internal class RecordingSources(database: CaptureDatabase, root: File) {
    private val queries = RecordingSourceQueries(database) {
        val base = root.canonicalPath + File.separator
        val accept: (String) -> Boolean = { path -> File(path).extension.equals("ogg", true) && File(path).canonicalPath.startsWith(base) }
        accept
    }
    suspend fun load() = queries.load().map { RecordingSource(it.row, it.excluded, it.eventTitle) }
    suspend fun metadata(exclusions: Boolean) = queries.metadata(exclusions).map { RecordingSource(it.row, it.excluded, it.eventTitle) }
}

internal object RecordingZip {
    fun select(all: List<RecordingSource>, range: RecordingRange, options: RecordingPackOptions): List<RecordingSource> {
        require(range.startMillis() <= range.endMillis()) { "开始时间不能晚于结束时间" }
        val start = range.startMillis(); val end = range.endMillis()
        return RecordingSelection.select(all, start, end, options.skipMedia) { it.file.isFile }
    }
    fun estimate(rows: List<RecordingSource>) = RecordingSelection.estimate(rows, { it.day.toString() }, { it.file.length() }).let {
        RecordingPackEstimate(it.days, it.count, it.bytes, it.millis)
    }
    fun name(range: RecordingRange) = RecordingSelection.name(range.from.toString(), range.to.toString(), range.wholeDays,
        { clock(range.fromTime) }, { clock(range.toTime) })
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
