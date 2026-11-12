package app.resound.skills.core
import app.resound.topic.core.*
import app.resound.digest.core.*
import kotlinx.coroutines.flow.flowOf
/** 只替代事务/落库；时段操作仍完整经过生产仓库规则。 */
internal class MemoryTopicStore160 : TopicStore {
 val events=mutableListOf<MemoryEventData>()
 private val rows=linkedMapOf<Long,TopicRow>()
 private val spans=mutableListOf<TopicSpanRow>()
 private val marks=mutableListOf<TopicMarkRow>()
 override fun observeAll()=flowOf(emptyList<TopicGroup>())
 override suspend fun all()=rows.keys.mapNotNull { get(it) }
 override suspend fun get(id: Long)=rows[id]?.let { row -> TopicGroup(row,spans.filter { it.topicId==id },marks.filter { it.topicId==id }) }
 override suspend fun insert(row: TopicRow): Long { val id=rows.size+1L;rows[id]=row.copy(id=id);return id }
 override suspend fun update(row: TopicRow) { rows[row.id]=row }
 override suspend fun span(row: TopicSpanRow): Long { val id=spans.size+1L;spans+=row.copy(id=id);return id }
 override suspend fun mark(row: TopicMarkRow): Long { val id=marks.size+1L;marks+=row.copy(id=id);return id }
 override suspend fun unmark(topicId: Long, markId: Long) { marks.removeAll { it.topicId==topicId && it.id==markId } }
 override suspend fun closeSpan(id: Long,at: Long) { for(i in spans.indices) if(spans[i].topicId==id && spans[i].endAt==null) spans[i]=spans[i].copy(endAt=maxOf(spans[i].startAt,at)) }
 override suspend fun deleteSpans(id: Long) { spans.removeAll { it.topicId==id } }
 override suspend fun deleteTopic(id: Long) { spans.removeAll { it.topicId==id }; marks.removeAll { it.topicId==id }; rows.remove(id) }
 override suspend fun digestedUntil(start: Long,end: Long): Long?=null
 override suspend fun segmentIds(id: Long,now: Long)=emptyList<Long>()
 override suspend fun ordinaryEvents(start: Long,end: Long)=events.filter { it.startAt < end && it.endAt > start }
 override suspend fun eventItems(ids: List<Long>)=emptyList<MemoryItemData>()
 override suspend fun attachItems(ids: List<Long>,eventId: Long?) {}
 override suspend fun releaseEvents(ids: List<Long>) {}
 override suspend fun releaseRange(start: Long,end: Long) {}
 override suspend fun deleteEvents(ids: List<Long>) {}
 override suspend fun deleteCard(id: Long) {}
 override suspend fun lastRecording(start: Long,end: Long): Long?=null
 override suspend fun prioritize(ids: List<Long>) {}
 override suspend fun event(id: Long): MemoryEventData?=null
 override suspend fun repairMergedItems() {}
 override suspend fun <T> transaction(block: suspend () -> T): T=block()
}
