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
 * 把已转写的句子整理成事件。按“段落”处理：相邻两句间隔超过 [GAP_MS] 就算另一段；
 * 一段要等最后一句过去 [GAP_MS]、且这段时间附近没有还没转写完的录音，才算说完了，才整理，避免一件事被切成两半。
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
    private val afterDigest: suspend () -> Unit = {},
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
    private val _last = MutableStateFlow(prefs.getLong("lastRunAt", 0).takeIf { it > 0 } to prefs.getString("lastError", null))
    val last = _last.asStateFlow()

    /** 用户点“现在整理”时置为 true：本轮不等 10 分钟，已转写完的都整理 */
    @Volatile var forceOnce = false

    /** true 表示有暂时性错误，希望稍后重试 */
    suspend fun run(): Boolean {
        if (!gate.tryLock()) return false
        var madeEvents = false
        val force = forceOnce.also { forceOnce = false }
        _progress.value = DigestProgress(startedAt = elapsed())
        _running.value = true
        try {
            val key = readKey()
            if (key.isBlank()) { record(null, "还没填写大模型密钥"); return false }
            val sessions = sessions(dao.undigestedLines())
            val chunks = mutableListOf<List<TranscriptLineEntity>>()
            var waiting = 0
            for (session in sessions) {
                if (closed(session, force)) chunks.addAll(session.chunked(MAX_LINES)) else waiting += session.size
            }
            _progress.value = _progress.value.copy(total = chunks.sumOf { it.size }, batches = chunks.size)
            for ((index, chunk) in chunks.withIndex()) {
                if (!networkAllowed()) { record(now(), "等待网络"); return true }
                _progress.value = _progress.value.copy(batch = index + 1, batchStartedAt = elapsed())
                val refreshed = dao.refreshLines(chunk.map { it.id })
                when (val outcome = if (refreshed.isEmpty()) null else digest(refreshed, key) { madeEvents = true }) {
                    null -> _progress.value = _progress.value.copy(done = _progress.value.done + chunk.size)
                    LlmError.Auth -> { record(now(), outcome.human); return false }
                    LlmError.Network, LlmError.Server, LlmError.Format -> { record(now(), outcome.human); return true }
                }
            }
            record(now(), if (waiting > 0) "$waiting 句等待转写、声纹或对话结束后整理" else null)
            return false
        } finally {
            _progress.value = _progress.value.copy(finishedAt = elapsed())
            _running.value = false
            gate.unlock()
            if (madeEvents) {
                try { afterDigest() }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { /* 技能调度失败不影响已完成的整理。 */ }
            }
        }
    }

    /** 整理一块；成功返回 null，否则返回错误类型 */
    private suspend fun digest(lines: List<TranscriptLineEntity>, key: String, onEvents: () -> Unit): LlmError? {
        val preset = settings.preset
        val (text, indexed) = DigestPrompt.transcript(lines)
        return try {
            val reply = request(preset, key, DigestPrompt.system(template(), LocalDate.now(), appName), DigestPrompt.withHotwords(text, hotwords()))
            dao.addUsage(LocalDate.now().toString(), reply.promptTokens, reply.completionTokens, UsagePurpose.Digest)
            val (events, items) = DigestPrompt.parse(reply.content, indexed)
            val at = now()
            dao.saveDigest(
                events = events.map { e ->
                    MemoryEventEntity(
                        startAt = e.startAt, endAt = e.endAt, scene = e.scene, title = e.title, summary = e.summary,
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
            )
            if (events.isNotEmpty()) onEvents()
            runCatching { app.resound.search.EmbeddingScheduler.enqueue(appContext) }
            clearFailures(lines.first().id)
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: LlmException) {
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

    internal suspend fun closed(session: List<TranscriptLineEntity>, force: Boolean): Boolean {
        if (voiceprintReady() && dao.voiceprintPending(session.map { it.segmentId }.distinct()) > 0) return false
        val start = session.first().startAt
        val end = session.maxOf { it.endAt }
        val quietLongEnough = force || now() - end >= GAP_MS
        return quietLongEnough && dao.unfinishedSegments(start - GAP_MS, if (force) end else end + GAP_MS) == 0
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
        const val GAP_MS = 10 * 60_000L
        const val MAX_LINES = 300
        const val MAX_FORMAT_FAILURES = 3

        /** 按时间间隔分段 */
        fun sessions(lines: List<TranscriptLineEntity>): List<List<TranscriptLineEntity>> {
            val out = mutableListOf<MutableList<TranscriptLineEntity>>()
            for (line in lines) {
                val current = out.lastOrNull()
                if (current != null && line.startAt - current.maxOf { it.endAt } <= GAP_MS) current += line
                else out += mutableListOf(line)
            }
            return out
        }
    }
}
