package app.resound.qa

/**
 * 问答证据守卫：校验模型回答里的 [#n] 出处编号都在本次取证的范围内，
 * 越界编号是模型编造，清掉并计数；另给出证据强度分级，供界面如实标注。
 * 纯文本规则、平台无关。
 */
object EvidenceGuard {
    private val marker = Regex("\\[#(\\d+)\\]")

    data class Checked(val text: String, val dropped: Int)

    /** 清掉指向不存在出处的引用标记；返回清理后文本与被清个数。 */
    fun clean(text: String, sourceCount: Int): Checked {
        var dropped = 0
        val out = marker.replace(text) { m ->
            val n = m.groupValues[1].toIntOrNull()
            if (n == null || n !in 1..sourceCount) { dropped++; "" } else m.value
        }
        return Checked(out, dropped)
    }

    /** 回答正文里是否还有有效出处标记（没有有效引用时界面可提示"未标出处"）。 */
    fun hasCitation(text: String, sourceCount: Int): Boolean =
        marker.findAll(text).any { it.groupValues[1].toIntOrNull() in 1..sourceCount }

    /**
     * 证据强度：0=无证据，1=单薄（仅一两条原话片段），2=尚可，3=强。
     * 事件级来源（已经过整理）与带价值加成的来源加权更高。
     */
    fun strength(total: Int, eventSources: Int, boostedSources: Int): Int = when {
        total == 0 -> 0
        total >= 4 || boostedSources > 0 || eventSources >= 2 -> 3
        total >= 2 || eventSources >= 1 -> 2
        else -> 1
    }
}
