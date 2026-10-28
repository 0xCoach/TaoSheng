package app.resound.transcribe

import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.GZIPInputStream

object DoubaoProtocol {
    data class Packet(val kind: Int, val flags: Int, val sequence: Int?, val code: Long?, val payload: JSONObject) {
        val final: Boolean get() = flags and 2 != 0
    }
    fun frame(kind: Int, flags: Int, payload: ByteArray, json: Boolean = false): ByteArray =
        ByteBuffer.allocate(8 + payload.size).order(ByteOrder.BIG_ENDIAN)
            .put(0x11).put(((kind shl 4) or flags).toByte()).put(if (json) 0x10 else 0).put(0)
            .putInt(payload.size).put(payload).array()
    fun config(format: String, hotwords: List<String> = emptyList(), separateSpeakers: Boolean = true): ByteArray = JSONObject().apply {
        put("user", JSONObject().put("uid", "resound"))
        put("audio", JSONObject().put("format", format).put("codec", if (format == "ogg") "opus" else "raw")
            .put("rate", 16000).put("bits", 16).put("channel", 1))
        put("request", JSONObject().put("model_name", "bigmodel").put("enable_itn", true).put("enable_punc", true)
            .put("enable_speaker_info", separateSpeakers).put("show_utterances", true).put("ssd_version", "200").put("result_type", "full").apply {
                if (hotwords.isNotEmpty()) put("corpus", JSONObject().put("context", JSONObject().put("hotwords",
                    org.json.JSONArray(Hotwords.doubao(hotwords).map { JSONObject().put("word", it) })).toString()))
            })
    }.toString().toByteArray(Charsets.UTF_8)
    fun parse(data: ByteArray): Packet = try {
        require(data.size >= 8)
        val header = (data[0].toInt() and 15) * 4
        require(data[0].toInt() ushr 4 == 1 && header >= 4 && header <= data.size - 4)
        val kind = (data[1].toInt() and 255) ushr 4
        val flags = data[1].toInt() and 15
        require(kind == 9 || kind == 15)
        val buffer = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN).apply { position(header) }
        val sequence = if (flags and 1 != 0) buffer.int else null
        val code = if (kind == 15) buffer.int.toLong() and 0xffffffffL else null
        val size = buffer.int
        require(size >= 0 && size <= 8 * 1024 * 1024 && buffer.remaining() == size)
        var payload = ByteArray(size).also { buffer.get(it) }
        val compression = data[2].toInt() and 15
        require(compression in 0..1)
        if (compression == 1) payload = GZIPInputStream(ByteArrayInputStream(payload)).use { input ->
            val output = ByteArrayOutputStream()
            val block = ByteArray(8192)
            while (true) {
                val sizeRead = input.read(block)
                if (sizeRead < 0) break
                require(output.size() + sizeRead <= 8 * 1024 * 1024)
                output.write(block, 0, sizeRead)
            }
            output.toByteArray()
        }
        val serialization = (data[2].toInt() and 255) ushr 4
        val body = if (serialization == 1) JSONObject(payload.toString(Charsets.UTF_8))
            else if (kind == 15) JSONObject() else throw IllegalArgumentException()
        Packet(kind, flags, sequence, code, body)
    } catch (_: Exception) { throw AsrException(AsrError.Protocol) }

    fun result(body: JSONObject, fallbackDuration: Long, logId: String?): AsrResult {
        val result = body.optJSONObject("result")
        val utterances = result?.optJSONArray("utterances")
        var previousEnd = 0L
        val lines = buildList {
            if (utterances != null) for (i in 0 until utterances.length()) {
                val u = utterances.optJSONObject(i) ?: continue
                val text = u.optString("text", "").trim()
                if (text.isEmpty()) continue
                val start = u.optLong("start_time", previousEnd).coerceIn(0, fallbackDuration)
                val end = u.optLong("end_time", start).coerceIn(start, fallbackDuration)
                val additions = u.optJSONObject("additions")
                val speaker = if (additions?.isNull("speaker_id") == false) additions.optString("speaker_id").takeIf { it.isNotBlank() } else null
                add(AsrSentence(start, end, text, speaker))
                previousEnd = end
            }
            if (isEmpty()) result?.optString("text", "")?.trim()?.takeIf { it.isNotEmpty() }?.let {
                add(AsrSentence(0, fallbackDuration, it, null))
            }
        }
        return AsrResult(lines, body.optJSONObject("audio_info")?.optLong("duration", fallbackDuration) ?: fallbackDuration, logId)
    }
    fun error(code: Long?, http: Int? = null): AsrError = when {
        http == 401 || http == 403 || code in setOf(45000030L, 45000000L, 45000001L, 45000002L) -> AsrError.Auth
        http == 429 || (http != null && http >= 500) || (code != null && code >= 55000000) -> AsrError.Server
        else -> AsrError.Protocol
    }
}
