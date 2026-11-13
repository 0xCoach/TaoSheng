@file:OptIn(kotlin.time.ExperimentalTime::class)

package app.resound.digest


import app.resound.data.MemoryDao
import app.resound.data.MemoryEventEntity
import app.resound.data.MemoryItemEntity
import app.resound.transcribe.TranscriptLine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.launch
import app.resound.platform.NowMillis
import kotlinx.datetime.*
import kotlin.time.Instant
import app.resound.platform.ZoneProvider

class MemoryStoreRepository(
    private val dao: MemoryDao,
    private val running: StateFlow<Boolean>,
    private val last: StateFlow<Pair<Long?, String?>>,
    private val runNowAction: () -> Unit,
    private val scope: CoroutineScope,
    reminders: StateFlow<AppointmentReminders>?,
    private val saveReminders: ((AppointmentReminders) -> Unit)?,
    private val eventChanged: () -> Unit,
    private val zoneProvider: ZoneProvider,
    private val now: NowMillis,
    private val io: CoroutineDispatcher,
    private val today: () -> String,
    private val codec: MemoryLegacyCodec,
    private val dueDate: (Long) -> MemoryDueDate,
    private val readVoiceInput: (String?) -> app.resound.transcribe.VoiceInputMeta?,
) : MemoryRepository {

    override fun events(date: LocalDate): Flow<List<MemoryEvent>> = events(date, TimeZone.of(zoneProvider.zoneId()))

    private fun events(date: LocalDate, zone: TimeZone): Flow<List<MemoryEvent>> {
        val start = date.atStartOfDayIn(zone).toEpochMilliseconds()
        val end = date.plus(1, DateTimeUnit.DAY).atStartOfDayIn(zone).toEpochMilliseconds()
        return combine(dao.events(start, end), dao.itemsBetween(start, end), dao.repeatCounts(), dao.doneHintLines()) { events, items, counts, lines ->
            val repeats = counts.associate { it.id to it.repeats }
            val byEvent = items.groupBy { it.eventId }
            events.map { it.toModel(byEvent[it.id].orEmpty(), repeats, lines.associateBy { line -> line.id }) }
        }.flowOn(io)
    }

    private fun listed(source: Flow<List<MemoryItemEntity>>): Flow<List<MemoryItem>> =
        combine(source, dao.repeatCounts(), dao.doneHintLines()) { rows, counts, lines ->
            val repeats = counts.associate { it.id to it.repeats }
            rows.map { it.toModel(repeats[it.id] ?: 1, lines.associateBy { line -> line.id }) }
        }.flowOn(io)

    override fun importantEvents(): Flow<List<MemoryEvent>> =
        combine(dao.importantEvents(), dao.importantItems(), dao.repeatCounts(), dao.doneHintLines()) { events, items, counts, lines ->
            val byEvent = items.groupBy { it.eventId }
            val repeats = counts.associate { it.id to it.repeats }
            events.map { it.toModel(byEvent[it.id].orEmpty(), repeats, lines.associateBy { line -> line.id }) }
        }.flowOn(io)

    override fun starredEvents(): Flow<List<MemoryEvent>> =
        combine(dao.starredEvents(), dao.starredItems(), dao.repeatCounts(), dao.doneHintLines()) { events, items, counts, lines ->
            val byEvent = items.groupBy { it.eventId }
            val repeats = counts.associate { it.id to it.repeats }
            events.map { it.toModel(byEvent[it.id].orEmpty(), repeats, lines.associateBy { line -> line.id }) }
        }.flowOn(io)

    override suspend fun eventById(eventId: Long): MemoryEvent? {
        val row = dao.event(eventId)?.takeIf { !it.deleted } ?: return null
        val zone = TimeZone.of(zoneProvider.zoneId())
        val day = Instant.fromEpochMilliseconds(row.startAt).toLocalDateTime(zone).date
        return events(day, zone).first().firstOrNull { it.id == eventId }
    }

    override fun editEvent(eventId: Long, title: String, summary: String, points: List<String>) {
        val json = codec.encodeStrings(points.toList())
        scope.launch { editEventRow(eventId, title, summary, json, now.now()); eventChanged() }
    }
    override fun restoreEvent(eventId: Long) {
        scope.launch { restoreEventRow(eventId); eventChanged() }
    }

    override fun setStarred(eventId: Long, starred: Boolean) { scope.launch { dao.setStarred(eventId, starred, now.now()) } }

    override fun mergedItems(itemId: Long): Flow<List<MemoryItem>> = listed(dao.mergedItems(itemId))

    override fun unmerge(itemId: Long) { scope.launch { dao.unmerge(itemId) } }

    override suspend fun sourceLines(lineId: Long): List<TranscriptLine> = kotlinx.coroutines.withContext(io) {
        dao.segmentLinesOf(lineId).map { TranscriptLine(it.id, it.startAt, it.endAt, it.text, it.speakerTag, it.speakerName, it.speakerScore, it.segmentId, readVoiceInput(it.voiceInputJson)) }
    }
    override fun eventLines(eventId: Long): Flow<List<TranscriptLine>> = dao.eventLines(eventId).map { rows ->
        rows.map { TranscriptLine(it.id, it.startAt, it.endAt, it.text, it.speakerTag, it.speakerName, it.speakerScore, it.segmentId, readVoiceInput(it.voiceInputJson)) }
    }.flowOn(io)

    override fun pendingItems(): Flow<List<MemoryItem>> = listed(dao.pendingItems())

    override fun items(categories: Set<String>, statuses: Set<ItemStatus>?): Flow<List<MemoryItem>> =
        listed(dao.itemsByCategory(categories.toList())).map { rows ->
            rows.filter { statuses == null || it.status in statuses }
        }.flowOn(io)

    override fun inProgress(): Flow<List<MemoryItem>> = listed(dao.inProgress())

    override fun appointments(): Flow<List<MemoryItem>> = listed(dao.appointments())

    override fun ongoing(): Flow<List<MemoryItem>> = listed(dao.ongoing())
    override fun doneItems(from: LocalDate): Flow<List<MemoryItem>> =
        listed(dao.doneItems(from.atStartOfDayIn(TimeZone.of(zoneProvider.zoneId())).toEpochMilliseconds()))
    override fun deleteItem(id: Long) { scope.launch { dao.deleteItem(id) } }
    override fun updateItem(id: Long, content: String, dueAt: Long?, hasTime: Boolean, who: String?) {
        scope.launch { updateItemRow(id, content, dueAt, hasTime, who, now.now()) }
    }
    override fun setStrongReminder(id: Long, on: Boolean) {
        scope.launch { dao.setStrongReminder(id, on) }
    }
    override fun setItemsStatus(ids: List<Long>, status: ItemStatus) {
        val snapshot = ids.toList()
        scope.launch { dao.setItemsStatus(snapshot, status.name, now.now()) }
    }
    override fun dismissDoneHint(id: Long) { scope.launch { dismissDoneHintRow(id) } }
    override val appointmentReminders: StateFlow<AppointmentReminders> =
        reminders ?: kotlinx.coroutines.flow.MutableStateFlow(AppointmentReminders())
    override fun setAppointmentReminders(value: AppointmentReminders) { saveReminders?.invoke(value) }

    override fun reorder(ids: List<Long>) {
        val snapshot = ids.toList()
        scope.launch { dao.reorder(snapshot) }
    }

    override fun dayItems(date: LocalDate): Flow<DayItems> {
        val zone = TimeZone.of(zoneProvider.zoneId())
        val start = date.atStartOfDayIn(zone).toEpochMilliseconds()
        val end = date.plus(1, DateTimeUnit.DAY).atStartOfDayIn(zone).toEpochMilliseconds()
        return listed(dao.dayItems(start, end)).map { items ->
            val tasks = items.filter { it.category in setOf("待办", "约定", "备忘") }
            DayItems(
                mentioned = tasks.filter { it.at >= start && it.at < end },
                due = tasks.filter { it.dueAt?.let { at -> at >= start && at < end } == true }.sortedBy { it.dueAt },
                done = items.filter { it.doneAt?.let { at -> at >= start && at < end } == true }.sortedBy { it.doneAt },
                notes = items.filter { it.category in setOf("灵感", "关键信息", "决定", "知识") && it.at >= start && it.at < end },
            )
        }.flowOn(io)
    }

    override fun activeDays(month: YearMonth): Flow<Set<LocalDate>> {
        val zone = TimeZone.of(zoneProvider.zoneId())
        val start = month.firstDay.atStartOfDayIn(zone).toEpochMilliseconds()
        val end = month.plus(1, DateTimeUnit.MONTH).firstDay.atStartOfDayIn(zone).toEpochMilliseconds()
        return combine(dao.activeEventTimes(start, end), dao.activeItemTimes(start, end)) { events, items ->
            (events + items).mapTo(mutableSetOf()) { Instant.fromEpochMilliseconds(it).toLocalDateTime(zone).date }
        }.flowOn(io)
    }

    override fun setItemStatus(id: Long, status: ItemStatus) { scope.launch { dao.setItemStatus(id, status.name, now.now()) } }

    override fun setImportant(eventId: Long, important: Boolean) = setStarred(eventId, important)

    override fun deleteEvent(eventId: Long) { scope.launch { dao.deleteEvent(eventId) } }

    private val pendingCount = dao.observeUndigestedCount().shareIn(scope, SharingStarted.Eagerly, replay = 1)
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val todayUsage = combine(dao.observeUsageChanges(), pendingCount, running, last) { _, _, _, _ -> today() }
        .distinctUntilChanged().flatMapLatest { dao.observeTokens(it).distinctUntilChanged() }
    override val state: StateFlow<DigestState> = combine(
        pendingCount, running, last, todayUsage,
    ) { pending, running, last, usage ->
        DigestState(pending, running, last.first, last.second, usage)
    }.stateIn(scope, SharingStarted.Eagerly, DigestState(0, false, null, null, 0))

    override fun runNow() = runNowAction()

    private fun MemoryEventEntity.toModel(items: List<MemoryItemEntity>, repeats: Map<Long, Int>, lines: Map<Long, app.resound.data.TranscriptLineEntity>): MemoryEvent {
        val originalTitle = this.originalTitle
        val originalSummary = this.originalSummary
        val originalPointsJson = this.originalPointsJson
        return MemoryEvent(
        id, startAt, endAt, scene, title, summary, pointsJson.strings(), peopleJson.strings(), place, emotion, intensity, important,
        items.map { it.toModel(repeats[it.id] ?: 1, lines) }, starred, starredAt, editedAt,
        if (originalTitle != null && originalSummary != null && originalPointsJson != null)
            EventOriginal(originalTitle, originalSummary, originalPointsJson.strings()) else null,
    )
    }

    private fun MemoryItemEntity.toModel(repeats: Int = 1, lines: Map<Long, app.resound.data.TranscriptLineEntity>) = MemoryItem(
        id, eventId, category, kind, content, dueText, dueAt, who,
        runCatching { ItemStatus.valueOf(status) }.getOrDefault(ItemStatus.Pending), lineId, createdAt, doneAt, statusChangedAt, sortOrder, repeats, mergedInto, editedAt,
        lines[doneHintLineId]?.let { DoneHint(it.id, it.text, doneHintAt ?: it.startAt) },
        ItemOrigin.entries.firstOrNull { it.name.equals(origin, ignoreCase = true) } ?: ItemOrigin.Digest, sourceText,
        strongReminder,
    )

    private fun String.strings(): List<String> = runCatching {
        codec.strings(this)
    }.getOrDefault(emptyList())

    private suspend fun editEventRow(id: Long, title: String, summary: String, points: String, at: Long) = dao.transaction {
        val row = dao.event(id) ?: return@transaction
        dao.normalizeEventPoints(id, codec.canonicalArray(row.pointsJson), row.originalPointsJson?.let(codec::canonicalArray))
        dao.editEventRaw(id, title, summary, codec.canonicalArray(points), at)
    }
    private suspend fun restoreEventRow(id: Long) = dao.transaction {
        val row = dao.event(id) ?: return@transaction
        dao.normalizeEventPoints(id, codec.canonicalArray(row.pointsJson), row.originalPointsJson?.let(codec::canonicalArray))
        dao.restoreEventRaw(id)
    }
    private suspend fun updateItemRow(id: Long, content: String, dueAt: Long?, hasTime: Boolean, who: String?, at: Long) {
        require(content.isNotBlank())
        val date = dueAt?.let(dueDate)
        val normalized = if (hasTime) dueAt else date?.startOfDay
        val text = date?.let { if (hasTime) it.dateTimeText else it.dateText }
        dao.updateItemRow(id, content.trim(), normalized, text, who?.trim()?.takeIf { it.isNotEmpty() }, at)
    }
    private suspend fun dismissDoneHintRow(id: Long) = dao.transaction {
        val row = dao.itemIncludingDeleted(id) ?: return@transaction
        val line = row.doneHintLineId ?: return@transaction
        dao.clearDoneHint(id, codec.appendLong(row.dismissedDoneLinesJson, line))
    }
}

/** 旧数据的宽松数组编解码；平台适配器保留旧解析器兼容行为。 */
interface MemoryLegacyCodec {
    fun strings(text: String): List<String>
    fun encodeStrings(values: List<String>): String
    fun canonicalArray(text: String): String
    fun appendLong(text: String, value: Long): String
}

/** 同一次系统时区取值产生的旧日期表示。 */
data class MemoryDueDate(val startOfDay: Long, val dateText: String, val dateTimeText: String)
