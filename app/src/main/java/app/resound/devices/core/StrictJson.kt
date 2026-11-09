package app.resound.devices.core

import org.json.JSONTokener

/** Android 的 JSONTokener 容忍单引号、注释、重复键等；在读卡前先拒绝这些非标准写法。 */
internal fun validateCardJson(text: String) = StrictJson(text).validate()

private class StrictJson(private val text: String) {
    private var at = 0
    fun validate() { value(0); whitespace(); require(at == text.length) { "说明卡：JSON 后有多余内容（偏移 $at）" } }
    private fun bad(): Nothing = throw IllegalArgumentException("说明卡：JSON 格式错误（偏移 $at）")
    private fun whitespace() { while (at < text.length && text[at] in " \t\r\n") at++ }
    private fun take(char: Char): Boolean { whitespace(); if (at < text.length && text[at] == char) { at++; return true }; return false }
    private fun value(depth: Int) {
        if (depth > 32) throw IllegalArgumentException("说明卡：JSON 嵌套不能超过 32 层")
        whitespace()
        when (text.getOrNull(at)) {
            '{' -> {
                at++; val keys = mutableSetOf<String>()
                if (take('}')) return
                do {
                    whitespace(); val key = string()
                    require(keys.add(key)) { "说明卡.$key：JSON 字段重复" }
                    if (!take(':')) bad()
                    value(depth + 1)
                } while (take(','))
                if (!take('}')) bad()
            }
            '[' -> { at++; if (take(']')) return; do { value(depth + 1) } while (take(',')); if (!take(']')) bad() }
            '"' -> string()
            't' -> literal("true")
            'f' -> literal("false")
            'n' -> literal("null")
            '-', in '0'..'9' -> {
                val match = NUMBER.find(text, at)?.takeIf { it.range.first == at } ?: bad()
                at = match.range.last + 1
            }
            else -> bad()
        }
    }
    private fun literal(value: String) { if (!text.startsWith(value, at)) bad(); at += value.length }
    private fun string(): String {
        if (text.getOrNull(at) != '"') bad()
        val start = at++
        while (at < text.length) {
            val char = text[at++]
            if (char == '"') return JSONTokener(text.substring(start, at)).nextValue() as String
            if (char < ' ') bad()
            if (char == '\\') {
                when (text.getOrNull(at++)) {
                    '"', '\\', '/', 'b', 'f', 'n', 'r', 't' -> Unit
                    'u' -> repeat(4) { if (text.getOrNull(at++)?.let { it in "0123456789abcdefABCDEF" } != true) bad() }
                    else -> bad()
                }
            }
        }
        bad()
    }
    companion object { private val NUMBER = Regex("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?") }
}
