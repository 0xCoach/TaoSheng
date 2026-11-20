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
    suspend fun protectedIds(): Set<Long> = if (channels.channels.value.none(::active)) emptySet() else gate.withLock { protection(load()) }
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
    suspend fun withPrivacy(ids: List<Long>, block: suspend () -> app.resound.privacy.ForgetResult): app.resound.privacy.ForgetResult = gate.withLock {
        val j = journal()
        ids.forEach { j.tombstones.put(it.toString(), true) }
        // 包括已删通道的 JSON：没有凭据也保留待删路径供用户手动处理。
        directory.listFiles().orEmpty().filter { it.nameWithoutExtension.toLongOrNull() != null && it.extension == "json" }.forEach { file ->
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
                    val record = records.getJSONObject(plan.segment.toString())
                    val daily = plan.path == RecordingKeepRules.dailyPath(plan.day)
                    val manual = !daily && RecordingPrivacyDeleteRules.manual(c?.subscription?.audioPrivacyDelete == true, plan.path.endsWith(".part"))
                    j.records.put(key, org.json.JSONObject().put("channelId", id)
                        .put("channelName", c?.name ?: state.json.optString("channelName", "已删除通道"))
                        .put("path", plan.path).put("destination", destination).put("segment", plan.segment)
                        .put("audioPath", record.getString("path")).put("bytes", record.opt("bytes"))
                        .put("manual", manual || j.records.optJSONObject(key)?.optBoolean("manual") == true)
                        .put("audioDeleteVerified", j.records.optJSONObject(key)?.optBoolean("audioDeleteVerified") == true))
                    state.dirtyDays.put(plan.day, true)
                }
            }
            queue(state.records, identity)
            state.json.optJSONArray("history")?.let { a -> repeat(a.length()) { val h = a.getJSONObject(it); queue(h.getJSONObject("records"), h.optString("destination")) } }
            // 先持久化清单再标脏，任意一步中断都可重试。
            j.save(); state.save()
        }
        j.save()
        try { block() } finally { revision.value++; scheduleDeletes() }
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
    suspend fun retryDeletes(): Boolean = gate.withLock {
        val j = journal(); var retry = false
        val attempted = mutableSetOf<String>()
        for (key in j.records.keys().asSequence().toList()) {
            if (!attempted.add(key)) continue
            val item = j.records.optJSONObject(key) ?: continue
            if (item.optBoolean("manual")) continue
            try {
                val channelId = item.getLong("channelId")
                val channel = channels.channels.value.find { it.id == channelId }
                val path = item.getString("path")
                if (!recordingPath(path, text = true)) continue
                val day = path.split('/')[1]
                val daily = path == RecordingKeepRules.dailyPath(day)
                if (!daily && RecordingPrivacyDeleteRules.manual(channel?.subscription?.audioPrivacyDelete == true, path.endsWith(".part"))) {
                    item.put("manual", true); j.save(); continue
                }
                if (!daily && (!item.has("bytes") || item.isNull("bytes") || item.optLong("bytes", -1) < 0 ||
                        !RecordingPrivacyRules.owned(item.optString("audioPath")))) {
                    item.put("manual", true)
                    FailureNotes.note("recordings/privacyDelete/MissingBytes")
                    j.save(); continue
                }
                val pair = channels.resolveAudio(channelId) ?: continue
                if (!RecordingPrivacyPlan.mayRetry(item.getString("destination"), folderIdentity(pair.second))) continue
                val state = state(channelId)
                val remote = remote(pair.first, pair.second)
                val completedKeys: List<String>
                if (daily) {
                    val content = RecordingKeepRules.dailyText(day, load().filter { it.day.toString() == day && it.completed && state.uploaded(it.id) }.map {
                        state.path(it).getString("path").substringAfterLast('/') to it.dailyLines
                    })
                    remote.text(path, content)
                    state.dailyHash.put(day, sha256(content)); state.dirtyDays.remove(day)
                    completedKeys = listOf(key)
                } else {
                    // 新清单自带原音频和大小；旧清单没有凭据时转人工，绝不直接删 txt。
                    val audio = item.optString("audioPath")
                    val group = j.records.keys().asSequence().filter { candidate ->
                        j.records.getJSONObject(candidate).let { row ->
                            row.getLong("channelId") == channelId && row.optString("destination") == item.optString("destination") &&
                                row.optString("audioPath") == audio && row.getString("path") in RecordingPrivacyRules.files(audio)
                        }
                    }.toList().ifEmpty { listOf(key) }
                    attempted.addAll(group)
                    if (group.any { j.records.getJSONObject(it).optBoolean("manual") }) {
                        group.forEach { j.records.getJSONObject(it).put("manual", true) }; j.save(); continue
                    }
                    val bytes = if (item.has("bytes") && !item.isNull("bytes")) item.getLong("bytes") else null
                    val check = if (bytes == null || !RecordingPrivacyRules.owned(audio)) RecordingDeleteCheck.MissingBytes
                        else RecordingPrivacyDeleteRules.check(bytes, remote.size(audio))
                    when (check) {
                        RecordingDeleteCheck.MissingBytes, RecordingDeleteCheck.SizeMismatch -> {
                            group.forEach { j.records.getJSONObject(it).put("manual", true) }
                            FailureNotes.note("recordings/privacyDelete/$check")
                            j.save(); continue
                        }
                        RecordingDeleteCheck.Delete -> {
                            // 核对凭据先落盘；删音频后即使进程中断，也能继续处理同名 txt。
                            group.forEach { j.records.getJSONObject(it).put("audioDeleteVerified", true) }; j.save()
                            remote.delete(audio)
                        }
                        RecordingDeleteCheck.Missing -> Unit
                    }
                    if (check == RecordingDeleteCheck.Delete || item.optBoolean("audioDeleteVerified")) {
                        val text = RecordingPrivacyRules.textPath(audio)
                        if (group.any { j.records.getJSONObject(it).getString("path") == text }) remote.delete(text)
                    }
                    completedKeys = group
                }
                // 先保存状态，再移除完成凭据；进程中断只会幂等重做这个精确文件。
                state.save(); completedKeys.forEach { j.records.remove(it) }; j.save()
                fun removeFinished(records: org.json.JSONObject) {
                    records.keys().asSequence().toList().filter { j.tombstones.has(it) }.forEach { segment ->
                        val audio = records.getJSONObject(segment).getString("path")
                        if (j.records.keys().asSequence().none { j.records.getJSONObject(it).let { r ->
                                r.getLong("channelId") == channelId && r.getString("path") in RecordingPrivacyRules.files(audio) } }) records.remove(segment)
                    }
                }
                removeFinished(state.records)
                state.json.optJSONArray("history")?.let { a -> repeat(a.length()) { removeFinished(a.getJSONObject(it).getJSONObject("records")) } }
                state.save()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { FailureNotes.record("recordings/privacyDelete/retry", e); retry = true }
        }
        revision.value++; retry
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
                channels.channels.value.find { it.id == id } == channel
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
            .conflate().collect { audio.refresh(); delay(DATA_REFRESH_GAP_MILLIS) }
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
