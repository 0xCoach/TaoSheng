package app.resound.transcribe

import app.resound.data.TranscriptLineEntity
import app.resound.voiceprint.VoiceprintThresholds
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import org.json.JSONArray

internal object ExportText {
    private fun clock(at: Long, pattern: String) = Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern(pattern))
    private fun position(ms: Long) = String.format(Locale.ROOT, "%02d:%02d", ms / 60000, ms / 1000 % 60)
    private fun subtitle(ms: Long) = String.format(Locale.ROOT, "%02d:%02d:%02d,%03d", ms / 3600000, ms / 60000 % 60, ms / 1000 % 60, ms % 1000)
    private fun speaker(line: TranscriptLineEntity) = line.speakerName
        ?: if ((line.speakerScore ?: 0f) >= VoiceprintThresholds.MAYBE) "可能是我？"
        else line.speakerTag?.let { if (it.startsWith("uncertain:")) "待确认" else "说话人 ${it.toIntOrNull()?.plus(1) ?: it}" } ?: "未知"

    fun render(title: String, sources: List<ExportSource>, positions: Map<Long, Pair<Long, Long>>,
        duration: Long, failed: Int, request: ExportRequest): Pair<String, String> {
        val markdown = request.textFormat == ExportTextFormat.Markdown
        fun plain(value: String) = value.replace(Regex("[\\r\\n]+"), " ")
        // 内容按普通文字转义，防止原话中的标题、HTML 或复选框改变文档结构。
        fun text(value: String): String = plain(value).let { raw ->
            if (!markdown) raw else buildString {
                for (char in raw) {
                    if (char in "\\`*_{}[]<>#!|.-+") append('\\')
                    append(char)
                }
            }
        }
        val events = sources.flatMap { it.events }.filterNot { it.deleted }.distinctBy { it.id }
            .sortedWith(compareBy({ it.startAt }, { it.id }))
        val people = events.flatMap { event -> runCatching {
            val array = JSONArray(event.peopleJson)
            (0 until array.length()).map { array.getString(it) }
        }.getOrDefault(emptyList()) }.distinct()
        val output = StringBuilder()
        output.append(if (markdown) "# ${text(title)}\n\n" else "${text(title)}\n")
        fun line(value: String) { output.append(value).append(if (markdown) "  \n" else "\n") }
        line("日期：${text(sources.map { clock(it.row.segment.startAt, "yyyy-MM-dd") }.distinct().joinToString("、"))}")
        if (request.includeAudio) line("总时长：${position(duration)}")
        line("人名：${text(people.joinToString("、"))}")
        if (failed > 0) line("有 $failed 段录音读不出来，已跳过音频，原话保留")
        fun section(name: String) { output.append("\n${if (markdown) "## " else ""}$name\n\n") }
        fun eventHeading(at: Long, title: String) {
            output.append("${if (markdown) "### " else ""}${text(clock(at, "yyyy-MM-dd HH:mm:ss"))} ${text(title)}\n\n")
        }
        if (ExportTextPart.Summary in request.textParts) {
            section("摘要")
            if (events.isEmpty()) line("无摘要")
            for (event in events) {
                eventHeading(event.startAt, event.title)
                line(text(event.summary))
                output.append('\n')
            }
        }
        if (ExportTextPart.Todos in request.textParts) {
            section("待办")
            val items = sources.flatMap { it.items }.distinctBy { it.id }.filter {
                !it.deleted && it.mergedInto == null && it.status != "Ignored" && it.category in setOf("待办", "约定")
            }.groupBy { it.eventId }
            var count = 0
            for (event in events) {
                val todos = items[event.id].orEmpty().sortedWith(compareBy({ it.createdAt }, { it.id }))
                if (todos.isEmpty()) continue
                eventHeading(event.startAt, event.title)
                for (todo in todos) {
                    val done = todo.status == "Done"
                    val due = todo.dueAt?.let { clock(it, if ((todo.dueText?.length ?: 0) == 10) "yyyy-MM-dd" else "yyyy-MM-dd HH:mm") }
                        ?: todo.dueText?.takeIf { it.isNotBlank() } ?: "未设置"
                    val prefix = if (markdown) "- [${if (done) "x" else " "}] " else ""
                    line("$prefix${if (done) "已完成" else "未完成"} · ${text(todo.category)}：${text(todo.content)} · 到期：${text(due)}")
                    count++
                }
                output.append('\n')
            }
            if (count == 0) line("无待办与约定")
        }
        if (ExportTextPart.Lines in request.textParts) section("原话")
        val srt = StringBuilder()
        var number = 0
        // 多段也按每句话的真实时间排序（录音段可能重叠）。
        val lines = sources.flatMap { source -> source.row.lines.map { source to it } }
            .sortedWith(compareBy({ it.second.startAt }, { it.second.id }))
        for ((source, transcript) in lines) {
            val pos = positions[source.row.segment.id]?.takeIf { request.includeAudio && transcript.voiceInputJson.isNullOrBlank() }
            val start = pos?.let { it.first + (transcript.startAt - source.row.segment.startAt).coerceIn(0, it.second) }
            val end = pos?.let { it.first + (transcript.endAt - source.row.segment.startAt).coerceIn(0, it.second) }
            val voice = if (!transcript.voiceInputJson.isNullOrBlank()) "⌨ 语音输入 · ${VoiceInputMetadata.read(transcript.voiceInputJson)?.app ?: "未知应用"}（无录音） " else ""
            if (ExportTextPart.Lines in request.textParts) {
                val location = if (request.includeAudio) "[${start?.let(::position) ?: "—"}] " else ""
                line("${text(location)}${clock(transcript.startAt, "HH:mm:ss")} ${text(voice + speaker(transcript) + "：" + transcript.text)}")
            }
            if (request.includeAudio && request.includeSrt && start != null && end != null && end > start) {
                srt.append("${++number}\n${subtitle(start)} --> ${subtitle(end)}\n${speaker(transcript)}：${plain(transcript.text)}\n\n")
            }
        }
        return output.toString() to srt.toString()
    }
}
