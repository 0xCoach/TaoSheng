package app.resound.net

/** single 只省略旧分句，不保证每包都是新句；确定句去重，临时尾句可被修订。 */
internal class NetDoubaoIncremental {
    // 同起点可能存在重叠说话人，不能只保留一条；整组替换也允许修订 speaker_id。
    private val stable = mutableMapOf<Long, List<Obj>>()
    private var pending = emptyList<Obj>()

    fun accept(body: Obj, final: Boolean): Obj {
        val result = body.optJSONObject("result")
        val array = result?.optJSONArray("utterances")
        val received = array?.let { list -> (0 until list.length()).map { list.getJSONObject(it) } }.orEmpty()
        if (received.isNotEmpty()) {
            val groups = received.groupBy { it.optLong("start_time", 0) }
            pending = groups.values.filter { group -> group.any { !it.optBoolean("definite", false) } }.flatten()
            groups.filterValues { group -> group.all { it.optBoolean("definite", false) } }
                .forEach { (start, lines) -> stable[start] = lines }
        }
        if (!final) return body
        // 最后一包可能只确认结束，仍要提交最近一版尾句；非终态从不对外落库。
        pending.groupBy { it.optLong("start_time", 0) }.forEach { (start, lines) -> stable[start] = lines }
        pending = emptyList()
        if (stable.isEmpty()) return body // 空音频，或无分句的旧式单包文本。
        val lines = stable.toList().sortedBy { it.first }.flatMap { it.second }
        return Obj(body.toString()).put("result", Obj(result?.toString() ?: "{}").apply {
            put("utterances", Arr(lines))
            put("text", lines.joinToString("") { it.optString("text", "") })
        })
    }
}
