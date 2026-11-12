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
class LlmException(val type: LlmError, val status: Int = 0, val detail: String = type.human, val usage: LlmReply? = null, val truncated: Boolean = false) : Exception(detail) {
    companion object {
        const val TRUNCATED = "回答没写完就被截断了：模型思考太久，可在设置里关掉深度思考或换个模型"
    }
}

data class LlmReply(val content: String, val promptTokens: Long, val completionTokens: Long, val actualModel: String? = null, val diagnostics: app.resound.diagnostics.LlmResponseTrace? = null)
/** 400 类拒收：换个时间重发同样内容也不会成功。 */
val LlmException.rejected: Boolean get() = type == LlmError.Server && status in LlmClient.REJECTED_STATUS


/** 安卓兼容入口；业务 JSON、SSE 与 HTTP 规则在 shared，旧链路保留到 0.3.1。 */
class LlmClient(
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(180, TimeUnit.SECONDS).callTimeout(200, TimeUnit.SECONDS).build(),
    private val requestHeaders: (LlmPreset) -> List<HeaderEntry> = { emptyList() },
    private val diagnostics: app.resound.diagnostics.LlmDiagnostics? = null,
) {
    private val legacy = if (app.resound.AppGraph.legacyNetwork) LegacyLlmClient(http, requestHeaders, diagnostics) else null
    private fun network(preset: LlmPreset): io.ktor.client.HttpClient {
        if (preset.baseUrl.isBlank() || preset.model.isBlank()) throw LlmException(LlmError.Format)
        return app.resound.net.androidNetwork(http.newBuilder().followRedirects(false).followSslRedirects(false)
            .connectTimeout(10, TimeUnit.SECONDS).apply {
                if (app.resound.transcribe.OpenAiEndpoint.kind(preset.baseUrl) == app.resound.transcribe.EndpointKind.LocalHttp)
                    readTimeout(maxOf(http.readTimeoutMillis.toLong(), 180_000), TimeUnit.MILLISECONDS)
            }.build())
    }
    private fun core(client: io.ktor.client.HttpClient, preset: LlmPreset) = app.resound.net.NetLlmClient(client,
        app.resound.transcribe.OpenAiEndpoint.bases(preset.baseUrl),http.callTimeoutMillis.toLong()) { error ->
        app.resound.transcribe.modelServiceError(preset.baseUrl, error, LlmError.Network.human)
    }
    private fun trace(value: app.resound.diagnostics.LlmResponseTrace?) = value?.let { t -> object : app.resound.net.NetworkTrace {
        override fun http(status: Int) = t.http(status)
        override fun content(text: String) = t.content(text)
        override fun finishReason(reason: String?) = t.finishReason(reason)
    } }
    private fun error(e: app.resound.net.NetLlmException): LlmException {
        val type = LlmError.valueOf(e.type.name)
        return LlmException(type, e.status, e.detail ?: type.human, e.usage?.let { LlmReply(it.content, it.promptTokens, it.completionTokens) }, e.truncated)
    }
    suspend fun chatJson(preset: LlmPreset, key: String, system: String, user: String, thinking: Boolean = false): LlmReply = withContext(Dispatchers.IO) {
        legacy?.let { return@withContext it.chatJson(preset, key, system, user, thinking) }
        val t = kotlin.coroutines.coroutineContext[app.resound.diagnostics.LlmResponseTrace]
            ?: runCatching { diagnostics?.start("其他/模型检测", preset) }.getOrNull()
        try {
            if (system.length.toLong() + user.length > LlmInputBudget.inputChars(preset)) throw LlmInputLimitException()
            val client = network(preset)
            val reply = try { core(client, preset).chat(preset, key, requestHeaders(preset), system, user, thinking, trace(t)) }
                finally { client.close() }
            t?.complete()
            LlmReply(reply.content, reply.promptTokens, reply.completionTokens, diagnostics = t)
        } catch (e: Throwable) {
            val safe = if (e is app.resound.net.NetLlmException) error(e) else e
            t?.complete(safe); throw safe
        }
    }
    suspend fun streamChat(preset: LlmPreset, key: String, messages: JSONArray, tools: JSONArray,
        onText: (String) -> Unit, onUsage: (Long, Long) -> Unit, allowTools: Boolean = true): LlmStreamReply {
        legacy?.let { return it.streamChat(preset, key, messages, tools, onText, onUsage, allowTools) }
        val t = kotlin.coroutines.coroutineContext[app.resound.diagnostics.LlmResponseTrace]
            ?: runCatching { diagnostics?.start("其他/模型检测", preset) }.getOrNull()
        try {
            val client = network(preset)
            val r = try { core(client, preset).stream(preset, key, requestHeaders(preset), messages.toString(), tools.toString(), onText, onUsage, allowTools, trace(t)) }
                finally { client.close() }
            t?.content(r.content); t?.complete()
            return LlmStreamReply(r.content, r.calls.map { LlmToolCall(it.id, it.name, it.arguments, it.extraContent?.let(::JSONObject)) }, r.reasoningContent, t)
        } catch(e: Throwable) {
            val safe = if (e is app.resound.net.NetLlmException) error(e) else e
            t?.complete(safe); throw safe
        }
    }
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
