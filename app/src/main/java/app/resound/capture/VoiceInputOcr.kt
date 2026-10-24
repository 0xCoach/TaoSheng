package app.resound.capture

/** ML Kit 行级位置，保留所属文字块；纯数据解析可用合成结果测试。 */
internal data class OcrLine(val text: String, val left: Int, val top: Int, val right: Int, val bottom: Int, val block: Int = 0)
internal data class OcrLayout(val width: Int, val height: Int, val contentTop: Int, val contentBottom: Int, val imeTop: Int?)
/** text 为输入框（输入法上方最贴近的一段）；bubble 为聊天区最下面靠右的气泡（已发送的那句），没有为 null */
internal data class OcrInput(val text: String, val context: List<String>, val title: String?, val bubble: String? = null)

internal object VoiceInputChrome {
    val send = setOf("发送", "Send", "send", "提交", "回车")
    val words = send + setOf("返回", "更多", "搜索", "取消", "确定", "语音输入", "按住说话", "切换到键盘", "表情", "Back", "More")
    private val time = Regex("[0-9]{1,2}[:：][0-9]{2}")
    // 终端键盘上方的按键行，如“Ctrl+C Ctrl+V”“ESC TAB ↑ ↓”：整行只有键名，不是原话
    private val keys = Regex("(?i)^(\\s*(ctrl|alt|shift|esc|tab|fn|home|end|pgup|pgdn|del|enter|[↑↓←→]|[a-z])(\\s*\\+\\s*[a-z0-9]+)?\\s*)+$")
    fun keep(text: String) = text.isNotBlank() && text.trim() !in words && !time.matches(text.trim()) && !keys.matches(text.trim())
}

internal object VoiceInputOcr {
    fun extract(source: List<OcrLine>, layout: OcrLayout): OcrInput? {
        if (layout.width <= 0 || layout.height <= 0) return null
        val keyboard = layout.imeTop?.takeIf { it in (layout.contentTop + 1)..layout.height }
            ?: (layout.height * .6).toInt()
        val bottom = minOf(keyboard, layout.contentBottom)
        val lines = source.filter { it.left >= 0 && it.right <= layout.width && it.right > it.left &&
            it.top >= layout.contentTop && it.bottom <= bottom && it.bottom > it.top && VoiceInputChrome.keep(it.text) }
            .sortedWith(compareBy<OcrLine> { it.top }.thenBy { it.left })
        // 终端：输入法上方往往是状态行和按键行，你正在输入的是最后一个以提示符开头的那一行（> $ ❯ 等）
        val prompt = lines.filter { it.top >= keyboard - layout.height * 2 / 5 && PROMPT.containsMatchIn(it.text.trim()) }.maxByOrNull { it.bottom }
        if (prompt != null) {
            val same = lines.filter { it.block == prompt.block && it.top >= prompt.top }.sortedBy { it.top }
            val text = joinLines(same.map { it.text.trim() }).replaceFirst(PROMPT, "").trim()
            if (text.isNotBlank()) {
                val above = lines.filter { it.bottom <= prompt.top }
                val titleLine = above.firstOrNull { it.bottom <= layout.contentTop + layout.height / 8 && it.text.trim().length <= 80 && looksLikeTitle(it.text) }
                return OcrInput(text.take(8000), above.filter { it !== titleLine }.map { it.text.trim() }.takeLast(20), titleLine?.text?.trim())
            }
        }
        val last = lines.maxByOrNull { it.bottom } ?: return null
        val lineHeight = last.bottom - last.top
        // 输入法上方必须确有文字，不能把远处的聊天气泡当作输入框。
        if (bottom - last.bottom > maxOf(lineHeight * 3, layout.height / 20)) return null
        val input = mutableListOf(last)
        for (candidate in lines.filter { it !== last }.asReversed()) {
            val first = input.minBy { it.top }
            val gap = first.top - candidate.bottom
            val aligned = candidate.left < first.right && candidate.right > first.left
            val close = gap <= maxOf(lineHeight, candidate.bottom - candidate.top) * .65
            if (aligned && close && (candidate.block == last.block || gap >= 0) &&
                last.bottom - candidate.top <= layout.height / 4) input.add(candidate)
            else if (candidate.bottom <= first.top) break
        }
        val inputTop = input.minOf { it.top }
        val above = lines.filter { it.bottom <= inputTop && it !in input }
        val titleLine = above.firstOrNull { it.bottom <= layout.contentTop + layout.height / 8 && it.text.trim().length <= 80 && looksLikeTitle(it.text) }
        val text = joinLines(input.sortedWith(compareBy<OcrLine> { it.top }.thenBy { it.left }).map { it.text.trim() }).take(8000)
        return OcrInput(text, above.filter { it !== titleLine }.map { it.text.trim() }.takeLast(20), titleLine?.text?.trim())
    }

    /** 标题至少两个字、要有正常文字（返回箭头“く”“<”这类不算） */
    fun looksLikeTitle(text: String) = text.trim().count { it.isLetterOrDigit() } >= 2
    private val PROMPT = Regex("^[>》❯$#➜»›]+\\s*")
    /** 输入框自动折行的几行接回一句：中文直接接上，英文之间补空格 */
    fun joinLines(parts: List<String>) = parts.filter(String::isNotBlank).fold("") { acc, p ->
        if (acc.isEmpty()) p else if (acc.last().isLetterOrDigit() && acc.last().code < 128 && p.first().isLetterOrDigit() && p.first().code < 128) "$acc $p" else acc + p
    }

    /** 聊天区最下面靠右的气泡：文字中心在宽度 55% 以右，取最靠下的一组相邻行 */
    fun lastRightBubble(source: List<OcrLine>, layout: OcrLayout): String? {
        val keyboard = layout.imeTop?.takeIf { it in (layout.contentTop + 1)..layout.height } ?: (layout.height * .6).toInt()
        val bottom = minOf(keyboard, layout.contentBottom)
        val right = source.filter { it.top >= layout.contentTop + layout.height / 10 && it.bottom <= bottom &&
            (it.left + it.right) / 2 > layout.width * .55 && VoiceInputChrome.keep(it.text) }.sortedBy { it.top }
        val last = right.lastOrNull() ?: return null
        val group = mutableListOf(last)
        for (c in right.dropLast(1).asReversed()) {
            val first = group.first()
            if (c.block == last.block || first.top - c.bottom <= (last.bottom - last.top) * .65) group.add(0, c) else break
        }
        return group.joinToString("") { it.text.trim() }.ifBlank { null }
    }

    /** 检查应用内容区域，状态栏亮点不能使被保护的黑屏通过。 */
    fun black(width: Int, height: Int, top: Int, bottom: Int, pixel: (Int, Int) -> Int): Boolean {
        if (width <= 0 || bottom <= top || height <= 0) return true
        for (y in top.coerceAtLeast(0) until bottom.coerceAtMost(height) step maxOf(1, (bottom - top) / 60)) {
            for (x in 0 until width step maxOf(1, width / 60)) {
                val rgb = pixel(x, y)
                if ((rgb shr 16 and 255) > 16 || (rgb shr 8 and 255) > 16 || (rgb and 255) > 16) return false
            }
        }
        return true
    }
}
