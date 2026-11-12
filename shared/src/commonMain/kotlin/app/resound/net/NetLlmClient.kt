package app.resound.net

import app.resound.digest.LlmPreset
import app.resound.digest.HeaderEntry
import io.ktor.client.HttpClient
import io.ktor.client.statement.*
import io.ktor.utils.io.*
import kotlinx.coroutines.*
import kotlinx.coroutines.CancellationException
import kotlinx.io.IOException

interface NetworkTrace {
    fun http(status: Int)
    fun content(text: String)
    fun finishReason(reason: String?)
}
enum class NetLlmError { Auth, Quota, RateLimit, Network, Server, Format }
data class NetReply(val content: String, val promptTokens: Long, val completionTokens: Long)
data class NetToolCall(val id: String, val name: String, val arguments: String, val extraContent: String?)
data class NetStreamReply(val content: String, val calls: List<NetToolCall>, val reasoningContent: String?)
class NetLlmException(val type: NetLlmError, val status: Int = 0, val detail: String? = null,
    val usage: NetReply? = null, val truncated: Boolean = false) : Exception(detail) {
    companion object { const val TRUNCATED = "回答没写完就被截断了：模型思考太久，可在设置里关掉深度思考或换个模型" }
}
/** client 和已校验的候选地址由平台装配；不会读取设置、时钟、密钥或创建平台实现。 */
class NetLlmClient(private val client: HttpClient, private val bases: List<String>,
    private val callTimeout: Long, private val networkError: (Throwable) -> String) {
    suspend fun chat(preset: LlmPreset, key: String, headers: List<HeaderEntry>, system: String, user: String,
        thinking: Boolean, trace: NetworkTrace?): NetReply = deadline {
        val body = Obj().put("model", preset.model).put("messages", Arr()
            .put(Obj().put("role", "system").put("content", system))
            .put(Obj().put("role", "user").put("content", user)))
            .put("response_format", Obj().put("type", "json_object"))
            .put("temperature", 0.2).put("max_tokens", MAX_JSON_OUTPUT_TOKENS)
        applyNetOptions(preset, body, thinking)
        val selectedHeaders = networkHeaders(key, headers)
        try {
            for ((index, base) in bases.withIndex()) {
                val result = client.consume("$base/chat/completions", selectedHeaders, body.toString().encodeToByteArray()) { response ->
                    if (response.status.value in setOf(404, 405) && index < bases.lastIndex) return@consume null
                    trace?.http(response.status.value)
                    val raw = response.bodyAsText(); trace?.content(raw)
                    val json = runCatching { Obj(raw) }.getOrNull()
                    val usage = json?.optJSONObject("usage")?.let { NetReply("", it.optLong("prompt_tokens"), it.optLong("completion_tokens")) }
                    if (response.status.value !in 200..299) throw httpError(response.status.value, usage, raw)
                    trace?.finishReason(json?.optJSONArray("choices")?.optJSONObject(0)?.optString("finish_reason")?.takeUnless { it == "null" })
                    val content = json?.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content")
                        ?: throw NetLlmException(NetLlmError.Format, usage = usage)
                    trace?.content(content)
                    NetReply(content, usage?.promptTokens ?: 0, usage?.completionTokens ?: 0)
                }
                if (result != null) return@deadline result
            }
            throw NetLlmException(NetLlmError.Format)
        } catch (e: CancellationException) { throw e }
        catch (e: NetLlmException) { throw e }
        catch (e: Exception) { throw NetLlmException(NetLlmError.Network, detail = networkError(e)) }
    }
    suspend fun stream(preset: LlmPreset, key: String, headers: List<HeaderEntry>, messages: String, tools: String,
        onText: (String) -> Unit, onUsage: (Long, Long) -> Unit, allowTools: Boolean, trace: NetworkTrace?): NetStreamReply = deadline {
        val toolArray = Arr(tools)
        val body = Obj().put("model", preset.model).put("messages", Arr(messages))
            .put("stream", true).put("stream_options", Obj().put("include_usage", true))
            .put("temperature", 0.2).put("max_tokens", 4096)
        if (toolArray.length() > 0) body.put("tools", toolArray).put("tool_choice", if (allowTools) "auto" else "none")
        applyNetOptions(preset, body)
        val selectedHeaders = networkHeaders(key, headers)
        try {
            for ((index, base) in bases.withIndex()) {
                val result = client.consume("$base/chat/completions", selectedHeaders, body.toString().encodeToByteArray()) { response ->
                    val status = response.status.value
                    if (status in setOf(404, 405) && index < bases.lastIndex) return@consume null
                    trace?.http(status)
                    if (status !in 200..299) {
                        runCatching { Obj(response.bodyAsText()).optJSONObject("usage") }.getOrNull()?.let { onUsage(it.optLong("prompt_tokens"), it.optLong("completion_tokens")) }
                        throw httpError(status)
                    }
                    val context = currentCoroutineContext()
                    val parser = NetSseParser({ context.ensureActive(); onText(it) }, onUsage, trace)
                    val source = response.bodyAsChannel()
                    try {
                        while (!parser.done) {
                            context.ensureActive()
                            val line = source.readUTF8Line(256_000) ?: throw NetLlmException(NetLlmError.Network, detail = networkError(UnexpectedEnd()))
                            if(line.encodeToByteArray().size > 256_000) throw NetLlmException(NetLlmError.Network,detail=networkError(UnexpectedEnd()))
                            parser.line(line)
                        }
                        parser.finish()
                    } finally { parser.recordPartial() }
                }
                if (result != null) return@deadline result
            }
            throw NetLlmException(NetLlmError.Format)
        } catch (e: CancellationException) { throw e }
        catch (e: NetLlmException) { throw e }
        catch (e: IOException) { throw NetLlmException(NetLlmError.Network, detail = networkError(e)) }
        catch (_: Exception) { throw NetLlmException(NetLlmError.Format) }
    }
    /** LLM 的路径探测共用一次 call deadline，不能每换一次路径就重新计时。 */
    private suspend fun <T> deadline(block: suspend () -> T): T = try {
        if(callTimeout > 0) withTimeout(callTimeout) { block() } else block()
    } catch(e: TimeoutCancellationException) {
        currentCoroutineContext().ensureActive()
        throw NetLlmException(NetLlmError.Network,detail="等待超时：本地模型首次加载可能较慢，稍后再试")
    }
    private class UnexpectedEnd : Exception()
    companion object {
        const val MAX_JSON_OUTPUT_TOKENS = 8192
        const val THINKING_JSON_OUTPUT_TOKENS = 32768
        fun httpError(status: Int, usage: NetReply? = null, raw: String = ""): NetLlmException {
            if (status in setOf(400, 413, 422)) {
                val text = raw.lowercase()
                val detail = when {
                    listOf("risk", "sensitive", "moderation", "content_filter", "inappropriate", "审核", "敏感", "违规", "不安全").any { it in text } -> "大模型服务认为内容不合规，拒收了这批原话"
                    listOf("context", "too long", "too large", "maximum", "length", "过长", "超出", "超过").any { it in text } -> "这批原话太长，大模型服务拒收"
                    else -> "大模型服务拒收了这次请求"
                }
                return NetLlmException(NetLlmError.Server, status, detail, usage)
            }
            return NetLlmException(when(status) { 401,403 -> NetLlmError.Auth; 402 -> NetLlmError.Quota; 429 -> NetLlmError.RateLimit; else -> NetLlmError.Server }, status, usage = usage)
        }
    }
}
