package app.resound.redo

import androidx.room.withTransaction
import app.resound.data.*
import app.resound.digest.*
import app.resound.transcribe.*
import app.resound.skills.SkillCardRow
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate

internal data class RedoSelection(val ranges: List<RedoRange>, val events: List<MemoryEventEntity>, val segments: List<SegmentWithLines>)
internal data class RedoDigest(val events: List<Pair<MemoryEventEntity, List<Long>>>, val items: (List<Long>) -> List<MemoryItemEntity>)

/** 合并的取料结果：严格按事件 id 取原话，不按时间范围捞。 */
internal data class MergeSelection(val events: List<MemoryEventEntity>, val lines: List<TranscriptLineEntity>)

/** 网络阶段只生成候选结果；事件/条目/技能卡和 applied 标记在同一数据库事务内提交。 */
internal class RedoEngine(
    private val db: CaptureDatabase,
    private val chooser: AsrChooser,
    private val recognize: suspend (SegmentEntity, TranscribeSource) -> AsrResult,
    private val digest: suspend (List<TranscriptLineEntity>, String?) -> RedoDigest,
    /** 合并：按这几件事已整理好的结果写成一件（参数：旧事件、它们的原话、模型） */
    private val merge: suspend (List<MemoryEventEntity>, List<TranscriptLineEntity>, String?) -> RedoDigest = { _, _, _ -> RedoDigest(emptyList()) { emptyList() } },
    private val identify: suspend (List<Long>) -> Unit = {},
    private val regenerate: suspend (List<SkillCardRow>, List<Pair<MemoryEventEntity, List<Long>>>, List<TranscriptLineEntity>) -> List<SkillCardRow> = { _, _, _ -> emptyList() },
    private val now: () -> Long = System::currentTimeMillis,
    /** 停多久算说完（与自动整理同一设置）；选中的相邻事件间隔不超过它就并成一段一起重新整理 */
    private val pauseMillis: () -> Long = { DigestRunner.GAP_MS },
) {
    private val dao = db.redoData()
    private val snapshots = RedoSnapshot(db)
    suspend fun select(request: RedoRequest): RedoSelection {
        val chosen = request.eventIds.distinct().mapNotNull { db.memory().event(it) }
        val explicit = db.transcribe().exportSegments(request.segmentIds.distinct())
        val picked = mergedRanges(if (request.scope == RedoScope.TranscribeOnly) explicit.map { RedoRange(it.segment.startAt, it.segment.endAt) }
            else chosen.map { RedoRange(it.startAt, it.endAt) })
        val ranges = if (request.scope == RedoScope.TranscribeOnly) picked else joinNearby(picked, chosen.map { it.id }.toSet())
        val events = if (request.scope == RedoScope.TranscribeOnly) emptyList() else ranges.flatMap { dao.events(it.start, it.end) }.distinctBy { it.id }
        val segments = if (request.scope == RedoScope.TranscribeOnly) explicit else db.transcribe().exportSegments(ranges.flatMap { dao.segments(it.start, it.end) }.map { it.id }.distinct())
        return RedoSelection(ranges, events, segments)
    }
    /**
     * 选中的相邻事件挨得近（间隔不超过停顿时长）、中间也没有没选的事件，就连成一段，
     * 这样一起重新整理，碎事件才能合并；否则每个事件各整理各的，碎的还是碎的。
     */
    private suspend fun joinNearby(ranges: List<RedoRange>, selected: Set<Long>): List<RedoRange> {
        val gap = pauseMillis()
        val out = mutableListOf<RedoRange>()
        for (r in ranges) {
            val last = out.lastOrNull()
            if (last != null && r.start - last.end <= gap && dao.events(last.end, r.start).all { it.id in selected })
                out[out.lastIndex] = last.copy(end = maxOf(last.end, r.end))
            else out += r
        }
        return out
    }

    /**
     * 合并的取料：只取选中事件自己的原话（按 eventId 关联），不按时间范围捞——
     * 否则中间没选中的事会被顺带卷进来重做。
     */
    suspend fun selectMerge(request: RedoRequest): MergeSelection {
        val events = request.eventIds.distinct().mapNotNull { db.memory().event(it) }.sortedBy { it.startAt }
        val lines = if (events.isEmpty()) emptyList()
            else dao.eventLines(events.map { it.id }).distinctBy { it.id }.sortedBy { it.startAt }
        return MergeSelection(events, lines)
    }

    /** 相邻两条事件之间的最大间隔；不足两条为 0。 */
    private fun maxGap(events: List<MemoryEventEntity>): Long =
        events.sortedBy { it.startAt }.zipWithNext().maxOfOrNull { (a, b) -> b.startAt - a.endAt } ?: 0L


    suspend fun estimate(request: RedoRequest): RedoEstimate {
        if (request.scope == RedoScope.Merge) {
            val s = selectMerge(request)
            return RedoEstimate(
                events = s.events.size, segments = 0, audioMs = 0, expiredEvents = 0, expiredSegments = 0,
                editedEvents = s.events.count { it.editedAt != null },
                etaMillis = 15_000L, costYuan = null,
                mergeLines = s.lines.size, mergeGapMillis = maxGap(s.events), mergeTooLong = false,
            )
        }
        val s = select(request)
        val available = s.segments.filter { File(it.segment.path).isFile }
        val duration = available.sumOf { (it.segment.endAt - it.segment.startAt).coerceAtLeast(0) }
        val asr = if (!request.scope.transcribes) AsrEstimate(0, 0.0) else chooser.estimate(duration, request.asr ?: chooser.lastChoice.value)
        val expired = s.events.count { event -> s.segments.none { File(it.segment.path).isFile && overlap(event.startAt, event.endAt, it.segment.startAt, it.segment.endAt) > 0 } }
        return RedoEstimate(s.events.size, s.segments.size, duration, expired, s.segments.size - available.size,
            s.events.count { it.editedAt != null }, asr.etaMillis?.let { it + if (request.scope == RedoScope.TranscribeOnly) 0 else 15_000L * s.ranges.size },
            if (request.scope == RedoScope.TranscribeOnly) asr.costYuan else null)
    }
    private fun selectors(s: RedoSelection, scope: RedoScope): Map<String, String> = linkedMapOf<String, String>().apply {
        if (scope != RedoScope.TranscribeOnly) {
            put("memory_events", "id IN (${s.events.map { it.id }.sqlIds()})")
            put("memory_items", "eventId IN (${s.events.map { it.id }.sqlIds()})")
            put("skill_cards", cardWhere(s.ranges))
        }
        val refs = "SELECT id FROM transcript_lines WHERE segmentId IN (${s.segments.map { it.segment.id }.sqlIds()})"
        put("memory_items", "eventId IN (${s.events.map { it.id }.sqlIds()}) OR lineId IN ($refs) OR doneHintLineId IN ($refs)")
        put("transcript_lines", "segmentId IN (${s.segments.map { it.segment.id }.sqlIds()}) OR eventId IN (${s.events.map { it.id }.sqlIds()})")
        if (scope.transcribes) put("segments", "id IN (${s.segments.map { it.segment.id }.sqlIds()})")
    }

    /** 合并的快照范围：旧事件、它们的条目、参与的原话行、这段时间的技能卡。 */
    private fun mergeSelectors(events: List<MemoryEventEntity>, lines: List<TranscriptLineEntity>, span: RedoRange): Map<String, String> =
        linkedMapOf<String, String>().apply {
            put("memory_events", "id IN (${events.map { it.id }.sqlIds()})")
            put("memory_items", "eventId IN (${events.map { it.id }.sqlIds()})")
            put("transcript_lines", "id IN (${lines.map { it.id }.sqlIds()})")
            put("skill_cards", cardWhere(listOf(span)))
        }

    private fun cardWhere(ranges: List<RedoRange>) = if (ranges.isEmpty()) "0" else
        "deletedAt IS NULL AND (" + ranges.joinToString(" OR ") { "(at < ${it.end} AND COALESCE(endAt, at + 1) > ${it.start})" } + ")"

    suspend fun run(id: String, request: RedoRequest, checkpoint: () -> Unit, progress: suspend (String, Float) -> Unit) {
        if (request.scope == RedoScope.Merge) { runMerge(id, request, checkpoint, progress); return }
        var snapshot = dao.fullSnapshot()?.takeIf { it.jobId == id }
        if (snapshot?.applied == true) return // 已提交但未发布终态时被杀，不重复收费/替换。
        val s = select(request)
        require(s.ranges.isNotEmpty()) { "所选记录已不存在" }
        val selectors = selectors(s, request.scope)
        if (snapshot == null) {
            db.withTransaction {
                checkpoint()
                val data = snapshots.capture(selectors)
                snapshot = RedoSnapshotRow(jobId = id, at = now(), payload = JSONObject().put("before", data).put("after", data).toString())
                dao.snapshot(snapshot!!)
            }
        }
        val payload = JSONObject(snapshot!!.payload)
        // 重新启动时仍用第一次快照；已转写段可从头重跑，但撤销目标不变。
        val before = payload.getJSONObject("before")
        val originalItems = before.getJSONArray("memory_items").let { a -> (0 until a.length()).map { a.getJSONObject(it).getLong("id") } }
        val trackedSelectors = selectors.toMutableMap().apply { put("memory_items", "${selectors.getValue("memory_items")} OR id IN (${originalItems.sqlIds()})") }
        suspend fun saveAfter(applied: Boolean = false) {
            payload.put("after", snapshots.capture(trackedSelectors))
            dao.snapshot(snapshot!!.copy(payload = payload.toString(), applied = applied))
        }
        if (request.scope.transcribes) {
            val available = s.segments.filter { File(it.segment.path).isFile }
            require(available.isNotEmpty()) { "原声全部过期，请选择只重新整理" }
            for ((index, row) in available.withIndex()) {
                checkpoint(); currentCoroutineContext().ensureActive()
                progress("重新转写 ${index + 1}/${available.size} 段", index.toFloat() / available.size * .65f)
                val segment = db.transcribe().findSegment(row.segment.id) ?: error("录音已删除，请重新选择")
                try {
                    val result = recognize(segment, request.asr ?: chooser.lastChoice.value)
                    currentCoroutineContext().ensureActive(); checkpoint()
                    db.withTransaction {
                        checkpoint()
                        check(snapshots.unchanged(payload.getJSONObject("after"))) { "记录已被修改，请重新重做" }
                        check(db.transcribe().findSegment(segment.id) != null) { "录音已删除" }
                        val lines = result.sentences.map { line ->
                            val start = segment.startAt + line.startMillis; val end = segment.startAt + line.endMillis
                            val old = row.lines.maxByOrNull { overlap(start, end, it.startAt, it.endAt) }
                            TranscriptLineEntity(segmentId = segment.id, startAt = start, endAt = end, text = line.text,
                                speakerTag = line.speakerTag, originalText = line.originalText, eventId = old?.eventId,
                                // 重转写不自动让常规整理再次产出事件；明确重新整理时才替换。
                                digestedAt = old?.digestedAt ?: now())
                        }
                        db.transcribe().completeRedo(db.transcribe().segment(segment.id).copy(status = if (lines.isEmpty()) "Empty" else "Done", completedAt = now(),
                            error = null, nextRetryAt = null, firstFailedAt = null, voiceprintProcessedAt = if (segment.separateSpeakers == false) now() else null), lines, now())
                        saveAfter()
                    }
                } catch (e: CancellationException) { throw e }
                catch (e: AsrException) {
                    db.withTransaction {
                        check(snapshots.unchanged(payload.getJSONObject("after"))) { "记录已被修改，请重新重做" }
                        val attempts = segment.attempts + 1
                        db.transcribe().findSegment(segment.id)?.let { db.transcribe().update(it.copy(status = if (e.type == AsrError.Audio) "Abandoned" else "Failed",
                            attempts = attempts, error = e.type.human, firstFailedAt = it.firstFailedAt ?: now(),
                            redoPending = true, asrSource = (request.asr ?: chooser.lastChoice.value).name, nextRetryAt = TranscribePolicy.retryAt(e.type, attempts, now()))) }
                        saveAfter()
                    }
                    throw e
                }
            }
            try { identify(available.map { it.segment.id }) }
            finally { withContext(NonCancellable) { db.withTransaction {
                check(snapshots.unchanged(payload.getJSONObject("after"), voiceprint = true)) { "记录已被修改，请重新重做" }
                saveAfter()
            } } }
        }
        checkpoint(); currentCoroutineContext().ensureActive()
        if (request.scope == RedoScope.TranscribeOnly) {
            db.withTransaction { checkpoint(); saveAfter(true) }; return
        }
        progress("重新整理", .7f)
        val lines = (db.transcribe().exportSegments(s.segments.map { it.segment.id }).flatMap { it.lines } + dao.eventLines(s.events.map { it.id }))
            .distinctBy { it.id }.filter { l -> s.ranges.any { it.overlaps(l.startAt, l.endAt) } }.sortedBy { it.startAt }
        require(lines.isNotEmpty()) { "这些时间段没有可整理的原话" }
        val seenLines = mutableSetOf<Long>()
        val batches = s.ranges.flatMap { range -> DigestRunner.batches(lines.filter { range.overlaps(it.startAt, it.endAt) && seenLines.add(it.id) }) }
        suspend fun digestWithRetry(batch: List<TranscriptLineEntity>): List<RedoDigest> {
            checkpoint(); currentCoroutineContext().ensureActive()
            return try {
                listOf(digest(batch, request.llmPresetId))
            } catch (e: LlmException) {
                if (e.type != LlmError.Format) throw e
                val (first, second) = splitForRetry(batch) ?: throw e
                // 保持两半的时间顺序，供后面的事件与条目 ID 按原偏移量拼接。
                digestWithRetry(first) + digestWithRetry(second)
            }
        }
        // 一天的内容会分成很多批，每批更新一次进度，免得停在 70% 像卡住
        val parts = batches.flatMapIndexed { index, batch ->
            checkpoint()
            if (batches.size > 1) progress("重新整理 ${index + 1}/${batches.size}", .7f + .25f * index / batches.size)
            digestWithRetry(batch)
        }
        var events = parts.flatMap { it.events }
        val starred = s.events.filter { it.starred }.mapNotNull { old -> events.indices.maxByOrNull { i -> overlap(old.startAt, old.endAt, events[i].first.startAt, events[i].first.endAt) }
            ?.takeIf { i -> overlap(old.startAt, old.endAt, events[i].first.startAt, events[i].first.endAt) > 0 } }.toSet()
        events = events.mapIndexed { i, pair -> pair.first.copy(starred = i in starred, starredAt = if (i in starred) now() else null) to pair.second }
        val cards = dao.cards().filter { c -> s.ranges.any { it.overlaps(c.at, c.endAt ?: c.at + 1) } }
        val newCards = regenerate(cards.filterNot { it.manual }, events, lines)
        checkpoint(); currentCoroutineContext().ensureActive()
        commitReplace(
            payload = payload, snapshotRow = snapshot!!, trackedSelectors = trackedSelectors, originalItems = originalItems,
            oldEvents = s.events, newEvents = events, itemsFor = { ids ->
                var offset = 0
                parts.flatMap { part -> part.items(ids.subList(offset, offset + part.events.size)).also { offset += part.events.size } }
            },
            participatingLines = lines, cards = cards, newCards = newCards, checkpoint = checkpoint,
        )
    }

    /**
     * 合并：把选中几件事的原话合起来重新分析，替换成一条。
     * 原话不重转写；间隔太久、原话太多都不让合。
     */
    private suspend fun runMerge(id: String, request: RedoRequest, checkpoint: () -> Unit, progress: suspend (String, Float) -> Unit) {
        var snapshot = dao.fullSnapshot()?.takeIf { it.jobId == id }
        if (snapshot?.applied == true) return
        val s = selectMerge(request)
        require(s.events.size >= 2) { "至少选两件事才能合并" }
        require(maxGap(s.events) < MERGE_MAX_GAP_MS) { "选中的事相隔太久，只能合并前后挨着的事" }
        require(s.lines.isNotEmpty()) { "这些事没有可合并的原话" }
        val span = RedoRange(
            minOf(s.events.minOf { it.startAt }, s.lines.first().startAt),
            maxOf(s.events.maxOf { it.endAt }, s.lines.maxOf { it.endAt }),
        )
        val selectors = mergeSelectors(s.events, s.lines, span)
        if (snapshot == null) {
            db.withTransaction {
                checkpoint()
                val data = snapshots.capture(selectors)
                snapshot = RedoSnapshotRow(jobId = id, at = now(), payload = JSONObject().put("before", data).put("after", data).toString())
                dao.snapshot(snapshot!!)
            }
        }
        val payload = JSONObject(snapshot!!.payload)
        val before = payload.getJSONObject("before")
        val originalItems = before.getJSONArray("memory_items").let { a -> (0 until a.length()).map { a.getJSONObject(it).getLong("id") } }
        val trackedSelectors = selectors.toMutableMap().apply { put("memory_items", "${selectors.getValue("memory_items")} OR id IN (${originalItems.sqlIds()})") }
        progress("合并中", .3f)
        val result = merge(s.events, s.lines, request.llmPresetId)
        require(result.events.size == 1) { "模型没能合成一条，请再试一次" }
        val (event, refs) = result.events.single()
        checkpoint(); currentCoroutineContext().ensureActive()
        // 重点：选中里有一条是重点，合并后就是重点。
        val starred = s.events.any { it.starred }
        val newEvent = event.copy(starred = starred, starredAt = if (starred) now() else null)
        val cards = dao.cards().filter { c -> span.overlaps(c.at, c.endAt ?: c.at + 1) }
        // 技能卡按这段时间的完整原话重建，避免只看到合并的那几句。
        val context = db.transcribe().exportSegments(dao.segments(span.start, span.end).map { it.id })
            .flatMap { it.lines }.distinctBy { it.id }.sortedBy { it.startAt }
        val newCards = regenerate(cards.filterNot { it.manual }, listOf(newEvent to refs), context)
        checkpoint(); currentCoroutineContext().ensureActive()
        commitReplace(
            payload = payload, snapshotRow = snapshot!!, trackedSelectors = trackedSelectors, originalItems = originalItems,
            oldEvents = s.events, newEvents = listOf(newEvent to refs), itemsFor = { emptyList() }, keepAllItems = true,
            participatingLines = s.lines, cards = cards, newCards = newCards, checkpoint = checkpoint,
        )
    }

    /**
     * 提交一次"用新事件替换旧事件"：删旧事件、把原话行改指新事件、写提取项、
     * 保留用户已确认的状态、重建技能卡。重做与合并共用这一段。
     */
    private suspend fun commitReplace(
        payload: JSONObject,
        snapshotRow: RedoSnapshotRow,
        trackedSelectors: Map<String, String>,
        originalItems: List<Long>,
        oldEvents: List<MemoryEventEntity>,
        newEvents: List<Pair<MemoryEventEntity, List<Long>>>,
        itemsFor: (List<Long>) -> List<MemoryItemEntity>,
        participatingLines: List<TranscriptLineEntity>,
        cards: List<SkillCardRow>,
        newCards: List<SkillCardRow>,
        checkpoint: () -> Unit,
        /** 合并：旧事件的提取项全部原样保留、改挂到新事件，不重新生成 */
        keepAllItems: Boolean = false,
    ) {
        val oldIds = oldEvents.map { it.id }
        db.withTransaction {
            checkpoint()
            check(snapshots.unchanged(payload.getJSONObject("after"))) { "记录已被修改，请重新选择后重做" }
            val oldItems = dao.items(oldIds)
            val preserved = if (keepAllItems) oldItems else
                oldItems.filter { (it.category in setOf("待办", "约定") && it.status in setOf("Accepted", "Done", "Ignored")) || it.deleted || it.editedAt != null }
            // 保留条目先解除旧事件外键；避免删除事件时级联删除用户状态。
            preserved.forEach { dao.item(it.copy(eventId = null)) }
            val sql = db.openHelper.writableDatabase
            sql.execSQL("UPDATE transcript_lines SET eventId = NULL WHERE eventId IN (${oldIds.sqlIds()})")
            sql.execSQL("DELETE FROM memory_events WHERE id IN (${oldIds.sqlIds()})")
            val ids = newEvents.map { (event, refs) -> db.memory().insertEvent(event).also { db.memory().assignLines(refs, it) } }
            val incoming = itemsFor(ids)
            // 沿用 saveDigest 的保护规则：同类别、同原话/同旧事件或相同文本不重复生成。
            val sourceEvents = participatingLines.associate { it.id to it.eventId }
            val kept = incoming.filterNot { fresh -> preserved.any { old ->
                (old.category == fresh.category || old.category in setOf("待办", "约定", "备忘") && fresh.category in setOf("待办", "约定", "备忘")) &&
                    (old.content.trim() == fresh.content.trim() || fresh.lineId != null && fresh.lineId == old.lineId ||
                        old.eventId != null && (sourceEvents[fresh.lineId] == old.eventId || fresh.lineId == null))
            } }
            db.memory().insertItems(kept)
            preserved.forEach { old ->
                val event = oldEvents.find { it.id == old.eventId }
                val target = event?.let { e -> newEvents.indices.maxByOrNull { i -> overlap(e.startAt, e.endAt, newEvents[i].first.startAt, newEvents[i].first.endAt) } }
                dao.item(old.copy(eventId = target?.let { ids[it] }))
            }
            db.memory().markDigested(participatingLines.map { it.id }, now())
            sql.execSQL("DELETE FROM skill_cards WHERE id IN (${cards.filterNot { it.manual }.map { it.id }.sqlIds()})")
            val cardIds = newCards.filterNot { fresh -> cards.any { it.manual && it.skillId == fresh.skillId && (it.entity == fresh.entity || overlap(it.at, it.endAt ?: it.at + 1, fresh.at, fresh.endAt ?: fresh.at + 1) > 0) } }.map { db.skills().save(it.copy(id = 0)) }
            // 提交后按新旧 ID 并集记录，撤销只触碰这些行，不吞掉后来新增的录音/事件。
            val afterSelectors = trackedSelectors.toMutableMap()
            afterSelectors["memory_events"] = "id IN (${ids.sqlIds()})"
            afterSelectors["memory_items"] = "eventId IN (${ids.sqlIds()}) OR id IN (${(preserved.map { it.id } + originalItems).sqlIds()})"
            afterSelectors["skill_cards"] = "id IN (${(cardIds + cards.filter { it.manual }.map { it.id }).sqlIds()})"
            payload.put("after", snapshots.capture(afterSelectors))
            dao.snapshot(snapshotRow.copy(payload = payload.toString(), applied = true))
        }
    }

    suspend fun undo(id: String): Result<Unit> = try {
        db.withTransaction {
            val row = dao.fullSnapshot() ?: error("撤销快照已失效")
            check(row.jobId == id && now() - row.at < 86_400_000) { "只能撤销一天内最近一次重做" }
            val payload = JSONObject(row.payload); val after = payload.getJSONObject("after"); val before = payload.getJSONObject("before")
            check(snapshots.unchanged(after)) { "记录在重做后又有变化，无法安全撤销" }
            snapshots.delete(after)
            snapshots.restore(before)
            dao.clear()
        }
        Result.success(Unit)
    } catch (e: CancellationException) { throw e } catch (e: Exception) { Result.failure(e) }
}
