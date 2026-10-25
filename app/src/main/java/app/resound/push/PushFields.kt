package app.resound.push

import app.resound.appDisplayName
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal object PushFields {
    fun fields(kind: PushKind): List<PushField> = when (kind) {
        PushKind.PushPlus -> listOf(f("token", "Token", "PushPlus 消息令牌", true), f("topic", "群组编码", "可选"))
        PushKind.WeCom -> listOf(f("webhook", "机器人地址", "企业微信机器人的 HTTPS 地址", true))
        PushKind.ServerChan -> listOf(f("sendKey", "SendKey", "Server酱 Turbo SendKey", true))
        PushKind.Webhook -> listOf(f("url", "接口地址", "HTTPS；局域网 / Tailscale 可用 HTTP"), f("authorization", "Authorization", "可选", true),
            f("headers", "附加请求头", "可选，一行一个 名字: 值，最多 5 个", true), f("signSecret", "签名密钥", "可选，HMAC-SHA256", true))
        PushKind.Feishu, PushKind.DingTalk -> listOf(f("webhook", "机器人地址", "群自定义机器人的 HTTPS Webhook", true), f("signSecret", "签名密钥", "可选", true))
        PushKind.Telegram -> listOf(f("token", "Bot Token", "从 BotFather 获取", true), f("chatId", "Chat ID", "数字、负数或 @用户名"), f("baseUrl", "接口地址", "可选，默认 https://api.telegram.org"))
        PushKind.Email -> listOf(f("host", "SMTP 服务器", "如 smtp.qq.com"), f("port", "端口", "默认 465；587 使用 STARTTLS"), f("account", "账号", "完整邮箱地址"), f("password", "授权码", "邮箱设置中开启 SMTP 后获取", true), f("to", "收件人", "一个邮箱地址"))
        PushKind.Notion -> listOf(f("token", "Integration Token", "数据库需共享给此 Integration", true), f("databaseId", "数据库 ID", "32 位 ID 或数据库链接"))
        PushKind.MarkdownFolder -> listOf(f("url", "WebDAV 地址", "默认 https://dav.jianguoyun.com/dav/"), f("account", "账号", "填坚果云账号即可，Obsidian 用 Remotely Save 等插件同步这个文件夹"), f("password", "应用密码", "坚果云安全选项中添加应用", true), f("folder", "文件夹", "如 Obsidian/${appDisplayName}"))
    }
    private fun f(key: String, label: String, hint: String, secret: Boolean = false) = PushField(key, label, hint, secret)
    fun default(values: Map<String, String>, key: String, fallback: String) = values[key]?.takeIf { it.isNotBlank() } ?: fallback
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
                val url = https(default(values, "url", "https://dav.jianguoyun.com/dav/"))
                if (url.query != null) throw PushFailure("WebDAV 地址不能含查询参数")
                required("account"); required("password")
                val folder = required("folder")
                if (folder.startsWith('/') || folder.contains("..") || folder.contains('\\') || folder.split('/').any { it.isBlank() } || folder.any { it.code < 32 }) throw PushFailure("文件夹须为相对路径，不能含 .. 或空目录")
            }
        }
    }
    fun headers(value: String): Map<String, String> {
        if (value.isBlank()) return emptyMap()
        val lines = value.lines()
        if (lines.size > 5) throw PushFailure("附加请求头最多 5 个")
        val result = linkedMapOf<String, String>()
        val blocked = setOf("host", "content-length", "content-type", "connection", "transfer-encoding", "te", "trailer", "upgrade", "expect", "authorization", "proxy-authorization", "proxy-connection", "accept-encoding")
        lines.forEach { line ->
            val parts = line.split(':', limit = 2)
            val name = parts[0].trim()
            val lower = name.lowercase(java.util.Locale.ROOT)
            if (parts.size != 2 || !name.matches(Regex("[A-Za-z0-9!#$%&'*+.^_`|~-]+")) || lower in blocked || lower.startsWith("x-resound-") || result.keys.any { it.equals(name, true) } || parts[1].trim().any { it.code !in 32..126 }) throw PushFailure("附加请求头格式不正确或包含禁止的名称")
            result[name] = parts[1].trim()
        }
        return result
    }
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
    private fun url(value: String): HttpUrl = value.toHttpUrlOrNull()?.takeIf { it.username.isEmpty() && it.password.isEmpty() && it.fragment == null }
        ?: throw PushFailure("请填写有效地址，不能含账号、密码或片段")
    private fun https(value: String): HttpUrl = url(value).also { if (!it.isHttps) throw PushFailure("请填写有效的 HTTPS 地址") }
}
