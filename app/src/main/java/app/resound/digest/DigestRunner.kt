package app.resound.digest

import app.resound.usage.UsagePurpose

import android.content.Context
import app.resound.appName
import app.resound.data.MemoryDao
import app.resound.data.MemoryEventEntity
import app.resound.data.MemoryItemEntity
import app.resound.data.TranscriptLineEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import org.json.JSONArray
import java.time.LocalDate

/**
 * 把已转写的句子整理成事件。按“段落”处理：相邻两句间隔超过停顿时长（默认 [GAP_MS]，可在设置里调）就算另一段；
 * 一段要等最后一句过去停顿时长、且这段时间附近没有还没转写完的录音，才算说完了，才整理，避免一件事被切成两半。
 * 一直在说、段落迟迟不结束时，超过最长等待（默认 [LONG_MS]）就先在已过去停顿时长的部分里挑最长的停顿切开，整理前半截。
 * 每段只整理一次；格式错误连续 [MAX_FORMAT_FAILURES] 次就放弃这段（标为已整理、不出事件），避免反复花钱。
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
    private val request: suspend (LlmPreset, String, String, String) -> LlmReply = client::chatJson,
) {
    private val appContext = context.applicationContext
    private val appName = context.appName

    private val prefs = context.getSharedPreferences("digest-state", Context.MODE_PRIVATE)
    private val gate = Mutex()
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
        try {
            val key = readKey()
            if (key.isBlank() && settings.preset.id != "custom") { record(null, "还没填写大模型密钥"); return false }
            val sessions = sessions(dao.undigestedLines(), gapMillis())
            val chunks = mutableListOf<List<TranscriptLineEntity>>()
            for (session in sessions) {
                val ready = if (closed(session, forceNow)) session else settledPrefix(session)
                chunks.addAll(ready.chunked(MAX_LINES))
            }
            _progress.value = _progress.value.copy(total = chunks.sumOf { it.size }, batches = chunks.size)
            for ((index, chunk) in chunks.withIndex()) {
                if (!networkAllowed()) { record(now(), "等待网络"); return true }
                _progress.value = _progress.value.copy(batch = index + 1, batchStartedAt = elapsed())
                val refreshed = dao.refreshLines(chunk.map { it.id })
                when (val outcome = if (refreshed.isEmpty()) null else digest(refreshed, key) { madeEvents = true }) {
                    null -> _progress.value = _progress.value.copy(done = _progress.value.done + chunk.size)
                    LlmError.Auth -> { record(now(), failureDetail ?: outcome.human); return false }
                    LlmError.Network, LlmError.Server, LlmError.Format -> { record(now(), failureDetail ?: outcome.human); return true }
                }
            }
            record(now(), null)
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

    /** 整理一块；成功返回 null，否则返回错误类型 */
    private suspend fun digest(lines: List<TranscriptLineEntity>, key: String, onEvents: () -> Unit): LlmError? {
        failureDetail = null
        val preset = settings.preset
        val (text, indexed) = DigestPrompt.transcript(lines)
        return try {
            val unfinished = dao.unfinishedForDigest()
            val candidates = unfinished.map { it.id }.toSet()
            val ignored = dao.ignoredForDigest(now() - 60L * 86_400_000)
            val input = DigestPrompt.withTodoContext(DigestPrompt.withHotwords(text, hotwords()), unfinished, ignored)
            val reply = request(preset, key, DigestPrompt.system(template(), LocalDate.now(), appName), input)
            dao.addUsage(LocalDate.now().toString(), reply.promptTokens, reply.completionTokens, UsagePurpose.Digest)
            val (events, items) = DigestPrompt.parse(reply.content, indexed)
            val at = now()
            dao.saveDigest(
                events = events.map { e ->
                    MemoryEventEntity(
                        startAt = e.startAt, endAt = e.endAt, scene = if (isMedia(e.startAt, e.endAt)) "媒体" else e.scene, title = e.title, summary = e.summary,
                        pointsJson = JSONArray(e.points).toString(), peopleJson = JSONArray(e.people).toString(),
                        place = e.place, emotion = e.emotion, intensity = e.intensity, important = e.important,
                        createdAt = at, model = preset.model,
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
            )
            if (events.isNotEmpty()) onEvents()
            runCatching { app.resound.search.EmbeddingScheduler.enqueue(appContext) }
            clearFailures(lines.first().id)
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: LlmException) {
            failureDetail = e.detail
            if (e.type == LlmError.Format && failed(lines.first().id) >= MAX_FORMAT_FAILURES) {
                // 这段连续几次都整理不出来：放弃，原话仍在“原话”页
                dao.markDigested(lines.map { it.id }, now())
                clearFailures(lines.first().id)
                null
            } else e.type
        } catch (_: Exception) {
            LlmError.Format
        }
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
        const val MAX_LINES = 300
        const val MAX_FORMAT_FAILURES = 3

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
