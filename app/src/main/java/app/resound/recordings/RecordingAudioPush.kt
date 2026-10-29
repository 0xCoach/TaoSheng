package app.resound.recordings

import android.content.Context
import androidx.work.*
import app.resound.AppGraph
import app.resound.data.CaptureDatabase
import app.resound.push.*
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class RecordingAudioPush(
    private val context: Context,
    private val channels: AndroidPushChannels,
    private val load: suspend () -> List<RecordingSource>,
) {
    private val locks = ConcurrentHashMap<Long, Mutex>()
    private val configured = ConcurrentHashMap<Long, PushChannel>()
    private val revision = MutableStateFlow(0L)
    private fun state(id: Long) = RecordingUploadState(File(context.filesDir, "recording-uploads/$id.json"))
    private fun active(c: PushChannel) = c.enabled && c.kind == PushKind.MarkdownFolder && c.subscription.audio != AudioPush.Off
    fun status(id: Long): Flow<AudioPushStatus> = combine(channels.channels, revision) { list, _ -> list.find { it.id == id } }
        .map { channel ->
            if (channel == null || !active(channel)) AudioPushStatus(0, 0, 0, null, null, null)
            else locks.getOrPut(id) { Mutex() }.withLock { state(id).status(load(), channel.subscription, System.currentTimeMillis()) }
        }.flowOn(Dispatchers.IO)
    suspend fun refresh() {
        val list = channels.channels.value
        (configured.keys - list.map { it.id }.toSet()).forEach { id ->
            WorkManager.getInstance(context).cancelUniqueWork("recording-upload-$id"); configured.remove(id)
        }
        val rows = if (list.any(::active)) load() else emptyList()
        for (channel in list) {
            val id = channel.id
            if (active(channel)) {
                locks.getOrPut(id) { Mutex() }.withLock { diagnostic(id, state(id), rows) }
                enqueue(channel)
            } else { WorkManager.getInstance(context).cancelUniqueWork("recording-upload-$id"); configured.remove(id) }
        }
        revision.value++
    }
    private fun enqueue(c: PushChannel) {
        WorkManager.getInstance(context).enqueueUniqueWork("recording-upload-${c.id}", if (configured.put(c.id, c)?.let { it != c } == true) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
            request(c.id, c.subscription.audioWifiOnly))
    }
    suspend fun run(id: Long): Boolean = withContext(Dispatchers.IO) { locks.getOrPut(id) { Mutex() }.withLock {
        val pair = try { channels.resolveAudio(id) } catch (_: Exception) {
            val state = state(id); state.json.put("error", "坚果云账号未连接或通道配置冲突，请检查推送设置"); state.save()
            diagnostic(id, state, load()); revision.value++; return@withLock true
        }
        val (channel, values) = pair ?: return@withLock false
        if (!active(channel)) return@withLock false
        val state = state(id)
        try {
            val rows = load()
            val before = state.status(rows, channel.subscription, System.currentTimeMillis()).uploaded
            try {
                RecordingUploader(state, RecordingDav(values)).run(rows, channel.subscription, System.currentTimeMillis()) {
                    channels.channels.value.find { it.id == id } == channel
                }
            } finally {
                val after = state.status(rows, channel.subscription, System.currentTimeMillis()).uploaded
                if (after > before) channels.recordAudio(channel, after - before)
            }
            if (state.status(rows, channel.subscription, System.currentTimeMillis()).pending > 0) {
                WorkManager.getInstance(context).enqueueUniqueWork("recording-upload-$id", ExistingWorkPolicy.APPEND_OR_REPLACE,
                    request(id, channel.subscription.audioWifiOnly))
            }
            false
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { true }
        finally { revision.value++; diagnostic(id, state, load()) }
    } }
    private fun diagnostic(id: Long, state: RecordingUploadState, rows: List<RecordingSource>) {
        // 只输出计数与固定错误，不含通道名/账号/文件内容。
        val s = channels.channels.value.find { it.id == id }?.subscription ?: return
        val summary = state.status(rows, s, System.currentTimeMillis())
        context.getSharedPreferences("push", Context.MODE_PRIVATE).edit()
            .putString("audio-diagnostic-$id", "已传=${summary.uploaded} 待传=${summary.pending} 本月字节=${summary.bytesThisMonth} 最近错误=${summary.lastError ?: "无"}").apply()
    }
    companion object {
        fun request(id: Long, wifi: Boolean) = OneTimeWorkRequestBuilder<RecordingAudioPushWorker>()
            .setInputData(workDataOf("id" to id, "wifi" to wifi))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(if (wifi) NetworkType.UNMETERED else NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build()
    }
}
class RecordingAudioPushWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val push = (AppGraph.push as? AndroidPushChannels)?.audioPush ?: return Result.retry()
        // 配置从任意网络改成 Wi-Fi 时，旧任务不可继续使用旧约束。
        val channel = (AppGraph.push as AndroidPushChannels).channels.value.find { it.id == inputData.getLong("id", -1) }
            ?: return Result.success()
        if (channel.subscription.audioWifiOnly && !inputData.getBoolean("wifi", true)) return Result.success()
        return if (push.run(channel.id)) Result.retry() else Result.success()
    }
}

/** 装配入口仅在 ResoundApp 调用一次。Room 通知 + 周期校对覆盖落盘、转写、重转和原声过期。 */
@OptIn(FlowPreview::class)
internal fun attachRecordingArchive(context: Context, database: CaptureDatabase, channels: AndroidPushChannels, scope: CoroutineScope) {
    val sources = RecordingSources(database, File(context.filesDir, "segments"))
    AppGraph.recordingPacker = AndroidRecordingPacker(context, sources::load, scope, metadata = sources::metadata)
    val audio = RecordingAudioPush(context, channels, sources::load)
    channels.audioPush = audio
    scope.launch(Dispatchers.IO) {
        merge(channels.channels.map { Unit }, database.invalidationTracker.createFlow("segments", "transcript_lines", "memory_events", "env_sounds").map { Unit },
            flow { while (currentCoroutineContext().isActive) { emit(Unit); delay(60_000) } })
            .debounce(1000).collect { audio.refresh() }
    }
    WorkManager.getInstance(context).enqueueUniquePeriodicWork("recording-audio-sweep", ExistingPeriodicWorkPolicy.KEEP,
        PeriodicWorkRequestBuilder<RecordingAudioSweepWorker>(15, TimeUnit.MINUTES).build())
}
class RecordingAudioSweepWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        (AppGraph.push as? AndroidPushChannels)?.audioPush?.refresh() ?: return Result.retry()
        return Result.success()
    }
}
