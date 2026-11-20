package app.resound.topic.core

import app.resound.diagnostics.FailureNotes
import app.resound.digest.core.TranscriptLineData
import app.resound.topic.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json

class TopicStalledException : IllegalStateException("专题整理已 30 分钟没有进展，请重试失败块")
data class TopicBlockRun(val waiting: Boolean, val results: List<TopicBlockResult> = emptyList(), val summary: TopicBlockSummary? = null) {
    val acceptedIds get() = results.flatMap { it.lineIds }.distinct()
    val items get() = results.flatMap { it.items }.distinctBy { listOf(it.category, it.content, it.dueText, it.who) }
    fun event(g: TopicGroup, now: () -> Long): app.resound.digest.core.MemoryEventData {
        val finalSummary = requireNotNull(summary)
        val merged = TopicGenerationRules.synthesize(g, results.flatMap { it.events }, acceptedIds.size, acceptedIds.size, "", now)
        val missing = finalSummary.text.substringAfter("\n\n未整理时段：", "")
        return merged.copy(summary = if (g.row.kind == "Normal") finalSummary.text else
            merged.summary + if (missing.isBlank()) "" else "\n\n未整理时段：$missing")
    }
}

/** 串行的一轮快照处理。请求成功即落盘；每块只保留当前指纹，源变化不会清空其他块。 */
class TopicBlockProcessor(
    private val id: Long, private val store: TopicCheckpointStore, private val generator: TopicBlockGenerator,
    private val hash: (String) -> String, private val classify: (Throwable) -> TopicFailure,
    private val pause: suspend (Long) -> Unit, private val now: () -> Long,
    private val clockText: (Long) -> String,
) {
    private var state = TopicCheckpoint(format = 3)
    private suspend fun save(value: TopicCheckpoint) { store.write(id, value); state = value }
    private fun session(index: Int?, fingerprint: String) = TopicProcessingSession(id, fingerprint, object : TopicCheckpointStore {
        override fun observe(id: Long): Flow<TopicProgress> = store.observe(id)
        override suspend fun read(id: Long) = TopicCheckpoint(fingerprint,
            if (index == null) state.summaryEntries else state.blocks.getValue(index).entries)
        override suspend fun write(id: Long, value: TopicCheckpoint) {
            val updated = if (index == null) state.copy(summaryEntries = value.entries) else state.copy(blocks = state.blocks +
                (index to state.blocks.getValue(index).copy(entries = value.entries)))
            save(updated.copy(progress = updated.progress.copy(phase = value.progress.phase)))
        }
        override suspend fun remove(id: Long) = error("块内请求不能删除专题")
    }, hash, classify, pause)
    private suspend fun progress(blocks: List<TopicBlock>, waits: Map<Int, TopicBlockReadiness>, network: Boolean, ended: Boolean) {
        val notes = blocks.map { b -> val cache = state.blocks[b.index] ?: TopicBlockCache("", b.ranges)
            TopicBlockNote(b.index, b.ranges, cache.state, cache.result?.notes.orEmpty(), cache.error ?: waits[b.index]?.takeUnless { it.ready }?.reason)
        }
        val pending = waits.filter { state.blocks[it.key]?.state == TopicBlockState.Waiting }.values
        val transcribe = pending.flatMap { it.transcribeIds }.toSet().size
        val voices = pending.filter { !it.ready && it.transcribeIds.isEmpty() && !it.recording }.flatMap { it.voiceprintIds }.toSet().size
        val waiting = pending.isNotEmpty()
        val stalled = ended && waiting && TopicBlocks.stalled(state.progress, now())
        val reason = when {
            transcribe > 0 -> "还有 $transcribe 段未成功转写"
            pending.any { it.recording } -> "录音仍未落盘"
            voices > 0 -> "还有 $voices 段等待认人"
            network -> "等待网络连接"
            else -> "等待剩余时间块"
        }
        val done = notes.count { it.state == TopicBlockState.Done }
        // 只报告连续完成的会议位置，避免跳过一个缺块后误报已全部覆盖。
        val until = notes.takeWhile { it.state == TopicBlockState.Done }.lastOrNull()?.ranges?.lastOrNull()?.endAt
        save(state.copy(progress = state.progress.copy(blocksTotal = blocks.size, blocksDone = done,
            blocksFailed = notes.count { it.state == TopicBlockState.Failed }, waitingTranscribe = transcribe,
            waitingVoiceprint = voices, waitingNetwork = network, organizedUntil = until, blockNotes = notes,
            notes = notes.filter { it.text.isNotBlank() }.associate { "整理纪要:${it.index}" to it.text },
            completed = done, total = blocks.size, stalledReason = reason.takeIf { stalled },
            phase = if (waiting) reason else state.progress.phase)))
    }
    private suspend fun <T : Any> bounded(block: suspend () -> T): T {
        val left = TopicBlocks.STALL - (now() - state.progress.lastProgressAt)
        if (left <= 0) throw TopicStalledException()
        return withTimeoutOrNull(left) { block() } ?: throw TopicStalledException()
    }
    suspend fun run(g: TopicGroup, lines: List<TranscriptLineData>, segments: List<TopicBlockSegment>,
        recording: (TopicBlock) -> Boolean, network: Boolean, todoContext: suspend () -> String,
        extra: (TopicBlock, List<TranscriptLineData>) -> String = { _, _ -> "" }, unseparated: Set<Long> = emptySet(),
        onWork: suspend () -> Unit = {}): TopicBlockRun {
        val ended = !TopicBlocks.active(g.row.status)
        val blocks = TopicBlocks.timeline(g, now())
        state = store.read(id)?.takeIf { it.format == 3 } ?: TopicCheckpoint(format = 3, progress = TopicProgress(lastProgressAt = now()))
        val complete = segments.filter { it.status in TopicBlocks.terminal }.map { it.id }.toSet()
        val newlyEnded = ended && state.endedAt == null
        val progressed = (complete - state.completedSegments).isNotEmpty() || newlyEnded
        save(state.copy(revision = g.row.revision, completedSegments = complete,
            endedAt = if (ended) state.endedAt ?: now() else null,
            blocks = state.blocks.filterKeys { index -> blocks.any { it.index == index } },
            progress = state.progress.copy(lastProgressAt = if (progressed) now() else state.progress.lastProgressAt, error = null)))
        val waits = linkedMapOf<Int, TopicBlockReadiness>()
        var previous = emptyList<TranscriptLineData>()
        for (block in blocks) {
            val speech = lines.filter { block.contains(it.startAt) }
            val context = extra(block, speech)
            val fp = TopicBlocks.fingerprint(g, block, speech, previous, generator.promptKey(g), generator.words(g), TopicGenerationRules.context(g, speech) + context, hash)
            val old = state.blocks[block.index]
            val cached = old?.takeIf { it.fingerprint == fp } ?: TopicBlockCache(fp, block.ranges,
                state = if (state.skipWaiting && old?.state == TopicBlockState.Skipped) TopicBlockState.Skipped else TopicBlockState.Waiting,
                error = old?.error?.takeIf { state.skipWaiting && old.state == TopicBlockState.Skipped },
                voiceprintWaitAt = old?.voiceprintWaitAt)
            save(state.copy(blocks = state.blocks + (block.index to cached)))
            var readiness = TopicBlocks.readiness(block, segments, recording(block), cached.voiceprintWaitAt, now())
            if (block.closed && !readiness.recording && readiness.transcribeIds.isEmpty() && readiness.voiceprint > 0 && cached.voiceprintWaitAt == null) {
                save(state.copy(blocks = state.blocks + (block.index to cached.copy(voiceprintWaitAt = now()))))
                readiness = TopicBlocks.readiness(block, segments, false, now(), now())
            }
            waits[block.index] = readiness
            val cache = state.blocks.getValue(block.index)
            if (cache.state == TopicBlockState.Done && readiness.transcribeIds.isEmpty() && !readiness.recording || cache.state in setOf(TopicBlockState.Failed, TopicBlockState.Skipped)) {
                previous = speech.takeLast(3); continue
            }
            if (!readiness.ready || !network) {
                val skipped = ended && state.skipWaiting
                save(state.copy(blocks = state.blocks + (block.index to cache.copy(
                    state = if (skipped) TopicBlockState.Skipped else TopicBlockState.Waiting,
                    result = null, error = if (skipped) (if (!network) "等待网络连接" else readiness.reason) else null))))
                previous = speech.takeLast(3); continue
            }
            // 等待转写阶段不算整理超时；首次真正开始/恢复整理时建立整理计时。
            if (state.generationStartedAt == null) save(state.copy(generationStartedAt = now(), progress = state.progress.copy(lastProgressAt = now())))
            try {
                onWork()
                val frozen = cache.todoContext ?: todoContext()
                save(state.copy(blocks = state.blocks + (block.index to cache.copy(todoContext = frozen))))
                val requests = session(block.index, fp); requests.begin()
                val result = bounded { generator.block(g, speech, previous, frozen, context, unseparated, requests) }
                save(state.copy(blocks = state.blocks + (block.index to state.blocks.getValue(block.index).copy(
                    state = TopicBlockState.Done, result = result, error = null)),
                    progress = state.progress.copy(lastProgressAt = now(), finished = false)))
            } catch (e: TopicStalledException) { throw e }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                FailureNotes.record("topic/core/TopicBlockProcessor:block", e)
                save(state.copy(blocks = state.blocks + (block.index to state.blocks.getValue(block.index).copy(
                    state = TopicBlockState.Failed, result = null, error = classify(e).message))))
            }
            previous = speech.takeLast(3)
            progress(blocks, waits, !network, ended)
        }
        progress(blocks, waits, !network, ended)
        val waiting = state.blocks.values.any { it.state == TopicBlockState.Waiting }
        val awaitingSpeech = waits.any { (index, ready) -> state.blocks[index]?.state == TopicBlockState.Waiting && !ready.ready }
        if (ended && !awaitingSpeech && !network && TopicBlocks.stalled(state.progress, now())) throw TopicStalledException()
        if (!ended || waiting) {
            // 会后等待窗口单独计时；新转写或完成块才推进 lastProgressAt。
            save(state.copy(generationStartedAt = null))
            return TopicBlockRun(waiting = waiting || !ended)
        }
        val results = blocks.mapNotNull { state.blocks[it.index]?.result }
        if (results.isEmpty() && state.blocks.values.any { it.state == TopicBlockState.Failed } && !state.skipWaiting)
            throw TopicException("全部时间块整理失败，请重试失败块")
        val missing = state.progress.blockNotes.filter { it.state != TopicBlockState.Done }.flatMap { note ->
            note.ranges.map { "${clockText(it.startAt)}–${clockText(it.endAt)} 这段没整理（${note.reason ?: "未完成"}）" }
        }.joinToString("\n")
        val summaryKey = hash(blocks.joinToString("|") { b -> state.blocks.getValue(b.index).let {
            if (it.state == TopicBlockState.Skipped) "skipped:${it.ranges}:${it.error}" else
                "${it.fingerprint}:${it.state}:${hash(Json.encodeToString(TopicBlockResult.serializer(), it.result ?: TopicBlockResult()))}:${it.error}"
        } })
        if (state.summaryKey != summaryKey) save(state.copy(summaryKey = summaryKey, summary = null, summaryEntries = emptyMap(),
            progress = state.progress.copy(finished = false)))
        val summary = state.summary?.let { Json.decodeFromString<TopicBlockSummary>(it) } ?: run {
            if (!network) return TopicBlockRun(true)
            if (state.generationStartedAt == null) save(state.copy(generationStartedAt = now(), progress = state.progress.copy(lastProgressAt = now())))
            onWork()
            val requests = session(null, summaryKey); requests.begin()
            val generated = bounded { generator.summarize(g, results, missing, requests) }
            save(state.copy(summary = Json.encodeToString(TopicBlockSummary.serializer(), generated),
                progress = state.progress.copy(lastProgressAt = now(), phase = "汇总完成")))
            generated
        }
        return TopicBlockRun(false, results, summary)
    }
    suspend fun finish(source: String = "") = save(state.copy(source = source, progress = state.progress.copy(finished = true, phase = "已完成", error = null)))
}
