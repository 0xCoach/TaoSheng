package app.resound.digest

import app.resound.data.MemoryDao
import app.resound.data.MemoryEventEntity
import app.resound.data.MemoryItemEntity
import app.resound.transcribe.TranscriptLine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.json.JSONArray
import java.time.Instant
import java.time.YearMonth
import java.time.LocalDate
import java.time.ZoneId

class RoomMemoryRepository(
    private val dao: MemoryDao,
    private val runner: DigestRunner,
    private val runNowAction: () -> Unit,
    private val scope: CoroutineScope,
) : MemoryRepository {

    override fun events(date: LocalDate): Flow<List<MemoryEvent>> {
        val zone = ZoneId.systemDefault()
        val start = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        return combine(dao.events(start, end), dao.itemsBetween(start, end)) { events, items ->
            val byEvent = items.groupBy { it.eventId }
            events.map { it.toModel(byEvent[it.id].orEmpty()) }
        }.flowOn(Dispatchers.IO)
    }

    override fun eventLines(eventId: Long): Flow<List<TranscriptLine>> = dao.eventLines(eventId).map { rows ->
        rows.map { TranscriptLine(it.id, it.startAt, it.endAt, it.text, it.speakerTag, it.speakerName, it.speakerScore, it.segmentId) }
    }.flowOn(Dispatchers.IO)

    override fun pendingItems(): Flow<List<MemoryItem>> = dao.pendingItems().map { rows -> rows.map { it.toModel() } }.flowOn(Dispatchers.IO)

    override fun items(categories: Set<String>, statuses: Set<ItemStatus>?): Flow<List<MemoryItem>> =
        dao.itemsByCategory(categories.toList()).map { rows ->
            rows.map { it.toModel() }.filter { statuses == null || it.status in statuses }
        }.flowOn(Dispatchers.IO)

    override fun inProgress(): Flow<List<MemoryItem>> = dao.inProgress().map { rows -> rows.map { it.toModel() } }.flowOn(Dispatchers.IO)

    override fun appointments(): Flow<List<MemoryItem>> = dao.appointments().map { rows -> rows.map { it.toModel() } }.flowOn(Dispatchers.IO)

    override fun reorder(ids: List<Long>) {
        val snapshot = ids.toList()
        scope.launch { dao.reorder(snapshot) }
    }

    override fun dayItems(date: LocalDate): Flow<DayItems> {
        val zone = ZoneId.systemDefault()
        val start = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        return dao.dayItems(start, end).map { rows ->
            val items = rows.map { it.toModel() }
            val tasks = items.filter { it.category in setOf("待办", "约定", "备忘") }
            DayItems(
                mentioned = tasks.filter { it.at >= start && it.at < end },
                due = tasks.filter { it.dueAt != null && it.dueAt >= start && it.dueAt < end }.sortedBy { it.dueAt },
                done = items.filter { it.doneAt != null && it.doneAt >= start && it.doneAt < end }.sortedBy { it.doneAt },
                notes = items.filter { it.category in setOf("灵感", "关键信息", "决定", "知识") && it.at >= start && it.at < end },
            )
        }.flowOn(Dispatchers.IO)
    }

    override fun activeDays(month: YearMonth): Flow<Set<LocalDate>> {
        val zone = ZoneId.systemDefault()
        val start = month.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val end = month.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
        return combine(dao.activeEventTimes(start, end), dao.activeItemTimes(start, end)) { events, items ->
            (events + items).mapTo(mutableSetOf()) { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }
        }.flowOn(Dispatchers.IO)
    }

    override fun setItemStatus(id: Long, status: ItemStatus) { scope.launch { dao.setItemStatus(id, status.name) } }

    override fun setImportant(eventId: Long, important: Boolean) { scope.launch { dao.setImportant(eventId, important) } }

    override fun deleteEvent(eventId: Long) { scope.launch { dao.deleteEvent(eventId) } }

    override val state: StateFlow<DigestState> = combine(
        dao.observeUndigestedCount(), runner.running, runner.last, dao.observeUsage(),
    ) { pending, running, last, usage ->
        val today = usage.firstOrNull { it.day == LocalDate.now().toString() }
        DigestState(pending, running, last.first, last.second, (today?.promptTokens ?: 0) + (today?.completionTokens ?: 0))
    }.stateIn(scope, SharingStarted.Eagerly, DigestState(0, false, null, null, 0))

    override fun runNow() = runNowAction()

    private fun MemoryEventEntity.toModel(items: List<MemoryItemEntity>) = MemoryEvent(
        id, startAt, endAt, scene, title, summary, pointsJson.strings(), peopleJson.strings(), place, emotion, intensity, important,
        items.map { it.toModel() },
    )

    private fun MemoryItemEntity.toModel() = MemoryItem(
        id, eventId, category, kind, content, dueText, dueAt, who,
        runCatching { ItemStatus.valueOf(status) }.getOrDefault(ItemStatus.Pending), lineId, createdAt, doneAt, statusChangedAt, sortOrder,
    )

    private fun String.strings(): List<String> = runCatching {
        val a = JSONArray(this)
        (0 until a.length()).map { a.getString(it) }
    }.getOrDefault(emptyList())
}
