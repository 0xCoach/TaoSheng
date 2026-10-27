package app.resound.digest

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.flow
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
    private val reminderSettings: AndroidAppointmentSettings? = null,
) : MemoryRepository {

    override fun events(date: LocalDate): Flow<List<MemoryEvent>> {
        val zone = ZoneId.systemDefault()
        val start = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        return combine(dao.events(start, end), dao.itemsBetween(start, end), dao.repeatCounts(), dao.doneHintLines()) { events, items, counts, lines ->
            val repeats = counts.associate { it.id to it.repeats }
            val byEvent = items.groupBy { it.eventId }
            events.map { it.toModel(byEvent[it.id].orEmpty(), repeats, lines.associateBy { line -> line.id }) }
        }.flowOn(Dispatchers.IO)
    }

    private fun listed(source: Flow<List<MemoryItemEntity>>): Flow<List<MemoryItem>> =
        combine(source, dao.repeatCounts(), dao.doneHintLines()) { rows, counts, lines ->
            val repeats = counts.associate { it.id to it.repeats }
            rows.map { it.toModel(repeats[it.id] ?: 1, lines.associateBy { line -> line.id }) }
        }.flowOn(Dispatchers.IO)

    override fun importantEvents(): Flow<List<MemoryEvent>> =
        combine(dao.importantEvents(), dao.importantItems(), dao.repeatCounts(), dao.doneHintLines()) { events, items, counts, lines ->
            val byEvent = items.groupBy { it.eventId }
            val repeats = counts.associate { it.id to it.repeats }
            events.map { it.toModel(byEvent[it.id].orEmpty(), repeats, lines.associateBy { line -> line.id }) }
        }.flowOn(Dispatchers.IO)

    override fun starredEvents(): Flow<List<MemoryEvent>> =
        combine(dao.starredEvents(), dao.starredItems(), dao.repeatCounts(), dao.doneHintLines()) { events, items, counts, lines ->
            val byEvent = items.groupBy { it.eventId }
            val repeats = counts.associate { it.id to it.repeats }
            events.map { it.toModel(byEvent[it.id].orEmpty(), repeats, lines.associateBy { line -> line.id }) }
        }.flowOn(Dispatchers.IO)

    override fun setStarred(eventId: Long, starred: Boolean) { scope.launch { dao.setStarred(eventId, starred) } }

    override fun mergedItems(itemId: Long): Flow<List<MemoryItem>> = listed(dao.mergedItems(itemId))

    override fun unmerge(itemId: Long) { scope.launch { dao.unmerge(itemId) } }

    override fun eventLines(eventId: Long): Flow<List<TranscriptLine>> = dao.eventLines(eventId).map { rows ->
        rows.map { TranscriptLine(it.id, it.startAt, it.endAt, it.text, it.speakerTag, it.speakerName, it.speakerScore, it.segmentId, app.resound.transcribe.VoiceInputMetadata.read(it.voiceInputJson)) }
    }.flowOn(Dispatchers.IO)

    override fun pendingItems(): Flow<List<MemoryItem>> = listed(dao.pendingItems())

    override fun items(categories: Set<String>, statuses: Set<ItemStatus>?): Flow<List<MemoryItem>> =
        listed(dao.itemsByCategory(categories.toList())).map { rows ->
            rows.filter { statuses == null || it.status in statuses }
        }.flowOn(Dispatchers.IO)

    override fun inProgress(): Flow<List<MemoryItem>> = listed(dao.inProgress())

    override fun appointments(): Flow<List<MemoryItem>> = listed(dao.appointments())

    override fun ongoing(): Flow<List<MemoryItem>> = listed(dao.ongoing())
    override fun doneItems(from: LocalDate): Flow<List<MemoryItem>> =
        listed(dao.doneItems(from.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()))
    override fun deleteItem(id: Long) { scope.launch { dao.deleteItem(id) } }
    override fun updateItem(id: Long, content: String, dueAt: Long?, hasTime: Boolean, who: String?) {
        scope.launch { dao.updateItem(id, content, dueAt, hasTime, who) }
    }
    override fun setItemsStatus(ids: List<Long>, status: ItemStatus) {
        val snapshot = ids.toList()
        scope.launch { dao.setItemsStatus(snapshot, status.name) }
    }
    override fun dismissDoneHint(id: Long) { scope.launch { dao.dismissDoneHint(id) } }
    override val appointmentReminders: StateFlow<AppointmentReminders> =
        reminderSettings?.state ?: kotlinx.coroutines.flow.MutableStateFlow(AppointmentReminders())
    override fun setAppointmentReminders(value: AppointmentReminders) { reminderSettings?.set(value) }

    override fun reorder(ids: List<Long>) {
        val snapshot = ids.toList()
        scope.launch { dao.reorder(snapshot) }
    }

    override fun dayItems(date: LocalDate): Flow<DayItems> {
        val zone = ZoneId.systemDefault()
        val start = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        return listed(dao.dayItems(start, end)).map { items ->
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

    override fun setImportant(eventId: Long, important: Boolean) = setStarred(eventId, important)

    override fun deleteEvent(eventId: Long) { scope.launch { dao.deleteEvent(eventId) } }

    /** 待整理句数一变就重算原因，另每分钟重算一次（话停够久后原因会从“等对话结束”变成可以整理） */
    private val waiting = combine(dao.observeUndigestedCount(), flow { while (true) { emit(Unit); delay(60_000) } }) { count, _ -> count }
        .mapLatest { count -> if (count == 0) null else runCatching { runner.waitingNow() }.getOrNull() }

    override val state: StateFlow<DigestState> = combine(
        dao.observeUndigestedCount(), runner.running, runner.last, dao.observeUsage(), waiting,
    ) { pending, running, last, usage, waiting ->
        val today = usage.firstOrNull { it.day == LocalDate.now().toString() }
        DigestState(pending, running, last.first, last.second, (today?.promptTokens ?: 0) + (today?.completionTokens ?: 0),
            waiting.takeIf { pending > 0 })
    }.stateIn(scope, SharingStarted.Eagerly, DigestState(0, false, null, null, 0))

    override fun runNow() = runNowAction()

    private fun MemoryEventEntity.toModel(items: List<MemoryItemEntity>, repeats: Map<Long, Int>, lines: Map<Long, app.resound.data.TranscriptLineEntity>) = MemoryEvent(
        id, startAt, endAt, scene, title, summary, pointsJson.strings(), peopleJson.strings(), place, emotion, intensity, important,
        items.map { it.toModel(repeats[it.id] ?: 1, lines) }, starred, starredAt,
    )

    private fun MemoryItemEntity.toModel(repeats: Int = 1, lines: Map<Long, app.resound.data.TranscriptLineEntity>) = MemoryItem(
        id, eventId, category, kind, content, dueText, dueAt, who,
        runCatching { ItemStatus.valueOf(status) }.getOrDefault(ItemStatus.Pending), lineId, createdAt, doneAt, statusChangedAt, sortOrder, repeats, mergedInto, editedAt,
        lines[doneHintLineId]?.let { DoneHint(it.id, it.text, doneHintAt ?: it.startAt) },
    )

    private fun String.strings(): List<String> = runCatching {
        val a = JSONArray(this)
        (0 until a.length()).map { a.getString(it) }
    }.getOrDefault(emptyList())
}
