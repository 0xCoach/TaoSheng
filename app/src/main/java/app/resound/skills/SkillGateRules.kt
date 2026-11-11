package app.resound.skills

/** 门槛只接收原话的基本数据；时间是同一时间轴上的毫秒，由调用方提供。 */
internal data class SkillGateLine(val startAt: Long, val speaker: String, val voiceInput: Boolean)

internal data class SkillGateStats(val speakers: Int, val spanMillis: Long, val lines: Int)

/** 不依赖数据库、时钟或平台；调用前和逐卡复核共用同一口径。 */
internal object SkillGateRules {
    fun validate(gate: SkillGate) {
        require(gate.minSpeakers in 1..20) { "门槛人数须为 1～20" }
        require(gate.minSpanMinutes in 0..600) { "门槛分钟数须为 0～600" }
        require(gate.minLines in 0..5000) { "门槛句数须为 0～5000" }
    }

    fun stats(gate: SkillGate, lines: List<SkillGateLine>): SkillGateStats {
        val counted = lines.filter { gate.countVoiceInput || !it.voiceInput }
        val people = counted.map { it.speaker }.filter { it != "待确认" }.distinct().size
        val span = if (counted.isEmpty()) 0L else counted.maxOf { it.startAt } - counted.minOf { it.startAt }
        return SkillGateStats(people, span, counted.size)
    }

    /** 顺序固定：时间、人数、句数；只返回第一个不达标项。 */
    fun failure(gate: SkillGate?, lines: List<SkillGateLine>, card: Boolean = false): String? {
        if (gate == null) return null
        val stats = stats(gate, lines)
        val subject = if (card) "整理出的讨论" else "讨论"
        val reason = when {
            stats.spanMillis < gate.minSpanMinutes * 60_000L -> "${subject}不到 ${gate.minSpanMinutes} 分钟"
            stats.speakers < gate.minSpeakers -> "${if (card) "整理出的内容" else ""}只有 ${stats.speakers} 个人在说"
            stats.lines < gate.minLines -> "${if (card) "整理出的内容中" else ""}原话只有 ${stats.lines} 句"
            else -> return null
        }
        return "没出卡：$reason"
    }

    /** 按句子的开始时间选入闭区间；没有结束时间就只有 at 这一刻。 */
    fun cardFailure(gate: SkillGate?, lines: List<SkillGateLine>, at: Long, endAt: Long?): String? =
        failure(gate, lines.filter { it.startAt >= at && it.startAt <= (endAt ?: at) }, card = true)
}
