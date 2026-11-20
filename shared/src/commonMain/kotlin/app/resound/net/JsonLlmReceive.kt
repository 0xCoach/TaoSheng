package app.resound.net

import app.resound.digest.LlmPreset
import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.*
import kotlinx.io.readByteArray
import kotlinx.coroutines.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** 只传累计字数，不传播或保存思考正文。每次请求从零开始。 */
class LlmReceiveProgress(val update: (reasoningChars: Int, contentChars: Int) -> Unit) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<LlmReceiveProgress>
}

/** 极小配置探针保持原传输与时限；通过上下文保留业务请求的可注入性。 */
class LlmProbeRequest : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<LlmProbeRequest>
}

/** 本机能力缓存，由装配层提供持久化；探针不经过这里。 */
interface JsonStreamSupport {
    fun nonStreaming(preset: LlmPreset): Boolean
    fun rememberNonStreaming(preset: LlmPreset)
}

internal class JsonLlmReceive(private val client: HttpClient, private val bases: List<String>,
    private val support: JsonStreamSupport?, private val networkError: (Throwable) -> String) {
    suspend fun chat(preset: LlmPreset, headers: List<Pair<String, String>>, body: Obj, trace: NetworkTrace?): NetReply {
        val progress = currentCoroutineContext()[LlmReceiveProgress]
        progress?.update(0, 0)
        return try {
            withTimeout(TOTAL_MS) {
                var streaming = support?.nonStreaming(preset) != true
                for ((index, base) in bases.withIndex()) {
                    var retry = true
                    while (retry) {
                        retry = false
                        if (streaming) body.put("stream", true).put("stream_options", Obj().put("include_usage", true))
                        else { body.put("stream", false); body.remove("stream_options") }
                        val result = idle(if (streaming) IDLE_MS else PLAIN_IDLE_MS) { tick ->
                            client.preparePost("$base/chat/completions") {
                                // 与其余请求一致：显式 UTF-8，按字节发，个别服务不写字符集会把中文读乱。
                                wireHeaders(headers); contentType(ContentType.parse("application/json; charset=utf-8")); setBody(body.toString().encodeToByteArray())
                                // 总上限由外层统一覆盖路径探测和回退；socket 由字节级 watchdog 负责。
                                timeout { requestTimeoutMillis = Long.MAX_VALUE; socketTimeoutMillis = Long.MAX_VALUE; connectTimeoutMillis = 15_000 }
                            }.execute { response ->
                                tick()
                                val status = response.status.value
                                trace?.http(status)
                                if (status in setOf(404, 405) && index < bases.lastIndex) return@execute null
                                val sse = response.headers[HttpHeaders.ContentType]?.substringBefore(';')?.trim()?.equals("text/event-stream", true) == true
                                if (status in 200..299 && sse) {
                                    receiveSse(response.bodyAsChannel(), tick, trace, progress)
                                } else {
                                    val raw = receiveText(response.bodyAsChannel(), tick)
                                    // 有的中转把流式内容标成 application/json：按内容认出“data:”分段，仍按流式解析。
                                    if (status in 200..299 && streaming && raw.trimStart().startsWith("data:"))
                                        return@execute receiveSse(ByteReadChannel(raw.encodeToByteArray()), tick, trace, progress)
                                    if (streaming && status in 400..499 && status != 429 && rejectsStream(raw)) {
                                        support?.rememberNonStreaming(preset)
                                        streaming = false; retry = true
                                        return@execute null
                                    }
                                    val json = runCatching { Obj(raw) }.getOrNull()
                                    val usage = usage(json)
                                    if (status !in 200..299) throw NetLlmClient.httpError(status, usage, raw)
                                    val choice = json?.optJSONArray("choices")?.optJSONObject(0)
                                    val reason = choice?.optString("finish_reason")?.takeUnless { it.isBlank() || it == "null" }
                                    trace?.finishReason(reason)
                                    val message = choice?.optJSONObject("message")
                                    val content = message?.takeUnless { it.isNull("content") }?.optString("content").orEmpty()
                                    val thinking = listOf("reasoning_content", "reasoning").sumOf { field ->
                                        message?.takeUnless { it.isNull(field) }?.optString(field)?.length ?: 0
                                    }
                                    progress?.update(thinking, content.length); trace?.content(content)
                                    if (reason == "length") throw truncated(usage)
                                    if (message == null || message.isNull("content")) throw NetLlmException(NetLlmError.Format, usage = usage)
                                    NetReply(content, usage?.promptTokens ?: 0, usage?.completionTokens ?: 0)
                                }
                            }
                        }
                        if (result != null) return@withTimeout result
                    }
                }
                throw NetLlmException(NetLlmError.Format)
            }
        } catch (e: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            throw NetLlmException(NetLlmError.TooSlow, detail = WAIT_TIMEOUT_TEXT)
        } catch (e: CancellationException) { throw e }
          catch (e: NetLlmException) { throw e }
          catch (e: Exception) {
              app.resound.diagnostics.FailureNotes.record("net/JsonLlmReceive.kt:transport", e)
              throw NetLlmException(NetLlmError.Network, detail = networkError(e))
          }
    }

    /** 字节到达即刷新（含不完整行、注释和心跳），不能把 readLine 的结束误当作活跃度。 */
    private suspend fun <T> idle(millis: Long, block: suspend (() -> Unit) -> T): T = coroutineScope {
        val ticks = Channel<Unit>(Channel.CONFLATED)
        val watchdog = launch {
            while (true) {
                if (withTimeoutOrNull(millis) { ticks.receive(); true } == null)
                    throw NetLlmException(NetLlmError.TooSlow, detail = WAIT_TIMEOUT_TEXT)
            }
        }
        try { block { ticks.trySend(Unit) } } finally {
            // receive 的快速路径可能在取消后继续读已就绪的 tick；先等监视器退出再关闭通道。
            withContext(NonCancellable) { watchdog.cancelAndJoin(); ticks.close() }
        }
    }

    private suspend fun receiveText(source: ByteReadChannel, tick: () -> Unit): String {
        val buffer = kotlinx.io.Buffer()
        val bytes = ByteArray(8192)
        while (true) {
            val n = source.readAvailable(bytes)
            if (n < 0) break
            if (n == 0) continue
            tick(); buffer.write(bytes, 0, n)
            if (buffer.size > 2_000_000) throw NetLlmException(NetLlmError.Format)
        }
        return buffer.readByteArray().decodeToString()
    }

    private suspend fun receiveSse(source: ByteReadChannel, tick: () -> Unit, trace: NetworkTrace?, progress: LlmReceiveProgress?): NetReply {
        val text = StringBuilder()
        var reasoningChars = 0
        var tokens: NetReply? = null
        var finish: String? = null
        var done = false
        val data = StringBuilder()
        fun dispatch() {
            val payload = data.toString().trim(); data.clear()
            if (payload.isEmpty()) return
            if (payload == "[DONE]") { done = true; return }
            val json = try { Obj(payload) } catch (_: Exception) { throw NetLlmException(NetLlmError.Format) }
            if (json.has("error")) throw NetLlmException(NetLlmError.Server)
            usage(json)?.let { tokens = it }
            val choices = json.optJSONArray("choices") ?: return
            for (i in 0 until choices.length()) {
                val choice = choices.optJSONObject(i) ?: continue
                if (choice.optInt("index", 0) != 0) continue
                if (!choice.isNull("finish_reason")) choice.optString("finish_reason").takeIf { it.isNotBlank() }?.let {
                    finish = it; trace?.finishReason(it)
                }
                val delta = choice.optJSONObject("delta") ?: continue
                if (!delta.isNull("content")) text.append(delta.optString("content"))
                for (field in listOf("reasoning_content", "reasoning")) {
                    if (!delta.isNull(field)) reasoningChars += delta.optString(field).length
                }
                if (text.length + reasoningChars > 2_000_000) throw NetLlmException(NetLlmError.Format)
                progress?.update(reasoningChars, text.length)
            }
        }
        val line = kotlinx.io.Buffer()
        val bytes = ByteArray(8192)
        var previousCr = false
        fun endLine() {
            val value = line.readByteArray().decodeToString()
            if (value.isEmpty()) dispatch()
            else if (value.startsWith("data:")) {
                data.append(value.substring(5).removePrefix(" ")).append('\n')
                if (data.length > 256_000) throw NetLlmException(NetLlmError.Format)
            }
        }
        try {
            while (!done) {
                val n = source.readAvailable(bytes)
                if (n < 0) { if (line.size > 0) endLine(); dispatch(); break }
                if (n == 0) continue
                tick()
                for (i in 0 until n) {
                    val b = bytes[i]
                    if (b == 13.toByte()) { endLine(); previousCr = true }
                    else {
                        if (b == 10.toByte()) { if (!previousCr) endLine() }
                        else { line.writeByte(b); if (line.size > 256_000) throw NetLlmException(NetLlmError.Format) }
                        previousCr = false
                    }
                    if (done) break
                }
            }
            if (finish == "length") throw truncated(tokens)
            if (finish == null) throw NetLlmException(NetLlmError.Network, detail = "模型响应中断，稍后重试", usage = tokens)
            // 各家结束标记不统一（stop/eos/end_turn…），除截断外交给后面的 JSON 校验判断内容是否完整。
            return NetReply(text.toString(), tokens?.promptTokens ?: 0, tokens?.completionTokens ?: 0)
        } finally { trace?.content(text.toString()) }
    }
    private fun usage(json: Obj?) = json?.optJSONObject("usage")?.let {
        NetReply("", it.optLong("prompt_tokens").coerceAtLeast(0), it.optLong("completion_tokens").coerceAtLeast(0))
    }
    private fun truncated(usage: NetReply?) = NetLlmException(NetLlmError.Truncated,
        detail = "回答没写完就被截断了，正在尝试缩小每批内容", usage = usage, truncated = true)
    companion object { const val IDLE_MS = 90_000L; const val PLAIN_IDLE_MS = 300_000L; const val TOTAL_MS = 600_000L }
}

/**
 * 服务明确拒绝 stream 参数才记成“不支持流式”。中转常见的 “upstream error / upstream_error” 也含 stream，
 * 偶发一次就永久关掉流式会让这个模型再也看不到接收进度。
 */
internal fun rejectsStream(raw: String): Boolean {
    val text = raw.replace(Regex("(?i)up_?stream"), "")
    return Regex("(?i)(\\bstream\\b|\"stream\"|'stream'|stream_options)").containsMatchIn(text) &&
        Regex("(?i)unsupported|not support|invalid|unknown|unrecognized|not allowed|not permitted|extra|不支持|无效|未知").containsMatchIn(text)
}

