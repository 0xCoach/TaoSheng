package app.resound.push

object PushFieldRules {
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
        PushKind.MarkdownFolder -> listOf(f("url", "WebDAV 地址", "默认 https://dav.jianguoyun.com/dav/"), f("account", "账号", "填坚果云账号即可，Obsidian 用 Remotely Save 等插件同步这个文件夹"), f("password", "应用密码", "坚果云安全选项中添加应用", true), f("folder", "文件夹", "如 Obsidian/记忆"))
    }
    private fun f(key: String, label: String, hint: String, secret: Boolean = false) = PushField(key, label, hint, secret)
    fun default(values: Map<String, String>, key: String, fallback: String) = values[key]?.takeIf { it.isNotBlank() } ?: fallback
    fun headers(value: String): Map<String, String> {
        if (value.isBlank()) return emptyMap()
        val lines = value.lines()
        if (lines.size > 5) throw IllegalArgumentException("附加请求头最多 5 个")
        val result = linkedMapOf<String, String>()
        val blocked = setOf("host", "content-length", "content-type", "connection", "transfer-encoding", "te", "trailer", "upgrade", "expect", "authorization", "proxy-authorization", "proxy-connection", "accept-encoding")
        lines.forEach { line ->
            val parts = line.split(':', limit = 2)
            val name = parts[0].trim()
            val lower = name.lowercase()
            if (parts.size != 2 || !name.matches(Regex("[A-Za-z0-9!#$%&'*+.^_`|~-]+")) || lower in blocked || lower.startsWith("x-resound-") || result.keys.any { it.equals(name, true) } || parts[1].trim().any { it.code !in 32..126 }) throw IllegalArgumentException("附加请求头格式不正确或包含禁止的名称")
            result[name] = parts[1].trim()
        }
        return result
    }
}
