package app.resound.topic
import app.resound.digest.snapshot
import androidx.room.withTransaction
import kotlinx.coroutines.flow.map
internal fun TopicRow.snapshot() = app.resound.topic.core.TopicRow(id = id, kind = kind, name = name, status = status, createdAt = createdAt, plannedStartAt = plannedStartAt, plannedEndAt = plannedEndAt, repeatRule = repeatRule, eventId = eventId, cardId = cardId, error = error, revision = revision, quietOverride = quietOverride, retainedItemIds = retainedItemIds, starred = starred, quietNotifiedAfter = quietNotifiedAfter, endingNotifiedAt = endingNotifiedAt)
internal fun app.resound.topic.core.TopicRow.android() = TopicRow(id = id, kind = kind, name = name, status = status, createdAt = createdAt, plannedStartAt = plannedStartAt, plannedEndAt = plannedEndAt, repeatRule = repeatRule, eventId = eventId, cardId = cardId, error = error, revision = revision, quietOverride = quietOverride, retainedItemIds = retainedItemIds, starred = starred, quietNotifiedAfter = quietNotifiedAfter, endingNotifiedAt = endingNotifiedAt)
internal fun TopicSpanRow.snapshot() = app.resound.topic.core.TopicSpanRow(id = id, topicId = topicId, startAt = startAt, endAt = endAt)
internal fun app.resound.topic.core.TopicSpanRow.android() = TopicSpanRow(id = id, topicId = topicId, startAt = startAt, endAt = endAt)
internal fun TopicMarkRow.snapshot() = app.resound.topic.core.TopicMarkRow(id = id, topicId = topicId, at = at)
internal fun app.resound.topic.core.TopicMarkRow.android() = TopicMarkRow(id = id, topicId = topicId, at = at)
internal fun TopicGroup.snapshot() = app.resound.topic.core.TopicGroup(row.snapshot(),spans.map { it.snapshot() },marks.map { it.snapshot() })
internal class RoomTopicStore(private val db: app.resound.data.CaptureDatabase) : app.resound.topic.core.TopicStore {
 private val dao = db.topics()
 override fun observeAll() = dao.observeAll().map { rows -> rows.map { it.snapshot() } }
 override suspend fun all() = dao.all().map { it.snapshot() }
 override suspend fun get(id: Long) = dao.get(id)?.snapshot()
 override suspend fun insert(row: app.resound.topic.core.TopicRow) = dao.insert(row.android())
 override suspend fun update(row: app.resound.topic.core.TopicRow) = dao.update(row.android())
 override suspend fun span(row: app.resound.topic.core.TopicSpanRow) = dao.span(row.android())
 override suspend fun mark(row: app.resound.topic.core.TopicMarkRow) = dao.mark(row.android())
 override suspend fun unmark(topicId: Long, markId: Long) = dao.unmark(topicId, markId)
 override suspend fun closeSpan(id: Long, at: Long) = dao.closeSpan(id, at)
 override suspend fun deleteSpans(id: Long) = dao.deleteSpans(id)
 override suspend fun deleteTopic(id: Long) = dao.deleteTopic(id)
 override suspend fun digestedUntil(start: Long, end: Long) = dao.digestedUntil(start, end)
 override suspend fun segmentIds(id: Long, now: Long) = dao.segments(id,now).map { it.id }
 override suspend fun ordinaryEvents(start: Long, end: Long) = dao.ordinaryEvents(start, end).map { it.snapshot() }
 override suspend fun eventItems(ids: List<Long>) = dao.eventItems(ids).map { it.snapshot() }
 override suspend fun attachItems(ids: List<Long>, eventId: Long?) = dao.attachItems(ids, eventId)
 override suspend fun releaseEvents(ids: List<Long>) = dao.releaseEvents(ids)
 override suspend fun releaseRange(start: Long, end: Long) = dao.releaseRange(start, end)
 override suspend fun deleteEvents(ids: List<Long>) = dao.deleteEvents(ids)
 override suspend fun deleteCard(id: Long) = dao.deleteCard(id)
 override suspend fun lastRecording(start: Long, end: Long) = dao.lastRecording(start, end)
 override suspend fun prioritize(ids: List<Long>) = dao.prioritize(ids)
 override suspend fun event(id: Long) = db.memory().event(id)?.snapshot()
 override suspend fun repairMergedItems() = db.memory().repairMergedItems()
 override suspend fun <T> transaction(block: suspend () -> T): T = db.withTransaction { block() }
}
