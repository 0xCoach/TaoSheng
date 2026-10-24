package app.resound.capture

/** ML Kit 行级位置，保留所属文字块；纯数据解析可用合成结果测试。 */
internal data class OcrLine(val text: String, val left: Int, val top: Int, val right: Int, val bottom: Int, val block: Int = 0)
internal data class OcrLayout(val width: Int, val height: Int, val contentTop: Int, val contentBottom: Int, val imeTop: Int?)
/** text 为输入框（输入法上方最贴近的一段）；bubble 为聊天区最下面靠右的气泡（已发送的那句），没有为 null */
internal data class OcrInput(val text: String, val context: List<String>, val title: String?, val bubble: String? = null,
    /** 是不是从终端提示符那一行取的 */
    val prompt: Boolean = false)

internal object VoiceInputChrome {
    val send = setOf("发送", "Send", "send", "提交", "回车")
    val words = send + setOf("返回", "更多", "搜索", "取消", "确定", "语音输入", "按住说话", "切换到键盘", "表情", "Back", "More")
    private val time = Regex("[0-9]{1,2}[:：][0-9]{2}")
    // 终端键盘上方的按键行，如“Ctrl+C Ctrl+V”“ESC TAB ↑ ↓”：整行只有键名，不是原话
    private val keys = Regex("(?i)^(\\s*(ctrl|alt|shift|esc|tab|fn|home|end|pgup|pgdn|del|enter|[↑↓←→]|[a-z])(\\s*\\+\\s*[a-z0-9]+)?\\s*)+$")
    private val edge = "()（）[]【】<>《》|｜丨:：·•. ".toSet()
    fun keep(text: String): Boolean {
        val t = text.trim(); val core = t.trim { it in edge }
        return t.isNotBlank() && core.isNotEmpty() && t !in words && core !in words && !time.matches(core) && !keys.matches(t)
    }
}

internal object VoiceInputOcr {
    /** 按应用类型：聊天类上下文标“我 / 对方”，终端不取标题 */
    fun extract(source: List<OcrLine>, layout: OcrLayout, pkg: String): OcrInput? {
        val r = extract(source, layout, pkg in TERMINAL) ?: return null
        if (pkg in TERMINAL && (!r.prompt || terminalPlaceholder(r.text))) return null // 不记录空框占位提示
        if (pkg !in CHAT) return r.copy(title = r.title?.takeIf { pkg in TITLED })
        val keyboard = layout.imeTop?.takeIf { it in (layout.contentTop + 1)..layout.height } ?: (layout.height * .6).toInt()
        val inputTop = source.filter { VoiceInputChrome.keep(it.text) && joinLines(listOf(it.text.trim())).let { t -> r.text.contains(t) } && it.bottom <= keyboard }
            .minOfOrNull { it.top } ?: keyboard
        val above = source.filter { it.top >= titleBarBottom(layout) && it.bottom <= inputTop && VoiceInputChrome.keep(it.text) }
            .sortedWith(compareBy<OcrLine> { it.top }.thenBy { it.left })
        val title = r.title?.takeIf { pkg in TITLED }
        return r.copy(context = chatContext(above, layout.width, pkg, title).takeLast(20), title = title)
    }

    /** 可见聊天按屏幕从上到下排列；单聊用联系人名，群聊仍保留“对方”。 */
    fun chatContext(lines: List<OcrLine>, width: Int, pkg: String, title: String?): List<String> {
        val name = title?.takeIf { pkg in TITLED && !GROUP.containsMatchIn(it) }
        return sided(lines.sortedWith(compareBy<OcrLine> { it.top }.thenBy { it.left }), width, pkg)
            .map { if (name != null && it.startsWith("对方：")) name + it.removePrefix("对方") else it }
    }

    fun extract(source: List<OcrLine>, layout: OcrLayout, rejectTerminalPlaceholder: Boolean = false): OcrInput? {
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
            if (rejectTerminalPlaceholder && terminalPlaceholder(prompt.text.trim().replaceFirst(PROMPT, ""))) return null
            val same = lines.filter { it.block == prompt.block && it.top >= prompt.top }.sortedBy { it.top }
            val text = joinLines(same.map { it.text.trim() }).replaceFirst(PROMPT, "").trim()
            if (text.isNotBlank()) {
                val above = lines.filter { it.bottom <= prompt.top }
                val title = title(above, layout)
                return OcrInput(text.take(8000), above.filter { it.top >= titleBarBottom(layout) }.map { it.text.trim() }.takeLast(20), title, prompt = true)
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
        val title = title(above, layout)
        val text = joinLines(input.sortedWith(compareBy<OcrLine> { it.top }.thenBy { it.left }).map { it.text.trim() }).take(8000)
        return OcrInput(text, above.filter { it.top >= titleBarBottom(layout) }.map { it.text.trim() }.takeLast(20), title)
    }

    /** 从应用内容顶部起，固定取屏幕高度的 8%；键盘弹出不改变标题栏范围。 */
    fun titleBarBottom(layout: OcrLayout): Int = layout.contentTop + (layout.height * .08).toInt()

    fun title(lines: List<OcrLine>, layout: OcrLayout): String? = lines
        .filter { it.top >= layout.contentTop && it.bottom <= titleBarBottom(layout) &&
            it.bottom > it.top && it.left >= 0 && it.right <= layout.width && it.right > it.left }
        .mapNotNull { line -> cleanTitle(line.text)?.let { line to it } }
        .minWithOrNull(compareBy<Pair<OcrLine, String>> {
            kotlin.math.abs((it.first.left.toLong() + it.first.right) - layout.width)
        }.thenBy { it.first.top })?.second

    /** 只去掉左侧导航符和未读数，保留名字里的表情、数字以及末尾群人数。 */
    fun cleanTitle(text: String): String? {
        val t = text.trim().trimStart { it in "<く‹〈←❮《＜" || it.isWhitespace() }
            .replace(UNREAD_PREFIX, "").trim()
        val name = t.replace(GROUP, "")
        val points = name.codePoints().toArray()
        return t.takeIf {
            points.size in 1..20 && VoiceInputChrome.keep(t) &&
                points.any { Character.isLetter(it) || Character.getType(it) == Character.OTHER_SYMBOL.toInt() } &&
                name.none { it in CODE }
        }
    }
    fun looksLikeTitle(text: String): Boolean = cleanTitle(text) != null
    private val UNREAD_PREFIX = Regex("^(?:[(（][0-9]+[+]?[)）]\\s*|[0-9]+[+]?\\s+)")
    private const val CODE = "()（）+=/\\;{}[]\"'`$&|"
    /** 去掉光标“|”和首尾空白；只剩一两个字的残渣当没读到 */
    fun clean(text: String?): String? {
        val t = text?.trim()?.trim('|', '｜', '丨')?.trim() ?: return null
        return t.takeIf { it.count { c -> c.isLetterOrDigit() } >= 2 }
    }
    /** 聊天类：右边是我、左边是对方 */
    val CHAT = setOf("com.tencent.mm", "com.openai.chatgpt", "com.google.android.apps.bard", "com.google.android.googlequicksearchbox", "com.twitter.android")
    /** 终端类：没有标题 */
    val TERMINAL = setOf("com.netease.uuremote", "com.termux")
    /** 谁说的：离右边更近的是我；离左边更近的是对方；两边一样近（整行铺满）在 AI 应用里是 AI 的回答，算对方，其他应用不标 */
    fun side(l: OcrLine, width: Int, pkg: String?): String {
        val dl = l.left; val dr = width - l.right; val gap = width * .05
        return when { dl - dr > gap -> "我"; dr - dl > gap -> "对方"; pkg in AI -> "对方"; else -> "" }
    }
    val AI = setOf("com.openai.chatgpt", "com.google.android.apps.bard", "com.google.android.googlequicksearchbox")
    /** 只有微信的标题是联系人名字，其他应用的标题（日期、会话名）不取 */
    val TITLED = setOf("com.tencent.mm")
    /** 群聊标题后面带人数，如“周五团建(12)” */
    val GROUP = Regex("[(（][0-9]+[)）]\\s*$")
    /** 同一文字块内紧邻的折行才合并；同一人连续发送的不同气泡各占一行。 */
    fun sided(lines: List<OcrLine>, width: Int, pkg: String? = null): List<String> {
        val out = mutableListOf<Pair<String, String>>()
        var previous: OcrLine? = null
        for (l in lines) {
            val who = side(l, width, pkg)
            val last = out.lastOrNull()
            val prev = previous
            val wrapped = prev != null && prev.block == l.block &&
                l.top - prev.bottom in 0..(maxOf(prev.bottom - prev.top, l.bottom - l.top) * .65).toInt()
            if (last != null && last.first == who && who.isNotEmpty() && wrapped) out[out.lastIndex] = who to joinLines(listOf(last.second, l.text.trim()))
            else out += who to l.text.trim()
            previous = l
        }
        return out.map { (w, t) -> if (w.isEmpty()) t else "$w：$t" }
    }
    /** Codex 空输入框占位符，不是用户输入；仅匹配整句，保留包含此词的真实指令。 */
    fun terminalPlaceholder(text: String): Boolean {
        val t = text.trim().trimEnd('|', '｜', '丨').trim()
        return CODEX_PLACEHOLDER.matches(t)
    }
    // 兼容真机 OCR 的空格丢失、Ask -> As、to -> 七o、i -> 1；只匹配整句。
    private val CODEX_PLACEHOLDER = Regex("(?i)^as[k]?\\s*codex(?:\\s*(?:to|七o)\\s*do\\s*anyth[i1]ng)?[.。…?？\\s]*$")
    private val PROMPT = Regex("^[>》❯$#➜»›＞]+\\s*")
    /** 输入框自动折行的几行接回一句：中文直接接上，英文之间补空格 */
    fun joinLines(parts: List<String>) = parts.filter(String::isNotBlank).fold("") { acc, p ->
        if (acc.isEmpty()) p else if (acc.last().isLetterOrDigit() && acc.last().code < 128 && p.first().isLetterOrDigit() && p.first().code < 128) "$acc $p" else acc + p
    }

    /** 输入框已清空时也保留聊天快照，不能因改读已发送气泡就丢掉上下文。 */
    fun screenshot(source: List<OcrLine>, layout: OcrLayout, pkg: String): OcrInput? {
        val input = extract(source, layout, pkg)
        if (pkg !in CHAT) return input
        val bubbleLines = lastRightBubbleLines(source, layout)
        val bubble = joinLines(bubbleLines.map { it.text.trim() }).ifBlank { null }
        if (input != null) return input.copy(bubble = bubble)
        val keyboard = layout.imeTop?.takeIf { it in (layout.contentTop + 1)..layout.height }
            ?: (layout.height * .6).toInt()
        val beforeBottom = bubbleLines.minOfOrNull { it.top } ?: minOf(keyboard, layout.contentBottom)
        val title = title(source, layout)?.takeIf { pkg in TITLED }
        val before = source.filter { it.top >= titleBarBottom(layout) && it.bottom <= beforeBottom &&
            VoiceInputChrome.keep(it.text) }
        return OcrInput("", chatContext(before, layout.width, pkg, title).takeLast(20), title, bubble)
    }

    /** 聊天区最下面“我”这一侧的气泡（离右边比离左边近），取最靠下的一组相邻行 */
    fun lastRightBubble(source: List<OcrLine>, layout: OcrLayout): String? =
        joinLines(lastRightBubbleLines(source, layout).map { it.text.trim() }).ifBlank { null }

    private fun lastRightBubbleLines(source: List<OcrLine>, layout: OcrLayout): List<OcrLine> {
        val keyboard = layout.imeTop?.takeIf { it in (layout.contentTop + 1)..layout.height } ?: (layout.height * .6).toInt()
        val bottom = minOf(keyboard, layout.contentBottom)
        val right = source.filter { it.top >= layout.contentTop + layout.height / 10 && it.bottom <= bottom &&
            side(it, layout.width, null) == "我" && VoiceInputChrome.keep(it.text) }.sortedBy { it.top }
        val last = right.lastOrNull() ?: return emptyList()
        val group = mutableListOf(last)
        for (c in right.dropLast(1).asReversed()) {
            val first = group.first()
            if (c.block == last.block || first.top - c.bottom <= (last.bottom - last.top) * .65) group.add(0, c) else break
        }
        return group
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

/** 截屏识字后按热词纠错：“Du远程”“叮远程”→“UU远程”。只纠和热词几乎一样的片段（长度差不超过 1、错字不超过约四分之一） */
internal object VoiceInputHotwords {
    /** 负责人常用应用的名字，热词表里没有也按这些纠 */
    val BUILT_IN = listOf("UU远程", "ChatGPT", "Gemini", "Claude", "Codex")
    fun correct(text: String, words: List<String>): String {
        var out = text
        for (w in (words + BUILT_IN).map(String::trim).filter { it.length >= 3 }.distinct().sortedByDescending { it.length }) {
            out = replaceNear(out, w)
        }
        return out
    }
    private fun replaceNear(text: String, word: String): String {
        val limit = maxOf(1, word.length / 4)
        val sb = StringBuilder(); var i = 0
        while (i < text.length) {
            // 几种长度里挑最像的一段：错字最少，同样少时取和热词一样长的
            var best: Int? = null; var bestScore = Int.MAX_VALUE
            for (len in maxOf(1, word.length - 1)..(word.length + 1)) {
                if (i + len > text.length) break
                val part = text.substring(i, i + len)
                val anchor = part.last().equals(word.last(), true) || part.first().equals(word.first(), true)
                val d = distance(part.lowercase(), word.lowercase())
                if (!anchor || d > limit) continue
                val score = d * 10 + kotlin.math.abs(len - word.length)
                if (score < bestScore) { bestScore = score; best = len }
            }
            if (best != null) { sb.append(word); i += best } else { sb.append(text[i]); i++ }
        }
        return sb.toString()
    }
    private fun distance(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        for (i in a.indices) {
            val cur = IntArray(b.length + 1); cur[0] = i + 1
            // 英文和汉字互认不算“认错一个字”（代价 2），避免把紧挨着的汉字吞进英文热词
            for (j in b.indices) cur[j + 1] = minOf(cur[j] + 1, prev[j + 1] + 1,
                prev[j] + if (a[i] == b[j]) 0 else if ((a[i].code < 128) != (b[j].code < 128)) 2 else 1)
            prev = cur
        }
        return prev[b.length]
    }
}
