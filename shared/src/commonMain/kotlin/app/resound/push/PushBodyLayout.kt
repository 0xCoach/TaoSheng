package app.resound.push

/** 只在消息发送边界调用；通用正文、重试快照和文档存储不带通道专用换行。format 是实际发送格式。 */
object PushBodyLayout {
    fun convert(kind: PushKind, format: PushFormat, body: String): String = when (kind) {
        // 官方明确区分 txt 的 \n 和 Markdown 的 <br/>，不能直接替换所有换行（会破坏列表）。
        // https://www.pushplus.plus/doc/help/line.html
        PushKind.PushPlus -> if (format == PushFormat.Markdown) hardBreaks(body, "<br/>") else body

        // desp 始终按 Markdown 展示，包括用户选择的纯文本；不支持 HTML。
        // https://sct.ftqq.com/docs/getting-started/faq/
        // 硬换行：https://spec.commonmark.org/0.31.2/#hard-line-breaks
        PushKind.ServerChan -> hardBreaks(body, "  ")

        // 行尾两个空格加真实换行；JSON 选项沿用发送层的 Markdown 回退。
        // https://open.dingtalk.com/document/orgapp/faq-robot
        // https://github.com/DingTalk-Real-AI/dingtalk-workspace-cli/blob/main/skills/mono/references/products/chat.md
        PushKind.DingTalk -> if (format == PushFormat.Markdown) hardBreaks(body, "  ") else body

        // Webhook 没有统一客户端；text/markdown 按标准硬换行，JSON 字符串和纯文本原样保留。
        // https://spec.commonmark.org/0.31.2/#hard-line-breaks
        PushKind.Webhook -> if (format == PushFormat.Markdown) hardBreaks(body, "  ") else body

        // 企业微信现用的 markdown 子集示例使用原生 \n，不注入不支持的 HTML。
        // https://developer.work.weixin.qq.com/document/path/91770
        PushKind.WeCom -> body
        // 当前是 interactive 卡片的 markdown 组件，不是 post 消息里的 md 标签。
        // https://open.feishu.cn/document/common-capabilities/message-card/message-cards-content/using-markdown-tags
        PushKind.Feishu -> body
        // Telegram HTML 模式仍用 \n；不支持 <br>，转义和截断继续由发送层负责。
        // https://core.telegram.org/bots/api#html-style
        PushKind.Telegram -> body
        // SMTP 已逐行生成 HTML 的 <br>/块元素；文档通道不走消息转换。
        PushKind.Email, PushKind.Notion, PushKind.MarkdownFolder -> body
    }

    private val heading = Regex("^ {0,3}#{1,6}(?:[ \\t]+|$)")
    private val fence = Regex("^ {0,3}(`{3,}|~{3,})(.*)$")
    private val rule = Regex("^ {0,3}(?:(?:\\*[ \\t]*){3,}|(?:-[ \\t]*){3,}|(?:_[ \\t]*){3,}|=+[ \\t]*)$")

    private fun hardBreaks(body: String, marker: String): String {
        val lines = body.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        var fenceChar: Char? = null
        var fenceSize = 0
        return lines.mapIndexed { index, line ->
            val match = fence.matchEntire(line)
            val inCode = fenceChar != null
            if (match != null) {
                val ticks = match.groupValues[1]
                if (fenceChar == null) { fenceChar = ticks[0]; fenceSize = ticks.length }
                else if (ticks[0] == fenceChar && ticks.length >= fenceSize && match.groupValues[2].isBlank()) fenceChar = null
            }
            // 空行仍是段落边界；标题、表格、分隔线、代码块自身负责换行，列表前缀不变。
            if (index == lines.lastIndex || line.isBlank() || lines[index + 1].isBlank() ||
                inCode || match != null || heading.containsMatchIn(line) || rule.matches(line) ||
                line.trimStart().startsWith('|') || line.endsWith("  ") || line.endsWith('\\') ||
                line.trimEnd().endsWith("<br/>") || line.trimEnd().endsWith("<br>") ||
                rule.matches(lines[index + 1])) line
            else line + marker
        }.joinToString("\n")
    }
}
