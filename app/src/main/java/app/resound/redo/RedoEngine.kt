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

/** 网络阶段只生成候选结果；事件/条目/技能卡和 applied 标记在同一数据库事务内提交。 */
internal class RedoEngine(
    private val db: CaptureDatabase,
    private val chooser: AsrChooser,
    private val recognize: suspend (SegmentEntity, TranscribeSource) -> AsrResult,
    private val digest: suspend (List<TranscriptLineEntity>, String?) -> RedoDigest,
    private val identify: suspend (List<Long>) -> Unit = {},
    private val regenerate: suspend (List<SkillCardRow>, List<Pair<MemoryEventEntity, List<Long>>>, List<TranscriptLineEntity>) -> List<SkillCardRow> = { _, _, _ -> emptyList() },
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val dao = db.redoData()
    private val snapshots = RedoSnapshot(db)
    suspend fun select(request: RedoRequest): RedoSelection {
        val chosen = request.eventIds.distinct().mapNotNull { db.memory().event(it) }
        val explicit = db.transcribe().exportSegments(request.segmentIds.distinct())
        val ranges = mergedRanges(if (request.scope == RedoScope.TranscribeOnly) explicit.map { RedoRange(it.segment.startAt, it.segment.endAt) }
            else chosen.map { RedoRange(it.startAt, it.endAt) })
        val events = if (request.scope == RedoScope.TranscribeOnly) emptyList() else ranges.flatMap { dao.events(it.start, it.end) }.distinctBy { it.id }
        val segments = if (request.scope == RedoScope.TranscribeOnly) explicit else db.transcribe().exportSegments(ranges.flatMap { dao.segments(it.start, it.end) }.map { it.id }.distinct())
        return RedoSelection(ranges, events, segments)
    }
    suspend fun estimate(request: RedoRequest): RedoEstimate {
        val s = select(request)
        val available = s.segments.filter { File(it.segment.path).isFile }
        val duration = available.sumOf { (it.segment.endAt - it.segment.startAt).coerceAtLeast(0) }
        val asr = if (request.scope == RedoScope.DigestOnly) AsrEstimate(0, 0.0) else chooser.estimate(duration, request.asr ?: chooser.lastChoice.value)
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
        if (scope != RedoScope.DigestOnly) put("segments", "id IN (${s.segments.map { it.segment.id }.sqlIds()})")
    }
    private fun cardWhere(ranges: List<RedoRange>) = if (ranges.isEmpty()) "0" else
        "deletedAt IS NULL AND (" + ranges.joinToString(" OR ") { "(at < ${it.end} AND COALESCE(endAt, at + 1) > ${it.start})" } + ")"

    suspend fun run(id: String, request: RedoRequest, checkpoint: () -> Unit, progress: suspend (String, Float) -> Unit) {
        var snapshot = dao.snapshot()?.takeIf { it.jobId == id }
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
        if (request.scope != RedoScope.DigestOnly) {
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
                            error = null, nextRetryAt = null, firstFailedAt = null, voiceprintProcessedAt = null), lines, now())
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
        val batches = s.ranges.flatMap { range -> lines.filter { range.overlaps(it.startAt, it.endAt) && seenLines.add(it.id) }.chunked(DigestRunner.MAX_LINES) }
        val parts = batches.map { batch -> checkpoint(); digest(batch, request.llmPresetId) }
        var events = parts.flatMap { it.events }
        val starred = s.events.filter { it.starred }.mapNotNull { old -> events.indices.maxByOrNull { i -> overlap(old.startAt, old.endAt, events[i].first.startAt, events[i].first.endAt) }
            ?.takeIf { i -> overlap(old.startAt, old.endAt, events[i].first.startAt, events[i].first.endAt) > 0 } }.toSet()
        events = events.mapIndexed { i, pair -> pair.first.copy(starred = i in starred, starredAt = if (i in starred) now() else null) to pair.second }
        val cards = dao.cards().filter { c -> s.ranges.any { it.overlaps(c.at, c.endAt ?: c.at + 1) } }
        val newCards = regenerate(cards.filterNot { it.manual }, events, lines)
        checkpoint(); currentCoroutineContext().ensureActive()
        db.withTransaction {
            checkpoint()
            check(snapshots.unchanged(payload.getJSONObject("after"))) { "记录已被修改，请重新选择后重做" }
            val oldItems = dao.items(s.events.map { it.id })
            val preserved = oldItems.filter { (it.category in setOf("待办", "约定") && it.status in setOf("Accepted", "Done", "Ignored")) || it.deleted || it.editedAt != null }
            // 保留条目先解除旧事件外键；避免删除事件时级联删除用户状态。
            preserved.forEach { dao.item(it.copy(eventId = null)) }
            val sql = db.openHelper.writableDatabase
            sql.execSQL("UPDATE transcript_lines SET eventId = NULL WHERE eventId IN (${s.events.map { it.id }.sqlIds()})")
            sql.execSQL("DELETE FROM memory_events WHERE id IN (${s.events.map { it.id }.sqlIds()})")
            val ids = events.map { (event, refs) -> db.memory().insertEvent(event).also { db.memory().assignLines(refs, it) } }
            var offset = 0
            val incoming = parts.flatMap { part -> part.items(ids.subList(offset, offset + part.events.size)).also { offset += part.events.size } }
            // 沿用 saveDigest 的保护规则：同类别、同原话/同旧事件或相同文本不重复生成。
            val sourceEvents = lines.associate { it.id to it.eventId }
            val kept = incoming.filterNot { fresh -> preserved.any { old ->
                (old.category == fresh.category || old.category in setOf("待办", "约定", "备忘") && fresh.category in setOf("待办", "约定", "备忘")) &&
                    (old.content.trim() == fresh.content.trim() || fresh.lineId != null && fresh.lineId == old.lineId ||
                        old.eventId != null && (sourceEvents[fresh.lineId] == old.eventId || fresh.lineId == null))
            } }
            db.memory().insertItems(kept)
            preserved.forEach { old ->
                val event = s.events.find { it.id == old.eventId }
                val target = event?.let { e -> events.indices.maxByOrNull { i -> overlap(e.startAt, e.endAt, events[i].first.startAt, events[i].first.endAt) } }
                dao.item(old.copy(eventId = target?.let { ids[it] }))
            }
            db.memory().markDigested(lines.map { it.id }, now())
            sql.execSQL("DELETE FROM skill_cards WHERE id IN (${cards.filterNot { it.manual }.map { it.id }.sqlIds()})")
            val cardIds = newCards.filterNot { fresh -> cards.any { it.manual && it.skillId == fresh.skillId && (it.entity == fresh.entity || overlap(it.at, it.endAt ?: it.at + 1, fresh.at, fresh.endAt ?: fresh.at + 1) > 0) } }.map { db.skills().save(it.copy(id = 0)) }
            // 提交后按新旧 ID 并集记录，撤销只触碰这些行，不吞掉后来新增的录音/事件。
            val afterSelectors = trackedSelectors.toMutableMap()
            afterSelectors["memory_events"] = "id IN (${ids.sqlIds()})"
            afterSelectors["memory_items"] = "eventId IN (${ids.sqlIds()}) OR id IN (${(preserved.map { it.id } + originalItems).sqlIds()})"
            afterSelectors["skill_cards"] = "id IN (${(cardIds + cards.filter { it.manual }.map { it.id }).sqlIds()})"
            payload.put("after", snapshots.capture(afterSelectors))
            dao.snapshot(snapshot!!.copy(payload = payload.toString(), applied = true))
        }
    }
    suspend fun undo(id: String): Result<Unit> = try {
        db.withTransaction {
            val row = dao.snapshot() ?: error("撤销快照已失效")
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
