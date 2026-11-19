package app.resound.auth

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/** Auth 服务端最小客户端；网络失败按 Network 归类，绝不把配额/网络错误当账号失效。 */
class AuthApi(
    private val baseUrl: () -> String,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).build(),
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val io = Dispatchers.IO

    sealed class Result {
        data class Ok(val body: kotlinx.serialization.json.JsonObject) : Result()
        data class Denied(val code: Int, val message: String) : Result()
        data class Network(val message: String) : Result()
    }

    private suspend fun call(method: String, path: String, body: String? = null, bearer: String = ""): Result = withContext(io) {
        val base = baseUrl().trimEnd('/')
        if (base.isEmpty()) return@withContext Result.Denied(0, "未配置授权服务地址")
        val url = "$base$path"
        val req = Request.Builder().url(url).apply {
            if (bearer.isNotEmpty()) header("Authorization", "Bearer $bearer")
            if (body != null) post(body.toRequestBody("application/json".toMediaType())) else get()
        }.build()
        try {
            client.newCall(req).execute().use { r ->
                val text = r.body?.string().orEmpty()
                val obj = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
                when {
                    r.isSuccessful && obj != null -> Result.Ok(obj)
                    else -> Result.Denied(r.code, obj?.get("error")?.jsonPrimitive?.contentOrNull ?: "HTTP ${r.code}")
                }
            }
        } catch (e: Exception) {
            Result.Network(e.message ?: "network error")
        }
    }

    suspend fun login(username: String, password: String, deviceId: String, deviceName: String, activationCode: String = "") =
        call("POST", "/auth/login", Json.encodeToString(
            kotlinx.serialization.json.buildJsonObject {
                put("username", kotlinx.serialization.json.JsonPrimitive(username))
                put("password", kotlinx.serialization.json.JsonPrimitive(password))
                put("device_id", kotlinx.serialization.json.JsonPrimitive(deviceId))
                put("device_name", kotlinx.serialization.json.JsonPrimitive(deviceName))
                if (activationCode.isNotEmpty()) put("activation_code", kotlinx.serialization.json.JsonPrimitive(activationCode))
            }))

    suspend fun refresh(refreshToken: String) =
        call("POST", "/auth/refresh", """{"refresh_token":"${refreshToken}"}""")

    suspend fun logout(refreshToken: String) =
        call("POST", "/auth/logout", """{"refresh_token":"${refreshToken}"}""")

    suspend fun status(accessToken: String) = call("GET", "/auth/status", bearer = accessToken)
    suspend fun devices(accessToken: String) = call("GET", "/auth/devices", bearer = accessToken)
    suspend fun revoke(sessionId: String, accessToken: String) =
        call("POST", "/auth/devices/revoke", """{"session_id":"${sessionId}"}""", bearer = accessToken)

    /** 会员视图：套餐 / 能力行 / 本月用量。 */
    suspend fun membership(accessToken: String) = call("GET", "/auth/membership", bearer = accessToken)

    /** 兑换码核销；request_id 供服务端幂等去重（网络重试不重复发权益）。 */
    suspend fun redeem(code: String, requestId: String, accessToken: String) =
        call("POST", "/auth/redeem", Json.encodeToString(
            kotlinx.serialization.json.buildJsonObject {
                put("code", kotlinx.serialization.json.JsonPrimitive(code))
                put("request_id", kotlinx.serialization.json.JsonPrimitive(requestId))
            }), bearer = accessToken)

    /** 成功转写时长上报；items 服务端按 id 去重，重传安全。 */
    suspend fun reportUsage(items: List<UsageItem>, accessToken: String) =
        call("POST", "/auth/usage", Json.encodeToString(
            kotlinx.serialization.json.buildJsonObject {
                put("items", kotlinx.serialization.json.JsonArray(items.map {
                    kotlinx.serialization.json.buildJsonObject {
                        put("id", kotlinx.serialization.json.JsonPrimitive(it.id))
                        put("minutes", kotlinx.serialization.json.JsonPrimitive(it.minutes))
                        put("month", kotlinx.serialization.json.JsonPrimitive(it.month))
                    }
                }))
            }), bearer = accessToken)

    data class UsageItem(val id: String, val minutes: Double, val month: String)

    /** 从登录/刷新响应里解出账号、token、凭证。 */
    fun parseSession(obj: kotlinx.serialization.json.JsonObject): ParsedSession? = runCatching {
        val account = obj["account"]!!.jsonObject
        val lic = obj["license"]!!.jsonObject
        val payloadStr = lic["payload"]!!.jsonPrimitive.content
        val sig = lic["signature"]!!.jsonPrimitive.content
        ParsedSession(
            accountId = account["id"]!!.jsonPrimitive.content,
            username = account["username"]!!.jsonPrimitive.content,
            access = obj["access_token"]!!.jsonPrimitive.content,
            refresh = obj["refresh_token"]!!.jsonPrimitive.content,
            licenseRaw = payloadStr, licenseSig = sig,
        )
    }.getOrNull()

    data class ParsedSession(
        val accountId: String, val username: String,
        val access: String, val refresh: String,
        val licenseRaw: String, val licenseSig: String,
    )
}
