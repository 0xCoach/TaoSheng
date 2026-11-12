package app.resound.net

import app.resound.transcribe.*

object NetDoubaoProtocol {
    internal data class Packet(val kind: Int, val flags: Int, val sequence: Int?, val code: Long?, val payload: Obj) {
        val final: Boolean get() = flags and 2 != 0
    }
    fun frame(kind: Int, flags: Int, payload: ByteArray, json: Boolean = false): ByteArray =
        byteArrayOf(0x11, ((kind shl 4) or flags).toByte(), if (json) 0x10 else 0, 0) + intBytes(payload.size) + payload
    private fun intBytes(value: Int) = ByteArray(4) { (value ushr (24 - it * 8)).toByte() }
    fun config(format: String, hotwords: List<String> = emptyList(), separateSpeakers: Boolean = true, incrementalResults: Boolean = true): ByteArray = Obj().apply {
        put("user", Obj().put("uid", "resound"))
        put("audio", Obj().put("format", format).put("codec", if (format == "ogg") "opus" else "raw")
            .put("rate", 16000).put("bits", 16).put("channel", 1))
        put("request", Obj().put("model_name", "bigmodel").put("enable_itn", true).put("enable_punc", true)
            .put("enable_speaker_info", separateSpeakers).put("show_utterances", true).put("ssd_version", "200").put("result_type", if (incrementalResults) "single" else "full").apply {
                if (hotwords.isNotEmpty()) put("corpus", Obj().put("context", Obj().put("hotwords",
                    Arr(hotwords.map { Obj().put("word", it) })).toString()))
            })
    }.toString().encodeToByteArray()
    internal fun parse(data: ByteArray, gunzip: (ByteArray, Int) -> ByteArray): Packet = try {
        require(data.size >= 8)
        val header = (data[0].toInt() and 15) * 4
        require(data[0].toInt() ushr 4 == 1 && header >= 4 && header <= data.size - 4)
        val kind = (data[1].toInt() and 255) ushr 4
        val flags = data[1].toInt() and 15
        require(kind == 9 || kind == 15)
        var offset = header
        fun int(): Int {
            require(offset + 4 <= data.size)
            var n = 0
            repeat(4) { n = (n shl 8) or (data[offset++].toInt() and 255) }
            return n
        }
        val sequence = if (flags and 1 != 0) int() else null
        val code = if (kind == 15) int().toLong() and 0xffffffffL else null
        val size = int()
        require(size >= 0 && size <= 8 * 1024 * 1024 && data.size - offset == size)
        var payload = data.copyOfRange(offset, data.size)
        val compression = data[2].toInt() and 15
        require(compression in 0..1)
        if (compression == 1) payload = gunzip(payload, 8 * 1024 * 1024).also { require(it.size <= 8 * 1024 * 1024) }
        val serialization = (data[2].toInt() and 255) ushr 4
        val body = if (serialization == 1) Obj(payload.decodeToString())
            else if (kind == 15) Obj() else throw IllegalArgumentException()
        Packet(kind, flags, sequence, code, body)
    } catch (_: Exception) { throw AsrException(AsrError.Protocol) }

    internal fun result(body: Obj, fallbackDuration: Long, logId: String?, separateSpeakers: Boolean = true): AsrResult {
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
                add(AsrSentence(start, end, text, speaker.takeIf { separateSpeakers }))
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
