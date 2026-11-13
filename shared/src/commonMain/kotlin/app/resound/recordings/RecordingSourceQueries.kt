package app.resound.recordings

import app.resound.data.CaptureDatabase
import app.resound.data.SegmentWithLines

interface RecordingSourceFields {
    val row: SegmentWithLines
    val excluded: Boolean
    val eventTitle: String?
}
data class RecordingSourceData(override val row: SegmentWithLines, override val excluded: Boolean, override val eventTitle: String?) : RecordingSourceFields

/** 归档的纯数据展示规则，日期/时区格式仍由平台按旧方式提供。 */
object RecordingTextRules {
    fun duration(source: RecordingSourceFields) = (source.row.segment.endAt - source.row.segment.startAt).coerceAtLeast(0)
    fun durationText(source: RecordingSourceFields) = duration(source).let { "${it / 60000}分${it / 1000 % 60}秒" }
    fun label(source: RecordingSourceFields) = with(source) { (eventTitle?.let { safe(it).take(16) }
        ?: row.lines.sortedBy { it.startAt }.firstOrNull { it.text.isNotBlank() }?.text?.let { safe(it).take(12) })
        ?.trim()?.ifBlank { null } ?: "未转写" }
    private fun safe(t: String) = t.filter { it !in "/\\:*?\"<>|" && !it.isISOControl() }.replace(Regex("[\\s，。！？、；：,.!?;]+$"), "").replace(Regex("\\s+"), " ")
    fun completed(source: RecordingSourceFields) = source.row.segment.status in setOf("Done", "Empty")
    fun text(source: RecordingSourceFields) = with(source) { row.lines.filter { it.text.isNotBlank() }.sortedWith(compareBy({ it.startAt }, { it.id }))
        .joinToString("\n") { "${it.speakerName ?: it.speakerTag ?: "未知说话人"}：${it.text}" }.ifBlank { "（未转写）" } }
    fun speech(source: RecordingSourceFields) = completed(source) && !source.excluded && source.row.lines.any { it.text.isNotBlank() }
}

class RecordingSourceQueries(private val database: CaptureDatabase, private val acceptPaths: () -> (String) -> Boolean) {
    suspend fun load(): List<RecordingSourceData> = loadRows(true, true)
    suspend fun metadata(exclusions: Boolean): List<RecordingSourceData> = loadRows(false, exclusions)
    private suspend fun loadRows(text: Boolean, exclusions: Boolean): List<RecordingSourceData> {
        val dao = database.transcribe()
        val accept = acceptPaths()
        val segments = dao.allAudio().filter { accept(it.path) }
        val rows = if (text) segments.map { it.id }.chunked(400).flatMap { dao.exportSegments(it) }
            else segments.map { SegmentWithLines(it, emptyList()) }
        return rows.map { row ->
            val s = row.segment
            val env = if (exclusions) database.envSound().get(s.id) else null
            val events = if (text || exclusions) dao.exportEvents(s.id, s.startAt, s.endAt) else emptyList()
            RecordingSourceData(row, (exclusions && events.any { it.scene == "媒体" }) || s.status == "SkippedEnv" ||
                (env != null && !env.reportedWrong && (env.verdict == "Media" || (env.skipped && !env.restored))),
                // 一段跨两件事时取开始得早的那件
                events.minByOrNull { it.startAt }?.title)
        }.sortedWith(compareBy({ it.row.segment.startAt }, { it.row.segment.id }))
    }
}

object RecordingSelection {
    fun <T : RecordingSourceFields> select(all: List<T>, start: Long, end: Long, skipMedia: Boolean, isFile: (T) -> Boolean): List<T> =
        all.filter { it.row.segment.startAt in start..end && isFile(it) && !(skipMedia && it.excluded) }
            .sortedWith(compareBy({ it.row.segment.startAt }, { it.row.segment.id }))
    fun <T : RecordingSourceFields> estimate(rows: List<T>, day: (T) -> String, size: (T) -> Long) = RecordingEstimateData(
        rows.map(day).distinct().size, rows.size, rows.sumOf(size), rows.sumOf { RecordingTextRules.duration(it) })
    fun name(from: String, to: String, wholeDays: Boolean, fromTime: () -> String, toTime: () -> String) =
        if (wholeDays) "涛声录音 $from" + (if (from == to) "" else " 至 $to") + ".zip"
        else "涛声录音 $from ${fromTime()} 至 " + (if (from == to) "" else "$to ") + toTime() + ".zip"
}
data class RecordingEstimateData(val days: Int, val count: Int, val bytes: Long, val millis: Long)
