package app.resound.voiceprint

import androidx.room.withTransaction
import app.resound.data.*
import app.resound.transcribe.TranscribePolicy
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/** gate 只保护快照和提交；耗时解码/推理在锁外，纠正通过版本号使旧轮次失效。 */
class LocalVoiceprintRepository(
    private val db: CaptureDatabase,
    private val scope: CoroutineScope,
    private val readPcm: suspend (File) -> ShortArray,
    private val createEngine: () -> SpeakerEmbedding,
    private val schedule: () -> Unit,
    private val now: () -> Long = System::currentTimeMillis,
) : VoiceprintRepository {
    private val dao = db.voiceprint()
    private val gate = Mutex()
    private val actions = Mutex()
    private val rounds = Mutex()
    private var revision = 0L
    private var resetGeneration = 0L
    private val reading = mutableMapOf<Long, Int>()
    private val cache by lazy {
        val path = db.openHelper.writableDatabase.path
        VoiceprintFeatureCache(path?.takeUnless { it.isBlank() || it == ":memory:" }
            ?.let { File("$it.voiceprint-features") })
    }
    private val error = MutableStateFlow<String?>(null)
    override val state: StateFlow<VoiceprintState> = combine(dao.observeSamples(), db.transcribe().observeSegments(), error) { samples, segments, lastError ->
        VoiceprintState(samples.size, (samples.sumOf { it.durationMillis } / 1000).toInt(), ready(samples),
            segments.count { it.status == "Done" && it.voiceprintProcessedAt != null },
            segments.count { it.status == "Done" && it.voiceprintProcessedAt == null }, lastError)
    }.stateIn(scope, SharingStarted.Eagerly, VoiceprintState(0, 0, false, 0, 0, null))

    override val people = combine(dao.observePeople(), dao.observeAllSamples()) { people, samples ->
        (listOf(VoiceprintPersonEntity(0, "我", 0)) + people).map { person ->
            val own = samples.filter { it.personId == person.id }
            VoiceprintPerson(person.id, person.name, own.size, (own.sumOf { it.durationMillis } / 1000).toInt(), ready(own))
        }
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())
    override suspend fun addPerson(name: String): Result<Long> = withContext(Dispatchers.IO) {
        try { Result.success(actions.withLock { gate.withLock {
            val value = validName(name)
            dao.insertPerson(VoiceprintPersonEntity(name = value, createdAt = now())).also { revision++ }
        } }) } catch (e: CancellationException) { throw e }
        catch (_: Exception) { Result.failure(IllegalArgumentException("请使用不重复的 1–30 字人物名")) }
    }
    private fun validName(name: String): String = name.trim().also {
        require(it.isNotBlank() && it.length <= 30 && it != "我") { "请填写 1–30 字且不重复的人物名" }
    }
    override fun renamePerson(id: Long, name: String) = launchAction {
        require(id != 0L)
        gate.withLock {
            val value = validName(name)
            db.withTransaction { dao.renamePerson(id, value); dao.renameResults(id, value) }
            revision++
        }
    }
    override fun deletePerson(id: Long) = launchAction {
        require(id != 0L)
        gate.withLock {
            db.withTransaction { dao.clearPersonResults(id); dao.clearPersonSamples(id); dao.deletePerson(id); dao.invalidate() }
            revision++
        }
    }
    override fun clearIdentity(lineId: Long) = launchAction {
        gate.withLock {
            val line = requireNotNull(dao.line(lineId))
            val key = VoiceprintAudio.groupKey(line)
            val ids = dao.lines(line.segmentId).filter { VoiceprintAudio.groupKey(it) == key }.map { it.id }
            db.withTransaction { dao.removeSample(line.segmentId, key); dao.identity(ids, null, null, null, true); dao.invalidate() }
            revision++
        }
    }
    suspend fun isReady(): Boolean = dao.allSamples().groupBy { it.personId }.values.any(::ready)
    private fun ready(samples: List<VoiceprintSampleEntity>) = samples.size >= 3 && samples.sumOf { it.durationMillis } >= 30_000
    private fun representative(samples: List<VoiceprintSampleEntity>) =
        VoiceprintMath.representative(samples.map { VoiceprintMath.decode(it.vector) })
    private fun pin(id: Long) { reading[id] = (reading[id] ?: 0) + 1 }
    private suspend fun unpin(id: Long) = withContext(NonCancellable) {
        gate.withLock { if (reading.getValue(id) == 1) reading.remove(id) else reading[id] = reading.getValue(id) - 1 }
    }
    private suspend fun <T> engine(block: suspend (SpeakerEmbedding) -> T): T {
        val model = createEngine()
        try { return block(model) } finally { (model as? AutoCloseable)?.close() }
    }
    private fun launchAction(block: suspend () -> Unit) {
        scope.launch(Dispatchers.IO) {
            try { actions.withLock { error.value = null; block() }; schedule() }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { error.value = "声纹操作未完成：请检查名字是否重复、录音是否可用后重试" }
        }
    }
    private data class Group(
        val segment: SegmentEntity, val key: String, val lines: List<TranscriptLineEntity>,
        val cacheKey: String, val feature: VoiceprintFeatureCache.Feature?,
    )
    private suspend fun group(lineId: Long): Group {
        val line = requireNotNull(dao.line(lineId))
        val segment = db.transcribe().segment(line.segmentId)
        val key = VoiceprintAudio.groupKey(line)
        val lines = dao.lines(segment.id).filter { VoiceprintAudio.groupKey(it) == key }
        val cacheKey = cache.key(segment, lines)
        val sample = dao.allSamples().firstOrNull { it.segmentId == segment.id && it.groupKey == key }
        val feature = sample?.let { VoiceprintFeatureCache.Feature(VoiceprintMath.decode(it.vector), it.durationMillis) }
            ?: cache.read(cacheKey)
        return Group(segment, key, lines, cacheKey, feature)
    }
    override fun markMe(lineId: Long) = assignPerson(lineId, 0)
    override fun assignPerson(lineId: Long, personId: Long) = launchAction { assign(lineId, personId) }
    override suspend fun nameAndAssign(lineId: Long, name: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            actions.withLock {
                error.value = null
                val id = gate.withLock {
                    if (name.trim() == "我") 0L else {
                        val value = validName(name)
                        dao.people().find { it.name == value }?.id
                            ?: dao.insertPerson(VoiceprintPersonEntity(name = value, createdAt = now())).also { revision++ }
                    }
                }
                assign(lineId, id)
            }
            schedule()
            Result.success(Unit)
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { Result.failure(IllegalArgumentException("保存未完成，请检查名字和原话后重试")) }
    }
    private suspend fun assign(lineId: Long, personId: Long) {
        val personName = if (personId == 0L) "我" else requireNotNull(dao.people().find { it.id == personId }).name
        val group = gate.withLock { group(lineId).also { pin(it.segment.id) } }
        try {
            val feature = group.feature ?: if (!File(group.segment.path).isFile) null else run {
                val pcm = VoiceprintAudio.group(readPcm(File(group.segment.path)), group.segment.startAt, group.lines)
                if (pcm.size < VoiceprintAudio.MIN_SAMPLES) null else
                    VoiceprintFeatureCache.Feature(engine { VoiceprintMath.unit(it.compute(pcm)) }, pcm.size / 16L)
            }
            val vector = feature?.vector
            gate.withLock {
                // 转写若被替换，不能将旧向量写给新句子。
                require(group.cacheKey == group(lineId).cacheKey)
                if (feature != null) cache.write(group.cacheKey, feature)
                db.withTransaction {
                    val existing = dao.allSamples().firstOrNull { it.segmentId == group.segment.id && it.groupKey == group.key }
                    require(personId == 0L || dao.people().any { it.id == personId })
                    // 自动样本经用户确认后晋升为手动样本，保留 id，永不自动淘汰。
                    if (vector != null) dao.saveSample(existing?.copy(source = "line", sourceLineId = lineId, personId = personId)
                        ?: VoiceprintSampleEntity(source = "line", sourceLineId = lineId, segmentId = group.segment.id,
                            groupKey = group.key, vector = VoiceprintMath.encode(vector),
                            durationMillis = feature.durationMillis, createdAt = now(), personId = personId))
                    dao.unexclude(group.segment.id, group.key)
                    dao.identity(group.lines.map { it.id }, personId, personName, 1f, true)
                    dao.invalidate() // 后台复用缓存重打分；不在本次点击中遍历历史录音。
                }
                revision++
            }
        } finally { unpin(group.segment.id) }
    }
    override fun markNotMe(lineId: Long) = launchAction {
        gate.withLock {
            val line = requireNotNull(dao.line(lineId))
            val key = VoiceprintAudio.groupKey(line)
            db.withTransaction {
                dao.removeSample(line.segmentId, key)
                dao.exclude(VoiceprintExclusionEntity(line.segmentId, key, now()))
                dao.identity(dao.lines(line.segmentId).filter { VoiceprintAudio.groupKey(it) == key }.map { it.id }, null, null, null, true)
                dao.invalidate()
            }
            revision++
        }
    }
    override fun reset() = launchAction {
        gate.withLock {
            cache.clear()
            db.withTransaction { dao.clearSamples(); dao.clearExclusions(); dao.clearResults(); dao.invalidate() }
            revision++; resetGeneration++
        }
    }
    override suspend fun enrollPcm(pcm: ShortArray): Result<Unit> = enrollPerson(0, pcm)
    override suspend fun enrollPerson(personId: Long, pcm: ShortArray): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            actions.withLock {
                val speech = VoiceprintAudio.trimSilence(pcm)
                require(speech.size >= VoiceprintAudio.MIN_SAMPLES)
                val vector = engine { VoiceprintMath.unit(it.compute(speech)) }
                gate.withLock {
                    db.withTransaction {
                        require(personId == 0L || dao.people().any { it.id == personId })
                        dao.saveSample(VoiceprintSampleEntity(source = "enroll", sourceLineId = null, segmentId = null, groupKey = null,
                            vector = VoiceprintMath.encode(vector), durationMillis = speech.size / 16L, createdAt = now(), personId = personId))
                        dao.invalidate()
                    }
                    revision++
                    error.value = null
                }
            }
            schedule()
            Result.success(Unit)
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) {
            error.value = "录入失败：需要至少 1.5 秒有效人声，请重新录制"
            Result.failure(IllegalArgumentException(error.value))
        }
    }

    private data class Round(val samples: List<VoiceprintSampleEntity>, val revision: Long, val generation: Long, val people: List<VoiceprintPersonEntity>)
    private data class SegmentInput(val lines: List<TranscriptLineEntity>, val excluded: Set<String>, val groups: List<Group>)

    /** 返回 true 表示仍需重试。并发 worker 串行；每轮自动补样本只在下一轮参与平均。 */
    suspend fun run(): Boolean = withContext(Dispatchers.IO) { rounds.withLock { process() } }
    private suspend fun process(): Boolean {
        val round = gate.withLock { Round(dao.allSamples(), revision, resetGeneration, dao.people()) }
        val canIdentify = round.samples.groupBy { it.personId }.values.any(::ready)
        // 导入音频转写后就删除；即使还没有注册声纹，也先缓存特征供日后从原话补样本。
        val pending = dao.pending().filter { canIdentify || it.sourceId == "import" }
        if (pending.isEmpty()) return false
        var retry = false
        var lastError: String? = null
        var model: SpeakerEmbedding? = null
        try {
            val candidates = round.samples.groupBy { it.personId }.filterValues(::ready).mapNotNull { (id, samples) ->
                val name = if (id == 0L) "我" else round.people.find { it.id == id }?.name ?: return@mapNotNull null
                SpeakerIdentity.Candidate(id, name, representative(samples))
            }
            for (segment in pending) {
                currentCoroutineContext().ensureActive()
                val input = gate.withLock {
                    if (revision != round.revision) return true
                    val lines = dao.lines(segment.id)
                    val excluded = dao.exclusions(segment.id).toSet()
                    val samples = dao.allSamples()
                    val groups = lines.groupBy(VoiceprintAudio::groupKey).map { (key, group) ->
                        val cacheKey = cache.key(segment, group)
                        val sample = samples.firstOrNull { it.segmentId == segment.id && it.groupKey == key }
                        Group(segment, key, group, cacheKey, cache.read(cacheKey) ?: sample?.let {
                            VoiceprintFeatureCache.Feature(VoiceprintMath.decode(it.vector), it.durationMillis)
                        })
                    }
                    pin(segment.id)
                    SegmentInput(lines, excluded, groups)
                }
                try {
                    val needAudio = input.groups.any { it.feature == null }
                    val pcm = if (needAudio && File(segment.path).isFile) readPcm(File(segment.path)) else null
                    if (needAudio && pcm == null) lastError = "部分旧录音已过期，无法补算声纹；已放行整理"
                    val features = input.groups.associateWith { group ->
                        group.feature ?: if (pcm == null) null else {
                            val speech = VoiceprintAudio.group(pcm, segment.startAt, group.lines)
                            val vector = if (speech.size < VoiceprintAudio.MIN_SAMPLES) null else {
                                val activeModel = model ?: createEngine().also { model = it }
                                VoiceprintMath.unit(activeModel.compute(speech))
                            }
                            VoiceprintFeatureCache.Feature(vector, speech.size / 16L)
                        }
                    }
                    gate.withLock {
                        val currentLines = dao.lines(segment.id)
                        if (cache.key(segment, currentLines) != cache.key(segment, input.lines)) {
                            retry = true
                        } else {
                            // 人工纠正后仍可保存纯特征，避免下轮重新推理；reset 后则不得复活缓存。
                            if (resetGeneration == round.generation) for ((group, feature) in features) {
                                if (feature != null) cache.write(group.cacheKey, feature)
                            }
                            if (revision != round.revision) return true
                            db.withTransaction {
                                for ((group, feature) in features) {
                                    val match = SpeakerIdentity.match(feature?.vector?.takeUnless { group.lines.any { line -> line.speakerTag?.startsWith("uncertain:") == true } },
                                        candidates.filter { it.id != 0L || group.key !in input.excluded })
                                    // 人工指定/未知均锁定，自动结果不得覆盖。
                                    dao.identity(group.lines.map { it.id }, match.personId, match.name, match.score)
                                    // 保留原单人自学习；多人模式只用人工确认样本，防止互相污染。
                                    if (round.people.isEmpty() && match.personId == 0L && (match.score ?: 0f) >= AUTO_SCORE && feature != null &&
                                        feature.durationMillis >= AUTO_MILLIS && group.lines.none { it.speakerManual } &&
                                        dao.allSamples().none { it.segmentId == segment.id && it.groupKey == group.key }) {
                                        dao.saveSample(VoiceprintSampleEntity(source = "auto", sourceLineId = group.lines.first().id,
                                            segmentId = segment.id, groupKey = group.key, vector = VoiceprintMath.encode(requireNotNull(feature.vector)),
                                            durationMillis = feature.durationMillis, createdAt = now()))
                                    }

                                }
                                dao.trimAutoSamples()
                                dao.processed(segment.id, now())
                            }
                        }
                    }
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { retry = true; lastError = "声纹处理失败，录音已保留，稍后重试" }
                finally { unpin(segment.id) }
                yield()
            }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { retry = true; lastError = "声纹模型或样本读取失败，稍后重试" }
        finally { (model as? AutoCloseable)?.close() }
        return gate.withLock {
            // 最后一段提交后发生纠正，也要让正在运行的 worker 重试，避免 KEEP 调度吞掉本次请求。
            if (revision != round.revision) true else { error.value = lastError; retry }
        }
    }

    /** 保留整理取数/请求的互斥语义；后台推理本身不占此锁。 */
    suspend fun <T> withStableResults(block: suspend () -> T): T = gate.withLock { block() }

    /** 与快照/提交互斥；正在推理的旧轮次不能再落特征或样本。 */
    internal suspend fun <T> withPrivacyDeletion(block: suspend () -> T): T = gate.withLock {
        revision++; resetGeneration++
        block()
    }

    internal fun removeFeatures(segment: SegmentEntity, lines: List<TranscriptLineEntity>) {
        lines.groupBy(VoiceprintAudio::groupKey).values.forEach { cache.remove(cache.key(segment, it)) }
    }

    suspend fun cleanupAudio() = gate.withLock {
        val timestamp = now()
        val waitForVoiceprint = isReady()
        app.resound.transcribe.cleanupImportedAudio(db.transcribe(), timestamp, waitForVoiceprint = true, reading = reading.keys)
        for (segment in db.transcribe().expired(timestamp - TranscribePolicy.RETENTION_MILLIS)) {
            if (segment.id in reading) continue
            if (waitForVoiceprint && segment.status == "Done" && segment.voiceprintProcessedAt == null) continue
            if (TranscribePolicy.canDelete(segment.status, segment.completedAt, timestamp)) File(segment.path).delete()
        }
    }

    private companion object {
        const val AUTO_SCORE = 0.80f
        const val AUTO_MILLIS = 3_000L
    }
}
