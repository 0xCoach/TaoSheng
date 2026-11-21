package app.resound.topic

import app.resound.diagnostics.FailureNotes

// 与安卓 DigestPrompt 适配器同名，core ParsedEvent 保留全限定名。
import app.resound.digest.core.MemoryEventData
import app.resound.topic.core.TopicGenerationRules

import app.resound.data.writeTransaction
import app.resound.data.*
import app.resound.digest.*
import app.resound.importer.*
import app.resound.skills.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.ZoneId
import app.resound.digest.snapshot
import app.resound.skills.android
import app.resound.topic.core.MeetingOpenItem
import app.resound.topic.core.MeetingSeriesRules
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

internal data class TopicOutput(val event: MemoryEventEntity, val items: List<DigestPrompt.ParsedItem>, val card: SkillCardRow?)

internal class TopicGenerator(
    private val template: () -> String,
    private val importTemplate: (ImportKind) -> Pair<Skill, String>,
    private val request: suspend (String, String) -> LlmReply,
    private val hotwords: () -> List<String> = { emptyList() },
    private val now: () -> Long = System::currentTimeMillis,
    private val appName: String = "声声不息",
    private val marks: (suspend (Long, Long) -> List<app.resound.marks.MarkRow>)? = null,
) {
    val blocks = app.resound.topic.core.TopicBlockGenerator(template, { importTemplate(ImportKind.valueOf(it.name)) },
        { system, user -> request(system, user).content }, hotwords, now,
        { kotlinx.datetime.TimeZone.of(ZoneId.systemDefault().id) }, appName)
    suspend fun context(block: app.resound.topic.core.TopicBlock, lines: List<TranscriptLineEntity>): String {
        val stored = marks?.invoke(block.startAt, block.endAt).orEmpty()
        return app.resound.marks.MarkPrompt.context(stored, lines.map { it.snapshot() }, true)
    }
    companion object {
        const val SCRIPT_PROMPT = TopicGenerationRules.SCRIPT_PROMPT
        fun scriptSize(text: String) = TopicGenerationRules.scriptSize(text)
    }
}

internal class TopicEngine(
    private val db: CaptureDatabase,
    private val repository: RoomTopicRepository,
    private val generator: TopicGenerator,
    private val voiceprintReady: suspend () -> Boolean = { false },
    private val recordingPending: (Long, Long) -> Boolean = { _, _ -> false },
    private val now: () -> Long = System::currentTimeMillis,
    private val ready: (Long) -> Unit = {},
    private val checkpoints: TopicCheckpointStore = MemoryTopicCheckpoints(),
) {
    private val gate = Mutex()
    private val dao = db.topics()
    /** 普通 Worker 处理会中；前台 Worker 只处理会后，避免长任务挡住会中入口。 */
    suspend fun run(activeOnly: Boolean? = null, network: Boolean = true, foreground: suspend () -> Unit = {}): Boolean = gate.withLock {
        var pending = false
        // 已完成的专题只在有新原话（未整理或未归到本专题事件）时才补整理；改字、认人不自动重做、不覆盖已有纪要，
        // 需要时由用户点“重新整理”（会把状态改回 Ended）。与 observeLateTranscriptIds 同一条规则，一次查出。
        val late = dao.observeLateTranscriptIds().first().toSet()
        for (initial in dao.all().filter { it.row.status in setOf("Running", "Paused", "Ended", "Summarizing", "Done") &&
            (activeOnly == null || app.resound.topic.core.TopicBlocks.active(it.row.status) == activeOnly) }) {
            if (initial.row.status == "Done" && initial.row.id !in late) continue
            val g = dao.get(initial.row.id) ?: continue
            val revision = g.row.revision
            val active = app.resound.topic.core.TopicBlocks.active(g.row.status)
            var processor: app.resound.topic.core.TopicBlockProcessor? = null
            try {
                val lines = dao.lines(g.row.id)
                val checkpoint = checkpoints.read(g.row.id)
                // 已完成专题：没有新原话就不动（新旧版都一样），不因升级、改字或认人自动重做整段。
                if (g.row.status == "Done" && lines.none { it.eventId != g.row.eventId || it.digestedAt == null }) continue
                val source = topicSource(g, lines)
                val blocks = app.resound.topic.core.TopicBlocks.timeline(g.snapshot(), now())
                val contexts = blocks.associate { it.index to generator.context(it, lines.filter { l -> it.contains(l.startAt) }) }
                val inputKey = topicHash(source + generator.blocks.promptKey(g.snapshot()) + generator.blocks.words(g.snapshot()).toString() + contexts.toString())
                if (g.row.status == "Done" && checkpoint?.progress?.finished == true && checkpoint.source == inputKey) continue
                // 已完成且输入没变的专题到此跳过；逐段认人状态、保留项等只在真要整理时才读（每段转写完都会触发一轮，旧做法每次把所有已完成专题逐段查一遍）。
                val segments = dao.segments(g.row.id, now())
                val recognize = voiceprintReady()
                // 认人待处理一次查出，不逐段查。
                val voicePending = if (!recognize) emptySet() else segments.filter { it.status == "Done" }.map { it.id }
                    .chunked(400).flatMap { db.memory().voiceprintPendingIds(it) }.toSet()
                val segmentStates = segments.map { segment -> app.resound.topic.core.TopicBlockSegment(
                    segment.id, segment.startAt, segment.endAt, segment.status, if (segment.id in voicePending) 1 else 0) }
                val retained = dao.items(g.row.retainedIds()).filterNot { it.deleted }
                val ignored = (db.memory().ignoredForDigest(now() - 60L * 86_400_000) + retained.filter { it.status in setOf("Done", "Ignored") }).distinctBy { it.id }
                processor = app.resound.topic.core.TopicBlockProcessor(g.row.id, checkpoints, generator.blocks, ::topicHash, ::topicFailure, ::delay, now,
                    { java.time.Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).format(java.time.format.DateTimeFormatter.ofPattern("HH:mm")) })
                var announced = false
                // 同系列上一次纪要：结论/决定/行动项 + 仍未闭环的待办与约定，注入本场资料区。
                val previousBlock = g.row.takeIf { it.kind != "Normal" && it.kind != "Script" && (it.seriesId != null || it.seriesKey != null) }
                    ?.let { dao.previousDone(g.row.kind, g.row.seriesKey ?: "", g.row.id, g.row.seriesId) }
                    ?.let { prev ->
                        val card = prev.cardId?.let { db.skills().card(it).first() }
                        val open = prev.eventId?.let { eid -> dao.eventItems(listOf(eid)) }.orEmpty()
                            .filter { !it.deleted && it.category in setOf("待办", "约定") && it.status in setOf("Pending", "Accepted") }
                            .map { MeetingOpenItem(it.kind ?: it.category, it.who, it.content, it.dueText) }
                        val prevStart = dao.get(prev.id)?.spans?.minOfOrNull { it.startAt } ?: prev.createdAt
                        val date = java.time.Instant.ofEpochMilli(prevStart).atZone(ZoneId.systemDefault()).toLocalDate().toString()
                        MeetingSeriesRules.previousContext(prev.name, date, card?.valuesJson, open)
                    } ?: ""
                val outcome = processor.run(g.snapshot(), lines.map { it.snapshot() }, segmentStates,
                    { block -> block.ranges.any { recordingPending(it.startAt, it.endAt) } }, network,
                    {
                        val latestRetained = dao.items((dao.get(g.row.id)?.row ?: g.row).retainedIds()).filterNot { it.deleted }
                        val latestUnfinished = (db.memory().unfinishedForDigest() + latestRetained.filter { it.status !in setOf("Done", "Ignored") }).distinctBy { it.id }
                        val latestIgnored = (db.memory().ignoredForDigest(now() - 60L * 86_400_000) + latestRetained.filter { it.status in setOf("Done", "Ignored") }).distinctBy { it.id }
                        DigestPrompt.withTodoContext("", latestUnfinished, latestIgnored)
                    },
                    { block, _ -> listOf(contexts.getValue(block.index), previousBlock).filter { it.isNotBlank() }.joinToString("\n\n") },
                    db.transcribe().unseparated(lines.map { it.segmentId }.distinct()).toSet(), onWork = {
                        if (!active && !announced) {
                            db.writeTransaction {
                                val latest = dao.get(g.row.id)?.row
                                if (latest?.revision != revision || latest.status !in setOf("Ended", "Summarizing", "Done"))
                                    throw TopicException("专题状态已变更")
                                dao.update(latest.copy(status = "Summarizing"))
                            }
                            foreground(); announced = true
                        }
                    })
                pending = pending || outcome.waiting
                if (active) continue
                val finalSummary = outcome.summary ?: continue
                val currentCheckpoint = checkpoints.read(g.row.id)
                if (g.row.status == "Done" && currentCheckpoint?.progress?.finished == true) { processor.finish(inputKey); continue }
                val claimed = db.writeTransaction {
                    val latest = dao.get(g.row.id)?.row
                    if (latest?.revision != revision || latest.status !in setOf("Ended", "Summarizing", "Done")) false
                    else { dao.update(latest.copy(status = "Summarizing")); true }
                }
                if (!claimed) continue
                val acceptedIds = outcome.acceptedIds
                val result = TopicOutput(outcome.event(g.snapshot(), now).android(), outcome.items.map { it.android() }, finalSummary.card?.android())
                val saved = db.writeTransaction {
                    val latest = dao.get(g.row.id) ?: return@writeTransaction false
                    if (latest.row.revision != revision || latest.row.status != "Summarizing") return@writeTransaction false
                    if (topicSource(latest, dao.lines(g.row.id)) != source || dao.segments(g.row.id, now()) != segments ||
                        (currentCheckpoint?.skipWaiting != true && latest.spans.any { recordingPending(it.startAt, it.endAt ?: now()) })) {
                        dao.update(latest.row.copy(status = "Ended")); pending = true; return@writeTransaction false
                    }
                    val previousEvent = latest.row.eventId?.let { db.memory().event(it) }
                    val replacement = repository.releaseGenerated(latest)
                    val existing = (dao.items(replacement.retainedIds()) + db.memory().protectedItems() + db.memory().unfinishedForDigest() + ignored).distinctBy { it.id }
                    val eventId = db.memory().insertEvent(result.event.copy(starred = replacement.starred,
                        starredAt = if (replacement.starred) previousEvent?.starredAt ?: now() else null))
                    dao.attachItems(replacement.retainedIds(), eventId)
                    val items = result.items.filterNot { item -> existing.any { old ->
                        old.category in setOf(item.category, "待办", "约定", "备忘") &&
                            (item.lineId != null && old.lineId == item.lineId || ImportTodoMerge.same(old.content, item.content))
                    } }.map { item ->
                        val recordedAt = lines.firstOrNull { it.id == item.lineId }?.startAt
                        if (app.resound.data.TemporalGuard.dueViolated(item.dueAt, recordedAt))
                            app.resound.diagnostics.FailureNotes.record("topic/TopicGeneration.kt:dueAt",
                                IllegalStateException("dueAt=${item.dueAt} vs recordedAt=$recordedAt"))
                        val dueAt = app.resound.data.TemporalGuard.sanitizeDue(item.dueAt, recordedAt)
                        MemoryItemEntity(eventId = eventId, category = item.category, kind = item.kind, content = item.content,
                        dueText = item.dueText, dueAt = dueAt, who = item.who, lineId = item.lineId, status = "Pending", createdAt = now(),
                        recordedAt = recordedAt,
                        valueScore = app.resound.data.MemoryValueRules.itemScore(item.category, item.kind, dueAt, item.who, "digest")) }
                    db.memory().insertItems(items)
                    db.memory().assignLines(acceptedIds, eventId)
                    db.memory().markDigested(acceptedIds, now())
                    val cardId = result.card?.let { db.skills().save(it) }
                    dao.update(replacement.copy(eventId = eventId, cardId = cardId, status = "Done", error = null))
                    true
                }
                if (saved) { runCatching { processor.finish(inputKey) }.onFailure {
                    FailureNotes.record("topic/TopicGeneration.kt:221", it)
                }; runCatching { ready(g.row.id) }.onFailure {
                    FailureNotes.record("topic/TopicGeneration.kt:221", it)
                } }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                FailureNotes.record("topic/TopicGeneration:run", e)
                checkpoints.read(g.row.id)?.let { checkpoint ->
                    runCatching { checkpoints.write(g.row.id, checkpoint.copy(progress = checkpoint.progress.copy(error = topicFailure(e).message))) }
                }
                db.writeTransaction {
                    val row = dao.get(g.row.id)?.row
                    if (row?.revision == revision && row.status in setOf("Ended", "Summarizing", "Done"))
                        dao.update(row.copy(status = "Failed", error = topicFailure(e).message))
                }
            }
        }
        pending
    }
}

private fun MemoryEventData.android() = MemoryEventEntity(id = id, startAt = startAt, endAt = endAt, scene = scene, title = title, summary = summary,
    pointsJson = pointsJson, peopleJson = peopleJson, place = place, emotion = emotion, intensity = intensity, important = important, deleted = deleted,
    createdAt = createdAt, model = model, starred = starred, starredAt = starredAt, editedAt = editedAt, originalTitle = originalTitle, originalSummary = originalSummary,
    originalPointsJson = originalPointsJson)
