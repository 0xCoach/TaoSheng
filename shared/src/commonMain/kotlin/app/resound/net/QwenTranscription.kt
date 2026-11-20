package app.resound.net

import app.resound.transcribe.*
import kotlinx.serialization.json.*

/** 千问时间为相对音频起点的毫秒；只选一层 sentences，绝不叠加重复 output。 */
fun qwenTranscription(body: String, duration: Long, separateSpeakers: Boolean): AsrResult {
    val root = Json.parseToJsonElement(body) as? JsonObject ?: throw AsrException(AsrError.Protocol)
    val output = root["output"] as? JsonObject ?: throw AsrException(AsrError.Protocol)
    val nested = output["output"] as? JsonObject
    val rows = (output["sentences"] as? JsonArray) ?: (nested?.get("sentences") as? JsonArray)
    val limit = duration.coerceAtLeast(0)
    fun rowSentence(row: JsonObject, speakers: MutableMap<String, String>?): AsrSentence? {
        val begin = row.long("begin_time")?.coerceIn(0, limit) ?: return null
        val end = row.long("end_time")?.coerceIn(0, limit) ?: return null
        val text = cleanCustomText(row.string("text"))
        if (end <= begin || text.isBlank()) return null
        val raw = (row["speaker_id"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
        return AsrSentence(begin, end, text, raw?.let { speakers?.getOrPut(it) { speakers.size.toString() } })
    }
    val sentences = if (rows != null) {
        val speakers = if (separateSpeakers) mutableMapOf<String, String>() else null
        rows.mapNotNull { (it as? JsonObject)?.let { row -> rowSentence(row, speakers) } }.sortedBy { it.startMillis }
    } else {
        val sentence = (output["sentence"] as? JsonObject) ?: (nested?.get("sentence") as? JsonObject)
        val words = sentence?.get("words") as? JsonArray
        if (!words.isNullOrEmpty()) {
            val result = mutableListOf<AsrSentence>()
            var text = StringBuilder()
            var begin: Long? = null
            var end = 0L
            fun finish() {
                val cleaned = cleanCustomText(text.toString())
                begin?.let { if (end > it && cleaned.isNotBlank()) result += AsrSentence(it, end, cleaned, null) }
                text = StringBuilder(); begin = null
            }
            for (element in words) {
                val word = element as? JsonObject ?: continue
                val from = word.long("begin_time")?.coerceIn(0, limit) ?: continue
                val to = word.long("end_time")?.coerceIn(0, limit) ?: continue
                val part = word.string("text")
                if (to <= from || part.isBlank()) continue
                if (begin == null) begin = from
                end = to
                val punctuation = word.string("punctuation")
                text.append(part).append(punctuation)
                if ((part + punctuation).any { it in "。！？!?；;…" } || text.length > 60 && (part + punctuation).any { it in "，," }) finish()
            }
            finish()
            result.sortedBy { it.startMillis }
        } else {
            val text = cleanCustomText(output.string("text").ifBlank { nested?.string("text").orEmpty() })
            if (text.isBlank() || limit == 0L) emptyList() else listOf(AsrSentence(0, limit, text, null))
        }
    }
    val usage = (root["usage"] as? JsonObject) ?: (output["usage"] as? JsonObject)
    return AsrResult(sentences, duration, null,
        inputTokens = usage?.long("input_tokens")?.takeIf { it >= 0 },
        outputTokens = usage?.long("output_tokens")?.takeIf { it >= 0 }, customCapability = CustomCapability.Speakers)
}
private fun JsonObject.long(key: String) = (get(key) as? JsonPrimitive)?.longOrNull
private fun JsonObject.string(key: String) = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
