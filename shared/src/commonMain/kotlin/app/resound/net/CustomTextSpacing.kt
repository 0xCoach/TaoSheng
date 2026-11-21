package app.resound.net

/** 仅清理汉字间、中文标点旁的空白；保留英文单词及中英混排间距。 */
fun cleanCustomText(text: String): String {
    fun han(c: Char) = c in '\u3400'..'\u9fff' || c in '\uf900'..'\ufaff'
    val punctuation = "，。！？；：、…（）【】《》“”‘’"
    val trimmed = text.trim()
    return Regex("\\s+").replace(trimmed) { match ->
        val left = trimmed.getOrNull(match.range.first - 1)
        val right = trimmed.getOrNull(match.range.last + 1)
        if (left != null && right != null && (han(left) && han(right) || left in punctuation || right in punctuation)) "" else match.value
    }
}
