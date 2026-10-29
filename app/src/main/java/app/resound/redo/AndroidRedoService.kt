package app.resound.redo

import android.content.Context
import androidx.work.*
import app.resound.data.CaptureDatabase
import app.resound.transcribe.TranscribeSource
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
    init {
        if (jobs.value.any { it.state is RedoJobState.Running }) enqueue()
        scope.launch {
            while (isActive) {
                delay(1000); mutableOptions.value = options()
                gate.withLock {
                    val snapshot = db.redoData().snapshot()
                    if (snapshot != null && snapshot.at < now() - 86_400_000) db.redoData().clear()
                    synchronized(this@AndroidRedoService) {
                        val undoId = snapshot?.takeIf { it.at >= now() - 86_400_000 }?.jobId
                        val fresh = jobs.value.filter { it.state is RedoJobState.Running || it.createdAt >= now() - 86_400_000 }.map {
                            val state = it.state
                            if (state is RedoJobState.Done) it.copy(state = state.copy(undoable = it.id == undoId)) else it
                        }
                        if (fresh != jobs.value) publish(fresh)
                    }
                }
            }
        }
    }
    override suspend fun estimate(request: RedoRequest) = engine.estimate(request)
    @Synchronized override fun start(request: RedoRequest): String {
        val normalized = request.copy(eventIds = request.eventIds.distinct(), segmentIds = request.segmentIds.distinct(),
            asr = request.asr ?: asr.lastChoice.value,
            llmPresetId = request.llmPresetId ?: choices.getString("llm", null)?.takeIf { id -> options().any { it.presetId == id && it.ready } } ?: currentLlm())
        val id = UUID.randomUUID().toString()
        if (request.scope != RedoScope.TranscribeOnly) check(choices.edit().putString("llm", normalized.llmPresetId).commit())
        publish(jobs.value + RedoJob(id, if (request.scope == RedoScope.TranscribeOnly) "重新转写" else "重做事件", normalized,
            RedoJobState.Running("排队中", 0f), now()))
        try { enqueue() } catch (_: Exception) { update(id, RedoJobState.Failed("无法安排后台重做，请重试")) }
        return id
    }
    @Synchronized override fun cancel(id: String) {
        if (job(id)?.state !is RedoJobState.Running) return
        update(id, RedoJobState.Cancelled)
        active[id]?.cancel()
        RedoNotifications.clear(context, id)
    }
    @Synchronized override fun dismiss(id: String) {
        if (job(id)?.state is RedoJobState.Running) return
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
        if (result.isSuccess) synchronized(this) { publish(jobs.value.map { if (it.id == id) it.copy(state = RedoJobState.Undone) else it }) }
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
                            if (next.request.scope != RedoScope.DigestOnly) require(asr.options.value.any { it.source == next.request.asr && it.ready }) { "指定转写方式未就绪" }
                            if (next.request.scope != RedoScope.TranscribeOnly) require(options().any { it.presetId == next.request.llmPresetId && it.ready }) { "指定整理模型未就绪" }
                            engine.run(next.id, next.request, ::checkpoint) { stage, fraction ->
                                update(next.id, RedoJobState.Running(stage, fraction)); job(next.id)?.let { progress(it) }
                            }
                            // applied 与取消竞争时，以已经原子提交的数据库事实为准。
                            synchronized(this@AndroidRedoService) {
                                publish(jobs.value.map { if (it.id == next.id) it.copy(state = RedoJobState.Done(
                                    if (next.request.scope == RedoScope.TranscribeOnly) "原话已更新，可以再去重新整理" else "重做完成", true))
                                    else if (it.state is RedoJobState.Done) it.copy(state = it.state.copy(undoable = false)) else it })
                            }
                        }
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) { update(next.id, RedoJobState.Failed(when (e) {
                        is app.resound.digest.LlmException -> e.type.human
                        is app.resound.transcribe.AsrException -> e.type.human
                        is IllegalArgumentException, is IllegalStateException -> e.message ?: "重做失败，请重试"
                        else -> "重做失败，请重试"
                    })) }
                }
                synchronized(this@AndroidRedoService) { active[next.id] = task }
                task.start(); task.join()
                synchronized(this@AndroidRedoService) { active.remove(next.id) }
            }
            currentCoroutineContext().ensureActive()
            if (db.redoData().snapshot()?.let { it.jobId == next.id && it.applied } == true && job(next.id)?.state !is RedoJobState.Done) {
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
    private fun load(): List<RedoJob> {
        val json = runCatching { JSONArray(prefs.getString("jobs", "[]")) }.getOrDefault(JSONArray())
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
        }.getOrNull() }
    }
    companion object {
        fun enqueue(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork("redo", ExistingWorkPolicy.APPEND_OR_REPLACE,
                OneTimeWorkRequestBuilder<RedoWorker>().setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST).build())
        }
    }
}
