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
            db.withTransaction { dao.clearPersonResults(id); dao.clearPersonSamples(id); dao.deletePerson(id); invalidateRetained() }
            revision++
        }
    }
    override fun clearIdentity(lineId: Long) = launchAction {
        gate.withLock {
            val line = requireNotNull(dao.line(lineId))
            require(line.voiceInputJson == null) { "语音输入的身份固定为我，不参与声纹" }
            val key = VoiceprintAudio.groupKey(line)
            val ids = dao.lines(line.segmentId).filter { VoiceprintAudio.groupKey(it) == key }.map { it.id }
            db.withTransaction { dao.removeSample(line.segmentId, key); dao.identity(ids, null, null, null, true); invalidateRetained() }
            revision++
        }
    }
    /** 转写前只做“我”的保守保护，不写声纹样本，不依赖尚未产生的转写句。 */
    suspend fun recognizesMeBeforeTranscribe(pcm: ShortArray): Boolean = rounds.withLock {
        val samples = gate.withLock { dao.allSamples().filter { it.personId == 0L } }
        if (!ready(samples)) return@withLock false
        val reference = representative(samples)
        engine { embedding ->
            // 遍历短窗，避免整段多人混合把本人稀释掉；不确定时由调用者整段保留。
            (pcm.indices step (5 * 16000)).any { start ->
                val chunk = VoiceprintAudio.trimSilence(pcm.copyOfRange(start, minOf(pcm.size, start + 10 * 16000)))
                chunk.size >= VoiceprintAudio.MIN_SAMPLES && VoiceprintMath.cosine(embedding.compute(chunk), reference) >= VoiceprintThresholds.SURE
            }
        }
    }
    suspend fun isReady(): Boolean = dao.allSamples().groupBy { it.personId }.values.any(::ready)
    private fun ready(samples: List<VoiceprintSampleEntity>): Boolean {
        val trusted = WindowedVoiceprint.trusted(samples)
        return trusted.size >= 3 && trusted.sumOf { it.durationMillis } >= 30_000
    }
    private fun representative(samples: List<VoiceprintSampleEntity>) =
        VoiceprintMath.representative(WindowedVoiceprint.trusted(samples).map { VoiceprintMath.decode(it.vector) })
    private fun pin(id: Long) { reading[id] = (reading[id] ?: 0) + 1 }
    private suspend fun unpin(id: Long) = withContext(NonCancellable) {
        gate.withLock { if (reading.getValue(id) == 1) reading.remove(id) else reading[id] = reading.getValue(id) - 1 }
    }
    private suspend fun <T> engine(block: suspend (SpeakerEmbedding) -> T): T {
        val model = createEngine()
        try { return block(model) } finally { (model as? AutoCloseable)?.close() }
    }
    /**
     * 声纹变了只需重配录音还在的段：录音已删的段 process() 也只是原样标记完成，
     * 全部置回待处理只会让进度显示成“全量重配”，并让清理录音、整理白白多等一轮。
     */
    private suspend fun invalidateRetained() {
        dao.doneSegments().filter { File(it.path).isFile }
            .map { it.id }.chunked(500).forEach { dao.invalidateSegments(it) }
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
        require(line.voiceInputJson == null) { "语音输入的身份固定为我，不参与声纹" }
        val segment = db.transcribe().segment(line.segmentId)
        val key = VoiceprintAudio.groupKey(line)
        val allLines = dao.lines(segment.id)
        val lines = allLines.filter { VoiceprintAudio.groupKey(it) == key }
        val cacheKey = cache.key(segment, lines)
        val sample = dao.allSamples().firstOrNull { it.segmentId == segment.id && it.groupKey == key }
        val feature = sample?.let { VoiceprintFeatureCache.Feature(VoiceprintMath.decode(it.vector), it.durationMillis) }
            ?: cache.read(cacheKey)
            ?: WindowedVoiceprint.windows(segment.startAt, segment.endAt, listOf(line)).mapNotNull {
                cache.read(cache.windowKey(segment, allLines, it))?.takeIf { feature -> feature.vector != null }
            }.firstOrNull()
        return Group(segment, key, lines, cacheKey, feature)
    }
    override fun markMe(lineId: Long) = assignPerson(lineId, 0)
    override fun assignPerson(lineId: Long, personId: Long) = launchAction { assign(lineId, personId) }
    override suspend fun nameAndAssign(lineId: Long, name: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            actions.withLock {
                require(requireNotNull(dao.line(lineId)).voiceInputJson == null) { "语音输入不参与声纹" }
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
            val feature = if (!File(group.segment.path).isFile) group.feature else {
                // 用户确认仍按约定锁定整组；补样本只使用所点句子的连续窗口。
                val clicked = group.lines.first { it.id == lineId }
                val pcm = readPcm(File(group.segment.path))
                val slices = WindowedVoiceprint.windows(group.segment.startAt, group.segment.endAt, listOf(clicked))
                    .mapNotNull { window ->
                        val from = ((window.start - group.segment.startAt) * 16).coerceIn(0, pcm.size.toLong()).toInt()
                        val to = ((window.end - group.segment.startAt) * 16).coerceIn(from.toLong(), pcm.size.toLong()).toInt()
                        VoiceprintAudio.trimSilence(pcm.copyOfRange(from, to)).takeIf { it.size >= VoiceprintAudio.MIN_SAMPLES }
                    }
                if (slices.isEmpty()) null else engine { model ->
                    val vectors = slices.map { VoiceprintMath.unit(model.compute(it)) }
                    val coherent = vectors.indices.all { i -> (0 until i).all { j ->
                        VoiceprintMath.cosine(vectors[i], vectors[j]) >= 0.65f
                    } }
                    if (!coherent) null
                    else VoiceprintFeatureCache.Feature(vectors.first(), slices.first().size / 16L)
                }
            }
            val vector = feature?.vector
            gate.withLock {
                // 转写若被替换，不能将旧向量写给新句子。
                require(group.cacheKey == group(lineId).cacheKey)
                db.withTransaction {
                    val existing = dao.allSamples().firstOrNull { it.segmentId == group.segment.id && it.groupKey == group.key }
                    require(personId == 0L || dao.people().any { it.id == personId })
                    // 自动样本经用户确认后晋升为手动样本，保留 id，永不自动淘汰。
                    if (vector != null) dao.saveSample(existing?.copy(source = "line", sourceLineId = lineId, personId = personId,
                        vector = VoiceprintMath.encode(vector), durationMillis = feature.durationMillis)
                        ?: VoiceprintSampleEntity(source = "line", sourceLineId = lineId, segmentId = group.segment.id,
                            groupKey = group.key, vector = VoiceprintMath.encode(vector),
                            durationMillis = feature.durationMillis, createdAt = now(), personId = personId))
                    // 明确重标但无法提取一致声音时，保留人工记录并隔离旧向量。
                    if (vector == null && existing != null && existing.source != "auto")
                        dao.saveSample(existing.copy(source = "line-review", sourceLineId = lineId, personId = personId))
                    dao.unexclude(group.segment.id, group.key)
                    dao.identity(group.lines.map { it.id }, personId, personName, 1f, true)
                    invalidateRetained() // 后台复用缓存重打分；只重配录音还在的段，不在本次点击中遍历历史录音。
                }
                revision++
            }
        } finally { unpin(group.segment.id) }
    }
    override fun markNotMe(lineId: Long) = launchAction {
        gate.withLock {
            val line = requireNotNull(dao.line(lineId))
            require(line.voiceInputJson == null) { "语音输入的身份固定为我，不参与声纹" }
            val key = VoiceprintAudio.groupKey(line)
            db.withTransaction {
                dao.removeSample(line.segmentId, key)
                dao.exclude(VoiceprintExclusionEntity(line.segmentId, key, now()))
                dao.identity(dao.lines(line.segmentId).filter { VoiceprintAudio.groupKey(it) == key }.map { it.id }, null, null, null, true)
                invalidateRetained()
            }
            revision++
        }
    }
    override fun reset() = launchAction {
        gate.withLock {
            cache.clear()
            db.withTransaction { dao.clearSamples(); dao.clearExclusions(); dao.clearResults(); invalidateRetained() }
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
                        invalidateRetained()
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

    private data class Round(val samples: List<VoiceprintSampleEntity>, val revision: Long, val generation: Long, val people: List<VoiceprintPersonEntity>, val inputs: Map<Long, String?>)
    private data class SegmentInput(val lines: List<TranscriptLineEntity>, val excluded: Set<String>, val groups: List<Group>)

    /** 返回 true 表示仍需重试。并发 worker 串行；每轮自动补样本只在下一轮参与平均。 */
    suspend fun run(onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }): Boolean =
        withContext(Dispatchers.IO) { rounds.withLock { process(onProgress = onProgress) } }
    data class SampleHealth(val sampleId: Long, val automatic: Boolean, val outlier: Boolean)

    /** 只返回样本状态，不返回声音/向量；供后续界面展示人工样本离群提示。 */
    suspend fun sampleHealth(): List<SampleHealth> = gate.withLock {
        dao.allSamples().groupBy { it.personId }.values.flatMap { samples ->
            val manual = WindowedVoiceprint.trusted(samples)
            val center = if (manual.isEmpty()) null else representative(manual)
            samples.map { sample ->
                val score = center?.let { runCatching { VoiceprintMath.cosine(VoiceprintMath.decode(sample.vector), it) }.getOrDefault(0f) }
                SampleHealth(sample.id, sample.source == "auto", sample.source == "line-review" || score == null || score < WindowedVoiceprint.OUTLIER)
            }
        }
    }

    /** 仅本地声纹重算；不调用转写/整理调度，不改变已过期录音的结果。 */
    suspend fun recomputeRecent(days: Int): Boolean = withContext(Dispatchers.IO) {
        require(days in 1..30)
        rounds.withLock {
            val since = now() - days * 86_400_000L
            val retained = db.transcribe().observeSegments().first().filter {
                it.status == "Done" && it.startAt >= since && File(it.path).isFile
            }
            process(retained)
        }
    }

    internal suspend fun recognizeRedo(ids: List<Long>): Boolean = withContext(Dispatchers.IO) {
        rounds.withLock { process(db.transcribe().exportSegments(ids).map { it.segment }, train = false) }
    }
    private suspend fun process(selected: List<SegmentEntity>? = null, train: Boolean = true, onProgress: (Int, Int) -> Unit = { _, _ -> }): Boolean {
        val round = gate.withLock { Round(dao.allSamples(), revision, resetGeneration, dao.people(), dao.sampleInputs().associate { it.sampleId to it.sourceId }) }
        val canIdentify = round.samples.groupBy { it.personId }.values.any(::ready)
        // 导入音频转写后就删除；即使还没有注册声纹，也先缓存特征供日后从原话补样本。
        val pending = (selected ?: dao.pending()).filter { canIdentify || it.sourceId == "import" }
        if (pending.isEmpty()) return false
        var retry = false
        var lastError: String? = null
        var model: SpeakerEmbedding? = null
        try {
            for ((index, segment) in pending.withIndex()) {
                currentCoroutineContext().ensureActive()
                onProgress(index, pending.size)
                val input = gate.withLock {
                    if (revision != round.revision) return true
                    val lines = dao.lines(segment.id)
                    val excluded = dao.exclusions(segment.id).toSet()
                    val groups = lines.groupBy(VoiceprintAudio::groupKey).map { (key, group) ->
                        val cacheKey = cache.key(segment, group)
                        Group(segment, key, group, cacheKey, null)
                    }
                    pin(segment.id)
                    SegmentInput(lines, excluded, groups)
                }
                try {
                    // 同段样本不参与自己判定；自动样本不进中心。
                    val candidates = round.samples.filter { it.segmentId != segment.id }.groupBy { it.personId }
                        .mapValues { (id, samples) -> if (id == 0L)
                            WindowedVoiceprint.forInput(samples, segment.sourceId, round.inputs) else samples }
                        .filterValues(::ready).mapNotNull { (id, samples) ->
                            val name = if (id == 0L) "我" else round.people.find { it.id == id }?.name ?: return@mapNotNull null
                            SpeakerIdentity.Candidate(id, name, representative(samples),
                                WindowedVoiceprint.trusted(samples).map { VoiceprintMath.decode(it.vector) }, WindowedVoiceprint.SURE)
                        }
                    // 音频过期时只保留结果，不用旧混合向量冒充窗口向量。
                    if (!File(segment.path).isFile) {
                        gate.withLock {
                            if (revision != round.revision) return true
                            dao.processed(segment.id, now())
                        }
                        continue
                    }
                    val layout = input.groups.associateWith { group ->
                        WindowedVoiceprint.windows(segment.startAt, segment.endAt, group.lines)
                    }
                    val saved = gate.withLock { layout.values.flatten().distinct().associateWith { window ->
                        cache.read(cache.windowKey(segment, input.lines, window))
                    } }
                    val pcm = if (saved.values.any { it == null }) readPcm(File(segment.path)) else null
                    val features = layout.mapValues { (_, windows) -> windows.map { window ->
                        val feature = saved[window] ?: run {
                            val audio = requireNotNull(pcm)
                            val from = ((window.start - segment.startAt) * 16).coerceIn(0, audio.size.toLong()).toInt()
                            val to = ((window.end - segment.startAt) * 16).coerceIn(from.toLong(), audio.size.toLong()).toInt()
                            val speech = VoiceprintAudio.trimSilence(audio.copyOfRange(from, to))
                            val vector = if (speech.size < VoiceprintAudio.MIN_SAMPLES) null else {
                                val activeModel = model ?: createEngine().also { model = it }
                                VoiceprintMath.unit(activeModel.compute(speech))
                            }
                            VoiceprintFeatureCache.Feature(vector, speech.size / 16L)
                        }
                        WindowedVoiceprint.Observation(window, feature)
                    } }
                    gate.withLock {
                        val currentLines = dao.lines(segment.id)
                        if (cache.key(segment, currentLines) != cache.key(segment, input.lines)) {
                            retry = true
                        } else {
                            // 人工纠正后仍可保存纯特征，避免下轮重新推理；reset 后则不得复活缓存。
                            if (resetGeneration == round.generation) for (observations in features.values) {
                                for ((window, feature) in observations) if (feature != null)
                                    cache.write(cache.windowKey(segment, input.lines, window), feature)
                            }
                            if (revision != round.revision) return true
                            db.withTransaction {
                                // 清理旧自动样本的离群/超长向量；用户手动样本绝不删除。
                                val me = candidates.find { it.id == 0L }
                                if (train && me != null) for (sample in dao.allSamples().filter { it.source == "auto" }) {
                                    val score = runCatching { VoiceprintMath.cosine(VoiceprintMath.decode(sample.vector), me.vector) }.getOrDefault(0f)
                                    if (sample.durationMillis > WindowedVoiceprint.WINDOW_MILLIS || score < WindowedVoiceprint.AUTO_SCORE) {
                                        sample.segmentId?.let { id -> sample.groupKey?.let { key -> dao.removeSample(id, key) } }
                                    }
                                }
                                for ((group, observations) in features) {
                                    val allowed = candidates.filter { it.id != 0L || group.key !in input.excluded }
                                    for (line in group.lines) {
                                        val match = if (line.speakerTag?.startsWith("uncertain:") == true)
                                            SpeakerIdentity.Match(null, null, null)
                                        else WindowedVoiceprint.vote(line, observations, allowed)
                                        dao.identity(listOf(line.id), match.personId, match.name, match.score)
                                    }
                                    val auto = if (round.people.isEmpty() && segment.playbackMillis == 0L &&
                                        group.lines.none { it.speakerManual || it.speakerTag?.startsWith("uncertain:") == true })
                                        WindowedVoiceprint.autoSample(observations, allowed) else null
                                    if (train && auto != null && dao.allSamples().none { it.segmentId == segment.id && it.groupKey == group.key }) {
                                        dao.saveSample(VoiceprintSampleEntity(source = "auto", sourceLineId = group.lines.first().id,
                                            segmentId = segment.id, groupKey = group.key, vector = VoiceprintMath.encode(requireNotNull(auto.vector)),
                                            durationMillis = auto.durationMillis, createdAt = now()))
                                    }
                                }
                                if (train) dao.trimAutoSamples()
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
        lines.groupBy(VoiceprintAudio::groupKey).values.forEach { group ->
            cache.remove(cache.key(segment, group))
            WindowedVoiceprint.windows(segment.startAt, segment.endAt, group).forEach {
                cache.remove(cache.windowKey(segment, lines, it))
            }
        }
    }

    internal var cleanupOriginalAudio: (suspend () -> Unit)? = null
    internal suspend fun <T> withAudioCleanup(block: suspend (Set<Long>) -> T): T = gate.withLock { block(reading.keys.toSet()) }

    suspend fun cleanupAudio() {
        gate.withLock {
            app.resound.transcribe.cleanupImportedAudio(db.transcribe(), now(), waitForVoiceprint = true, reading = reading.keys)
        }
        cleanupOriginalAudio?.invoke()
    }

}
