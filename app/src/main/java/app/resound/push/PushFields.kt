package app.resound.push

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal object PushFields {
    fun fields(kind: PushKind) = PushFieldRules.fields(kind)
    fun default(values: Map<String, String>, key: String, fallback: String) = PushFieldRules.default(values, key, fallback)
    fun validate(kind: PushKind, values: Map<String, String>) {
        fun required(key: String) = values[key].orEmpty().also { if (it.isBlank()) throw PushFailure("请填写必填配置；恢复备份后需重填密钥") }
        fun ascii(value: String) { if (value.any { it.code !in 32..126 }) throw PushFailure("配置格式不正确") }
        when (kind) {
            PushKind.PushPlus, PushKind.ServerChan -> if (!required(if (kind == PushKind.PushPlus) "token" else "sendKey").matches(Regex("[A-Za-z0-9_-]+"))) throw PushFailure("密钥格式不正确")
            PushKind.WeCom -> {
                val url = https(required("webhook"))
                if (url.host != "qyapi.weixin.qq.com" || url.port != 443 || url.encodedPath != "/cgi-bin/webhook/send" || url.queryParameterNames != setOf("key") || url.queryParameterValues("key").size != 1 || url.queryParameter("key").isNullOrBlank()) throw PushFailure("请填写有效的企业微信机器人地址")
            }
            PushKind.Feishu, PushKind.DingTalk -> {
                val url = https(required("webhook"))
                val hosts = if (kind == PushKind.Feishu) setOf("open.feishu.cn", "open.larksuite.com") else setOf("oapi.dingtalk.com")
                if (url.host !in hosts || url.port != 443) throw PushFailure("机器人地址域名不正确")
            }
            PushKind.Webhook -> {
                val url = url(required("url"))
                if (!url.isHttps && !localHost(url.host)) throw PushFailure("公网接口必须使用 HTTPS；HTTP 仅限局域网或组网地址")
                ascii(values["authorization"].orEmpty()); headers(values["headers"].orEmpty())
            }
            PushKind.Telegram -> {
                if (!required("token").matches(Regex("[0-9]+:[A-Za-z0-9_-]+"))) throw PushFailure("Bot Token 格式不正确")
                if (!required("chatId").matches(Regex("-?[0-9]+|@[A-Za-z0-9_]+"))) throw PushFailure("Chat ID 格式不正确")
                val url = https(default(values, "baseUrl", "https://api.telegram.org"))
                if (url.query != null) throw PushFailure("接口地址不能含查询参数")
            }
            PushKind.Email -> {
                val host = required("host")
                if (!host.matches(Regex("[A-Za-z0-9.-]+")) || host.startsWith('.') || host.contains("..")) throw PushFailure("SMTP 服务器格式不正确")
                val port = default(values, "port", "465").toIntOrNull()
                if (port == null || port !in 1..65535) throw PushFailure("端口须为 1～65535")
                listOf(required("account"), required("to")).forEach { if (!it.matches(Regex("[A-Za-z0-9.!#$%&'*+/=?^_`{|\\}~-]+@[A-Za-z0-9-]+(?:\\.[A-Za-z0-9-]+)+"))) throw PushFailure("邮箱格式不正确") }
                required("password")
            }
            PushKind.Notion -> { ascii(required("token")); databaseId(required("databaseId")) }
            PushKind.MarkdownFolder -> {
                try {
                    if (values["cloudKind"] == "S3") app.resound.backup.CloudAddress.s3(app.resound.backup.s3FromJson(required("s3")))
                    else app.resound.backup.CloudAddress.parse(default(values, "url", "https://dav.jianguoyun.com/dav/"))
                } catch (e: IllegalArgumentException) { throw PushFailure(e.message ?: "云端地址格式不正确") }
                required("account"); required("password")
                val folder = required("folder")
                if (folder.startsWith('/') || folder.contains("..") || folder.contains('\\') || folder.split('/').any { it.isBlank() } || folder.any { it.code < 32 }) throw PushFailure("文件夹须为相对路径，不能含 .. 或空目录")
            }
        }
    }
    fun headers(value: String): Map<String, String> = try { PushFieldRules.headers(value) }
        catch (e: IllegalArgumentException) { throw PushFailure(e.message.orEmpty()) }
    fun databaseId(value: String): String {
        val raw = if (value.startsWith("https://")) url(value).pathSegments.lastOrNull().orEmpty().takeLast(36).let { last ->
            if (last.replace("-", "").length == 32) last else url(value).pathSegments.lastOrNull().orEmpty().takeLast(32)
        } else value
        val compact = raw.replace("-", "")
        if (compact.length != 32 || compact.any { it !in "0123456789abcdefABCDEF" } || (raw.length != 32 && !(raw.length == 36 && listOf(8,13,18,23).all { raw[it] == '-' }))) throw PushFailure("Notion 数据库 ID 格式不正确")
        return compact.lowercase(java.util.Locale.ROOT)
    }
    private fun localHost(host: String): Boolean {
        if (host.endsWith(".local") || host.endsWith(".ts.net")) return true
        val n = host.split('.').map { it.toIntOrNull() ?: return false }
        if (n.size != 4 || n.any { it !in 0..255 }) return false
        return n[0] == 10 || (n[0] == 192 && n[1] == 168) || (n[0] == 172 && n[1] in 16..31) || (n[0] == 100 && n[1] in 64..127)
    }
    private fun url(value: String): HttpUrl {
        val parsed = value.toHttpUrlOrNull() ?: throw PushFailure(PushErrors.INVALID_URL)
        if (parsed.username.isNotEmpty() || parsed.password.isNotEmpty() || parsed.fragment != null)
            throw PushFailure("请填写有效地址，不能含账号、密码或片段")
        return parsed
    }
    private fun https(value: String): HttpUrl = url(value).also { if (!it.isHttps) throw PushFailure("请填写有效的 HTTPS 地址") }
}
