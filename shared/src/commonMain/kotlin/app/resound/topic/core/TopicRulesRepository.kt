@file:OptIn(kotlin.time.ExperimentalTime::class)
package app.resound.topic.core
import app.resound.topic.*

import app.resound.digest.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import app.resound.net.Arr as JSONArray
import kotlinx.datetime.*
import app.resound.platform.NowMillis
import app.resound.platform.ZoneProvider

class TopicRulesRepository(
    private val dao: TopicStore,
    scope: CoroutineScope,
    private val clock: NowMillis,
    private val zones: ZoneProvider,
    private val quiet: (Long, Long) -> Boolean = { _, _ -> false },
    private val recordingEnabled: () -> Boolean = { true },
) : TopicRepository {
    private fun now() = clock.now()
    private fun zone() = TimeZone.of(zones.zoneId())
    private val gate = Mutex()
    private val active = MutableStateFlow<Topic?>(null)
    override val current = active.asStateFlow()
    var onChanged: suspend () -> Unit = {}
    var onEnded: suspend (Long) -> Unit = {}
    var onReminder: (Long, TopicReminder) -> Unit = { _, _ -> }
    init { scope.launch { dao.observeAll().collect { gate.withLock { publish(dao.all()) } } } }
    private fun publish(rows: List<TopicGroup>) {
        active.value = rows.firstOrNull { it.row.status in setOf("Running", "Paused") }?.topic()
    }
    private suspend fun changed() {
        publish(dao.all())
        // 调度/通知失败不把已提交的数据操作误报为失败；启动和分钟巡检会重新排钟。
        try { onChanged() } catch (e: CancellationException) { throw e } catch (_: Exception) { }
    }
    private suspend fun <T> edit(block: suspend () -> T): T = gate.withLock {
        val result = dao.transaction { block() }
        changed()
        result
    }
    private suspend fun <T> result(block: suspend () -> T): Result<T> = try { Result.success(block()) }
        catch (e: CancellationException) { throw e }
        catch (e: TopicException) { Result.failure(e) }
        catch (_: Exception) { Result.failure(TopicException("专题保存失败，请重试")) }
    private fun requireTopic(ok: Boolean, error: String) { if (!ok) throw TopicException(error) }
    private suspend fun get(id: Long) = dao.get(id) ?: throw TopicException("专题不存在")
    private suspend fun currentGroup() = dao.all().firstOrNull { it.row.status in setOf("Running", "Paused") }
        ?: throw TopicException("没有进行中的专题")
    private suspend fun checkConflict(start: Long, end: Long, except: Long? = null, repeat: Set<DayOfWeek> = emptySet()) {
        requireTopic(!TopicPolicy.conflicts(start, end, repeat, dao.all().filter { it.row.id != except }, zone()), TopicPolicy.CONFLICT)
    }
    override fun observeDay(date: LocalDate): Flow<List<Topic>> {
        val start = date.atStartOfDayIn(zone()).toEpochMilliseconds()
        val end = date.plus(1, DateTimeUnit.DAY).atStartOfDayIn(zone()).toEpochMilliseconds()
        return dao.observeAll().map { rows -> rows.filter { g -> g.spans.any {
            TopicPolicy.overlaps(it.startAt, it.endAt ?: g.deadline(), start, end)
        } || g.row.createdAt in start until end }.map { it.topic() } }
    }
    override fun observe(topicId: Long) = dao.observeAll().map { rows -> rows.firstOrNull { it.row.id == topicId }?.topic() }.distinctUntilChanged()
    override fun observeScheduled() = dao.observeAll().map { rows -> rows.filter { it.row.status == "Scheduled" }
        .sortedBy { it.row.plannedStartAt }.map { it.topic() } }.distinctUntilChanged()

    override suspend fun start(kind: TopicKind, name: String?, durationMinutes: Int?): StartResult {
        val outcome = result { edit {
            requireTopic(durationMinutes == null || durationMinutes in 15..240, "专题时长应为 15～240 分钟")
            requireTopic(dao.all().none { it.row.status in setOf("Running", "Paused") }, "请先结束当前专题")
            val at = now()
            val previous = dao.all().filter { it.row.status != "Cancelled" }.flatMap { it.spans }
                .mapNotNull { it.endAt }.filter { it <= at }.maxOrNull() ?: Long.MIN_VALUE
            val from = maxOf(at - TopicPolicy.LOOK_BACK, previous, dao.digestedUntil(at - TopicPolicy.LOOK_BACK, at) ?: Long.MIN_VALUE)
                .coerceAtMost(at)
            val end = durationMinutes?.let { at + it * 60_000L }
            checkConflict(from, end ?: from + TopicPolicy.MAX_DURATION)
            val override = quiet(at, end ?: from + TopicPolicy.MAX_DURATION)
            val id = dao.insert(TopicRow(kind = kind.name, name = name.clean(), status = "Running", createdAt = at,
                plannedEndAt = end, quietOverride = override))
            dao.span(TopicSpanRow(topicId = id, startAt = from))
            StartResult(id, override, !recordingEnabled())
        } }
        return outcome.getOrElse { StartResult(error = it.message) }
    }
    override suspend fun pause() { edit {
        val g = currentGroup()
        if (g.row.status == "Running") {
            dao.closeSpan(g.row.id, minOf(now(), g.deadline()))
            dao.update(g.row.copy(status = "Paused", revision = g.row.revision + 1))
        }
    }; tick() }
    override suspend fun resume() { edit {
        val g = currentGroup()
        if (g.row.status == "Paused" && now() < g.deadline()) {
            val start = maxOf(now(), g.spans.maxOf { it.endAt ?: it.startAt })
            checkConflict(start, g.deadline(), g.row.id)
            dao.span(TopicSpanRow(topicId = g.row.id, startAt = start))
            dao.update(g.row.copy(status = "Running", revision = g.row.revision + 1, quietNotifiedAfter = null))
        }
    }; tick() }
    private suspend fun finish(g: TopicGroup, at: Long) {
        dao.closeSpan(g.row.id, at)
        dao.update(g.row.copy(status = "Ended", revision = g.row.revision + 1, error = null))
        dao.prioritize(dao.segmentIds(g.row.id, at))
    }
    override suspend fun end() {
        val id = edit { val g = currentGroup(); finish(g, minOf(now(), g.deadline())); g.row.id }
        onEnded(id)
    }
    override suspend fun extend(minutes: Int) { edit {
        requireTopic(minutes > 0 && minutes <= 240, "延长时长应为 1～240 分钟")
        val g = currentGroup()
        requireTopic(now() < g.deadline(), "专题已到结束时间")
        val end = g.deadline() + minutes * 60_000L
        checkConflict(now(), end, g.row.id)
        dao.update(g.row.copy(plannedEndAt = end, revision = g.row.revision + 1, endingNotifiedAt = null,
            quietOverride = g.row.quietOverride || quiet(now(), end)))
    } }
    override suspend fun mark() { edit {
        val g = currentGroup()
        dao.mark(TopicMarkRow(topicId = g.row.id, at = now()))
        dao.update(g.row.copy(revision = g.row.revision + 1))
    } }
    override suspend fun unmark(topicId: Long, markId: Long) { edit {
        val g = get(topicId)
        requireTopic(g.row.status != "Cancelled", "专题已取消")
        dao.unmark(topicId, markId)
        dao.update(g.row.copy(revision = g.row.revision + 1))
    } }
    override suspend fun update(topicId: Long, kind: TopicKind, name: String?) {
        val ended = edit {
            val g = get(topicId)
            requireTopic(g.row.status != "Cancelled", "专题已取消")
            val regenerate = g.row.status in setOf("Ended", "Summarizing", "Done", "Failed")
            val row = if (regenerate) releaseGenerated(g) else g.row
            dao.update(row.copy(kind = kind.name, name = name.clean(), revision = row.revision + 1,
                status = if (regenerate) "Ended" else row.status, error = null))
            regenerate
        }
        if (ended) onEnded(topicId)
    }
    override suspend fun schedule(kind: TopicKind, name: String?, startAt: Long, endAt: Long, repeatDays: Set<DayOfWeek>) = result { edit {
        requireTopic(startAt > now() && endAt > startAt, "预约开始时间须在将来，结束时间须晚于开始")
        if (repeatDays.isNotEmpty()) {
            var occurrence = startAt
            // 检查一整周的所有相邻预约，不能只检查用户指定的第一次。
            repeat(8) {
                val next = TopicPolicy.next(startAt, repeatDays, occurrence, zone())!!
                requireTopic(endAt - startAt <= next - occurrence, TopicPolicy.CONFLICT)
                occurrence = next
            }
        }
        checkConflict(startAt, endAt, repeat = repeatDays)
        insertSchedule(kind.name, name.clean(), startAt, endAt, TopicPolicy.mask(repeatDays))
    } }
    private suspend fun insertSchedule(kind: String, name: String?, start: Long, end: Long, repeat: Int?): Long {
        val id = dao.insert(TopicRow(kind = kind, name = name, status = "Scheduled", createdAt = now(),
            plannedStartAt = start, plannedEndAt = end, repeatRule = repeat, quietOverride = quiet(start, end)))
        dao.span(TopicSpanRow(topicId = id, startAt = start, endAt = end))
        return id
    }
    override suspend fun cancelSchedule(topicId: Long) { edit {
        val g = get(topicId)
        requireTopic(g.row.status == "Scheduled", "预约已开始，请在专题中结束或取消")
        dao.update(g.row.copy(status = "Cancelled", revision = g.row.revision + 1))
    } }

    override suspend fun previewFromEvents(ids: List<Long>): TopicReplacePreview = dao.transaction { previewEvents(ids) }
    private suspend fun previewEvents(ids: List<Long>): TopicReplacePreview {
        requireTopic(ids.isNotEmpty(), "请先选择事件")
        val events = ids.distinct().map { dao.event(it) ?: throw TopicException("所选事件已不存在") }
        requireTopic(dao.all().none { it.row.status != "Cancelled" && it.row.eventId in ids }, "所选事件已经属于专题")
        return preview(events.minOf { it.startAt }, events.maxOf { it.endAt })
    }
    override suspend fun previewFromRange(start: Long, end: Long) = dao.transaction { preview(start, end) }
    private suspend fun preview(start: Long, end: Long): TopicReplacePreview {
        requireTopic(end > start && end <= now(), "请选择已经过去的有效时段")
        var from = start; var until = end
        var events: List<MemoryEventData>
        do {
            events = dao.ordinaryEvents(from, until)
            val a = minOf(from, events.minOfOrNull { it.startAt } ?: from)
            val b = maxOf(until, events.maxOfOrNull { it.endAt } ?: until)
            val expanded = a != from || b != until
            from = a; until = b
        } while (expanded)
        val conflict = TopicPolicy.conflicts(from, until, emptySet(), dao.all(), zone())
        return TopicReplacePreview(from, until, events.map { TopicReplacedEvent(it.id, it.title, it.startAt, it.endAt, it.starred) },
            dao.eventItems(events.map { it.id }).count { !it.deleted && it.category in setOf("待办", "约定", "备忘") && TopicPolicy.changed(it) },
            events.count { it.starred }, conflict, if (conflict) TopicPolicy.CONFLICT else null)
    }
    override suspend fun createFromEvents(ids: List<Long>, kind: TopicKind, name: String?) = create(kind, name) { previewEvents(ids) }
    override suspend fun createFromRange(start: Long, end: Long, kind: TopicKind, name: String?) = create(kind, name) { preview(start, end) }
    private suspend fun create(kind: TopicKind, name: String?, preview: suspend () -> TopicReplacePreview): Result<Long> = result {
        val id = edit {
            // 确认后重新计算，不能用界面早先的预览作为写入依据。
            val p = preview()
            requireTopic(!p.conflict, TopicPolicy.CONFLICT)
            val events = p.events.map { it.id }
            val retained = dao.eventItems(events).filter(TopicPolicy::changed).map { it.id }
            val id = dao.insert(TopicRow(kind = kind.name, name = name.clean(), status = "Ended", createdAt = now(),
                retainedItemIds = JSONArray(retained).toString(), starred = p.starredEventCount > 0))
            dao.span(TopicSpanRow(topicId = id, startAt = p.startAt, endAt = p.endAt))
            dao.attachItems(retained, null)
            dao.releaseEvents(events)
            dao.deleteEvents(events)
            dao.repairMergedItems()
            id
        }
        onEnded(id)
        id
    }
    /** 删除旧生成物前先移开用户动过的条目；保留 id、状态、编辑时间和提醒设置。 */
    suspend fun releaseGenerated(g: TopicGroup): TopicRow {
        val event = g.row.eventId?.let { dao.event(it) }
        val retained = (g.row.retainedIds() + dao.eventItems(listOfNotNull(g.row.eventId)).filter(TopicPolicy::changed).map { it.id }).distinct()
        dao.attachItems(retained, null)
        dao.releaseEvents(listOfNotNull(g.row.eventId))
        dao.deleteEvents(listOfNotNull(g.row.eventId))
        g.row.cardId?.let { dao.deleteCard(it) }
        dao.repairMergedItems()
        return g.row.copy(eventId = null, cardId = null, retainedItemIds = JSONArray(retained).toString(), starred = event?.starred ?: g.row.starred)
    }
    override suspend fun cut(topicId: Long, start: Long, end: Long) {
        val shouldGenerate = edit {
            requireTopic(end > start && end <= now(), "请选择已经过去的有效时段")
            val g = get(topicId)
            requireTopic(g.row.status !in setOf("Scheduled", "Cancelled"), "该专题不能剪时段")
            val row = releaseGenerated(g)
            val spans = g.spans.flatMap { span ->
                val until = span.endAt ?: g.deadline()
                if (!TopicPolicy.overlaps(start, end, span.startAt, until)) listOf(span)
                else buildList {
                    if (span.startAt < start) add(span.copy(id = 0, endAt = start))
                    if (end < until) add(span.copy(id = 0, startAt = end))
                }
            }
            dao.deleteSpans(topicId)
            spans.forEach { dao.span(it.copy(id = 0)) }
            val state = if (spans.isEmpty()) "Cancelled" else if (g.row.status in setOf("Running", "Paused")) g.row.status else "Ended"
            dao.update(row.copy(status = state, revision = row.revision + 1, error = null,
                plannedEndAt = if (state in setOf("Running", "Paused")) g.deadline() else row.plannedEndAt))
            // releaseGenerated 已释放原事件全部句子，未合成时也要解除被剪句子的整理标记。
            g.spans.forEach { span ->
                val a = maxOf(start, span.startAt); val b = minOf(end, span.endAt ?: now())
                if (a < b) dao.releaseRange(a, b)
            }
            state == "Ended"
        }
        if (shouldGenerate) onEnded(topicId)
    }
    override suspend fun cancel(topicId: Long) { edit {
        val g = get(topicId)
        if (g.row.status != "Cancelled") {
            val row = releaseGenerated(g)
            dao.closeSpan(topicId, minOf(now(), g.deadline()))
            // 只释放实际时段；预约没有已归属原话。
            if (g.row.status != "Scheduled") g.spans.forEach { dao.releaseRange(it.startAt, it.endAt ?: minOf(now(), g.deadline())) }
            dao.update(row.copy(status = "Cancelled", revision = row.revision + 1, error = null))
        }
    } }
    override suspend fun regenerate(topicId: Long) {
        edit {
            val g = get(topicId)
            requireTopic(g.row.status in setOf("Ended", "Summarizing", "Done", "Failed"), "请先结束专题再重新生成")
            val row = releaseGenerated(g)
            dao.update(row.copy(status = "Ended", revision = row.revision + 1, error = null))
        }
        onEnded(topicId)
    }

    /** 闹钟、启动恢复与分钟巡检共用；所有提醒标记与状态转换持久化、原子写入。 */
    suspend fun tick() {
        val ended = mutableListOf<Long>()
        val reminders = mutableListOf<Pair<Long, TopicReminder>>()
        edit {
            val at = now()
            for (original in dao.all()) {
                var g = original
                if (g.row.status == "Scheduled" && g.row.plannedStartAt!! <= at) {
                    val start = g.row.plannedStartAt!!; val end = g.row.plannedEndAt!!
                    dao.deleteSpans(g.row.id)
                    dao.span(TopicSpanRow(topicId = g.row.id, startAt = start, endAt = if (end <= at) end else null))
                    dao.update(g.row.copy(status = if (end <= at) "Ended" else "Running", revision = g.row.revision + 1))
                    if (!recordingEnabled()) reminders += g.row.id to TopicReminder.RecordingOff
                    if (end <= at) ended += g.row.id
                    val next = TopicPolicy.next(start, TopicPolicy.days(g.row.repeatRule), maxOf(at, start), zone())
                    if (next != null) {
                        checkConflict(next, next + end - start, g.row.id, TopicPolicy.days(g.row.repeatRule))
                        insertSchedule(g.row.kind, g.row.name, next, next + end - start, g.row.repeatRule)
                    }
                    g = get(g.row.id)
                }
                if (g.row.status !in setOf("Running", "Paused")) continue
                val deadline = g.deadline()
                if (at >= deadline) { finish(g, deadline); ended += g.row.id; continue }
                var row = g.row
                if (row.plannedEndAt != null && at >= deadline - 5 * 60_000L && row.endingNotifiedAt != deadline) {
                    reminders += row.id to TopicReminder.Ending
                    row = row.copy(endingNotifiedAt = deadline)
                }
                if (row.status == "Running") {
                    val span = g.spans.lastOrNull { it.endAt == null }
                    if (span != null) {
                        val last = maxOf(span.startAt, row.createdAt, dao.lastRecording(span.startAt, at) ?: span.startAt)
                        if (at - last >= TopicPolicy.QUIET && (row.quietNotifiedAfter == null || row.quietNotifiedAfter < last)) {
                            reminders += row.id to TopicReminder.Quiet
                            row = row.copy(quietNotifiedAfter = last)
                        }
                    }
                }
                if (row != g.row) dao.update(row)
            }
        }
        reminders.forEach { (id, type) -> onReminder(id, type) }
        ended.distinct().forEach { onEnded(it) }
    }
}

fun TopicRow.retainedIds(): List<Long> = JSONArray(retainedItemIds).let { a -> (0 until a.length()).map { a.getLong(it) } }
private fun String?.clean() = this?.trim()?.takeIf { it.isNotEmpty() }
