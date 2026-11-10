package app.resound.digest

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

enum class LlmError(val human: String, val retryable: Boolean) {
    Auth("大模型密钥无效或没开通", false),
    Quota("大模型余额不足或额度已用完", false),
    RateLimit("请求太频繁，请稍后再试", true),
    Network("网络连接失败，等待重试", true),
    Server("大模型服务暂时不可用，等待重试", true),
    Format("大模型返回的格式不对", true),
}

/** 只用固定文案，不带服务端原文或请求内容，避免密钥、原话进日志。status 是 HTTP 状态码（0 = 没拿到），供模型检测分辨原因。 */
class LlmException(val type: LlmError, val status: Int = 0, val detail: String = type.human, val usage: LlmReply? = null) : Exception(detail)

data class LlmReply(val content: String, val promptTokens: Long, val completionTokens: Long, val actualModel: String? = null, val diagnostics: app.resound.diagnostics.LlmResponseTrace? = null)
/** 400 类拒收：换个时间重发同样内容也不会成功。 */
val LlmException.rejected: Boolean get() = type == LlmError.Server && status in LlmClient.REJECTED_STATUS
class LlmInputLimitException(message: String = "内容超出所选模型的容量，请换用容量更大的模型") : IllegalArgumentException(message)

/** 兼容 OpenAI chat/completions 的服务（DeepSeek、方舟、自建）。 */
class LlmClient(
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(180, TimeUnit.SECONDS).callTimeout(200, TimeUnit.SECONDS).build(),
    private val requestHeaders: (LlmPreset) -> List<HeaderEntry> = { emptyList() },
    private val diagnostics: app.resound.diagnostics.LlmDiagnostics? = null,
) {
    suspend fun chatJson(preset: LlmPreset, key: String, system: String, user: String, thinking: Boolean = false): LlmReply = withContext(Dispatchers.IO) {
        val trace = kotlin.coroutines.coroutineContext[app.resound.diagnostics.LlmResponseTrace]
            ?: runCatching { diagnostics?.start("其他/模型检测", preset) }.getOrNull()
        try {
            val reply = chatJsonRaw(preset, key, system, user, thinking, trace)
            trace?.complete()
            reply.copy(diagnostics = trace)
        } catch (e: Throwable) { trace?.complete(e); throw e }
    }

    private suspend fun chatJsonRaw(preset: LlmPreset, key: String, system: String, user: String, thinking: Boolean,
        trace: app.resound.diagnostics.LlmResponseTrace?): LlmReply {
        if (system.length.toLong() + user.length > LlmInputBudget.inputChars(preset)) throw LlmInputLimitException()
        val body = JSONObject()
            .put("model", preset.model)
            .put("messages", JSONArray()
                .put(JSONObject().put("role", "system").put("content", system))
                .put(JSONObject().put("role", "user").put("content", user)))
            .put("response_format", JSONObject().put("type", "json_object"))
            .put("temperature", 0.2).put("max_tokens", MAX_JSON_OUTPUT_TOKENS)
        applyLlmOptions(preset, body, thinking)
        val request = Request.Builder()
            .url(preset.baseUrl.trimEnd('/') + "/chat/completions")
            .serviceHeaders(key, requestHeaders(preset))
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        return try { clientFor(preset).newCall(request).consumeResponse {
            trace?.http(it.code)
            val raw = it.body?.string().orEmpty()
            trace?.content(raw)
            val json = runCatching { JSONObject(raw) }.getOrNull()
            val usage = json?.optJSONObject("usage")?.let { u -> LlmReply("", u.optLong("prompt_tokens"), u.optLong("completion_tokens")) }
            if (it.code !in 200..299) throw httpError(it.code, usage, raw)
            trace?.finishReason(json?.optJSONArray("choices")?.optJSONObject(0)?.optString("finish_reason")?.takeUnless { reason -> reason == "null" })
            val content = json?.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content")
                ?: throw LlmException(LlmError.Format, usage = usage)
            trace?.content(content)
            LlmReply(content, usage?.promptTokens ?: 0, usage?.completionTokens ?: 0)

        } } catch (e: IOException) { throw LlmException(LlmError.Network, detail = app.resound.transcribe.modelServiceError(preset.baseUrl, e, LlmError.Network.human)) }
    }

    /** 读取响应体期间取消协程会立即 cancel HTTP call，而不只是取消等待响应头。 */
    suspend fun streamChat(
        preset: LlmPreset, key: String, messages: JSONArray, tools: JSONArray,
        onText: (String) -> Unit, onUsage: (Long, Long) -> Unit,
        allowTools: Boolean = true,
    ): LlmStreamReply {
        val trace = kotlin.coroutines.coroutineContext[app.resound.diagnostics.LlmResponseTrace]
            ?: runCatching { diagnostics?.start("其他/模型检测", preset) }.getOrNull()
        return try {
            val reply = streamChatRaw(preset, key, messages, tools, onText, onUsage, allowTools, trace)
            trace?.content(reply.content)
            trace?.complete()
            reply.copy(diagnostics = trace)
        } catch (e: Throwable) { trace?.complete(e); throw e }
    }

    private suspend fun streamChatRaw(
        preset: LlmPreset, key: String, messages: JSONArray, tools: JSONArray,
        onText: (String) -> Unit, onUsage: (Long, Long) -> Unit, allowTools: Boolean,
        trace: app.resound.diagnostics.LlmResponseTrace?,
    ): LlmStreamReply = suspendCancellableCoroutine { cont ->
        val body = JSONObject().put("model", preset.model).put("messages", messages)
            .put("stream", true).put("stream_options", JSONObject().put("include_usage", true))
            .put("temperature", 0.2).put("max_tokens", 1800)
        if (tools.length() > 0) body.put("tools", tools).put("tool_choice", if (allowTools) "auto" else "none")
        applyLlmOptions(preset, body)
        val request = Request.Builder().url(preset.baseUrl.trimEnd('/') + "/chat/completions")
            .serviceHeaders(key, requestHeaders(preset))
            .post(body.toString().toRequestBody("application/json".toMediaType())).build()
        val call = clientFor(preset).newCall(request)
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) cont.resumeWithException(LlmException(LlmError.Network, detail = app.resound.transcribe.modelServiceError(preset.baseUrl, e, LlmError.Network.human)))
            }
            override fun onResponse(call: Call, response: Response) {
                try {
                    trace?.http(response.code)
                    val reply = response.use {
                        if (it.code !in 200..299) {
                            val usage = runCatching { JSONObject(it.body?.string().orEmpty()).optJSONObject("usage") }.getOrNull()
                            usage?.let { u -> onUsage(u.optLong("prompt_tokens"), u.optLong("completion_tokens")) }
                            throw httpError(it.code)
                        }
                        val parser = LlmSseParser({ delta -> if (cont.isActive) onText(delta) }, onUsage, trace)
                        val source = it.body?.source() ?: throw LlmException(LlmError.Format)
                        try {
                            while (!parser.done) {
                                if (!cont.isActive) return
                                val line = source.readUtf8LineStrict(256_000)
                                parser.line(line)
                            }
                            parser.finish()
                        } finally { parser.recordPartial() }
                    }
                    if (cont.isActive) cont.resume(reply)
                } catch (e: Exception) {
                    val safe = when (e) {
                        is LlmException -> e
                        is IOException -> LlmException(LlmError.Network, detail = app.resound.transcribe.modelServiceError(preset.baseUrl, e, LlmError.Network.human))
                        else -> LlmException(LlmError.Format)
                    }
                    if (cont.isActive) cont.resumeWithException(safe)
                }
            }
        })
    }

    private fun clientFor(preset:LlmPreset):OkHttpClient {
        if(preset.baseUrl.isBlank() || preset.model.isBlank())throw LlmException(LlmError.Format)
        val bases=app.resound.transcribe.OpenAiEndpoint.bases(preset.baseUrl)
        return http.newBuilder().apply {
            followRedirects(false); followSslRedirects(false)
            connectTimeout(10, TimeUnit.SECONDS)
            if(app.resound.transcribe.OpenAiEndpoint.kind(preset.baseUrl)==app.resound.transcribe.EndpointKind.LocalHttp) readTimeout(maxOf(http.readTimeoutMillis.toLong(),180_000), TimeUnit.MILLISECONDS)
            interceptors().add(0, okhttp3.Interceptor { chain ->
            var response:Response?=null
            for((index,base) in bases.withIndex()) {
                response=chain.proceed(chain.request().newBuilder().url("$base/chat/completions").build())
                if(response.code !in setOf(404,405) || index==bases.lastIndex)break
                response.close()
            }
            response!!
        }) }.build()
    }

    private suspend fun Call.consumeResponse(read: (Response) -> LlmReply): LlmReply = suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { if (cont.isActive) cont.resumeWithException(e) }
            override fun onResponse(call: Call, response: Response) {
                try {
                    val reply = response.use { if (!cont.isActive) return; read(it) }
                    if (cont.isActive) cont.resume(reply)
                } catch (e: Exception) { if (cont.isActive) cont.resumeWithException(e) }
            }
        })
    }

    /** 服务端原文只用来挑固定文案，不写进异常或日志。 */
    private fun httpError(status: Int, usage: LlmReply? = null, raw: String = ""): LlmException =
        if (status in REJECTED_STATUS) LlmException(LlmError.Server, status, rejectionDetail(raw), usage)
        else LlmException(when (status) {
            401, 403 -> LlmError.Auth
            402 -> LlmError.Quota
            429 -> LlmError.RateLimit
            else -> LlmError.Server
        }, status, usage = usage)

    companion object {
        const val MAX_JSON_OUTPUT_TOKENS = 8192
        /** 先思考时，思考过程也算在输出里；一批 300 句实测要 8000～12000，留足余量免得答一半被截断 */
        const val THINKING_JSON_OUTPUT_TOKENS = 32_768
        /** 服务方明确不接这次请求（内容审核、太长、参数不对）；原样重发不会好。 */
        val REJECTED_STATUS = setOf(400, 413, 422)
        private val MODERATION = listOf("risk", "sensitive", "moderation", "content_filter", "inappropriate", "审核", "敏感", "违规", "不安全")
        private val TOO_LONG = listOf("context", "too long", "too large", "maximum", "length", "过长", "超出", "超过")
        internal fun rejectionDetail(raw: String): String = raw.lowercase().let { text -> when {
            MODERATION.any { it in text } -> "大模型服务认为内容不合规，拒收了这批原话"
            TOO_LONG.any { it in text } -> "这批原话太长，大模型服务拒收"
            else -> "大模型服务拒收了这次请求"
        } }
    }
}
