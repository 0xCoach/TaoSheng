package app.resound.remote

import android.content.Context
import androidx.room.withTransaction
import app.resound.data.*
import app.resound.transcribe.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.MultipartBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** 连接、人物映射、待同步动作共用现有 WorkManager 转写调度。 */
class RemoteSpeechService(
    context: Context, private val db: CaptureDatabase, private val settings: AndroidTranscribeSettings,
    private val scope: CoroutineScope, private val schedule: () -> Unit, private val networkAllowed: () -> Boolean,
) {
    private val dao = db.remoteSpeech()
    val client = SpeechServiceClient(dao, canRequest = networkAllowed)
    private val prefs = context.getSharedPreferences("remote-speech", Context.MODE_PRIVATE)
    private val audioDir = File(context.filesDir, "remote-voiceprint").apply { mkdirs() }
    /** 已上传样本的声音副本，人物页回放用 */
    val sampleAudio = app.resound.voiceprint.SampleAudioStore(File(context.filesDir, "voiceprint-samples"))
    private val syncing = Mutex()
    private val _connection = MutableStateFlow<SpeechConnection?>(null)
    val connection = _connection.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()
    val people = dao.observePeople().stateIn(scope, SharingStarted.Eagerly, emptyList())
    private var lastPeopleRefresh = 0L

    init { configurationChanged(); scope.launch { dao.orphanTasks().collect { tasks -> if (tasks.any { it.scope == connection.value?.scope }) schedule() } } }
    private fun configKey(key: String) = speechHash("${settings.customEndpoint}\n${settings.customModel}\n${speechHash(key)}")
    private fun current(c: SpeechConnection) = runCatching {
        settings.customModel == c.model &&
            OpenAiEndpoint.bases(settings.customEndpoint).contains(c.base) && speechHash(settings.credential(TranscribeSource.Custom)) == speechHash(c.key)
    }.getOrDefault(false)
    fun configurationChanged() {
        audioDir.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 86_400_000L }?.forEach { it.delete() }
        _connection.value = null
        if (settings.source.value != TranscribeSource.Custom) return
        val key = runCatching { settings.credential(TranscribeSource.Custom) }.getOrNull() ?: return
        prefs.getString(configKey(key), null)?.let { _connection.value = SpeechConnection(it, settings.customModel, key) }
        scope.launch { try { resolve(key) } catch (_: Exception) { /* 现有任务重试负责恢复，初始化不弹错误。 */ } }
    }
    suspend fun resolve(key: String = settings.credential(TranscribeSource.Custom)): SpeechConnection? {
        _connection.value?.takeIf { current(it) && it.key == key }?.let { return it }
        if (!networkAllowed()) return null
        val endpoint = settings.customEndpoint; val model = settings.customModel
        val c = client.discover(endpoint, model, key)
        if (endpoint != settings.customEndpoint || model != settings.customModel) throw AsrException(AsrError.Interrupted)
        if (c != null) {
            check(prefs.edit().putString(configKey(key), c.base).commit())
            if (settings.source.value == TranscribeSource.Custom) _connection.value = c
        }
        return c
    }

    suspend fun recognize(c: SpeechConnection, audio: AsrAudio, beforeAudio: suspend () -> Unit): AsrResult {
        val key = "asr:${c.scope}:${speechHash(c.model)}:${audio.segmentId ?: "test"}:${speechFileHash(audio.file)}:${audio.separateSpeakers}"
        val result = client.operation(c, key, "POST", "/transcription-jobs", { SpeechServiceClient.audioBody(c, audio) }, audio.segmentId,
            beforeUpload = beforeAudio, valid = { current(c) && (audio.segmentId == null || db.transcribe().findSegment(audio.segmentId) != null) })
        if (!current(c)) throw AsrException(AsrError.Interrupted)
        val parsed = parseSpeechResult(result, audio.durationMillis, c.scope) { remoteId, name -> mapPerson(c, remoteId, name)?.localId }
        if (audio.segmentId == null) client.forget(key)
        return parsed
    }
    suspend fun consumed(segmentId: Long) = dao.consumeSegment(segmentId)

    private suspend fun mapPerson(c: SpeechConnection, remoteId: String, name: String): RemoteSpeechPerson? = db.withTransaction {
        val profiles = dao.people(c.scope)
        profiles.find { it.remoteId == remoteId }?.let { old ->
            if (old.deleted) return@withTransaction null
            val renamed = old.copy(name = name)
            if (old.localId != 0L && old.name != name && db.voiceprint().people().none { it.id != old.localId && it.name == name }) {
                db.voiceprint().renamePerson(old.localId, name); db.voiceprint().renameResults(old.localId, name)
            }
            dao.person(renamed); return@withTransaction renamed
        }
        val localId = if (name == "我") 0L else db.voiceprint().people().find { it.name == name }?.id
            ?: db.voiceprint().insertPerson(VoiceprintPersonEntity(name = name, createdAt = System.currentTimeMillis()))
        profiles.find { it.localId == localId }?.let { existing ->
            if (existing.deleted || existing.remoteId != null) {
                if (existing.deleted && existing.remoteId == null) dao.person(existing.copy(remoteId = remoteId))
                return@withTransaction null
            }
        }
        RemoteSpeechPerson(c.scope, localId, remoteId, name).also { dao.person(it) }
    }

    private suspend fun ensurePerson(c: SpeechConnection, id: Long): RemoteSpeechPerson {
        val old = dao.people(c.scope).find { it.localId == id }
        if (old?.deleted == true) throw AsrException(AsrError.Protocol, detail = "人物正在删除，请等待同步完成")
        if (old?.remoteId != null) return old
        val name = if (id == 0L) "我" else db.voiceprint().people().find { it.id == id }?.name
            ?: throw AsrException(AsrError.Protocol)
        val key = "${c.scope}:create:$id"
        val result = client.operation(c, key, "POST", "/people", { SpeechServiceClient.jsonBody(JSONObject().put("name", name)) }, valid = { current(c) })
        val remote = result.optString("id"); if (!validSpeechId(remote)) throw AsrException(AsrError.Protocol)
        val latest = dao.people(c.scope).find { it.localId == id }
        val row = RemoteSpeechPerson(c.scope, id, remote, name, deleted = latest?.deleted == true)
        dao.person(row); client.forget(key)
        return row
    }

    private suspend fun refreshPeople(c: SpeechConnection) {
        val key = "${c.scope}:people:list"
        val result = client.operation(c, key, "GET", "/people", { null }, valid = { current(c) })
        val array = result.optJSONArray("people") ?: throw AsrException(AsrError.Protocol)
        val seen = mutableSetOf<String>()
        for (i in 0 until array.length()) {
            val p = array.getJSONObject(i); val id = p.optString("id"); val name = p.optString("name")
            if (!validSpeechId(id) || name.isBlank() || name.length > 80) throw AsrException(AsrError.Protocol)
            seen += id
            val local = mapPerson(c, id, name) ?: continue
            dao.person(local.copy(samples = p.optInt("samples").coerceAtLeast(0), seconds = p.optDouble("seconds", 0.0).toInt().coerceAtLeast(0), ready = p.optBoolean("ready")))
        }
        for (p in dao.people(c.scope)) {
            if (p.remoteId != null && p.remoteId !in seen && dao.actions(c.scope).none { it.localId == p.localId }) {
                dao.person(p.copy(remoteId = null, samples = 0, seconds = 0, ready = false))
                dao.clearResults(c.scope, p.localId)
            }
        }
        client.forget(key); lastPeopleRefresh = System.currentTimeMillis()
    }

    suspend fun queue(id: Long, kind: String, value: String? = null, pcm: ShortArray? = null, lineId: Long? = null) {
        val c = connection.value?.takeIf(::current) ?: throw IllegalStateException("请先配置并测试自建语音服务")
        if (kind != "delete" && dao.actions(c.scope).any { it.localId == id && it.kind == "delete" })
            throw IllegalStateException("该人物的删除尚未同步，请稍后重新录入")
        val name = value.takeIf { kind == "create" || kind == "rename" } ?: if (id == 0L) "我" else db.voiceprint().people().find { it.id == id }?.name
            ?: throw IllegalStateException("人物不存在")
        val old = dao.people(c.scope).find { it.localId == id }
        val actionId = speechId()
        var file: File? = null
        try {
            if (pcm != null) {
                require(pcm.size >= 5 * 16000) { "远端声纹需要至少5秒清晰单人人声，建议录制10至20秒" }
                file = File(audioDir, "$actionId.wav"); writeSpeechWav(file, pcm)
            }
            db.withTransaction {
                dao.person((old ?: RemoteSpeechPerson(c.scope, id, null, name)).copy(name = name, deleted = kind == "delete",
                    samples = if (kind == "delete") 0 else old?.samples ?: 0, seconds = if (kind == "delete") 0 else old?.seconds ?: 0,
                    ready = if (kind == "delete") false else old?.ready ?: false))
                dao.action(RemoteSpeechAction(actionId, c.scope, id, kind, value, file?.path, System.currentTimeMillis(),
                    lineId = lineId, durationMillis = pcm?.let { it.size / 16L }))
                if (kind == "delete") { dropSamples(c.scope, id); dao.clearExplicitResults(c.scope, id); dao.clearSamples(c.scope, id) }
            }
        } catch (e: Exception) { file?.delete(); throw e }
        _error.value = "声纹变更已保存，等待与服务端同步"
        lastPeopleRefresh = 0; schedule()
    }

    suspend fun syncPending(): Boolean = syncing.withLock {
        if (settings.source.value != TranscribeSource.Custom || !networkAllowed()) { val saved = connection.value; return@withLock saved != null && dao.actions(saved.scope).any { it.error == null } }
        val c = try { resolve() } catch (e: CancellationException) { throw e } catch (_: Exception) { return@withLock connection.value != null } ?: return@withLock false
        try {
            // 删除本地录音后取消遗留远端任务，不保存旧凭据向其他服务发送取消请求。
            for (task in dao.tasks(c.scope)) if (task.segmentId != null) {
                val segment = db.transcribe().findSegment(task.segmentId)
                if (segment == null || (segment.status in setOf("Done", "Empty", "Abandoned") && segment.remoteSpeakerScope != c.scope)) client.cancel(c, task)
                else if (segment.status in setOf("Done", "Empty") && segment.remoteSpeakerScope == c.scope) dao.consumeSegment(segment.id)
            }
            // 首次先拉取已有远端人物，避免重装/换密钥后重复创建“我”。
            if (lastPeopleRefresh == 0L) refreshPeople(c)
            for (action in dao.actions(c.scope)) {
                if (!current(c)) return@withLock true
                if (action.error != null) {
                    if (System.currentTimeMillis() - action.createdAt > 86_400_000L) action.audioPath?.let { File(it).delete() }
                    continue
                }
                val key = "${c.scope}:action:${action.id}"
                try {
                    val profile = dao.people(c.scope).find { it.localId == action.localId }
                    if (action.kind != "delete" && profile?.deleted == true) {
                        dao.task(key)?.let { client.cancel(c, it) }; action.audioPath?.let { File(it).delete() }; dao.removeAction(action.id); continue
                    }
                    when (action.kind) {
                        "create" -> ensurePerson(c, action.localId)
                        "rename" -> {
                            val person = ensurePerson(c, action.localId)
                            client.operation(c, key, "PATCH", "/people/${person.remoteId}", { SpeechServiceClient.jsonBody(JSONObject().put("name", action.value)) }, valid = { current(c) })
                        }
                        "delete" -> {
                            profile?.remoteId?.let { remote -> client.operation(c, key, "DELETE", "/people/$remote", { null }, valid = { current(c) }) }
                            profile?.let { dao.person(it.copy(remoteId = null, samples = 0, seconds = 0, ready = false, deleted = true)) }
                            for (old in dao.actions(c.scope).filter { it.localId == action.localId && it.id != action.id }) {
                                old.audioPath?.let { File(it).delete() }; dao.removeAction(old.id)
                            }
                            dropSamples(c.scope, action.localId); dao.clearSamples(c.scope, action.localId)
                        }
                        "sample-delete" -> {
                            val sample = requireNotNull(action.value)
                            profile?.remoteId?.let { remote -> client.operation(c, key, "DELETE", "/people/$remote/samples/$sample", { null }, valid = { current(c) }) }
                            dao.removeSample(sample); sampleAudio.delete("remote-$sample")
                        }
                        "enroll" -> {
                            val person = ensurePerson(c, action.localId)
                            val file = File(requireNotNull(action.audioPath))
                            if (!file.isFile) throw AsrException(AsrError.Protocol, detail = "声纹录音已不存在，请重新录制")
                            val result = client.operation(c, key, "POST", "/people/${person.remoteId}/samples", {
                                MultipartBody.Builder().setType(MultipartBody.FORM).addFormDataPart("file", file.name, file.asRequestBody("audio/wav".toMediaType())).build()
                            }, valid = { current(c) })
                            // 样本 id 即服务端任务 id；本地记下来源原话，人物页才能列出“哪句话”并单独删除。
                            val sampleId = result.optString("id")
                            if (validSpeechId(sampleId)) dao.sample(RemoteSpeechSample(sampleId, c.scope, action.localId, action.lineId,
                                (result.optDouble("duration", 0.0) * 1000).toLong().takeIf { it > 0 } ?: action.durationMillis ?: 0L, action.createdAt))
                                .also { sampleAudio.adopt("remote-$sampleId", file) }
                        }
                    }
                    client.forget(key); action.audioPath?.let { File(it).delete() }; dao.removeAction(action.id)
                    _error.value = null; lastPeopleRefresh = 0
                } catch (e: AsrException) {
                    _error.value = e.detail
                    if (e.type.retryable || e.type == AsrError.Auth) return@withLock e.type.retryable
                    dao.action(action.copy(error = e.detail))
                }
            }
            if (System.currentTimeMillis() - lastPeopleRefresh > 30_000) refreshPeople(c)
            return@withLock dao.actions(c.scope).any { it.error == null }
        } catch (e: CancellationException) { throw e }
        catch (e: AsrException) { _error.value = e.detail; e.type.retryable }
        catch (_: Exception) { _error.value = "声纹同步失败，稍后重试"; true }
    }
    /** 拉取此人在服务端的样本，补齐本地索引；服务端已没有的删掉。未连接或无网时静默跳过。 */
    suspend fun refreshSamples(localId: Long) = syncing.withLock {
        val c = connection.value?.takeIf(::current) ?: return@withLock
        if (!networkAllowed()) return@withLock
        val remoteId = dao.people(c.scope).find { it.localId == localId && !it.deleted }?.remoteId ?: return@withLock
        val key = "${c.scope}:samples:$remoteId"
        val result = client.operation(c, key, "GET", "/people/$remoteId/samples", { null }, valid = { current(c) })
        val array = result.optJSONArray("samples") ?: throw AsrException(AsrError.Protocol)
        val deleting = dao.actions(c.scope).filter { it.kind == "sample-delete" }.mapNotNull { it.value }.toSet()
        db.withTransaction {
            val known = dao.samples(c.scope, localId).associateBy { it.id }
            val seen = mutableSetOf<String>()
            for (i in 0 until array.length()) {
                val p = array.getJSONObject(i); val id = p.optString("id")
                if (!validSpeechId(id) || id in deleting) continue
                seen += id
                val millis = (p.optDouble("duration", 0.0) * 1000).toLong().coerceAtLeast(0)
                dao.sample(known[id]?.copy(durationMillis = millis) ?: RemoteSpeechSample(id, c.scope, localId, null, millis, 0L))
            }
            known.keys.filter { it !in seen && it !in deleting }.forEach { dao.removeSample(it); sampleAudio.delete("remote-$it") }
        }
        client.forget(key)
    }
    private suspend fun dropSamples(scope: String, id: Long) = dao.samples(scope, id).forEach { sampleAudio.delete("remote-${it.id}") }
    /** 还没上传的样本直接撤销 */
    suspend fun cancelAction(id: String) = syncing.withLock {
        val action = dao.findAction(id) ?: return@withLock
        connection.value?.takeIf { it.scope == action.scope }?.let { c ->
            dao.task("${c.scope}:action:$id")?.let { runCatching { client.cancel(c, it) } }
        }
        action.audioPath?.let { File(it).delete() }; dao.removeAction(id)
    }
    suspend fun isReady(local: suspend () -> Boolean): Boolean = connection.value?.takeIf(::current)?.let { c -> dao.people(c.scope).any { it.ready && !it.deleted } } ?: local()
    fun report(message: String) { _error.value = message }
}

internal fun writeSpeechWav(file: File, pcm: ShortArray) {
    val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
    h.put("RIFF".toByteArray()).putInt(36 + pcm.size * 2).put("WAVEfmt ".toByteArray()).putInt(16)
        .putShort(1).putShort(1).putInt(16000).putInt(32000).putShort(2).putShort(16).put("data".toByteArray()).putInt(pcm.size * 2)
    file.outputStream().use { out -> out.write(h.array()); val buffer = ByteBuffer.allocate(8192).order(ByteOrder.LITTLE_ENDIAN)
        for (sample in pcm) { if (buffer.remaining() < 2) { out.write(buffer.array(), 0, buffer.position()); buffer.clear() }; buffer.putShort(sample) }
        out.write(buffer.array(), 0, buffer.position())
    }
}
