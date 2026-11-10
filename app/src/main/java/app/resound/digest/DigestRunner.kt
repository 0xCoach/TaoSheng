package app.resound.digest

import android.content.Context
import app.resound.appName
import app.resound.data.MemoryDao
import app.resound.data.MemoryEventEntity
import app.resound.data.MemoryItemEntity
import app.resound.data.TranscriptLineEntity
import app.resound.data.PendingDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import org.json.JSONArray
import java.time.LocalDate

internal class DigestBatchLimitException : IllegalArgumentException("单段转写内容过长，请拆分后再整理")

/**
 * 把已转写的句子整理成事件。按“段落”处理：相邻两句间隔超过停顿时长（默认 [GAP_MS]，可在设置里调）就算另一段；
 * 一段要等最后一句过去停顿时长、且这段时间附近没有还没转写完的录音，才算说完了，才整理，避免一件事被切成两半。
 * 一直在说、段落迟迟不结束时，超过最长等待（默认 [LONG_MS]）就先在已过去停顿时长的部分里挑最长的停顿切开，整理前半截。
 * 格式错误先拆小重试；不能再拆的小批连续 [MAX_FORMAT_FAILURES] 次失败才放弃（标为已整理、不出事件），避免反复花钱。
 */
class DigestRunner(
    context: Context,
    private val dao: MemoryDao,
    private val client: LlmClient,
    private val settings: AndroidDigestSettings,
    private val template: () -> String,
    private val networkAllowed: () -> Boolean,
    private val now: () -> Long = System::currentTimeMillis,
    private val elapsed: () -> Long = android.os.SystemClock::elapsedRealtime,
    private val voiceprintReady: suspend () -> Boolean = { false },
    private val readKey: () -> String = settings::readKey,
    private val hotwords: () -> List<String> = { emptyList() },
    /** 参数为 true 表示用户点了“现在整理”。 */
    private val afterDigest: suspend (Boolean) -> Unit = {},
    private val isMedia: suspend (Long, Long) -> Boolean = { _, _ -> false },
    private val unseparated: suspend (List<Long>) -> List<Long> = { emptyList() },
    private val thingExtraction: app.resound.things.DigestThingExtraction? = null,
    private val request: suspend (LlmPreset, String, String, String) -> LlmReply = settings.digestRequest(client),
    private val situation: app.resound.context.SituationLookup? = null,
) {
    private val diagnostics = app.resound.diagnostics.LlmDiagnostics(context)
    private val responseTraces = mutableMapOf<String, app.resound.diagnostics.LlmResponseTrace>()
    private val appContext = context.applicationContext
    private val appName = context.appName

    private val prefs = context.getSharedPreferences("digest-state", Context.MODE_PRIVATE)
    private val gate = Mutex()
    // 数据库暂时不能写时保留本进程已收到的响应；进程死亡则由请求标记阻止自动重发。
    private var unsavedReply: PendingDigest? = null
    private val actualModels = mutableMapOf<String, String>()
    private val _running = MutableStateFlow(false)
    val running = _running.asStateFlow()
    private val _progress = MutableStateFlow(DigestProgress())
    val progress = _progress.asStateFlow()
    // 旧版把“N 句等待转写、声纹或对话结束后整理”当错误存着，升级后不再显示
    private val _last = MutableStateFlow(prefs.getLong("lastRunAt", 0).takeIf { it > 0 } to
        prefs.getString("lastError", null)?.takeUnless { it.contains("等待转写、声纹或对话结束") })
    val last = _last.asStateFlow()

    /** 兼容旧调用的单次 force；生产流水线用 run 的显式参数，避免自动任务消费手动意图。 */
    @Volatile var forceOnce = false

    /** true 表示有暂时性错误，希望稍后重试 */
    suspend fun run(force: Boolean? = null, manual: Boolean = false): Boolean {
        if (!gate.tryLock()) return false
        var madeEvents = false
        // force 只决定对话是否结束；manual 只决定技能是否跳过最短间隔。
        val forceNow = force ?: forceOnce.also { forceOnce = false }
        _progress.value = DigestProgress(startedAt = elapsed())
        _running.value = true
        probe = null
        try {
            val (preset, key) = synchronized(settings.providers.guard) { settings.preset to readKey() }
            if (key.isBlank() && !settings.canUseWithoutKey(preset) && !settings.failover.hasBackup(preset)) { record(null, "还没填写大模型密钥"); return false }
            unsavedReply?.let { dao.rememberDigest(it); unsavedReply = null; clearInFlight() }
            val undigested = dao.undigestedLines()
            val chunks = mutableListOf<List<TranscriptLineEntity>>()
            for (pending in dao.pendingDigests()) {
                val source = dao.refreshLines(pending.ids())
                // 核验不了的旧缓存与原逻辑一样丢弃、重新整理：多花一次请求，好过整理永久停住。
                if (source.size == pending.ids().size && matches(pending, source, preset)) chunks += source
                else dao.removePendingDigest(pending.id)
            }
            val inFlight = prefs.getString("inflight", null)
            if (inFlight != null) {
                val ids = prefs.getString("inflight-lines", "")!!.split(',').mapNotNull(String::toLongOrNull).toSet()
                if (dao.pendingDigest(inFlight) != null || undigested.none { it.id in ids } || manual) {
                    clearInFlight()
                } else {
                    record(now(), "上次整理结果未能保存，已暂停自动请求；请检查存储后点“现在整理”重试")
                    return false
                }
            }
            val replayIds = chunks.flatten().map { it.id }.toSet()
            val sessions = sessions(undigested.filterNot { it.id in replayIds }, gapMillis())
            for (session in sessions) {
                val ready = if (closed(session, forceNow)) session else settledPrefix(session)
                chunks.addAll(batches(ready))
            }
            _progress.value = _progress.value.copy(total = chunks.sumOf { it.size }, batches = chunks.size)
            for ((index, chunk) in chunks.withIndex()) {
                if (!networkAllowed() && cached(chunk, preset) == null) { record(now(), "等待网络"); return true }
                _progress.value = _progress.value.copy(batch = index + 1, batchStartedAt = elapsed())
                val refreshed = dao.refreshLines(chunk.map { it.id })
                when (val outcome = if (refreshed.isEmpty()) null else digest(refreshed, preset, key) { madeEvents = true }) {
                    null -> _progress.value = _progress.value.copy(done = _progress.value.done + chunk.size)
                    LlmError.Auth, LlmError.Quota -> { record(now(), failureDetail ?: outcome.human); return false }
                    LlmError.Network, LlmError.Server, LlmError.RateLimit, LlmError.Format -> { record(now(), failureDetail ?: outcome.human); return retryFailure }
                }
            }
            record(now(), null)
            return false
        } catch (e: CancellationException) { throw e }
        catch (e: LlmInputLimitException) { record(now(), e.message); return false }
        catch (e: DigestBatchLimitException) { record(now(), e.message); return false }
        catch (_: Exception) {
            record(now(), "整理的本地数据暂时无法读取或保存，请检查存储后重试")
            return false
        } finally {
            _progress.value = _progress.value.copy(finishedAt = elapsed())
            _running.value = false
            gate.unlock()
            if (madeEvents) {
                app.resound.PipelineProgress.stage(app.resound.PipelineProgress.Stage.Skills)
                try { afterDigest(manual) }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { /* 技能调度失败不影响已完成的整理。 */ }
            }
        }
    }

    internal suspend fun withIdle(block: suspend () -> Unit) { gate.lock(); try { block() } finally { gate.unlock() } }

    private var failureDetail: String? = null
    private var retryFailure = true

    private fun hash(value: String): String = java.security.MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private fun identity(lines: List<TranscriptLineEntity>) = "batch-v2:" + hash(JSONArray(lines.map { it.toString() }).toString())

    private fun matches(row: PendingDigest, lines: List<TranscriptLineEntity>, primary: LlmPreset): Boolean {
        if (row.id.startsWith("batch-v2:")) return row.id == identity(lines)
        val presets = (settings.presets + primary).distinct()
        return presets.any { row.id == hash(it.toString() + JSONArray(lines.map { line -> line.toString() })) }
    }
    private suspend fun cached(lines: List<TranscriptLineEntity>, primary: LlmPreset): PendingDigest? =
        dao.pendingDigest(identity(lines)) ?: dao.pendingDigests().firstOrNull { it.ids() == lines.map { l -> l.id } && matches(it, lines, primary) }

    private fun clearInFlight() {
        check(prefs.edit().remove("inflight").remove("inflight-lines").commit())
    }

    /** 整理一块；成功返回 null，否则返回错误类型 */
    private suspend fun digest(lines: List<TranscriptLineEntity>, preset: LlmPreset, key: String, onEvents: () -> Unit): LlmError? {
        failureDetail = null
        retryFailure = true
        val batchId = identity(lines)
        var storedId = batchId
        return try {
            val contextPrompt = SituationPrompt(settings.includeSituation.value, situation)
            val (text, indexed) = contextPrompt.transcript(lines, unseparated = unseparated(lines.map { it.segmentId }.distinct()).toSet())
            val unfinished = dao.unfinishedForDigest()
            val candidates = unfinished.map { it.id }.toSet()
            val ignored = dao.ignoredForDigest(now() - 60L * 86_400_000)
            val input = DigestPrompt.withTodoContext(DigestPrompt.withHotwords(text, hotwords()), unfinished, ignored)
            var thingBatch = thingExtraction?.prepare(indexed)
            val cached = cached(lines, preset)
            storedId = cached?.id ?: batchId
            val stored = cached ?: run {
                val system = contextPrompt.system(DigestPrompt.system(template(), LocalDate.now(), appName)) + (thingBatch?.system?.let { "\n\n$it" } ?: "")
                val user = (thingBatch?.input?.let { "$it\n\n以下为本次新原话与整理参考：\n" } ?: "") + input
                check(prefs.edit().putString("inflight", batchId).putString("inflight-lines", lines.joinToString(",") { it.id.toString() }).commit())
                val received = try {
                    settings.failover.chat("整理", preset, key, system, user, request) { usage ->
                        dao.addUsage(LocalDate.now().toString(), usage.promptTokens, usage.completionTokens, app.resound.usage.UsagePurpose.Digest)
                    }.also { actualModels[batchId] = it.actualModel ?: preset.model
                        it.diagnostics?.let { trace -> responseTraces[batchId] = trace } }
                } catch (e: LlmException) { clearInFlight(); throw e }
                catch (e: LlmInputLimitException) { clearInFlight(); throw e }
                PendingDigest(batchId, lines.joinToString(",") { it.id.toString() }, received.content,
                    LocalDate.now().toString(), received.promptTokens, received.completionTokens, thingBatch?.revision)
                    .also {
                        unsavedReply = it
                        val saved = dao.rememberDigest(it)
                        unsavedReply = null
                        if (!saved) { responseTraces.remove(batchId); actualModels.remove(batchId); clearInFlight(); return null }
                    }
            }
            clearInFlight()
            // 用户关停/重新开启寻物后，不能把旧响应冒充新一轮提取。
            thingBatch = stored.thingRevision?.let { revision -> thingBatch?.copy(revision = revision) }
            val reply = LlmReply(stored.content, stored.promptTokens, stored.completionTokens)
            val trace = responseTraces.remove(batchId) ?: runCatching {
                diagnostics.start("整理（缓存解析）", preset.copy(name = "暂无（历史缓存）", model = "暂无")).also { it.content(reply.content) }
            }.getOrNull()
            val (events, items) = DigestPrompt.parse(reply.content, indexed, diagnostics = trace)
            val at = now()
            val save: suspend () -> Unit = { dao.saveDigest(
                events = events.map { e ->
                    MemoryEventEntity(
                        startAt = e.startAt, endAt = e.endAt, scene = if (isMedia(e.startAt, e.endAt)) "媒体" else e.scene, title = e.title, summary = e.summary,
                        pointsJson = JSONArray(e.points).toString(), peopleJson = JSONArray(e.people).toString(),
                        place = e.place, emotion = e.emotion, intensity = e.intensity, important = e.important,
                        createdAt = at, model = actualModels[stored.id] ?: preset.model,
                    ) to e.lineIds
                },
                items = { ids ->
                    items.map { i ->
                        MemoryItemEntity(
                            eventId = ids[i.eventIndex], category = i.category, kind = i.kind, content = i.content,
                            dueText = i.dueText, dueAt = i.dueAt, who = i.who, lineId = i.lineId,
                            status = ItemStatus.Pending.name, createdAt = at,
                        )
                    }
                },
                allLineIds = lines.map { it.id },
                at = at,
                doneHints = DigestPrompt.doneHints(reply.content, indexed, candidates),
                hintCandidates = candidates,
            ) }
            if (thingExtraction != null) thingExtraction.commit(thingBatch, reply.content, events, save) else save()
            dao.removePendingDigest(storedId)
            actualModels.remove(storedId)
            prefs.edit().remove("local-$batchId").apply()
            if (events.isNotEmpty()) onEvents()
            runCatching { app.resound.search.EmbeddingScheduler.enqueue(appContext) }
            clearFailures(lines.first().id)
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: LlmInputLimitException) {
            throw e
        } catch (e: LlmException) {
            diagnostics.error(e.type, now())
            failureDetail = e.detail
            if (e.type == LlmError.Format) {
                dao.removePendingDigest(storedId)
                actualModels.remove(storedId)
                splitForRetry(lines)?.let { (first, second) ->
                    // 同一轮按时间处理两半，各自沿用原来的 identity、缓存及在途标记；大批不累计失败。
                    return digest(first, preset, key, onEvents) ?: digest(second, preset, key, onEvents)
                }
            }
            if (e.rejected) {
                when (configAccepts(preset, key)) {
                    null -> return e.type
                    false -> {
                        failureDetail = "大模型拒收了整理请求，请到设置里点“检测”检查模型配置"
                        retryFailure = false
                        return e.type
                    }
                    true -> Unit
                }
                // 配置没问题，是这批内容被拒：切小重试，最后只跳过被拒的几句，后面的批次照常整理。
                dao.removePendingDigest(storedId)
                actualModels.remove(storedId)
                splitForRetry(lines, MIN_REJECT_SPLIT_LINES)?.let { (first, second) ->
                    return digest(first, preset, key, onEvents) ?: digest(second, preset, key, onEvents)
                }
                dao.markDigested(lines.map { it.id }, now())
                diagnostics.abandoned(lines.size, now())
                clearFailures(lines.first().id)
                failureDetail = null
                return null
            }
            if (e.type == LlmError.Format && failed(lines.first().id) >= MAX_FORMAT_FAILURES) {
                // 这段连续几次都整理不出来：放弃，原话仍在“原话”页
                dao.markDigested(lines.map { it.id }, now())
                diagnostics.abandoned(lines.size, now())
                clearFailures(lines.first().id)
                null
            } else e.type
        } catch (_: Exception) {
            val failures = prefs.getInt("local-$batchId", 0) + 1
            prefs.edit().putInt("local-$batchId", failures).apply()
            retryFailure = failures < MAX_FORMAT_FAILURES
            failureDetail = "整理结果本地保存失败，将保留结果重试，不重复请求模型"
            LlmError.Format
        }
    }

    /** 本轮是否已确认“模型配置本身能用”：true 能用，false 连测试话也被拒，null 还没测。 */
    private var probe: Boolean? = null

    /** 发一句很短的测试话，分清是内容被拒还是配置被拒；网络等暂时问题返回 null，按原错误稍后重试。 */
    private suspend fun configAccepts(preset: LlmPreset, key: String): Boolean? {
        probe?.let { return it }
        val result = try { request(preset, key, PROBE_SYSTEM, PROBE_USER); true }
            catch (e: CancellationException) { throw e }
            catch (e: LlmException) { if (e.rejected) false else null }
            catch (_: Exception) { null }
        probe = result
        return result
    }

    private fun gapMillis() = settings.pauseMinutes.value * 60_000L

    internal suspend fun closed(session: List<TranscriptLineEntity>, force: Boolean): Boolean {
        val gap = gapMillis()
        if (voiceprintReady() && dao.voiceprintPending(session.map { it.segmentId }.distinct()) > 0) return false
        val start = session.first().startAt
        val end = session.maxOf { it.endAt }
        val quietLongEnough = force || now() - end >= gap
        return quietLongEnough && dao.unfinishedSegments(start - gap, if (force) end else end + gap) == 0
    }


    /**
     * 段落还没结束但已经说了很久：只看已过去停顿时长的句子（后面不会再插进新句子），
     * 前半截至少一个停顿时长那么长，在其中最长的停顿处切开，返回可以先整理的前半截；不满足返回空。
     */
    internal suspend fun settledPrefix(session: List<TranscriptLineEntity>): List<TranscriptLineEntity> {
        val start = session.first().startAt
        val gap = gapMillis()
        val settled = now() - gap
        if (settled - start < settings.maxWaitMinutes.value * 60_000L) return emptyList()
        var cut = -1
        var bestGap = -1L
        var prefixEnd = Long.MIN_VALUE
        for (i in 0 until session.size - 1) {
            prefixEnd = maxOf(prefixEnd, session[i].endAt)
            if (prefixEnd > settled) break
            if (prefixEnd - start < gap) continue
            val pause = session[i + 1].startAt - prefixEnd
            if (pause >= bestGap) { bestGap = pause; cut = i }
        }
        if (cut < 0) return emptyList()
        val prefix = session.subList(0, cut + 1)
        if (voiceprintReady() && dao.voiceprintPending(prefix.map { it.segmentId }.distinct()) > 0) return emptyList()
        val end = prefix.maxOf { it.endAt }
        return if (dao.unfinishedSegments(start - gap, end) == 0) prefix else emptyList()
    }

    private fun failed(firstLineId: Long): Int {
        val n = prefs.getInt("fail-$firstLineId", 0) + 1
        prefs.edit().putInt("fail-$firstLineId", n).apply()
        return n
    }

    private fun clearFailures(firstLineId: Long) = prefs.edit().remove("fail-$firstLineId").apply()

    private fun record(at: Long?, error: String?) {
        prefs.edit().putLong("lastRunAt", at ?: prefs.getLong("lastRunAt", 0)).putString("lastError", error).apply()
        _last.value = (at ?: _last.value.first) to error
    }

    companion object {
        const val GAP_MS = AndroidDigestSettings.DEFAULT_PAUSE * 60_000L
        const val LONG_MS = AndroidDigestSettings.DEFAULT_MAX_WAIT * 60_000L
        /**
         * 一批最多交给大模型多少句、多少字。模型能读 12.8 万 token，限制主要防回答太长被截断：
         * 实测 600 句（约 2.1 万字）不思考回答约 4600 token、思考约 1.8 万，都在上限内；再超出还有拆小重试兜底。
         */
        const val MAX_LINES = 600
        const val MAX_FORMAT_FAILURES = 3
        internal const val PROBE_SYSTEM = "你是格式检测助手，只输出 JSON。"
        internal const val PROBE_USER = "请原样返回这个 JSON：{\"ok\":true}"
        const val MAX_BATCH_CHARS = 40_000
        /** 每句除正文外的编号、时间、说话人（如“[123] 12:34:56 说话人1：”）约 20 字，留点余量 */
        const val LINE_OVERHEAD_CHARS = 30

        internal fun batches(lines: List<TranscriptLineEntity>): List<List<TranscriptLineEntity>> {
            val chunks = mutableListOf<MutableList<TranscriptLineEntity>>()
            var chars = 0
            for (line in lines) {
                val size = line.text.length + LINE_OVERHEAD_CHARS
                if (size > MAX_BATCH_CHARS) throw DigestBatchLimitException()
                if (chunks.isEmpty() || chunks.last().size >= MAX_LINES || chars + size > MAX_BATCH_CHARS) {
                    chunks += mutableListOf<TranscriptLineEntity>(); chars = 0
                }
                chunks.last() += line; chars += size
            }
            return chunks
        }

        /** 按时间间隔分段 */
        fun sessions(lines: List<TranscriptLineEntity>, gapMillis: Long = GAP_MS): List<List<TranscriptLineEntity>> {
            val out = mutableListOf<MutableList<TranscriptLineEntity>>()
            for (line in lines) {
                val current = out.lastOrNull()
                if (current != null && line.startAt - current.maxOf { it.endAt } <= gapMillis) current += line
                else out += mutableListOf(line)
            }
            return out
        }
    }
}
