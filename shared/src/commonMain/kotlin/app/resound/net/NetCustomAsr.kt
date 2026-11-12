package app.resound.net

import app.resound.digest.HeaderEntry
import app.resound.transcribe.*
import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.http.content.OutgoingContent
import io.ktor.utils.io.*

/** 保留旧 multipart 的顺序、Content-Length、CRLF 和文件名转义；边界是外部随机输入。 */
fun customMultipart(audio: AudioUpload, model: String, prompt: String?, boundary: String): OutgoingContent.WriteChannelContent {
    fun quote(s: String) = s.replace("\n", "%0A").replace("\r", "%0D").replace("\"", "%22")
    fun field(name: String, value: String) = "--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\nContent-Length: ${value.encodeToByteArray().size}\r\n\r\n$value\r\n"
    val mime = when(audio.format) { "ogg" -> "audio/ogg"; "wav" -> "audio/wav"; else -> "audio/mp4" }
    val prefix = (field("model", model) + "--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"${quote(audio.name)}\"\r\nContent-Type: $mime\r\nContent-Length: ${audio.size}\r\n\r\n").encodeToByteArray()
    val suffix = ("\r\n" + field("response_format", "json") + (prompt?.takeIf { it.isNotEmpty() }?.let { field("prompt", it) } ?: "") + "--$boundary--\r\n").encodeToByteArray()
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
    audio: AudioUpload, duration: Long, model: String, prompt: String?, boundary: String, beforeAudio: suspend () -> Unit): AsrResult {
    if (urls.isEmpty() || model.isBlank()) throw AsrException(AsrError.Protocol)
    val body = customMultipart(audio, model, prompt, boundary)
    for (url in urls) {
        beforeAudio()
        val result = client.preparePost(url) {
            wireHeaders(networkHeaders(key, headers))
            setBody(body)
        }.execute { r ->
            val code = r.status.value
            if (code in setOf(404,405)) return@execute null
            if (code in setOf(401,403)) throw AsrException(AsrError.Auth, detail = "鉴权失败，请检查密钥")
            if (code !in 200..299) throw AsrException(if (code >= 500 || code == 429) AsrError.Server else AsrError.Protocol)
            val text = try { customTranscriptionText(r.limitedText(1_000_000, "识别响应不完整或格式异常")) }
                catch(e: AsrException) { throw e }
                catch(_: IllegalStateException) { throw AsrException(AsrError.Protocol) }
            AsrResult(if(text.isBlank()) emptyList() else listOf(AsrSentence(0,duration,text,null)),duration,null)
        }
        if(result != null) return result
    }
    throw AsrException(AsrError.Protocol)
}
