package app.resound.decision

/** 一条决定的演变链：当前生效（或最后状态）+ 被它替代/否决过的历史版本，按时间排。 */
data class DecisionChain(
    /** 链首：仍生效的那条；全链都被否/撤回时是最新一条 */
    val current: DecisionRow,
    /** 历史版本：Superseded/Rejected/Withdrawn，按 decidedAt 从新到旧 */
    val history: List<DecisionRow>,
)

object DecisionChains {
    /**
     * 把决定编成演变链。
     * 成链依据：topicKey 相同 或 supersededBy 指针相连——措辞微调导致 key 漂移也靠指针串上。
     * 冲突：同 topicKey 多条 Active 各成链首，由界面标“待确认”，数据层绝不自动并掉。
     */
    fun build(decisions: List<DecisionRow>): List<DecisionChain> {
        val live = decisions.filter { !it.deleted }
        val indexOf = live.withIndex().associate { it.value.id to it.index }
        val parent = IntArray(live.size) { it }
        fun find(x: Int): Int {
            var r = x
            while (parent[r] != r) r = parent[r]
            return r
        }
        fun union(a: Int, b: Int) {
            val ra = find(a); val rb = find(b)
            if (ra != rb) parent[ra] = rb
        }
        // 同主题并组
        val byTopic = HashMap<String, Int>()
        live.forEachIndexed { i, d ->
            if (d.topicKey.isNotBlank()) {
                val prev = byTopic.put(d.topicKey, i)
                if (prev != null) union(i, prev)
            }
        }
        // supersededBy 指针并组（跨 key 的修订链）
        live.forEachIndexed { i, d -> d.supersededBy?.let { indexOf[it] }?.let { union(i, it) } }

        return live.indices.groupBy { find(it) }.values.map { idx ->
            val g = idx.map { live[it] }.sortedByDescending { it.decidedAt }
            val current = g.firstOrNull { it.status == DecisionStatus.Active.name } ?: g.first()
            DecisionChain(current, g.filter { it.id != current.id })
        }.sortedByDescending { it.current.decidedAt }
    }

    /** 潜在冲突：同主题仍有多条生效决定——给界面标“待确认”，不在数据层自动改状态。 */
    fun conflicts(decisions: List<DecisionRow>): List<List<DecisionRow>> =
        decisions.filter { !it.deleted && it.status == DecisionStatus.Active.name && it.topicKey.isNotBlank() }
            .groupBy { it.topicKey }.values.filter { it.size > 1 }
}
