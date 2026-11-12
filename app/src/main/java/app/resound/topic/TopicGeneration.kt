package app.resound.topic

import androidx.room.withTransaction
import app.resound.data.*
import app.resound.digest.*
import app.resound.importer.*
import app.resound.skills.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.ceil
import app.resound.digest.snapshot
import app.resound.skills.android
import app.resound.topic.core.TopicProcessingSession
import app.resound.topic.core.TopicProcessingRules
import kotlinx.coroutines.delay

internal data class TopicOutput(val event: MemoryEventEntity, val items: List<DigestPrompt.ParsedItem>, val card: SkillCardRow?)

/** 与卡 134 一致：格式错拆批；拒收先探测配置，再缩小到少数原话。被拒部分不会进入后续纪要。 */
internal class TopicBatches(private val request: suspend (String, String) -> LlmReply) {
    private var accepts: Boolean? = null
    suspend fun <T> read(lines: List<TranscriptLineEntity>, block: suspend (List<TranscriptLineEntity>) -> T): List<Pair<List<TranscriptLineEntity>, T>> {
        if (lines.isEmpty()) return emptyList()
        try { return listOf(lines to block(lines)) }
        catch (e: LlmException) {
            if (e.rejected) {
                if (accepts == null) accepts = try { request(DigestRunner.PROBE_SYSTEM, DigestRunner.PROBE_USER); true }
                    catch (probe: LlmException) { if (probe.rejected) false else throw probe }
                if (accepts != true) throw e
                val halves = splitForRetry(lines, MIN_REJECT_SPLIT_LINES) ?: return emptyList()
                return read(halves.first, block) + read(halves.second, block)
            }
            if (e.type != LlmError.Format) throw e
            splitForRetry(lines)?.let { return read(it.first, block) + read(it.second, block) }
            // 小批最多三次，不在失败后自动无限重发。
            repeat(DigestRunner.MAX_FORMAT_FAILURES - 1) {
                try { return listOf(lines to block(lines)) }
                catch (retry: LlmException) { if (retry.type != LlmError.Format) throw retry }
            }
            throw e
        }
    }
}

internal class TopicGenerator(
    private val template: () -> String,
    private val importTemplate: (ImportKind) -> Pair<Skill, String>,
    private val request: suspend (String, String) -> LlmReply,
    private val hotwords: () -> List<String> = { emptyList() },
    private val now: () -> Long = System::currentTimeMillis,
    private val appName: String = "涛声",
) {
    suspend fun generate(g: TopicGroup, lines: List<TranscriptLineEntity>, unfinished: List<MemoryItemEntity>,
        ignored: List<MemoryItemEntity>, unseparated: Set<Long> = emptySet(), session: TopicProcessingSession? = null): TopicOutput {
        val row = g.row
        val words = (hotwords() + TopicPolicy.words(row.name)).distinct()
        val kind = TopicKind.valueOf(row.kind)
        val events = mutableListOf<DigestPrompt.ParsedEvent>()
        val items = mutableListOf<DigestPrompt.ParsedItem>()
        val accepted = mutableListOf<TranscriptLineEntity>()
        var model = ""
        val batches = TopicBatches(request)
        val data = context(g, lines)
        val digestChunks = if (session != null) TopicProcessingRules.chunks(lines, { line, text -> line.copy(text = text) }) { it.text } else DigestRunner.batches(lines)
        session?.phase("提取要点", digestChunks.size)
        for (chunk in digestChunks) {
            val read: suspend (List<TranscriptLineEntity>) -> Pair<List<TranscriptLineEntity>, Pair<List<DigestPrompt.ParsedEvent>, List<DigestPrompt.ParsedItem>>> = { part ->
                val plain = DigestPrompt.transcript(part, unseparated = if (kind == TopicKind.Training) part.map { it.segmentId }.toSet() else unseparated).first
                val text = DigestPrompt.withTodoContext(DigestPrompt.withHotwords(plain, words), unfinished, ignored)
                val system = DigestPrompt.system(template(), Instant.ofEpochMilli(part.first().startAt).atZone(ZoneId.systemDefault()).toLocalDate(), appName) +
                    app.resound.topic.core.TopicGenerationRules.DIGEST_SUFFIX
                val input = "$data\n$text"
                var reply: LlmReply? = null
                val call: suspend (String, String) -> String = { s, u ->
                    request(s, u).also { reply = it; model = it.actualModel ?: model }.content
                }
                val validate: (String) -> Unit = { value ->
                    try {
                        val parsed = DigestPrompt.parse(value, part)
                        reply?.diagnostics?.parsed(parsed.first.size, parsed.second.size, parsed.first.isEmpty())
                    } catch (e: LlmException) { reply?.diagnostics?.parsed(empty = value.isBlank(), error = e.type); throw e }
                }
                val content = session?.request(system, input, call, validate,
                    { DigestPrompt.parse(it, part).first.joinToString("\n") { event -> event.summary } })
                    ?: TopicProcessingRules.normalize(call(system, input)).also(validate)
                part to DigestPrompt.parse(content, part)
            }
            val parsed = if (session != null) session.splitRead(chunk, read) else batches.read(chunk) { read(it).second }
            parsed.forEach { (part, result) -> accepted += part; events += result.first; items += result.second }
            session?.advance()
        }
        val start = g.spans.minOf { it.startAt }; val end = g.spans.maxOf { requireNotNull(it.endAt) }
        val acceptedIds = accepted.map { it.id }.toSet()
        val acceptedLines = lines.filter { it.id in acceptedIds }
        val card = when (kind) {
            TopicKind.Normal -> null
            TopicKind.Script -> script(g, acceptedLines, data, words, session)
            else -> {
                val summary = ImportSummary(importTemplate, request, now, session)
                summary.generate(ImportRecordRow(id = row.id, pendingId = "topic:${row.id}", fileName = row.name ?: kind.title,
                    kind = kind.name, startAt = start, endAt = end, byChannel = false),
                    if (kind == TopicKind.Training) acceptedLines.map { it.copy(speakerName = null, speakerTag = null) } else acceptedLines,
                    unfinished + ignored, extraContext = DigestPrompt.withHotwords(context(g, acceptedLines), words)).card
                    .copy(entity = "topic:${row.id}")
            }
        }
        val event = app.resound.topic.core.TopicGenerationRules.synthesize(g.snapshot(), events.map { it.shared() }, lines.size, acceptedLines.size, model, now).android()
        return TopicOutput(event, items.distinctBy { listOf(it.category, it.content, it.dueText, it.who) }, card)
    }
    private fun context(g: TopicGroup, lines: List<TranscriptLineEntity>) = app.resound.topic.core.TopicGenerationRules.context(g.snapshot(), lines.map { it.snapshot() })

    private suspend fun script(g: TopicGroup, lines: List<TranscriptLineEntity>, context: String, words: List<String>, session: TopicProcessingSession?): SkillCardRow {
        val organized = mutableListOf<String>(); val verbatim = mutableListOf<String>()
        val chunks = if (session != null) TopicProcessingRules.chunks(lines, { line, text -> line.copy(text = text) }) { it.text } else ImportChunks.split(lines)
        session?.phase("整理口播稿", chunks.size)
        fun parse(text: String): Pair<String, String> = try {
            app.resound.topic.core.TopicGenerationRules.parseScript(TopicProcessingRules.normalize(text))
        } catch (_: Exception) { throw LlmException(LlmError.Format) }
        for (chunk in chunks) {
            val read: suspend (List<TranscriptLineEntity>) -> Pair<String, String> = { part ->
                val user = app.resound.topic.core.TopicGenerationRules.scriptInput(context, part.map { it.snapshot() }, words)
                val text = session?.request(SCRIPT_PROMPT, user, { a, b -> request(a, b).content }, { parse(it) }, { parse(it).first })
                    ?: request(SCRIPT_PROMPT, user).content
                parse(text)
            }
            val results = session?.splitRead(chunk, read) ?: TopicBatches(request).read(chunk, read).map { it.second }
            results.forEach { organized += it.first; verbatim += it.second }
            session?.advance()
        }
        return app.resound.topic.core.TopicGenerationRules.scriptCard(g.snapshot(),organized,verbatim,lines.map { it.id },now).android()
    }
    companion object {
        const val SCRIPT_PROMPT = app.resound.topic.core.TopicGenerationRules.SCRIPT_PROMPT
        fun scriptSize(text: String) = app.resound.topic.core.TopicGenerationRules.scriptSize(text)
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
    private suspend fun waiting(g: TopicGroup): Boolean {
        if (g.spans.any { recordingPending(it.startAt, it.endAt ?: now()) }) return true
        val segments = dao.segments(g.row.id, now())
        if (segments.any { it.status in setOf("Pending", "Running") || it.status == "Failed" && it.attempts < 5 }) return true
        if (segments.any { it.status !in setOf("Done", "Empty", "SkippedEnv", "SkippedPlayback") }) throw TopicException("专题有未成功转写的录音，请重试转写后重新生成")
        return voiceprintReady() && db.memory().voiceprintPending(segments.filter { it.status == "Done" }.map { it.id }) > 0
    }
    /** 返回 true 表示仍在等录音落盘/转写/声纹；失败由用户显式 regenerate。 */
    suspend fun run(): Boolean = gate.withLock {
        var pending = false
        for (initial in dao.all().filter { it.row.status in setOf("Ended", "Summarizing", "Done") }) {
            var g = dao.get(initial.row.id) ?: continue
            if (g.row.status == "Done") {
                if (dao.lines(g.row.id).none { it.eventId != g.row.eventId || it.digestedAt == null }) continue
                // 结束时还在落盘的片段、恢复/补转的新原话不能永久漏掉。
                repository.regenerate(g.row.id)
                g = dao.get(g.row.id) ?: continue
            }
            val revision = g.row.revision
            var session: TopicProcessingSession? = null
            try {
                if (waiting(g)) { pending = true; continue }
                val lines = dao.lines(g.row.id)
                val source = topicSource(g, lines)
                session = TopicProcessingSession(g.row.id, source, checkpoints, ::topicHash, ::topicFailure, ::delay)
                session.begin()
                val claimed = db.withTransaction {
                    val latest = dao.get(g.row.id)?.row
                    if (latest?.revision != revision || latest.status !in setOf("Ended", "Summarizing")) false
                    else { dao.update(latest.copy(status = "Summarizing")); true }
                }
                if (!claimed) continue
                val retained = dao.items(g.row.retainedIds()).filterNot { it.deleted }
                val unfinished = (db.memory().unfinishedForDigest() + retained.filter { it.status !in setOf("Done", "Ignored") }).distinctBy { it.id }
                val ignored = (db.memory().ignoredForDigest(now() - 60L * 86_400_000) + retained.filter { it.status in setOf("Done", "Ignored") }).distinctBy { it.id }
                val result = generator.generate(g, lines, unfinished, ignored, db.transcribe().unseparated(lines.map { it.segmentId }.distinct()).toSet(), session)
                val saved = db.withTransaction {
                    val latest = dao.get(g.row.id) ?: return@withTransaction false
                    if (latest.row.revision != revision || latest.row.status != "Summarizing") return@withTransaction false
                    if (dao.lines(g.row.id) != lines || waiting(latest)) {
                        dao.update(latest.row.copy(status = "Ended")); pending = true; return@withTransaction false
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
                    } }.map { item -> MemoryItemEntity(eventId = eventId, category = item.category, kind = item.kind, content = item.content,
                        dueText = item.dueText, dueAt = item.dueAt, who = item.who, lineId = item.lineId, status = "Pending", createdAt = now()) }
                    db.memory().insertItems(items)
                    db.memory().assignLines(lines.map { it.id }, eventId)
                    db.memory().markDigested(lines.map { it.id }, now())
                    val cardId = result.card?.let { db.skills().save(it) }
                    dao.update(replacement.copy(eventId = eventId, cardId = cardId, status = "Done", error = null))
                    true
                }
                if (saved) { runCatching { session.finish() }; runCatching { ready(g.row.id) } }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                runCatching { session?.fail(e) }
                db.withTransaction {
                    val row = dao.get(g.row.id)?.row
                    if (row?.revision == revision && row.status in setOf("Ended", "Summarizing"))
                        dao.update(row.copy(status = "Failed", error = if (session != null || e is LlmException || e is TopicException) topicFailure(e).message else TopicPolicy.GENERATION_ERROR))
                }
            }
        }
        pending
    }
}

private fun app.resound.digest.core.MemoryEventData.android() = MemoryEventEntity(id = id, startAt = startAt, endAt = endAt, scene = scene, title = title, summary = summary, pointsJson = pointsJson, peopleJson = peopleJson, place = place, emotion = emotion, intensity = intensity, important = important, deleted = deleted, createdAt = createdAt, model = model, starred = starred, starredAt = starredAt, editedAt = editedAt, originalTitle = originalTitle, originalSummary = originalSummary, originalPointsJson = originalPointsJson)
private fun DigestPrompt.ParsedEvent.shared() = app.resound.digest.core.DigestPrompt.ParsedEvent(lineIds,startAt,endAt,scene,title,summary,points,people,place,emotion,intensity,important)
