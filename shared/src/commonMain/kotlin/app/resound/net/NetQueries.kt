package app.resound.net

import app.resound.digest.HeaderEntry
import app.resound.transcribe.*
import io.ktor.client.HttpClient
import io.ktor.http.Url
import kotlinx.serialization.json.*
import app.resound.platform.SharedJson

data class NetBalance(val total: Double, val granted: Double?, val toppedUp: Double?, val currency: String, val available: Boolean)
object BalanceRules {
    fun parse(body: String): NetBalance {
        val json = Obj(body)
        val infos = json.getJSONArray("balance_infos")
        val all = (0 until infos.length()).map { infos.getJSONObject(it) }
        val pick = all.firstOrNull { it.optString("currency") == "CNY" } ?: all.firstOrNull() ?: error("没有余额信息")
        fun num(k: String) = pick.getString(k).toDoubleOrNull()?.takeIf { it.isFinite() } ?: error("余额不是有效数字")
        return NetBalance(num("total_balance"), num("granted_balance"), num("topped_up_balance"), pick.optString("currency", "CNY"), json.optBoolean("is_available", true))
    }
    fun pick(body: String, path: String): Double {
        var node = runCatching { SharedJson.parseToJsonElement(body) }.getOrNull()?.takeIf { it is JsonObject || it is JsonArray } ?: error("返回的不是 JSON")
        for (part in path.split('.').filter { it.isNotBlank() }) node = when(val n = node) {
            is JsonObject -> n[part] ?: error("返回结果里没有“$part”")
            is JsonArray -> part.toIntOrNull()?.let { n.getOrNull(it) } ?: error("“$part”不是有效的序号")
            else -> error("“$part”之前已经不是对象了")
        }
        return (node as? JsonPrimitive)?.content?.trim()?.toDoubleOrNull()?.takeIf { it.isFinite() } ?: error("“$path”不是数字")
    }
}
suspend fun queryBalance(client: HttpClient, url: String, key: String, headers: List<HeaderEntry>, path: String?): NetBalance {
    val parsed = runCatching { Url(url) }.getOrElse { error("查询地址格式不对") }
    require(parsed.protocol.name in setOf("http", "https") && parsed.user.isNullOrEmpty() && parsed.password.isNullOrEmpty() && '?' !in url && '#' !in url)
    val requestHeaders = networkHeaders(key, listOf(HeaderEntry("Accept", "application/json")) + headers)
    return client.consume(url, requestHeaders) { r ->
        check(r.status.value in 200..299) { "余额查询失败" }
        val text = r.limitedText(65_536, "余额响应过大")
        if (path == null) BalanceRules.parse(text) else NetBalance(BalanceRules.pick(text, path), null, null, "CNY", true)
    }
}
data class NetModels(val base: String, val ids: List<String>)
suspend fun queryModels(client: HttpClient, bases: List<String>, key: String, headers: List<HeaderEntry>): NetModels {
    for (base in bases) {
        val result = client.consume("$base/models", networkHeaders(key, headers)) { r ->
            if (r.status.value in setOf(404,405)) return@consume null
            if (r.status.value in setOf(401,403)) error("鉴权失败，请检查密钥")
            check(r.status.value in 200..299) { "拉取失败；可手动填写模型名称" }
            val data = Obj(r.limitedText(1_000_000, "模型列表过大，请手动填写")).optJSONArray("data") ?: error("服务未返回兼容的模型列表，可手动填写")
            val ids = (0 until data.length()).mapNotNull { data.optJSONObject(it)?.optString("id")?.takeIf { v -> v.isNotBlank() && v.length <= 200 } }.distinct().sorted()
            check(ids.isNotEmpty()) { "没有可选模型，请手动填写" }
            NetModels(base, ids)
        }
        if (result != null) return result
    }
    error("服务没有提供模型列表，可手动填写模型名称")
}
fun customTranscriptionText(text: String): String {
    val json = Obj(text)
    if (json.has("text")) return json.optString("text").trim()
    val code = json.optJSONObject("base_resp")?.optInt("status_code", 0) ?: 0
    if (code == 1004) throw AsrException(AsrError.Auth, detail = "鉴权失败，请检查密钥")
    if (code != 0) throw AsrException(AsrError.Protocol, detail = "服务返回错误，请检查地址、密钥和参数")
    throw AsrException(AsrError.Protocol)
}
/** 分块来源可重开，避免把长录音整体载入内存。文件名与边界由平台传入。 */
class AudioUpload(val name: String, val format: String, val size: Long,
    val read: suspend (suspend (ByteArray) -> Unit) -> Unit)
