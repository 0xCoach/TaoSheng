package app.resound.skills.core

import app.resound.net.Arr
import app.resound.digest.core.MemoryEventData
import app.resound.digest.core.TranscriptLineData

/** 显式结束时间优先，其次为引用原话的末句，最后使用覆盖卡片起点的事件。 */
object SkillCardTiming {
    fun complete(row: SkillCardData, lines: List<TranscriptLineData>, events: List<MemoryEventData>): SkillCardData {
        if (row.endAt != null) return row
        val ids = Arr(row.evidenceJson).elements().mapNotNull { (it as? Number)?.toLong() }.toSet()
        val quotedEnd = lines.filter { it.id in ids && it.endAt >= row.at }.maxOfOrNull { it.endAt }
        val eventEnd = events.filter { row.at >= it.startAt && row.at <= it.endAt }
            .minByOrNull { it.endAt - it.startAt }?.endAt
        return row.copy(endAt = quotedEnd ?: eventEnd)
    }
}
