package app.resound.topic.core
import kotlinx.coroutines.flow.Flow
import app.resound.digest.core.*
/** 事务包括读取、冲突复核、修改和恢复凭据；调用者不依赖平台。 */
interface TopicStore {
fun observeAll(): Flow<List<TopicGroup>>
suspend fun all(): List<TopicGroup>
suspend fun get(id: Long): TopicGroup?
suspend fun insert(row: TopicRow): Long
suspend fun update(row: TopicRow)
suspend fun span(row: TopicSpanRow): Long
suspend fun mark(row: TopicMarkRow): Long
suspend fun closeSpan(id: Long, at: Long)
suspend fun deleteSpans(id: Long)
suspend fun digestedUntil(start: Long, end: Long): Long?
suspend fun segmentIds(id: Long, now: Long): List<Long>
suspend fun ordinaryEvents(start: Long, end: Long): List<MemoryEventData>
suspend fun eventItems(ids: List<Long>): List<MemoryItemData>
suspend fun attachItems(ids: List<Long>, eventId: Long?)
suspend fun releaseEvents(ids: List<Long>)
suspend fun releaseRange(start: Long, end: Long)
suspend fun deleteEvents(ids: List<Long>)
suspend fun deleteCard(id: Long)
suspend fun lastRecording(start: Long, end: Long): Long?
suspend fun prioritize(ids: List<Long>)
suspend fun event(id: Long): MemoryEventData?
suspend fun repairMergedItems()
suspend fun <T> transaction(block: suspend () -> T): T
}
