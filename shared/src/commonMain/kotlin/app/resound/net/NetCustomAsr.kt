package app.resound.net

import app.resound.diagnostics.FailureNotes

import app.resound.digest.HeaderEntry
import app.resound.transcribe.*
import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.http.content.OutgoingContent
import io.ktor.utils.io.*

/** 保留旧 multipart 的顺序、Content-Length、CRLF 和文件名转义；边界是外部随机输入。 */
fun customMultipart(audio: AudioUpload, model: String, prompt: String?, boundary: String, responseFormat: String = "json"): OutgoingContent.WriteChannelContent {
    fun quote(s: String) = s.replace("\n", "%0A").replace("\r", "%0D").replace("\"", "%22")
    fun field(name: String, value: String) = "--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\nContent-Length: ${value.encodeToByteArray().size}\r\n\r\n$value\r\n"
    val mime = when(audio.format) { "ogg" -> "audio/ogg"; "wav" -> "audio/wav"; else -> "audio/mp4" }
    val prefix = (field("model", model) + "--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"${quote(audio
        .name)}\"\r\nContent-Type: $mime\r\nContent-Length: ${audio.size}\r\n\r\n").encodeToByteArray()
    val suffix = ("\r\n" + field("response_format", responseFormat) + (if (responseFormat == "diarized_json") field("chunking_strategy", "auto") else "") + (prompt?.takeUnless { responseFormat == "diarized_json" }?.takeIf { it.isNotEmpty() }?.let { field("prompt", it) } ?: "") + "--$boundary--\r\n").encodeToByteArray()
    return object : OutgoingContent.WriteChannelContent() {
        override val contentType = ContentType.parse("multipart/form-data; boundary=$boundary")
        override val contentLength = prefix.size.toLong() + audio.size + suffix.size
        override suspend fun writeTo(channel: ByteWriteChannel) {
            channel.writeFully(prefix)
            audio.read { channel.writeFully(it) }
            channel.writeFully(suffix)
        }
    }
}
suspend fun customRecognize(client: HttpClient, urls: List<String>, key: String, headers: List<HeaderEntry>,
    audio: AudioUpload, duration: Long, model: String, prompt: String?, boundary: String, beforeAudio: suspend () -> Unit,
    separateSpeakers: Boolean = true, formats: CustomAsrFormats? = null,
    speakerFormat: CustomSpeakerFormat = CustomSpeakerFormat.Auto): AsrResult {
    if (urls.isEmpty() || model.isBlank()) throw AsrException(AsrError.Protocol)
    for (url in urls) {
        var format = if (speakerFormat == CustomSpeakerFormat.Diarized) "diarized_json" else formats?.format(url) ?: "verbose_json"
        var fallback = false
        while (true) {
            beforeAudio()
            var retry = false
            val result = client.preparePost(url) {
                wireHeaders(networkHeaders(key, headers))
                setBody(customMultipart(audio, model, prompt, boundary, format))
            }.execute { r ->
                val code = r.status.value
                if (code in setOf(401,403)) throw AsrException(AsrError.Auth, detail = "鉴权失败，请检查密钥")
                if (format == "diarized_json" && code in 400..499 && code != 429)
                    throw AsrException(AsrError.Protocol, detail = "这个模型不支持分说话人，请把说话人改成自动")
                if (format == "verbose_json" && code in 400..499 && code != 429) {
                    val errorBody = r.limitedText(1_000_000, "识别响应不完整或格式异常")
                    if (CustomAsrFormats.shouldFallback(code, errorBody)) {
                        retry = true
                        return@execute null
                    }
                }
                if (code in setOf(404,405)) return@execute null
                if (code !in 200..299) throw AsrException(if (code >= 500 || code == 429) AsrError.Server else AsrError.Protocol)
                val sentences = try { customTranscription(r.limitedText(1_000_000, "识别响应不完整或格式异常"), duration, separateSpeakers) }
                    catch(e: AsrException) { throw e }
                    catch(caught: IllegalArgumentException) {
                        FailureNotes.record("net/NetCustomAsr.kt:parse", caught)
                        throw AsrException(AsrError.Protocol)
                    }
                    catch(caught: IllegalStateException) {
                        FailureNotes.record("net/NetCustomAsr.kt:parse", caught)
                        throw AsrException(AsrError.Protocol)
                    }
                AsrResult(sentences, duration, null, customCapability = when (format) {
                    "diarized_json" -> CustomCapability.Speakers
                    "verbose_json" -> CustomCapability.TimedText
                    else -> CustomCapability.TextOnly
                })
            }
            if (retry) { format = "json"; fallback = true; continue }
            if (result != null) {
                if (fallback) formats?.remember(url)
                return result
            }
            break
        }
    }
    throw AsrException(AsrError.Protocol)
}
