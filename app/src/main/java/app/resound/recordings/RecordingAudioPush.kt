package app.resound.recordings

import app.resound.diagnostics.FailureNotes

import app.resound.scheduling.BackgroundRefreshPolicy
import app.resound.scheduling.refreshWhileNeeded
import android.content.Context
import androidx.work.*
import app.resound.AppGraph
import app.resound.data.CaptureDatabase
import app.resound.push.*
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class RecordingAudioPush(
    private val context: Context,
    private val channels: AndroidPushChannels,
    private val source: suspend () -> List<RecordingSource>,
    private val remoteFactory: ((PushChannel, Map<String, String>) -> RecordingRemote)? = null,
    private val enqueueDeletes: (() -> Unit)? = null,
) {
    // 同一把协程锁覆盖上传、核对凭据、隐私删除日志和保留快照。
    private val gate = Mutex()
    /** 隐私删除、原声清理在等锁时置位：上传传完手上这个文件就先让出，不让它们等整轮上传（可能几十分钟）。 */
    private val waiting = java.util.concurrent.atomic.AtomicInteger(0)
    private suspend fun <T> urgent(block: suspend () -> T): T {
        waiting.incrementAndGet()
        try { return gate.withLock { block() } } finally { waiting.decrementAndGet() }
    }
    private val configured = mutableMapOf<Long, PushChannel>()
    private val revision = MutableStateFlow(0L)
    private val directory = File(context.filesDir, "recording-uploads")
    private fun state(id: Long) = RecordingUploadState(File(directory, "$id.json"))
    private fun journal() = RecordingUploadState(File(directory, "pending-deletes.json"))
    private suspend fun load(): List<RecordingSource> {
        val removed = journal().tombstones
        return source().filterNot { removed.has(it.id.toString()) }
    }
    private fun active(c: PushChannel) = c.enabled && channels.capabilities(c.kind).audio && c.subscription.audio != AudioPush.Off
    fun channelNames() = channels.channels.value.filter(::active).map { it.name }
    fun anyActive() = channels.channels.value.any(::active)
    private fun destination(c: PushChannel): String? = try { channels.resolveAudio(c.id)?.second?.let(::folderIdentity) }
        catch (_: Exception) { null }
    /** 切换目的地后不复用凭据；旧路径留在历史里供以后隐私删除使用。 */
    private fun prepare(c: PushChannel, state: RecordingUploadState, identity: String) {
        val previous = state.json.optString("destination")
        val legacyS3 = previous.isBlank() && channels.resolveAudio(c.id)?.second?.get("cloudKind") == "S3"
        if ((previous.isNotBlank() && previous != identity) || legacyS3) {
            val history = state.json.optJSONArray("history") ?: org.json.JSONArray()
            history.put(org.json.JSONObject().put("destination", previous).put("records", org.json.JSONObject(state.records.toString())))
            state.json.put("history", history)
            state.records.keys().asSequence().toList().forEach { state.records.remove(it) }
            state.dailyHash.keys().asSequence().toList().forEach { state.dailyHash.remove(it) }
            state.json.remove("since")
        }
        state.json.put("destination", identity).put("channelName", c.name); state.save()
    }
    private suspend fun protection(rows: List<RecordingSource>, without: Long? = null): Set<Long> {
        val today = java.time.LocalDate.now()
        val configured = channels.channels.value.filter { active(it) && it.id != without }.map { c ->
            val state = state(c.id)
            val identity = destination(c)
            if (identity != null) prepare(c, state, identity)
            RecordingChannel(c.id, c.subscription, state.since(c.subscription, today)?.toString(),
                if (identity == null) emptySet() else state.successful())
        }
        return RecordingKeepRules.held(rows.map { it.keep() }, configured, today.toString())
    }
    // 没有开启推录音的渠道时不需要逐段读取全部录音和原话。
    suspend fun protectedIds(): Set<Long> = if (channels.channels.value.none(::active)) emptySet() else urgent { protection(load()) }
    suspend fun offPreview(id: Long): Int {
        (AppGraph.originalAudio as? app.resound.transcribe.LocalOriginalAudio)?.refreshHeld()
        return gate.withLock {
        val rows = load()
        val lost = protection(rows) - protection(rows, id)
        // 只计当前已到期且满足原声清理条件的失保护段。
        rows.count { it.id in lost && it.id in (AppGraph.originalAudio as? app.resound.transcribe.LocalOriginalAudio)?.heldIds.orEmpty() }
    } }
    fun status(id: Long): Flow<AudioPushStatus> = combine(channels.channels, revision) { list, _ -> list.find { it.id == id } }
        .map { c -> gate.withLock {
            if (c == null || !active(c)) AudioPushStatus(0, 0, 0, null, null, null)
            else {
                val rows = load(); val state = state(id)
                destination(c)?.let { prepare(c, state, it) }
                val current = state.status(rows, c.subscription, System.currentTimeMillis())
                val held = protection(rows)
                val expired = (AppGraph.originalAudio as? app.resound.transcribe.LocalOriginalAudio)?.heldIds.orEmpty()
                current.copy(held = state.candidates(rows, c.subscription, java.time.LocalDate.now()).count { it.id in held && it.id in expired },
                    quotaHint = state.json.optString("quotaHint").takeIf { it.isNotBlank() && state.json.optString("quotaMonth") == java.time.LocalDate.now().toString().take(7) })
            }
        } }.flowOn(Dispatchers.IO)

    val pendingDeletes: Flow<List<PendingRemoteDelete>> = revision.map { gate.withLock { pending(journal()) } }.flowOn(Dispatchers.IO)
    private fun pending(j: RecordingUploadState) = j.records.keys().asSequence().map { key -> j.records.getJSONObject(key).let {
        PendingRemoteDelete(it.getString("channelName"), it.getString("path"), it.getLong("channelId"), it.optBoolean("manual"))
    } }.toList().distinct()
    suspend fun dismiss(channelId: Long, path: String) = gate.withLock {
        val j = journal()
        j.records.keys().asSequence().toList().filter { key -> j.records.getJSONObject(key).let { it.getLong("channelId") == channelId && it.getString("path") == path } }
            .forEach { j.records.remove(it) }
        j.save(); revision.value++
    }
    /**
     * 推到远端（网盘、手机文件夹）的文件，声声不息一律不自动删：隐私删除只把远端相关文件列进“需要你自己删”的清单。
     * 本地删除失败时撤回这次写入的删除标记和清单，录音照旧受保护、照旧可推。
     */
    suspend fun withPrivacy(ids: List<Long>, block: suspend () -> app.resound.privacy.ForgetResult): app.resound.privacy.ForgetResult = urgent {
        val files = directory.listFiles().orEmpty().filter { it.extension == "json" }
        val before = files.associateWith { it.readText() }
        val j = journal()
        ids.forEach { j.tombstones.put(it.toString(), true) }
        // 包括已删通道的 JSON：没有凭据也保留路径供用户自己处理。
        files.filter { it.nameWithoutExtension.toLongOrNull() != null }.forEach { file ->
            val id = file.nameWithoutExtension.toLong(); val state = state(id)
            val c = channels.channels.value.find { it.id == id }
            val identity = state.json.optString("destination").ifBlank { c?.let(::destination).orEmpty() }
            fun queue(records: org.json.JSONObject, destination: String) {
                val rows = records.keys().asSequence().mapNotNull { key -> key.toLongOrNull()?.let { id ->
                    val record = records.getJSONObject(key)
                    PublishedRecording(id, record.getString("path"), record.optString("temporary").takeIf { it.isNotEmpty() })
                } }.toList()
                val dailyDays = state.dailyHash.keys().asSequence().toSet() + state.dirtyDays.keys().asSequence().toSet() +
                    if ((state.json.optJSONArray("history")?.length() ?: 0) > 0) rows.map { it.path.split('/').getOrElse(1) { "" } } else emptyList()
                for (plan in RecordingPrivacyPlan.create(ids.toSet(), rows, dailyDays.toSet(),
                    if (c?.kind == PushKind.LocalFolder) state.dirtyDays.keys().asSequence().toSet() else emptySet())) {
                    val key = sha256("$id|$destination|${plan.path}")
                    j.records.put(key, org.json.JSONObject().put("channelId", id)
                        .put("channelName", c?.name ?: state.json.optString("channelName", "已删除通道"))
                        .put("path", plan.path).put("destination", destination).put("segment", plan.segment).put("manual", true))
                    // 通道还在时，下次上传会重写这一天的逐字稿（去掉删掉的原话）；远端旧文件仍由用户自己处理。
                    state.dirtyDays.put(plan.day, true)
                }
            }
            queue(state.records, identity)
            state.json.optJSONArray("history")?.let { a -> repeat(a.length()) { val h = a.getJSONObject(it); queue(h.getJSONObject("records"), h.optString("destination")) } }
            state.save()
        }
        j.save()
        try { block() }
        catch (e: CancellationException) { throw e } // 取消时可能已删了一部分：保留清单，宁可多列给用户看
        catch (e: Exception) {
            // 本地没删成：恢复删除前的上传状态和清单，不能让录音失去保护、从推送里消失。
            before.forEach { (file, text) -> runCatching { file.writeText(text) }.onFailure { FailureNotes.record("recordings/privacy/rollback", it) } }
            (directory.listFiles().orEmpty().toSet() - before.keys).filter { it.extension == "json" }.forEach { it.delete() }
            throw e
        } finally { revision.value++ }
    }
    private suspend fun remote(c: PushChannel, values: Map<String, String>): RecordingRemote {
        remoteFactory?.let { return it(c, values) }
        if (c.kind == PushKind.LocalFolder) {
            val store = LocalFolderStorage(context, values.getValue("treeUri"))
            return object : RecordingRemote {
                override suspend fun audio(path: String, file: File) { val coroutine = currentCoroutineContext(); store.audio(path, file) { coroutine.ensureActive() } }
                override suspend fun text(path: String, content: String) = store.text(path, content)
                override suspend fun size(path: String): Long? = store.size(path)
                override suspend fun delete(path: String) = store.delete(path)
            }
        }
        return linkedS3(values, cancellation = currentCoroutineContext().job)?.let { s3 ->
            val store = S3FolderRemote(s3, values.getValue("folder"))
            object : RecordingRemote {
                override suspend fun audio(path: String, file: File) { currentCoroutineContext().ensureActive(); store.audio(path, file); currentCoroutineContext().ensureActive() }
                override suspend fun text(path: String, content: String) = store.text(path, content)
                override suspend fun size(path: String): Long? = store.size(path)
                override suspend fun delete(path: String) = store.delete(path)
            }
        } ?: RecordingDav(values)
    }
    /** 远端文件不再自动删：旧版本留下的“正在删”条目一律转成“需要你自己删”。 */
    suspend fun retryDeletes(): Boolean = gate.withLock {
        val j = journal(); var changed = false
        for (key in j.records.keys().asSequence().toList()) {
            val item = j.records.optJSONObject(key) ?: continue
            if (!item.optBoolean("manual")) { item.put("manual", true); changed = true }
        }
        if (changed) j.save()
        revision.value++; false
    }
    private fun scheduleDeletes() {
        val records = journal().records
        if (records.keys().asSequence().none { !records.getJSONObject(it).optBoolean("manual") }) return
        enqueueDeletes?.let { it(); return }
        WorkManager.getInstance(context).enqueueUniqueWork("recording-privacy-deletes", ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<RecordingRemoteDeleteWorker>()
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build())
    }
    suspend fun refresh() {
        gate.withLock {
            val list = channels.channels.value
            RecordingAudioSweepWorker.schedule(context, list)
            (configured.keys - list.filter(::active).map { it.id }.toSet()).forEach { id ->
                WorkManager.getInstance(context).cancelUniqueWork("recording-upload-$id"); configured.remove(id)
            }
            list.filter(::active).forEach { c ->
                val state = state(c.id); destination(c)?.let { prepare(c, state, it) }
                state.since(c.subscription, java.time.LocalDate.now())
                WorkManager.getInstance(context).enqueueUniqueWork("recording-upload-${c.id}",
                    if (configured.put(c.id, c)?.let { it != c } == true) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP, request(c))
            }
            scheduleDeletes(); revision.value++
        }
        (AppGraph.originalAudio as? app.resound.transcribe.LocalOriginalAudio)?.refreshHeld()
        RecordingKeepNotice(context).show(AppGraph.originalAudio.heldSummary.value)
    }
    suspend fun run(id: Long): Boolean = withContext(Dispatchers.IO) { gate.withLock {
        val state = state(id)
        try {
            val (channel, values) = channels.resolveAudio(id) ?: return@withLock false
            if (!active(channel)) return@withLock false
            prepare(channel, state, folderIdentity(values))
            // 清单处理完成前不重建日文件，避免先重建再被迟到的删除任务删掉。
            val pending = journal().records
            if (pending.keys().asSequence().any { pending.getJSONObject(it).let { row -> !row.optBoolean("manual") && row.getLong("channelId") == id && row.optString("destination") == folderIdentity(values) } }) { scheduleDeletes(); return@withLock true }
            val rows = load()
            val count = RecordingUploader(state, remote(channel, values)).run(rows, channel.subscription, System.currentTimeMillis()) {
                channels.channels.value.find { it.id == id } == channel && waiting.get() == 0
            }
            if (count > 0) channels.recordAudio(channel, count)
            val status = state.status(rows, channel.subscription, System.currentTimeMillis())
            val month = java.time.LocalDate.now().toString().take(7)
            if (state.json.optString("quotaMonth") != month) { state.json.remove("quotaHint") }
            val hint = RecordingKeepRules.quotaHint(channel.kind == PushKind.MarkdownFolder && values["cloudKind"] != "S3" &&
                values["url"].orEmpty().ifBlank { "https://dav.jianguoyun.com/dav/" }.contains("jianguoyun", true), status.bytesThisMonth)
            if (hint != null && state.json.optString("quotaMonth") != month) state.json.put("quotaMonth", month).put("quotaHint", hint)
            state.save()
            if (status.pending > 0) WorkManager.getInstance(context).enqueueUniqueWork("recording-upload-$id", ExistingWorkPolicy.APPEND_OR_REPLACE, request(channel))
            false
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            state.json.put("error", if (e is PushFailure) e.message else "录音推送失败，请检查文件夹或网络后重试"); state.save(); true
        } finally { revision.value++ }
    } }
    companion object {
        fun request(c: PushChannel) = request(c.id, c.subscription.audioWifiOnly, c.subscription.audioChargingOnly, c.kind == PushKind.LocalFolder)
        fun request(id: Long, wifi: Boolean, charging: Boolean = false, local: Boolean = false) = OneTimeWorkRequestBuilder<RecordingAudioPushWorker>()
            .setInputData(workDataOf("id" to id, "wifi" to wifi, "charging" to charging, "local" to local))
            .setConstraints(Constraints.Builder().setRequiresCharging(charging)
                .setRequiredNetworkType(if (local) NetworkType.NOT_REQUIRED else if (wifi) NetworkType.UNMETERED else NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build()
    }
}
internal fun recordingPath(path: String, text: Boolean = false) = RecordingPrivacyRules.owned(path, text)
class RecordingRemoteDeleteWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val audio = (AppGraph.push as? AndroidPushChannels)?.audioPush ?: return@withContext Result.retry()
        if (audio.retryDeletes()) Result.retry() else { audio.refresh(); Result.success() }
    }
}
class RecordingAudioPushWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val push = (AppGraph.push as? AndroidPushChannels)?.audioPush ?: return Result.retry()
        // 配置从任意网络改成 Wi-Fi 时，旧任务不可继续使用旧约束。
        val channel = (AppGraph.push as AndroidPushChannels).channels.value.find { it.id == inputData.getLong("id", -1) }
            ?: return Result.success()
        if (channel.subscription.audioWifiOnly && !inputData.getBoolean("wifi", true)) return Result.success()
        if (channel.subscription.audioChargingOnly && !inputData.getBoolean("charging", false)) return Result.success()
        if (channel.kind != PushKind.LocalFolder && inputData.getBoolean("local", false)) return Result.success()
        return if (push.run(channel.id)) Result.retry() else Result.success()
    }
}

/** 装配入口仅在 ResoundApp 调用一次。Room 通知 + 周期校对覆盖落盘、转写、重转和原声过期。 */
@OptIn(FlowPreview::class)
private const val DATA_REFRESH_GAP_MILLIS = 60_000L
internal fun attachRecordingArchive(context: Context, database: CaptureDatabase, channels: AndroidPushChannels, scope: CoroutineScope) {
    val sources = RecordingSources(database, File(context.filesDir, "segments"))
    AppGraph.recordingPacker = AndroidRecordingPacker(context, sources::load, scope, metadata = sources::metadata)
    val pushSources = RecordingSources(database, File(context.filesDir, "segments"), includeImports = true)
    val audio = RecordingAudioPush(context, channels, pushSources::load)
    channels.audioPush = audio
    // 设置变化立即生效；录音、转写带来的数据变动很频繁，合并成最多每分钟一次。
    scope.launch(Dispatchers.IO) { channels.channels.debounce(1000).collect { audio.refresh() } }
    scope.launch(Dispatchers.IO) {
        merge(database.invalidationTracker.createFlow("segments", "transcript_lines", "memory_events", "env_sounds", "import_records").map { Unit },
            channelFlow { channels.channels.map { list -> BackgroundRefreshPolicy.audioNeedsTick(list) }
                .refreshWhileNeeded(60_000) { send(Unit) } })
            // 没开推录音时，录音/转写变动不影响保留核算（设置变化那一路已经清过状态），不必每分钟逐个检查全部录音文件。
            .conflate().collect { if (audio.anyActive()) { audio.refresh(); delay(DATA_REFRESH_GAP_MILLIS) } }
    }
    RecordingAudioSweepWorker.schedule(context, channels.channels.value)
}
class RecordingAudioSweepWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    companion object {
        internal fun schedule(context: Context, channels: List<PushChannel>) {
            val work = WorkManager.getInstance(context)
            if (BackgroundRefreshPolicy.audioNeedsTick(channels))
                work.enqueueUniquePeriodicWork("recording-audio-sweep", ExistingPeriodicWorkPolicy.KEEP,
                    PeriodicWorkRequestBuilder<RecordingAudioSweepWorker>(15, TimeUnit.MINUTES).build())
            else work.cancelUniqueWork("recording-audio-sweep")
        }
    }
    override suspend fun doWork(): Result {
        (AppGraph.push as? AndroidPushChannels)?.audioPush?.refresh() ?: return Result.retry()
        return Result.success()
    }
}
