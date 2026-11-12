package app.resound.skills.core
import app.resound.digest.core.*
/** 原查询顺序和边界不变；时刻为 Unix 毫秒，日期为 epoch day。 */
interface SkillInputStore {
 suspend fun events(start: Long, end: Long, since: Long, until: Long): List<MemoryEventData>
 suspend fun lines(start: Long, end: Long): List<TranscriptLineData>
 suspend fun eventLines(events: List<Long>): List<TranscriptLineData>
 suspend fun items(events: List<Long>): List<MemoryItemData>
 suspend fun standaloneItems(start: Long, end: Long): List<MemoryItemData>
 suspend fun starredEvents(start: Long, end: Long, until: Long): List<MemoryEventData>
 suspend fun reviews(start: String, end: String): List<SkillReviewData>
 suspend fun dayRows(id: String, date: Long): List<SkillCardData>
}
data class SkillReviewData(val startDate: String, val brief: String, val sectionsJson: String)
