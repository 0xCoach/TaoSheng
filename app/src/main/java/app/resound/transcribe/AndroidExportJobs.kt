package app.resound.transcribe

import android.content.Context
import androidx.work.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONArray
import org.json.JSONObject

/** 请求与结果先持久化再调度，WorkManager 的 Data 只放 id，避免大批段号超过 10 KiB。 */
internal class AndroidExportJobs(
    private val context: Context,
    private val exporter: AudioExporter,
    private val scope: CoroutineScope,
    private val enqueue: (String) -> Unit = { id -> enqueueWork(context, id) },
    private val stop: (String) -> Unit = { id -> WorkManager.getInstance(context).cancelUniqueWork(workName(id)); Unit },
    private val now: () -> Long = System::currentTimeMillis,
) : ExportJobs {
    private data class Entry(val request: ExportRequest, val job: ExportJob, val updated: Long)
    private val prefs = context.getSharedPreferences("export-jobs", Context.MODE_PRIVATE)
    private val entries = linkedMapOf<String, Entry>()
    private val mutableJobs = MutableStateFlow<List<ExportJob>>(emptyList())
    override val jobs: StateFlow<List<ExportJob>> = mutableJobs.asStateFlow()

    init {
        val array = runCatching { JSONArray(prefs.getString("jobs", "[]")) }.getOrDefault(JSONArray())
        for (i in 0 until array.length()) runCatching {
            val json = array.getJSONObject(i)
            val ids = json.getJSONArray("segments")
            val request = ExportRequest((0 until ids.length()).map { ids.getLong(it) }, json.getString("title"),
                json.getBoolean("text"), json.getBoolean("srt"), json.getBoolean("media"))
            val state = when (json.getString("state")) {
                "done" -> ExportJobState.Done(json.getJSONArray("files").let { files ->
                    (0 until files.length()).map { File(files.getString(it)) }
                })
                "failed" -> ExportJobState.Failed(json.getString("message"))
                "cancelled" -> ExportJobState.Cancelled
                else -> ExportJobState.Running(0f)
            }
            val id = json.getString("id")
            entries[id] = Entry(request, ExportJob(id, request.title, json.getLong("started"), state), json.getLong("updated"))
        }
        entries.values.filter { it.job.state is ExportJobState.Cancelled }.forEach {
            (exporter as? LocalAudioExporter)?.removeJobFiles(it.job.id)
        }
        prune()
        publish()
        // KEEP 防止与系统正在恢复的同一 Work 重复；也补上持久化后、enqueue 前的进程退出。
        entries.values.filter { it.job.state is ExportJobState.Running }.forEach { enqueue(it.job.id) }
        scope.launch {
            while (isActive) { delay(60_000); synchronized(this@AndroidExportJobs) { prune(); publish() } }
        }
    }

    @Synchronized override fun start(request: ExportRequest): String {
        prune()
        val normalized = request.copy(segmentIds = request.segmentIds.distinct().sorted())
        entries.values.firstOrNull { it.request == normalized && it.job.state is ExportJobState.Running }
            ?.let { return it.job.id }
        val id = UUID.randomUUID().toString()
        entries[id] = Entry(normalized, ExportJob(id, request.title, now(), ExportJobState.Running(0f)), now())
        publish()
        try { enqueue(id) } catch (_: Exception) { update(id, ExportJobState.Failed("无法安排后台导出，请重试")) }
        return id
    }

    @Synchronized override fun cancel(id: String) {
        val entry = entries[id] ?: return
        if (entry.job.state !is ExportJobState.Running) return
        update(id, ExportJobState.Cancelled)
        stop(id)
        (exporter as? LocalAudioExporter)?.removeJobFiles(id)
        ExportNotifications.clear(context, id)
    }

    @Synchronized override fun dismiss(id: String) {
        if (entries[id]?.job?.state is ExportJobState.Running) return
        entries.remove(id)
        publish()
        ExportNotifications.clear(context, id)
    }

    @Synchronized internal fun job(id: String): ExportJob? = entries[id]?.job
    @Synchronized private fun request(id: String): ExportRequest? = entries[id]
        ?.takeIf { it.job.state is ExportJobState.Running }?.request

    @Synchronized internal fun update(id: String, state: ExportJobState): Boolean {
        val old = entries[id] ?: return false
        if (old.job.state !is ExportJobState.Running) return false
        entries[id] = old.copy(job = old.job.copy(state = state), updated = now())
        publish()
        return true
    }

    /** Worker 与本卡测试共用同一路径；系统停止保留 Running，WorkManager 下次从头合成。 */
    internal suspend fun run(id: String, progress: suspend (ExportJob) -> Unit): Boolean {
        val request = request(id) ?: return job(id)?.state is ExportJobState.Done
        var result: ExportJobState? = null
        var lastProgress = 0L
        try {
            val flow = (exporter as? LocalAudioExporter)?.exportIn(request, id) ?: exporter.export(request)
            flow.collect { value ->
                currentCoroutineContext().ensureActive()
                if (job(id)?.state !is ExportJobState.Running) throw CancellationException("导出已取消")
                when (value) {
                    is ExportProgress.Working -> {
                        val at = android.os.SystemClock.elapsedRealtime()
                        if (lastProgress == 0L || at - lastProgress >= 1000 || value.fraction == 1f) {
                            lastProgress = at
                            update(id, ExportJobState.Running(value.fraction))
                            job(id)?.let { progress(it) }
                        }
                    }
                    is ExportProgress.Done -> result = ExportJobState.Done(value.files)
                    is ExportProgress.Failed -> result = ExportJobState.Failed(value.message)
                }
            }
            val terminal = result ?: ExportJobState.Failed("导出未生成结果，请重试")
            if (!update(id, terminal)) {
                (result as? ExportJobState.Done)?.files?.forEach { it.delete() }
                (exporter as? LocalAudioExporter)?.removeJobFiles(id)
                return false
            }
            return terminal is ExportJobState.Done
        } catch (e: CancellationException) {
            // collect 已取消，LocalAudioExporter 的 finally 会删除本轮半成品。
            if (job(id)?.state !is ExportJobState.Done) {
                (result as? ExportJobState.Done)?.files?.forEach { it.delete() }
                (exporter as? LocalAudioExporter)?.removeJobFiles(id)
            }
            throw e
        } catch (e: Exception) {
            update(id, ExportJobState.Failed(e.message ?: "导出失败，请重试"))
            return false
        }
    }

    private fun prune() {
        entries.entries.removeAll { it.value.job.state !is ExportJobState.Running && it.value.updated < now() - 86_400_000 }
    }
    private fun publish() {
        val array = JSONArray()
        entries.values.forEach { entry ->
            val r = entry.request; val j = entry.job
            val json = JSONObject().put("id", j.id).put("title", j.title).put("started", j.startedAt)
                .put("updated", entry.updated).put("segments", JSONArray(r.segmentIds))
                .put("text", r.includeText).put("srt", r.includeSrt).put("media", r.skipMedia)
            when (val state = j.state) {
                is ExportJobState.Running -> json.put("state", "running")
                is ExportJobState.Done -> json.put("state", "done").put("files", JSONArray(state.files.map { it.path }))
                is ExportJobState.Failed -> json.put("state", "failed").put("message", state.message)
                ExportJobState.Cancelled -> json.put("state", "cancelled")
            }
            array.put(json)
        }
        check(prefs.edit().putString("jobs", array.toString()).commit()) { "无法保存导出任务" }
        mutableJobs.value = entries.values.map { it.job }.sortedByDescending { it.startedAt }
    }
    companion object {
        private fun workName(id: String) = "audio-export-$id"
        private fun enqueueWork(context: Context, id: String) {
            WorkManager.getInstance(context).enqueueUniqueWork(workName(id), ExistingWorkPolicy.KEEP, workRequest(id))
        }
        internal fun workRequest(id: String): OneTimeWorkRequest = OneTimeWorkRequestBuilder<AudioExportWorker>()
                .setInputData(workDataOf("export-id" to id))
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .addTag("audio-export").build()
    }
}
