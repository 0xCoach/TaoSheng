package app.resound.push

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import java.security.cert.CertificateException

/** 不保留原异常、响应或完整 URL；脱敏必须先于截短，避免泄露半截密钥。 */
internal object PushErrors {
    const val INVALID_URL = "地址格式不对：需以 http:// 或 https:// 开头"
    const val INVALID_RESPONSE = "服务返回了无法识别的内容：地址可能指向了网页而不是接口"
    const val TIMEOUT = "等待超时：服务没有响应，检查网络或接收端是否卡住"

    fun clean(text: String, values: Map<String, String>): String {
        val secrets = mutableSetOf<String>()
        listOf("token", "sendKey", "password", "signSecret", "authorization", "webhook").forEach { key ->
            values[key]?.takeIf(String::isNotBlank)?.let { secrets += it }
        }
        values["authorization"]?.substringAfter(' ', "")?.takeIf(String::isNotBlank)?.let { secrets += it }
        values["headers"]?.lines()?.forEach { line ->
            line.substringAfter(':', "").trim().takeIf(String::isNotBlank)?.let { secrets += it }
        }
        values.values.forEach { value -> value.toHttpUrlOrNull()?.let { url ->
            url.queryParameterNames.filter { it.lowercase() in setOf("key", "token", "access_token", "secret") }
                .forEach { key -> url.queryParameterValues(key).filterNotNull().filter(String::isNotBlank).forEach { secrets += it } }
            // 飞书的机器人令牌在路径中。
            if (value == values["webhook"]) url.pathSegments.lastOrNull()?.takeIf(String::isNotBlank)?.let { secrets += it }
        } }
        var result = text
        secrets.sortedByDescending(String::length).forEach { result = result.replace(it, "***") }
        return result.replace(Regex("https?://[^\\s<>\"']+", RegexOption.IGNORE_CASE)) { match ->
            match.value.toHttpUrlOrNull()?.let { "${it.host}:${it.port}" } ?: "***"
        }.replace(Regex("[\\r\\n\\u0085\\u2028\\u2029]+"), " ").trim()
    }

    fun refused(kind: PushKind, json: JSONObject, values: Map<String, String>): String {
        val fields = when (kind) {
            PushKind.WeCom, PushKind.DingTalk -> listOf("errmsg", "msg", "description", "message")
            PushKind.Telegram -> listOf("description", "msg", "errmsg", "message")
            PushKind.Notion -> listOf("message", "msg", "errmsg", "description")
            else -> listOf("msg", "errmsg", "description", "message")
        }
        val reason = fields.mapNotNull { (json.opt(it) as? String)?.takeIf(String::isNotBlank) }.firstOrNull()
        val safe = reason?.let { clean(it, values) }.orEmpty()
        val short = safe.codePoints().toArray().take(80).toIntArray().let { String(it, 0, it.size) }
        return clean("${kind.title}：${short.ifBlank { "服务拒绝了消息，检查密钥和当天额度" }}", values)
    }

    fun status(code: Int, folder: Boolean = false) = PushErrorRules.status(code, folder)

    fun failure(error: Throwable, values: Map<String, String>, endpoint: String = ""): PushFailure {
        PushTrace.current()?.let { if (error !is PushFailure || it.exception.isEmpty()) it.exception = error.javaClass.simpleName }
        val causes = generateSequence(error) { it.cause }.take(8).toList()
        val url = endpoint.toHttpUrlOrNull()
        val host = url?.host ?: values["host"].orEmpty().ifBlank { "目标主机" }
        val port = url?.port?.toString() ?: PushFields.default(values, "port", "465")
        val text = when {
            error is PushFailure -> error.message.orEmpty()
            error is app.resound.backup.BackupFailure -> error.human
            causes.any { it is UnknownHostException } -> "找不到 $host：检查地址拼写；用 Tailscale 等内网地址时确认手机已连上"
            causes.any { it is SSLException || it is CertificateException } -> "安全连接失败：证书无效或过期；内网服务可改用 http://"
            causes.any { it is InterruptedIOException } -> TIMEOUT
            causes.any { it is ConnectException || it is NoRouteToHostException } -> "连不上 $host:$port：确认电脑上的接收服务已启动、端口正确；局域网/Tailscale 地址需手机和电脑在同一网络"
            else -> "推送失败（${error.javaClass.simpleName}）：检查通道设置"
        }
        return PushFailure(clean(text, values))
    }
}
