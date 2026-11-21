package app.resound.redo

import app.resound.diagnostics.FailureNotes

import app.resound.digest.LlmException
import app.resound.transcribe.AsrException

import app.resound.scheduling.BackgroundRefreshPolicy
import app.resound.scheduling.refreshWhileNeeded
import android.content.Context
import androidx.work.*
import app.resound.data.CaptureDatabase
import app.resound.transcribe.TranscribeSource
import app.resound.transcribe.calendarChanges
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

internal class AndroidRedoService(
    private val context: Context,
    private val db: CaptureDatabase,
    override val asr: AsrChooser,
    private val engine: RedoEngine,
    private val scope: CoroutineScope,
    private val options: () -> List<LlmOption>,
    private val currentLlm: () -> String,
    private val idle: suspend (suspend () -> Unit) -> Unit = { it() },
    private val enqueue: () -> Unit = { enqueue(context) },
    private val now: () -> Long = System::currentTimeMillis,
) : RedoService {
    private val prefs = context.getSharedPreferences("redo-jobs", Context.MODE_PRIVATE)
    private val choices = context.getSharedPreferences("redo-choices", Context.MODE_PRIVATE)
    private val mutableJobs = MutableStateFlow(load())
    override val jobs = mutableJobs.asStateFlow()
    private val mutableOptions = MutableStateFlow(options())
    override val llmOptions = mutableOptions.asStateFlow()
    private val gate = Mutex()
    private val active = mutableMapOf<String, Job>()
    private val mutableUndoable = MutableStateFlow<RedoUndoable?>(null)
    override val undoable = mutableUndoable.asStateFlow()
    /** 撤销后快照被清掉，任务列表可能没变（任务已关掉），靠它重新看一遍 */
    private val undoChanges = MutableStateFlow(0)
    init {
        if (jobs.value.any { it.state is RedoJobState.Running }) enqueue()
        scope.launch {
            mutableOptions.subscriptionCount.map { it > 0 }.refreshWhileNeeded(1000) { mutableOptions.value = options() }
        }
        scope.launch {
            combine(jobs, calendarChanges(context), undoChanges) { rows, _, _ -> rows }.collectLatest {
                while (isActive) {
                    var nextExpiry: Long? = null
                    gate.withLock {
                        val snapshot = db.redoData().snapshotMeta()
                        if (snapshot != null && snapshot.at < now() - 86_400_000) db.redoData().clear()
                        else nextExpiry = snapshot?.at?.let(BackgroundRefreshPolicy::redoDeadline)
                        synchronized(this@AndroidRedoService) {
                            val undoId = snapshot?.takeIf { it.at >= now() - 86_400_000 }?.jobId
                            val fresh = jobs.value.filter { it.state is RedoJobState.Running || it.createdAt >= now() - 86_400_000 }.map {
                                val state = it.state
                                if (state is RedoJobState.Done) it.copy(state = state.copy(undoable = it.id == undoId)) else it
                            }
                            if (fresh != jobs.value) publish(fresh)
                            // 只认已生效的快照：新重做一开始就会占用快照位，跑完（applied）之前不能撤
                            mutableUndoable.value = snapshot?.takeIf { it.jobId == undoId && it.applied }
                                ?.let { s -> fresh.find { it.id == s.jobId }.let { j -> if (j?.state is RedoJobState.Running) null else undoableOf(s.jobId, s.at, j) } }
                            nextExpiry = (fresh.filter { it.state !is RedoJobState.Running }.map { BackgroundRefreshPolicy.redoDeadline(it.createdAt) } + listOfNotNull(nextExpiry)).minOrNull()
                        }
                    }
                    val deadline = nextExpiry ?: break
                    delay((deadline - now()).coerceAtLeast(1))
                }
            }
        }
    }
    override suspend fun estimate(request: RedoRequest): RedoEstimate {
        (asr as? AndroidAsrChooser)?.refresh()
        return engine.estimate(request)
    }
    @Synchronized override fun start(request: RedoRequest): String {
        (asr as? AndroidAsrChooser)?.refresh()
        val normalized = request.copy(eventIds = request.eventIds.distinct(), segmentIds = request.segmentIds.distinct(),
            asr = request.asr ?: asr.lastChoice.value,
            llmPresetId = request.llmPresetId ?: choices.getString("llm", null)?.takeIf { id -> options().any { it.presetId == id && it.ready } } ?: currentLlm())
        val id = UUID.randomUUID().toString()
        if (request.scope.digests) check(choices.edit().putString("llm", normalized.llmPresetId).commit())
        publish(jobs.value + RedoJob(id, when (request.scope) {
            RedoScope.TranscribeOnly -> "重新转写"
            RedoScope.Merge -> "合并事件"
            else -> "重做事件"
        }, normalized,
            RedoJobState.Running("排队中", 0f), now()))
        try { enqueue() } catch (caught: Exception) {
            FailureNotes.record("redo/AndroidRedoService.kt:87", caught)
            update(id, RedoJobState.Failed("无法安排后台重做，请重试")) }
        return id
    }
    @Synchronized override fun cancel(id: String) {
        if (job(id)?.state !is RedoJobState.Running) return
        update(id, RedoJobState.Cancelled)
        active[id]?.cancel()
        RedoNotifications.clear(context, id)
    }
    @Synchronized override fun dismiss(id: String) {
        val job = job(id)
        if (job?.state is RedoJobState.Running) return
        // 卡 219：关掉的任务从列表拿走，但撤销快照还在；留下它是哪种重做，撤销入口好写清楚
        if ((job?.state as? RedoJobState.Done)?.undoable == true) check(prefs.edit().putString(UNDO_META,
            JSONObject().put("id", id).put("title", job.title).put("scope", job.request.scope.name).toString()).commit()) { "无法保存重做任务" }
        publish(jobs.value.filterNot { it.id == id }); RedoNotifications.clear(context, id)
    }
    @Synchronized internal fun job(id: String) = jobs.value.find { it.id == id }
    @Synchronized private fun update(id: String, state: RedoJobState) {
        if (job(id)?.state !is RedoJobState.Running) return
        publish(jobs.value.map { if (it.id == id) it.copy(state = state) else it })
    }
    override suspend fun undo(id: String): Result<Unit> = gate.withLock {
        if (jobs.value.any { it.state is RedoJobState.Running }) return@withLock Result.failure(IllegalStateException("请等待重做结束后再撤销"))
        var result: Result<Unit> = Result.failure(IllegalStateException("无法撤销"))
        idle { result = engine.undo(id) }
        if (result.isSuccess) synchronized(this) {
            publish(jobs.value.map { if (it.id == id) it.copy(state = RedoJobState.Undone) else it })
            mutableUndoable.value = null; undoChanges.value++
        }
        result
    }
    internal suspend fun drain(progress: suspend (RedoJob) -> Unit) = gate.withLock {
        while (true) {
            val next = synchronized(this) { jobs.value.firstOrNull { it.state is RedoJobState.Running } } ?: break
            // 子 Job 只取消当前任务，不取消后续排队任务；系统停止则保留 Running。
            supervisorScope {
                val task = launch(start = CoroutineStart.LAZY) {
                    try {
                        idle {
                            fun checkpoint() { if (job(next.id)?.state !is RedoJobState.Running) throw CancellationException("已取消") }
                            checkpoint()
                            // 无界面订阅时仍以执行当下的配置/下载状态校验后台任务。
                            (asr as? AndroidAsrChooser)?.refresh()
                            if (next.request.scope.transcribes) require(asr.options.value.any { it.source == next.request.asr && it.ready }) { "指定转写方式未就绪" }
                            if (next.request.scope.digests) require(options().any { it.presetId == next.request.llmPresetId && it.ready }) { "指定整理模型未就绪" }
                            engine.run(next.id, next.request, ::checkpoint) { stage, fraction ->
                                update(next.id, RedoJobState.Running(stage, fraction)); job(next.id)?.let { progress(it) }
                            }
                            // applied 与取消竞争时，以已经原子提交的数据库事实为准。
                            synchronized(this@AndroidRedoService) {
                                publish(jobs.value.map { if (it.id == next.id) it.copy(state = RedoJobState.Done(
                                    when (next.request.scope) {
                                        RedoScope.TranscribeOnly -> "原话已更新，可以再去重新整理"
                                        RedoScope.Merge -> "合并完成"
                                        else -> "重做完成"
                                    }, true))
                                    else if (it.state is RedoJobState.Done) it.copy(state = it.state.copy(undoable = false)) else it })
                            }
                        }
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) { update(next.id, RedoJobState.Failed(when (e) {
                        is LlmException -> e.type.human
                        is AsrException -> e.type.human
                        is IllegalArgumentException, is IllegalStateException -> e.message ?: "重做失败，请重试"
                        else -> "重做失败，请重试"
                    })) }
                }
                synchronized(this@AndroidRedoService) { active[next.id] = task }
                task.start(); task.join()
                synchronized(this@AndroidRedoService) { active.remove(next.id) }
            }
            currentCoroutineContext().ensureActive()
            if (db.redoData().snapshotMeta()?.let { it.jobId == next.id && it.applied } == true && job(next.id)?.state !is RedoJobState.Done) {
                synchronized(this) { publish(jobs.value.map { if (it.id == next.id) it.copy(state = RedoJobState.Done("重做完成", true)) else it }) }
            }
            job(next.id)?.let { RedoNotifications.finished(context, it) }
        }
    }
    private fun publish(value: List<RedoJob>) {
        val json = JSONArray(value.map { j -> JSONObject().apply {
            put("id", j.id); put("title", j.title); put("at", j.createdAt)
            put("events", JSONArray(j.request.eventIds)); put("segments", JSONArray(j.request.segmentIds)); put("scope", j.request.scope.name)
            put("asr", j.request.asr?.name ?: ""); put("llm", j.request.llmPresetId ?: "")
            when (val s = j.state) {
                is RedoJobState.Running -> put("state", "running")
                is RedoJobState.Done -> { put("state", "done"); put("message", s.summary); put("undo", s.undoable) }
                is RedoJobState.Failed -> { put("state", "failed"); put("message", s.message) }
                RedoJobState.Cancelled -> put("state", "cancelled")
                RedoJobState.Undone -> put("state", "undone")
            }
        } })
        check(prefs.edit().putString("jobs", json.toString()).commit()) { "无法保存重做任务" }
        mutableJobs.value = value
    }
    private fun undoableOf(id: String, at: Long, job: RedoJob?): RedoUndoable {
        val meta = job?.let { it.title to it.request.scope } ?: runCatching {
            JSONObject(prefs.getString(UNDO_META, null) ?: return@runCatching null).takeIf { it.getString("id") == id }
                ?.let { it.getString("title") to RedoScope.valueOf(it.getString("scope")) }
        }.getOrNull()
        return RedoUndoable(id, at, at + 86_400_000, meta?.first, meta?.second)
    }
    private fun load(): List<RedoJob> {
        val json = runCatching { JSONArray(prefs.getString("jobs", "[]")) }.onFailure {
            FailureNotes.record("redo/AndroidRedoService.kt:176", it)
        }.getOrDefault(JSONArray())
        return (0 until json.length()).mapNotNull { i -> runCatching {
            val j = json.getJSONObject(i)
            fun ids(key: String) = j.getJSONArray(key).let { a -> (0 until a.length()).map { a.getLong(it) } }
            val request = RedoRequest(ids("events"), ids("segments"), RedoScope.valueOf(j.getString("scope")),
                j.optString("asr").takeIf { it.isNotEmpty() }?.let(TranscribeSource::valueOf), j.optString("llm").takeIf { it.isNotEmpty() })
            val state = when (j.getString("state")) {
                "done" -> RedoJobState.Done(j.getString("message"), j.optBoolean("undo"))
                "failed" -> RedoJobState.Failed(j.getString("message"))
                "cancelled" -> RedoJobState.Cancelled
                "undone" -> RedoJobState.Undone
                else -> RedoJobState.Running("等待恢复", 0f)
            }
            RedoJob(j.getString("id"), j.getString("title"), request, state, j.getLong("at"))
        }.onFailure {
            FailureNotes.record("redo/AndroidRedoService.kt:177", it)
        }.getOrNull() }
    }
    companion object {
        private const val UNDO_META = "undo-meta"
        fun enqueue(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork("redo", ExistingWorkPolicy.APPEND_OR_REPLACE,
                OneTimeWorkRequestBuilder<RedoWorker>().setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST).build())
        }
    }
}
