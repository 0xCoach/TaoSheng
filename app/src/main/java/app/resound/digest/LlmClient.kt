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
    Network("网络连接失败，等待重试", true),
    Server("大模型服务暂时不可用，等待重试", true),
    Format("大模型返回的格式不对", true),
}

/** 只用固定文案，不带服务端原文或请求内容，避免密钥、原话进日志。status 是 HTTP 状态码（0 = 没拿到），供模型检测分辨原因。 */
class LlmException(val type: LlmError, val status: Int = 0) : Exception(type.human)

data class LlmReply(val content: String, val promptTokens: Long, val completionTokens: Long)

/** 兼容 OpenAI chat/completions 的服务（DeepSeek、方舟、自建）。 */
class LlmClient(
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(180, TimeUnit.SECONDS).callTimeout(200, TimeUnit.SECONDS).build(),
) {
    suspend fun chatJson(preset: LlmPreset, key: String, system: String, user: String): LlmReply = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("model", preset.model)
            .put("messages", JSONArray()
                .put(JSONObject().put("role", "system").put("content", system))
                .put(JSONObject().put("role", "user").put("content", user)))
            .put("response_format", JSONObject().put("type", "json_object"))
            .put("temperature", 0.2)
        // 方舟的豆包默认会“深度思考”，整理不需要，关掉省时省钱
        if (preset.baseUrl.contains("volces.com")) body.put("thinking", JSONObject().put("type", "disabled"))
        val request = Request.Builder()
            .url(preset.baseUrl.trimEnd('/') + "/chat/completions")
            .header("Authorization", "Bearer $key")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        val response = try { clientFor(preset).newCall(request).await() } catch (e: IOException) { throw LlmException(LlmError.Network) }
        response.use {
            when (it.code) {
                in 200..299 -> Unit
                401, 403 -> throw LlmException(LlmError.Auth, it.code)
                else -> throw LlmException(LlmError.Server, it.code)
            }
            val json = try { JSONObject(it.body!!.string()) } catch (_: Exception) { throw LlmException(LlmError.Format) }
            val content = json.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content")
                ?: throw LlmException(LlmError.Format)
            val usage = json.optJSONObject("usage")
            LlmReply(content, usage?.optLong("prompt_tokens") ?: 0, usage?.optLong("completion_tokens") ?: 0)
        }
    }

    /** 读取响应体期间取消协程会立即 cancel HTTP call，而不只是取消等待响应头。 */
    suspend fun streamChat(
        preset: LlmPreset, key: String, messages: JSONArray, tools: JSONArray,
        onText: (String) -> Unit, onUsage: (Long, Long) -> Unit,
        allowTools: Boolean = true,
    ): LlmStreamReply = suspendCancellableCoroutine { cont ->
        val body = JSONObject().put("model", preset.model).put("messages", messages)
            .put("stream", true).put("stream_options", JSONObject().put("include_usage", true))
            .put("temperature", 0.2).put("max_tokens", 1800)
        if (tools.length() > 0) body.put("tools", tools).put("tool_choice", if (allowTools) "auto" else "none")
        else body.put("tool_choice", "none")
        if (preset.baseUrl.contains("volces.com")) body.put("thinking", JSONObject().put("type", "disabled"))
        val request = Request.Builder().url(preset.baseUrl.trimEnd('/') + "/chat/completions")
            .header("Authorization", "Bearer $key")
            .post(body.toString().toRequestBody("application/json".toMediaType())).build()
        val call = clientFor(preset).newCall(request)
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) cont.resumeWithException(LlmException(LlmError.Network))
            }
            override fun onResponse(call: Call, response: Response) {
                try {
                    val reply = response.use {
                        when (it.code) {
                            in 200..299 -> Unit
                            401, 403 -> throw LlmException(LlmError.Auth, it.code)
                            else -> throw LlmException(LlmError.Server, it.code)
                        }
                        val parser = LlmSseParser({ delta -> if (cont.isActive) onText(delta) }, onUsage)
                        val source = it.body?.source() ?: throw LlmException(LlmError.Format)
                        while (!parser.done) {
                            if (!cont.isActive) return
                            val line = source.readUtf8LineStrict(256_000)
                            parser.line(line)
                        }
                        parser.finish()
                    }
                    if (cont.isActive) cont.resume(reply)
                } catch (e: Exception) {
                    val safe = when (e) {
                        is LlmException -> e
                        is IOException -> LlmException(LlmError.Network)
                        else -> LlmException(LlmError.Format)
                    }
                    if (cont.isActive) cont.resumeWithException(safe)
                }
            }
        })
    }

    private fun clientFor(preset:LlmPreset):OkHttpClient {
        if(preset.id!="custom")return http
        if(preset.baseUrl.isBlank() || preset.model.isBlank())throw LlmException(LlmError.Format)
        val bases=app.resound.transcribe.OpenAiEndpoint.bases(preset.baseUrl)
        return http.newBuilder().apply { interceptors().add(0, okhttp3.Interceptor { chain ->
            var response:Response?=null
            for((index,base) in bases.withIndex()) {
                response=chain.proceed(chain.request().newBuilder().url("$base/chat/completions").build())
                if(response.code !in setOf(404,405) || index==bases.lastIndex)break
                response.close()
            }
            response!!
        }) }.build()
    }

    private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) = cont.resumeWithException(e)
            override fun onResponse(call: Call, response: Response) = cont.resume(response)
        })
    }
}
