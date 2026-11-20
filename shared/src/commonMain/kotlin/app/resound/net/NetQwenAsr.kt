package app.resound.net

import app.resound.digest.HeaderEntry
import app.resound.transcribe.*
import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.json.*
import kotlinx.coroutines.CancellationException
import kotlin.io.encoding.Base64

suspend fun qwenRequest(audio: AudioUpload, model: String, separateSpeakers: Boolean, hotwords: List<String>): String {
    val mime = when (audio.format) {
        "ogg" -> "audio/ogg"
        "m4a" -> "audio/mp4"
        "wav" -> "audio/wav"
        else -> throw AsrException(AsrError.Protocol, detail = "千问接口不支持这个录音格式")
    }
    // 以分块来源读完整段；块长不保证是 3 的倍数，因此合并后仅编码一次。
    if (audio.size !in 0..Int.MAX_VALUE.toLong()) throw AsrException(AsrError.Audio)
    val bytes = ByteArray(audio.size.toInt())
    var offset = 0
    audio.read { chunk ->
        if (chunk.size > bytes.size - offset) throw AsrException(AsrError.Audio)
        chunk.copyInto(bytes, offset); offset += chunk.size
    }
    if (offset != bytes.size) throw AsrException(AsrError.Audio)
    val vocabulary = hotwords.asSequence().map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(500).toList()
    return buildJsonObject {
        put("model", model)
        putJsonObject("input") {
            putJsonArray("messages") { addJsonObject {
                put("role", "user")
                putJsonArray("content") { addJsonObject {
                    put("type", "input_audio")
                    putJsonObject("input_audio") { put("data", "data:$mime;base64,${Base64.encode(bytes)}") }
                } }
            } }
        }
        putJsonObject("parameters") {
            put("format", audio.format)
            put("speaker_diarization_enabled", separateSpeakers)
            if (vocabulary.isNotEmpty()) putJsonObject("vocabulary") { vocabulary.forEach { put(it, 4) } }
        }
    }.toString()
}

suspend fun qwenRecognize(client: HttpClient, url: String, key: String, headers: List<HeaderEntry>,
    audio: AudioUpload, duration: Long, model: String, hotwords: List<String>, beforeAudio: suspend () -> Unit,
    separateSpeakers: Boolean = true): AsrResult {
    if (duration > 300_500) throw AsrException(AsrError.Protocol, detail = "这段超过 5 分钟，千问接口转不了")
    if (url.isBlank() || model.isBlank()) throw AsrException(AsrError.Protocol)
    val body = qwenRequest(audio, model, separateSpeakers, hotwords)
    beforeAudio()
    return client.preparePost(url) {
        wireHeaders(networkHeaders(key, headers))
        contentType(ContentType.Application.Json)
        setBody(body)
    }.execute { response ->
        val status = response.status.value
        if (status == 401 || status == 403) throw AsrException(AsrError.Auth, detail = "鉴权失败，请检查密钥")
        if (status == 429 || status >= 500) throw AsrException(AsrError.Server)
        val text = try { response.limitedText(4_000_000, AsrError.Protocol.human) }
        catch (e: CancellationException) { throw e }
        catch (_: IllegalStateException) { throw AsrException(AsrError.Protocol) }
        val root = try { Json.parseToJsonElement(text) as? JsonObject } catch (_: IllegalArgumentException) { null }
        val code = (root?.get("code") as? JsonPrimitive)?.contentOrNull.orEmpty()
        when {
            code == "InvalidApiKey" -> throw AsrException(AsrError.Auth, detail = "鉴权失败，请检查密钥")
            code.startsWith("Throttling") -> throw AsrException(AsrError.Server)
            code == "DataInspectionFailed" -> throw AsrException(AsrError.Protocol, detail = "内容没通过千问审核，这段跳过")
            status !in 200..299 || code.isNotEmpty() -> {
                // 只保留短错误编号；服务文案和响应正文可能含请求内容或凭据，不进日志。
                val safeCode = code.takeIf { it.length <= 80 && it.all { c -> c.isLetterOrDigit() || c in "._-" } &&
                    (key.isBlank() || !it.contains(key)) }.orEmpty()
                throw AsrException(AsrError.Protocol, detail = "千问接口返回错误" + if (safeCode.isEmpty()) "" else "（$safeCode）")
            }
        }
        try { qwenTranscription(text, duration, separateSpeakers) }
        catch (e: AsrException) { throw e }
        catch (_: IllegalArgumentException) { throw AsrException(AsrError.Protocol) }
        catch (_: IllegalStateException) { throw AsrException(AsrError.Protocol) }
    }
}
