package app.resound.remote

import androidx.room.withTransaction
import app.resound.data.CaptureDatabase
import app.resound.voiceprint.*
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** 复用现有人物界面及约定；只有确认自建协议后才路由到远端。 */
class RemoteVoiceprintRepository(
    private val db: CaptureDatabase, private val local: LocalVoiceprintRepository,
    private val remote: RemoteSpeechService, private val scope: CoroutineScope,
    private val readPcm: suspend (File) -> ShortArray,
) : VoiceprintRepository {
    private val active get() = remote.connection.value
    override val people: StateFlow<List<VoiceprintPerson>> = combine(local.people, remote.people, remote.connection) { locals, profiles, connection ->
        if (connection == null) locals else locals.map { person ->
            val match = profiles.find { it.scope == connection.scope && it.localId == person.id && !it.deleted }
            person.copy(samples = match?.samples ?: 0, seconds = match?.seconds ?: 0, ready = match?.ready ?: false)
        }
    }.stateIn(scope, SharingStarted.Eagerly, local.people.value)
    override val state: StateFlow<VoiceprintState> = combine(local.state, people, remote.connection, remote.error) { state, people, connection, error ->
        if (connection == null) state else {
            val me = people.find { it.id == 0L }
            state.copy(samples = me?.samples ?: 0, seconds = me?.seconds ?: 0, ready = me?.ready ?: false, lastError = error)
        }
    }.stateIn(scope, SharingStarted.Eagerly, local.state.value)

    private val speech get() = db.remoteSpeech()

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun samples(personId: Long): Flow<List<VoiceprintSample>> = remote.connection.flatMapLatest { c ->
        if (c == null) local.samples(personId)
        else combine(speech.observeSamples(c.scope, personId), speech.observeActions(c.scope, personId)) { saved, actions ->
            val deleting = actions.filter { it.action.kind == "sample-delete" }.mapNotNull { it.action.value }.toSet()
            actions.filter { it.action.kind == "enroll" }.map { row -> val a = row.action
                VoiceprintSample("pending:${a.id}", if (a.lineId != null) SampleOrigin.Line else SampleOrigin.Recorded, a.lineId, row.text,
                    row.lineStart ?: a.createdAt, a.durationMillis ?: 0, if (a.error != null) SampleState.Failed else SampleState.Pending,
                    a.error ?: "等待上传到语音服务", a.audioPath?.let { File(it).isFile } == true)
            } + saved.map { row -> val r = row.sample
                VoiceprintSample("remote:${r.localId}:${r.id}", if (r.lineId != null) SampleOrigin.Line else SampleOrigin.Recorded, r.lineId, row.text,
                    row.lineStart ?: r.createdAt, r.durationMillis, if (r.id in deleting) SampleState.Pending else SampleState.Active,
                    if (r.id in deleting) "正在从语音服务删除" else null,
                    remote.sampleAudio.exists("remote-${r.id}") || (r.lineId != null && row.segmentPath?.let { File(it).isFile } == true))
            }
        }.flowOn(Dispatchers.IO).onStart {
            scope.launch { try { remote.refreshSamples(personId) } catch (e: CancellationException) { throw e } catch (_: Exception) { /* 无网时显示本地索引 */ } }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun lines(personId: Long): Flow<List<PersonLine>> = remote.connection.flatMapLatest { c ->
        if (c == null) local.lines(personId)
        else combine(db.voiceprint().observePersonLines(personId), speech.observeSamples(c.scope, personId), speech.observeActions(c.scope, personId), local.lines(personId)) { lines, saved, actions, locals ->
            val sourceIds = saved.mapNotNull { it.sample.lineId }.toSet() + actions.filter { it.action.kind == "enroll" }.mapNotNull { it.action.lineId }
            // 远端样本取自整组同一说话人的句子，同组其余句子也视为已在声纹里。
            val groups = lines.filter { it.id in sourceIds }.map { it.segmentId to VoiceprintAudio.groupKey(it) }.toSet()
            val localUsed = locals.filter { it.inVoiceprint }.map { it.lineId }.toSet()
            lines.map { PersonLine(it.id, it.text, it.startAt, it.endAt, it.speakerManual,
                it.id in localUsed || (it.segmentId to VoiceprintAudio.groupKey(it)) in groups) }
        }
    }

    override suspend fun sampleAudio(key: String): File? = withContext(Dispatchers.IO) {
        when {
            key.startsWith("local:") -> local.sampleAudio(key)
            key.startsWith("pending:") -> speech.findAction(key.removePrefix("pending:"))?.audioPath?.let(::File)?.takeIf { it.isFile }
            key.startsWith("remote:") -> {
                val (person, id) = key.removePrefix("remote:").split(":").let { it[0].toLong() to it[1] }
                remote.sampleAudio.file("remote-$id") ?: active?.let { c ->
                    speech.samples(c.scope, person).find { it.id == id }?.lineId?.let { db.voiceprint().line(it) }?.let { local.clipLine(it) }
                }
            }
            else -> null
        }
    }

    override fun deleteSample(key: String) {
        when {
            key.startsWith("local:") -> local.deleteSample(key)
            key.startsWith("pending:") -> action { remote.cancelAction(key.removePrefix("pending:")) }
            key.startsWith("remote:") -> action {
                val (person, sample) = key.removePrefix("remote:").split(":").let { it[0].toLong() to it[1] }
                remote.queue(person, "sample-delete", sample)
            }
        }
    }

    /** 改标或清除时，撤掉这组句子已交给语音服务的样本，避免声音留在错的人名下。 */
    private suspend fun dropGroupSamples(lineIds: List<Long>, keep: Long?) {
        val c = active ?: return
        for (a in speech.actions(c.scope)) if (a.kind == "enroll" && a.lineId in lineIds && a.localId != keep) remote.cancelAction(a.id)
        for (s in speech.samplesFor(c.scope, lineIds)) if (s.localId != keep) {
            try { remote.queue(s.localId, "sample-delete", s.id) }
            catch (e: CancellationException) { throw e }
            catch (_: IllegalStateException) { /* 该人物正在整体删除，样本会随之清掉 */ }
        }
    }

    private fun action(block: suspend () -> Unit) { scope.launch {
        try { block() } catch (e: CancellationException) { throw e }
        catch (e: app.resound.transcribe.AsrException) { remote.report(e.detail) }
        catch (e: IllegalArgumentException) { remote.report(e.message ?: "声纹操作未完成") }
        catch (_: Exception) { remote.report("声纹操作未完成，请稍后重试") }
    } }
    override suspend fun addPerson(name: String): Result<Long> {
        val result = local.addPerson(name)
        if (active != null) result.getOrNull()?.let { id ->
            try { remote.queue(id, "create", name.trim()) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { remote.report("人物已在本地保存，请测试服务连接后录入声音") }
        }
        return result
    }
    override fun renamePerson(id: Long, name: String) {
        if (active == null) { local.renamePerson(id, name); return }
        action {
            require(id != 0L && name.trim().isNotEmpty() && name.trim().length <= 30 && name.trim() != "我") { "请填写1至30字人物名" }
            require(db.voiceprint().people().none { it.id != id && it.name == name.trim() }) { "人物名不能重复" }
            remote.queue(id, "rename", name.trim()); local.renamePerson(id, name)
        }
    }
    override fun deletePerson(id: Long) {
        if (active == null) { local.deletePerson(id); return }
        action { require(id != 0L); remote.queue(id, "delete"); local.deletePerson(id) }
    }
    override suspend fun enrollPcm(pcm: ShortArray): Result<Unit> = enrollPerson(0, pcm)
    override suspend fun enrollPerson(personId: Long, pcm: ShortArray): Result<Unit> {
        if (active == null) return local.enrollPerson(personId, pcm)
        return try { remote.queue(personId, "enroll", pcm = pcm); Result.success(Unit) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { Result.failure(IllegalArgumentException(e.message ?: "声纹录音保存失败")) }
    }
    override fun reset() {
        if (active == null) local.reset() else action { remote.queue(0, "delete") }
    }
    override fun markMe(lineId: Long) = assignPerson(lineId, 0)
    override fun assignPerson(lineId: Long, personId: Long) = action {
        val line = requireNotNull(db.voiceprint().line(lineId))
        val segment = db.transcribe().segment(line.segmentId)
        if (segment.remoteSpeakerScope == null && active == null) local.assignPerson(lineId, personId)
        else assignRemote(lineId, personId)
    }
    override suspend fun nameAndAssign(lineId: Long, name: String): Result<Unit> {
        val line = db.voiceprint().line(lineId) ?: return Result.failure(IllegalArgumentException("原话不存在"))
        if (active == null && db.transcribe().segment(line.segmentId).remoteSpeakerScope == null) return local.nameAndAssign(lineId, name)
        return try {
            val id = if (name.trim() == "我") 0L else db.voiceprint().people().find { it.name == name.trim() }?.id ?: addPerson(name).getOrThrow()
            assignRemote(lineId, id); Result.success(Unit)
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { Result.failure(IllegalArgumentException("人物标注未完成，请检查原话和声音样本")) }
    }
    private suspend fun assignRemote(lineId: Long, personId: Long) {
        val line = requireNotNull(db.voiceprint().line(lineId))
        require(line.voiceInputJson == null) { "语音输入的身份固定为我" }
        val segment = db.transcribe().segment(line.segmentId)
        val name = if (personId == 0L) "我" else requireNotNull(db.voiceprint().people().find { it.id == personId }).name
        val group = db.voiceprint().lines(line.segmentId).filter { VoiceprintAudio.groupKey(it) == VoiceprintAudio.groupKey(line) }
        db.voiceprint().identity(group.map { it.id }, personId, name, null, true)
        dropGroupSamples(group.map { it.id }, personId)
        val c = active
        if (c == null || (segment.remoteSpeakerScope != null && segment.remoteSpeakerScope != c.scope)) {
            remote.report("已保存本地标注；当前服务与这段录音不同，未上传声音")
            return
        }
        if (!File(segment.path).isFile) { remote.report("已保存人物标注；原录音已清理，请另录声音样本"); return }
        val c2 = active ?: return
        // 同组已有样本（同一个人）就不重复上传。
        if (speech.samplesFor(c2.scope, group.map { it.id }).any { it.localId == personId } ||
            speech.actions(c2.scope).any { it.kind == "enroll" && it.localId == personId && it.lineId in group.map { g -> g.id } }) return
        val pcm = local.withAudioCleanup { readPcm(File(segment.path)) }
        val audio = remoteSampleAudio(pcm, segment.startAt, line, group)
        if (audio.size < 5 * 16000) { remote.report("已保存人物标注；这个人在这段录音里有效说话不足5秒，暂未加入声纹"); return }
        remote.queue(personId, "enroll", pcm = audio, lineId = lineId)
    }
    override fun clearIdentity(lineId: Long) = clear(lineId, false)
    override fun markNotMe(lineId: Long) = clear(lineId, true)
    private fun clear(lineId: Long, notMe: Boolean) = action {
        val line = requireNotNull(db.voiceprint().line(lineId))
        if (db.transcribe().segment(line.segmentId).remoteSpeakerScope == null) {
            if (notMe) local.markNotMe(lineId) else local.clearIdentity(lineId)
        } else {
            require(line.voiceInputJson == null)
            val ids = db.voiceprint().lines(line.segmentId).filter { VoiceprintAudio.groupKey(it) == VoiceprintAudio.groupKey(line) }.map { it.id }
            db.voiceprint().identity(ids, null, null, null, true)
            dropGroupSamples(ids, null)
            remote.report("已清除本地身份标注；不会把未确认的声音加入服务端样本")
        }
    }
}

/** 远端样本：先放所点那句，再按时间远近补同组句子，去静音后最多 30 秒（服务端要求≥5秒单人人声）。 */
internal fun remoteSampleAudio(pcm: ShortArray, segmentStart: Long, clicked: app.resound.data.TranscriptLineEntity,
                               group: List<app.resound.data.TranscriptLineEntity>, maxSamples: Int = 30 * 16000): ShortArray {
    val ordered = listOf(clicked) + group.filter { it.id != clicked.id }.sortedBy { kotlin.math.abs(it.startAt - clicked.startAt) }
    val out = ArrayList<ShortArray>(); var size = 0
    for (line in ordered) {
        if (size >= maxSamples) break
        val part = VoiceprintAudio.group(pcm, segmentStart, listOf(line))
        out += part; size += part.size
    }
    val merged = ShortArray(minOf(size, maxSamples)); var offset = 0
    for (part in out) { val n = minOf(part.size, merged.size - offset); part.copyInto(merged, offset, 0, n); offset += n; if (offset >= merged.size) break }
    return merged
}
