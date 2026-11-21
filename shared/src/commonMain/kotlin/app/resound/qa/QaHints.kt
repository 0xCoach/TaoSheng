package app.resound.qa

/**
 * 有出处问答的问题解析：从自然语言里抽出时间窗、状态维度的检索提示。
 * 纯文本规则、平台无关；命中的提示用于过滤证据并告知模型用了哪些过滤条件。
 */
data class QaHints(
    /** 命中相对时间词时给出 [fromMs, toMs] 闭区间；null = 不限时间。 */
    val window: Pair<Long, Long>? = null,
    /** 命中状态词时给出要保留的提取项/决策/灵感状态名；null = 不限。 */
    val statuses: Set<String>? = null,
    /** 去掉时间/状态词后剩下的主题词（提示用，不强过滤）。 */
    val topicWords: List<String> = emptyList(),
) {
    val active get() = window != null || statuses != null
}

object QaRules {

    private const val DAY = 24L * 3600 * 1000

    /** 时间词表：前面的先匹配（"上周"先于"周前"这类歧义）。 */
    private val timeWords = listOf("今天", "昨天", "前天", "上周", "上礼拜", "这周", "本周",
        "上个月", "上月", "这个月", "本月", "最近", "这几天", "近期")
    private val stateWords = listOf("否决", "放弃", "不做了", "算了", "还没验证", "没验证", "未验证",
        "没研究", "还没研究", "已经做了", "做完了", "完成了", "推进了")

    /**
     * 抽取问题里的过滤提示。dayStart 传"今天零点"的毫秒（由调用方按时区算好传入）。
     * 识别：今天/昨天/前天/这周/上周/这个月/上个月/最近/N天前/N周前/N个月前。
     */
    fun parse(question: String, nowMs: Long, dayStart: Long): QaHints {
        val q = question
        fun days(n: Long) = n * DAY

        val window: Pair<Long, Long>? = when {
            q.contains("今天") -> dayStart to dayStart + DAY - 1
            q.contains("昨天") -> dayStart - DAY to dayStart - 1
            q.contains("前天") -> dayStart - 2 * DAY to dayStart - DAY - 1
            q.contains("上周") || q.contains("上礼拜") -> {
                val dow = ((dayStart / DAY + 4) % 7).toInt() // 1970-01-01 是周四
                val weekStart = dayStart - days(dow.toLong())
                weekStart - 7 * DAY to weekStart - 1
            }
            q.contains("这周") || q.contains("本周") -> {
                val dow = ((dayStart / DAY + 4) % 7).toInt()
                dayStart - days(dow.toLong()) to nowMs
            }
            q.contains("上个月") || q.contains("上月") -> dayStart - 31 * DAY to dayStart - 1 // 粗粒度按 31 天近似
            q.contains("这个月") || q.contains("本月") -> dayStart - 31 * DAY to nowMs
            else -> {
                val rel = Regex("(\\d{1,2})\\s*(天|周|个月|月)前").find(q)
                if (rel == null) {
                    if (timeWords.any { q.contains(it) }) dayStart - 7 * DAY to nowMs else null
                } else {
                    val n = rel.groupValues[1].toLong()
                    when (rel.groupValues[2]) {
                        "天" -> dayStart - days(n) to dayStart - days(n - 1) - 1
                        "周" -> dayStart - days(7 * n) to dayStart - days(7 * (n - 1)) - 1
                        else -> dayStart - days(31 * n) to dayStart - days(31 * (n - 1)) - 1
                    }
                }
            }
        }

        // 状态词：否决/放弃 → 已否决态；还没验证 → 未验证态；做了 → 完成态
        val statuses = mutableSetOf<String>()
        if (stateWords.take(4).any { q.contains(it) }) statuses += listOf("Rejected", "Abandoned", "Withdrawn")
        if (stateWords.slice(4..8).any { q.contains(it) }) statuses += listOf("Research", "Studying", "Active")
        if (stateWords.drop(9).any { q.contains(it) }) statuses += listOf("Done", "Verified")

        val stop = (timeWords + stateWords + listOf("\\d{1,2}\\s*(天|周|个月|月)前"))
            .joinToString("|") { if (it.startsWith("\\")) it else Regex.escape(it) }
        val topicWords = q.replace(Regex(stop), "")
            .split(Regex("[，。！？、\\s]+")).filter { it.length >= 2 }
        return QaHints(window, statuses.takeIf { it.isNotEmpty() }?.toSet(), topicWords)
    }

    /** 用提示过滤时间戳；window 为 null 一律放行。 */
    fun inWindow(hints: QaHints, at: Long): Boolean = hints.window?.let { at in it.first..it.second } ?: true

    /** 人类可读的过滤说明，给 UI/模型提示用了哪些过滤条件。 */
    fun describe(hints: QaHints): String = buildString {
        if (hints.window != null) append("已限定时间范围；")
        hints.statuses?.let { append("状态：${it.joinToString("/")}") }
    }.ifEmpty { "未加过滤" }
}
