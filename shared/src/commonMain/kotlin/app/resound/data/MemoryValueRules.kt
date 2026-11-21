package app.resound.data

/**
 * 记忆价值分层：条目存"内在分"（提取时的自身属性），父事件星标/重点加成在查询时另算
 * （用户随时改星标，存库会过期）。整理输入与问答证据按有效分排序，高分排前。
 */
object MemoryValueRules {
    const val TIER_HIGH = 70
    const val TIER_MID = 40
    const val PARENT_STAR_BONUS = 20
    const val PARENT_IMPORTANT_BONUS = 10
    const val EVENT_STAR_BONUS = 40
    const val EVENT_IMPORTANT_BONUS = 25

    /** 条目内在分：类别、承诺对象、期限、人名、来源。AI 提取（digest）低于用户主动记录。 */
    fun itemScore(category: String?, kind: String?, dueAt: Long?, who: String?, origin: String?): Int {
        var score = when (category) {
            "约定" -> 45
            "待办" -> 40
            "备忘" -> 25
            else -> 15
        }
        if (kind == "我答应别人" || kind == "别人答应我") score += 15
        if (dueAt != null) score += 15
        if (!who.isNullOrBlank()) score += 10
        if (origin != null && origin != "digest") score += 10
        return score.coerceAtMost(100)
    }

    /** 查询时有效分：内在分 + 父事件实时星标/重点加成（与 ReviewDao 排序表达式一致）。 */
    fun effectiveItemScore(base: Int, eventStarred: Boolean, eventImportant: Boolean): Int =
        (base + (if (eventStarred) PARENT_STAR_BONUS else 0) +
            (if (eventImportant) PARENT_IMPORTANT_BONUS else 0)).coerceAtMost(100)

    /** 事件检索加成：星标/重点的事件原话级证据排前（RecordSearch 的 valueBonus）。 */
    fun eventBonus(starred: Boolean, important: Boolean): Int =
        (if (starred) EVENT_STAR_BONUS else 0) + (if (important) EVENT_IMPORTANT_BONUS else 0)

    /** 0=普通 1=低 2=中 3=高 */
    fun tier(score: Int): Int = if (score >= TIER_HIGH) 3 else if (score >= TIER_MID) 2 else if (score > 0) 1 else 0
}
