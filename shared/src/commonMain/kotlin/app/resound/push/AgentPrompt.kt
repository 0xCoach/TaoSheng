package app.resound.push

/**
 * “复制给智能体”：把同步文件夹的账号、应用密码、文件夹写成一段话，用户粘贴给能运行命令的智能体
 * （Claude Code、Codex 等），让它只读这个文件夹。怎么读写在文件夹的 README.md 里，随同步更新，
 * 这里只给“钥匙和入口”。
 */
object AgentPrompt {
    const val NUTSTORE_HOST = "dav.jianguoyun.com"

    /** S3 不是 WebDAV，暂不支持；返回 null 时界面不显示按钮 */
    fun text(url: String, account: String, password: String, folder: String, s3: Boolean = false): String? {
        if (s3 || account.isBlank() || password.isBlank()) return null
        val cleanFolder = folder.trim().trim('/').ifBlank { return null }
        val nutstore = url.isBlank() || url.contains(NUTSTORE_HOST, ignoreCase = true)
        val where = if (nutstore) "在我坚果云根目录的 $cleanFolder 文件夹里" else "在我的 WebDAV 网盘的 $cleanFolder 文件夹里"
        val keys = buildList {
            if (!nutstore) add("- WebDAV 地址：${url.trim()}")
            add("- ${if (nutstore) "坚果云账号" else "账号"}：$account")
            add("- 应用密码：$password")
        }.joinToString("\n")
        val how = if (nutstore) "通过坚果云 WebDAV 只读访问" else "通过 WebDAV 只读访问（HTTP 基本认证）"
        return """
            |这是我的「声声不息」个人记忆库，$where，里面有我每天的经历、待办、想法和决定。以后我问起“我之前……”“我答应过谁……”“上周做了什么”这类问题，请到这里查。
            |
            |$keys
            |
            |$how，先读文件夹里的 README.md，按里面的说明找文件。只读，不要上传、修改或删除；内容只用来回答我，不要发到别处。
            |
            |现在先读一次，告诉我记忆库里最新是哪一天，确认能用；然后把这些信息记到你的长期配置里，以后不用我再发。
        """.trimMargin()
    }
}
