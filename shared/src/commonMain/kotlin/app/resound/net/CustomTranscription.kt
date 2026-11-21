package app.resound.net

import app.resound.transcribe.AsrSentence
import kotlinx.serialization.json.*
import kotlin.math.roundToLong

/** 服务时间单位为秒；输出相对录音起点的毫秒，标签按有效段在原响应中的首次出现编号。 */
fun customTranscription(body: String, duration: Long, separateSpeakers: Boolean): List<AsrSentence> {
    val root = Json.parseToJsonElement(body) as? JsonObject ?: error("响应不是对象")
    val speakers = mutableMapOf<String, String>()
    val sentences = (root["segments"] as? JsonArray).orEmpty().mapNotNull { element ->
        val row = element as? JsonObject ?: return@mapNotNull null
        fun seconds(key: String) = (row[key] as? JsonPrimitive)?.doubleOrNull?.takeIf { it.isFinite() && it >= 0 }
        val startSeconds = seconds("start") ?: return@mapNotNull null
        val endSeconds = seconds("end") ?: return@mapNotNull null
        if (endSeconds <= startSeconds) return@mapNotNull null
        val start = (startSeconds.coerceAtMost(duration.coerceAtLeast(0) / 1000.0) * 1000).roundToLong()
        val end = (endSeconds.coerceAtMost(duration.coerceAtLeast(0) / 1000.0) * 1000).roundToLong()
        val text = (row["text"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()
            ?.replace(speakerPrefix, "")?.trim().orEmpty()
        if (end <= start || text.isEmpty()) return@mapNotNull null
        fun speaker(key: String) = (row[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() && it != "null" }
        val raw = if (separateSpeakers) speaker("speaker") ?: speaker("speaker_id") else null
        val tag = raw?.let { speakers.getOrPut(it) { speakers.size.toString() } }
        AsrSentence(start, end, cleanCustomText(text), tag)
    }.sortedBy { it.startMillis }
    if (sentences.isNotEmpty()) return sentences
    val text = cleanCustomText(customTranscriptionText(body))
    return if (text.isBlank()) emptyList() else listOf(AsrSentence(0, duration, text, null))
}
private val speakerPrefix = Regex("^(?:\\[\\d+:\\d{2}\\]\\s*)?说话人\\d+\\s*[:：]\\s*")
