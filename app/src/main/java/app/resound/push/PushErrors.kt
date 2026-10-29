package app.resound.push

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONException
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

    fun status(code: Int, folder: Boolean = false): String = when (code) {
        400 -> "服务拒绝了请求（400）：多为参数或格式不对，检查通道设置"
        401, 403 -> "认证失败（$code）：检查密钥、Token 或权限"
        404 -> "地址不存在（404）：检查接口路径"
        409 -> "文件夹冲突（409）：检查父文件夹是否存在及路径是否正确"
        412 -> "文件已被修改（412）：稍后重试，避免覆盖其他修改"
        413 -> "内容太大（413）：接收端限制了大小"
        423 -> "文件被锁定（423）：关闭占用文件的应用后重试"
        429 -> "发送太频繁（429）：稍后再试，或检查当天额度"
        503 -> if (folder) "坚果云暂时限流，稍后会自动续传" else "服务出错（503）：对方服务器问题，稍后会自动重试"
        507 -> if (folder) "存储空间不足（507）：清理坚果云空间或增加容量" else "服务出错（507）：对方服务器问题，稍后会自动重试"
        in 500..599 -> "服务出错（$code）：对方服务器问题，稍后会自动重试"
        in 300..399 -> "服务要求跳转（$code），已停止发送：检查地址是否完整"
        else -> "服务拒绝了请求（$code）：检查通道设置"
    }

    fun failure(error: Throwable, values: Map<String, String>, endpoint: String = ""): PushFailure {
        val causes = generateSequence(error) { it.cause }.take(8).toList()
        val url = endpoint.toHttpUrlOrNull()
        val host = url?.host ?: values["host"].orEmpty().ifBlank { "目标主机" }
        val port = url?.port?.toString() ?: PushFields.default(values, "port", "465")
        val text = when {
            error is PushFailure -> error.message.orEmpty()
            causes.any { it is UnknownHostException } -> "找不到 $host：检查地址拼写；用 Tailscale 等内网地址时确认手机已连上"
            causes.any { it is SSLException || it is CertificateException } -> "安全连接失败：证书无效或过期；内网服务可改用 http://"
            causes.any { it is InterruptedIOException } -> TIMEOUT
            causes.any { it is ConnectException || it is NoRouteToHostException } -> "连不上 $host:$port：确认电脑上的接收服务已启动、端口正确；局域网/Tailscale 地址需手机和电脑在同一网络"
            causes.any { it is JSONException } -> INVALID_RESPONSE
            else -> "推送失败（${error.javaClass.simpleName}）：检查通道设置"
        }
        return PushFailure(clean(text, values))
    }
}
