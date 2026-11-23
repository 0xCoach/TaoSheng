package app.resound.transcribe.local

import app.resound.transcribe.AsrSentence

/**
 * 卡 228：本机识别出结果前的通用兜底，不分 CPU / NPU，不分用户。
 *
 * 断行规则（换人、停顿 ≥0.8 秒、句末标点）在远场、小声、识别零散时会切出一行一两个字。
 * 同一个人连着的两行，前一行没说完（不以句末标点结尾），且其中一行很短或中间根本没停顿，就并成一行。
 * 句末标点断开的仍然分行；不同的人永远不合并、不改说话人；合并后一行最多 [MAX_CHARS] 个字。
 * 试过把夹在中间的一两个字“归回”前后那个人：AliMeeting 上说话人正确时，被归回的 16 行全是别人插的“嗯/对”，放弃。
 */
internal object ShortLines {
    /** 少于这么多字算“很短” */
    const val SHORT = 4
    /** 同一个人两行隔得再久就不并了 */
    const val MAX_GAP_MS = 5_000L
    /** 和 TimedSentences 的停顿断行一致：短于它说明两行之间其实没停 */
    const val PAUSE_MS = 800L
    const val MAX_CHARS = 60
    private val sentenceEnd = setOf('。', '！', '？', '!', '?', '；', ';', '.')

    private fun letters(text: String) = text.count(Char::isLetterOrDigit)
    private fun finished(text: String) = text.trimEnd().lastOrNull()?.let { it in sentenceEnd } ?: false
    private fun cjk(c: Char) = Character.UnicodeScript.of(c.code) == Character.UnicodeScript.HAN

    fun merge(lines: List<AsrSentence>): List<AsrSentence> {
        val out = mutableListOf<AsrSentence>()
        for (line in lines) {
            val last = out.lastOrNull()
            val gap = if (last == null) 0L else line.startMillis - last.endMillis
            val join = last != null && last.speakerTag == line.speakerTag && !finished(last.text) &&
                gap <= MAX_GAP_MS && letters(last.text) + letters(line.text) <= MAX_CHARS &&
                (gap < PAUSE_MS || letters(last.text) < SHORT || letters(line.text) < SHORT)
            if (!join) { out += line; continue }
            out[out.lastIndex] = last!!.copy(endMillis = maxOf(last.endMillis, line.endMillis),
                text = last.text.trimEnd() + separator(last.text.trimEnd(), line.text.trimStart(), gap) + line.text.trimStart())
        }
        return out
    }

    /** 中间停过就用逗号（中文）或空格（外文）隔开；没停就直接接上 */
    private fun separator(left: String, right: String, gap: Long): String {
        val a = left.lastOrNull() ?: return ""; val b = right.firstOrNull() ?: return ""
        if (!a.isLetterOrDigit()) return ""
        return if (!cjk(a) && !cjk(b)) " " else if (gap < PAUSE_MS) "" else "，"
    }
}
