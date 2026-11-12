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
        ignored: List<MemoryItemEntity>, unseparated: Set<Long> = emptySet()): TopicOutput {
        val row = g.row
        val words = (hotwords() + TopicPolicy.words(row.name)).distinct()
        val kind = TopicKind.valueOf(row.kind)
        val events = mutableListOf<DigestPrompt.ParsedEvent>()
        val items = mutableListOf<DigestPrompt.ParsedItem>()
        val accepted = mutableListOf<TranscriptLineEntity>()
        var model = ""
        val batches = TopicBatches(request)
        val data = context(g, lines)
        for (chunk in DigestRunner.batches(lines)) {
            val parsed = batches.read(chunk) { part ->
                val plain = DigestPrompt.transcript(part, unseparated = if (kind == TopicKind.Training) part.map { it.segmentId }.toSet() else unseparated).first
                val text = DigestPrompt.withTodoContext(DigestPrompt.withHotwords(plain, words), unfinished, ignored)
                val reply = request(DigestPrompt.system(template(), Instant.ofEpochMilli(part.first().startAt).atZone(ZoneId.systemDefault()).toLocalDate(), appName) +
                    app.resound.topic.core.TopicGenerationRules.DIGEST_SUFFIX, "$data\n$text")
                model = reply.actualModel ?: model
                DigestPrompt.parse(reply.content, part)
            }
            parsed.forEach { (part, result) -> accepted += part; events += result.first; items += result.second }
        }
        val start = g.spans.minOf { it.startAt }; val end = g.spans.maxOf { requireNotNull(it.endAt) }
        val card = when (kind) {
            TopicKind.Normal -> null
            TopicKind.Script -> script(g, accepted, data, words)
            else -> {
                val summary = ImportSummary(importTemplate, request, now)
                summary.generate(ImportRecordRow(id = row.id, pendingId = "topic:${row.id}", fileName = row.name ?: kind.title,
                    kind = kind.name, startAt = start, endAt = end, byChannel = false),
                    if (kind == TopicKind.Training) accepted.map { it.copy(speakerName = null, speakerTag = null) } else accepted,
                    unfinished + ignored, extraContext = DigestPrompt.withHotwords(context(g, accepted), words)).card
                    .copy(entity = "topic:${row.id}")
            }
        }
        val event = app.resound.topic.core.TopicGenerationRules.synthesize(g.snapshot(), events.map { it.shared() }, lines.size, accepted.size, model, now).android()
        return TopicOutput(event, items.distinctBy { listOf(it.category, it.content, it.dueText, it.who) }, card)
    }
    private fun context(g: TopicGroup, lines: List<TranscriptLineEntity>) = app.resound.topic.core.TopicGenerationRules.context(g.snapshot(), lines.map { it.snapshot() })

    private suspend fun script(g: TopicGroup, lines: List<TranscriptLineEntity>, context: String, words: List<String>): SkillCardRow {
        val organized = mutableListOf<String>(); val verbatim = mutableListOf<String>()
        val batches = TopicBatches(request)
        for (chunk in ImportChunks.split(lines)) batches.read(chunk) { part ->
            val reply = request(SCRIPT_PROMPT, app.resound.topic.core.TopicGenerationRules.scriptInput(context, part.map { it.snapshot() }, words))
            try {
                val json = JSONObject(reply.content.trim().removePrefix("```json").removePrefix("```").removeSuffix("```"))
                app.resound.topic.core.TopicGenerationRules.parseScript(json.toString())
            } catch (_: Exception) { throw LlmException(LlmError.Format) }
        }.forEach { (_, text) -> organized += text.first; verbatim += text.second }
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
            try {
                if (waiting(g)) { pending = true; continue }
                val lines = dao.lines(g.row.id)
                val claimed = db.withTransaction {
                    val latest = dao.get(g.row.id)?.row
                    if (latest?.revision != revision || latest.status !in setOf("Ended", "Summarizing")) false
                    else { dao.update(latest.copy(status = "Summarizing")); true }
                }
                if (!claimed) continue
                val retained = dao.items(g.row.retainedIds()).filterNot { it.deleted }
                val unfinished = (db.memory().unfinishedForDigest() + retained.filter { it.status !in setOf("Done", "Ignored") }).distinctBy { it.id }
                val ignored = (db.memory().ignoredForDigest(now() - 60L * 86_400_000) + retained.filter { it.status in setOf("Done", "Ignored") }).distinctBy { it.id }
                val result = generator.generate(g, lines, unfinished, ignored, db.transcribe().unseparated(lines.map { it.segmentId }.distinct()).toSet())
                val saved = db.withTransaction {
                    val latest = dao.get(g.row.id) ?: return@withTransaction false
                    if (latest.row.revision != revision || latest.row.status != "Summarizing") return@withTransaction false
                    if (dao.lines(g.row.id) != lines || waiting(latest)) {
                        dao.update(latest.row.copy(status = "Ended")); pending = true; return@withTransaction false
                    }
                    val existing = (dao.items(latest.row.retainedIds()) + db.memory().protectedItems() + db.memory().unfinishedForDigest() + ignored).distinctBy { it.id }
                    val eventId = db.memory().insertEvent(result.event)
                    dao.attachItems(latest.row.retainedIds(), eventId)
                    val items = result.items.filterNot { item -> existing.any { old ->
                        old.category in setOf(item.category, "待办", "约定", "备忘") &&
                            (item.lineId != null && old.lineId == item.lineId || ImportTodoMerge.same(old.content, item.content))
                    } }.map { item -> MemoryItemEntity(eventId = eventId, category = item.category, kind = item.kind, content = item.content,
                        dueText = item.dueText, dueAt = item.dueAt, who = item.who, lineId = item.lineId, status = "Pending", createdAt = now()) }
                    db.memory().insertItems(items)
                    db.memory().assignLines(lines.map { it.id }, eventId)
                    db.memory().markDigested(lines.map { it.id }, now())
                    val cardId = result.card?.let { db.skills().save(it) }
                    dao.update(latest.row.copy(eventId = eventId, cardId = cardId, status = "Done", error = null))
                    true
                }
                if (saved) runCatching { ready(g.row.id) }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                db.withTransaction {
                    val row = dao.get(g.row.id)?.row
                    if (row?.revision == revision && row.status in setOf("Ended", "Summarizing"))
                        dao.update(row.copy(status = "Failed", error = TopicPolicy.GENERATION_ERROR))
                }
            }
        }
        pending
    }
}

private fun app.resound.digest.core.MemoryEventData.android() = MemoryEventEntity(id = id, startAt = startAt, endAt = endAt, scene = scene, title = title, summary = summary, pointsJson = pointsJson, peopleJson = peopleJson, place = place, emotion = emotion, intensity = intensity, important = important, deleted = deleted, createdAt = createdAt, model = model, starred = starred, starredAt = starredAt, editedAt = editedAt, originalTitle = originalTitle, originalSummary = originalSummary, originalPointsJson = originalPointsJson)
private fun DigestPrompt.ParsedEvent.shared() = app.resound.digest.core.DigestPrompt.ParsedEvent(lineIds,startAt,endAt,scene,title,summary,points,people,place,emotion,intensity,important)
