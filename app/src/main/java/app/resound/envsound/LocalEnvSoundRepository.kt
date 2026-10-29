package app.resound.envsound

import android.content.Context
import android.content.SharedPreferences
import androidx.room.withTransaction
import app.resound.data.*
import app.resound.transcribe.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONObject
import java.io.File
import java.time.*

class LocalEnvSoundRepository(
    private val db: CaptureDatabase,
    private val prefs: SharedPreferences,
    private val scope: CoroutineScope,
    private val modelState: StateFlow<EnvModelState>,
    private val download: suspend () -> Unit,
    private val analyze: suspend (File) -> Pair<Map<String, Float>, Boolean>,
    private val schedule: () -> Unit,
    private val log: (String) -> Unit = {},
    private val now: () -> Long = System::currentTimeMillis,
) : EnvSoundRepository {
    private val dao = db.envSound()
    override val settings = MutableStateFlow(EnvSoundSettings(prefs.getBoolean("enabled", false),
        enumValue(prefs.getString("skip", null), SkipMode.MarkOnly), enumValue(prefs.getString("media", null), MediaMode.Keep),
        prefs.getBoolean("tags",true), prefs.getBoolean("specialSounds",true), prefs.getBoolean("showInSummaries",true)))
    override val model = modelState
    private val rows = dao.observe().stateIn(scope, SharingStarted.Eagerly, emptyList())
    internal val eventMetadata = dao.events().stateIn(scope, SharingStarted.Eagerly, emptyList())
    internal val presentation = combine(dao.events(), dao.observe()) { events, rows -> mergeSounds(events, rows) }.stateIn(scope, SharingStarted.Eagerly, emptyMap())
    @Synchronized override fun update(settings: EnvSoundSettings) {
        val edit = prefs.edit()
        if(settings.enabled && !this.settings.value.enabled) edit.putLong("enabledAt", now())
        check(edit.putBoolean("enabled",settings.enabled).putString("skip",settings.skip.name).putString("media",settings.media.name)
            .putBoolean("tags",settings.tags).putBoolean("specialSounds",settings.specialSounds).putBoolean("showInSummaries",settings.showInSummaries).commit())
        this.settings.value = settings
        if(settings.enabled) downloadModel()
    }
    private var downloading: Job? = null
    @Synchronized override fun downloadModel() {
        if(!settings.value.enabled || downloading?.isActive == true) return
        downloading = scope.launch { download() }
    }
    /** 在 TranscribeRunner 的串行锁内执行，结果落库后才允许改变转写状态。 */
    suspend fun beforeTranscribe(segment: SegmentEntity): Boolean {
        val existing = dao.get(segment.id)
        if(existing != null) return existing.skipped && !existing.restored && !existing.reportedWrong
        val config = settings.value
        if(!config.enabled || segment.startAt < prefs.getLong("enabledAt", Long.MAX_VALUE) || segment.attempts > 0) return false
        if(model.value != EnvModelState.Ready) { log("环境音 未就绪，照常转写"); return false }
        val start = now()
        try {
            val (scores, me) = analyze(File(segment.path))
            // 用户推理中关闭总开关或重新启用，丢弃这次结果；子开关以落库时的值为准。
            val latest = settings.value
            if(!latest.enabled || segment.startAt < prefs.getLong("enabledAt",Long.MAX_VALUE)) return false
            val decision = EnvSoundRules.decide(scores, latest, me)
            val elapsed = (now()-start).coerceAtLeast(0)
            val row = EnvSoundRow(segment.id,segment.startAt,segment.endAt,segment.path,decision.verdict.name,decision.reason,
                decision.tags.encoded(),decision.sounds.encoded(),JSONObject(scores.filterKeys { it in (EnvSoundRules.speech + EnvSoundRules.human + EnvSoundRules.tags.values.flatMap { v -> v.second } + EnvSoundRules.sounds.values.flatMap { v -> v.second }) }).toString(),decision.skip,decision.markMedia,elapsedMs=elapsed)
            db.withTransaction { if(db.transcribe().findSegment(segment.id) != null) dao.insert(row) }
            log("环境音 ${elapsed}ms ${decision.verdict.name}")
            return decision.skip
        } catch(e: CancellationException) { throw e }
        catch(_: Exception) { log("环境音 ${(now()-start).coerceAtLeast(0)}ms 判断失败，照常转写"); return false }
        catch(_: LinkageError) { log("环境音 JNI 不可用，照常转写"); return false }
    }
    private fun bounds(date: LocalDate): Pair<Long,Long> = date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() to date.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
    override fun flagged(date: LocalDate): Flow<List<SegmentSound>> {
        val (from,to) = bounds(date)
        return combine(dao.observe(), tick()) { rows, _ -> rows.filter { it.startAt < to && it.endAt > from && it.verdict != "Keep" }.map { it.view() } }.flowOn(Dispatchers.IO)
    }
    override fun eventSounds(date: LocalDate): Flow<Map<Long, EventSounds>> {
        val (from,to) = bounds(date)
        return combine(dao.events(), dao.observe()) { events, rows -> mergeSounds(events.filter { it.startAt < to && it.endAt > from }, rows) }
    }
    override fun eventsWith(tag: EnvTag?, sound: SpecialSound?): Flow<List<Long>> = combine(dao.events(),dao.observe()) { events, rows ->
        val merged = mergeSounds(events,rows)
        events.filter { e -> merged[e.id]?.let { (tag == null || tag in it.tags) && (sound == null || sound in it.sounds) } == true }.map { it.id }
    }
    override fun hourly(date: LocalDate): Flow<List<HourSound>> = combine(db.transcribe().observeSegments(),dao.observe()) { segments, rows -> hourlySounds(date,segments,rows,ZoneId.systemDefault()) }
    override fun stats(days: Int): Flow<EnvStats> = combine(dao.observe(),tick()) { rows, _ ->
        val n = days.coerceAtLeast(1); val from = bounds(LocalDate.now().minusDays(n-1L)).first
        val selected = rows.filter { it.startAt >= from && it.startAt <= now() }
        EnvStats(n, selected.count { it.verdict != "Keep" || it.reportedWrong }, selected.count { it.restored }, selected.count { it.reportedWrong },
            (selected.filter { it.skipped && !it.restored && !it.reportedWrong }.sumOf { (it.endAt-it.startAt).coerceAtLeast(0) }/60000).toInt())
    }
    override suspend fun restore(segmentId: Long): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            db.withTransaction {
                val row = requireNotNull(dao.get(segmentId)) { "未找到这段录音" }
                check(File(row.path).isFile) { "这段录音已过保留期" }
                if(row.skipped && !row.restored) {
                    check(dao.requeue(segmentId) > 0) { "这段录音尚未跳过，请稍后重试" }
                    dao.restored(segmentId)
                }
            }
            schedule(); Result.success(Unit)
        } catch(e: CancellationException) { throw e }
        catch(e: Exception) { Result.failure(e) }
    }
    override fun reportWrong(segmentId: Long) {
        scope.launch {
            db.withTransaction {
                val row = dao.get(segmentId) ?: return@withTransaction
                dao.wrong(segmentId)
                if((row.skipped || row.verdict == "Skippable") && File(row.path).isFile) {
                    if(dao.requeueCorrection(segmentId) > 0 && row.skipped) dao.restored(segmentId)
                }
            }
            schedule()
        }
    }
    override fun audio(segmentId: Long): File? = rows.value.firstOrNull { it.segmentId == segmentId }?.let { File(it.path).takeIf(File::isFile) }
    suspend fun diagnostics(): String {
        val recent = dao.observe().first().filter { it.startAt >= now()-7*86_400_000L }
        return "设置=${settings.value}\n模型=${model.value}\n最近7天=${recent.groupingBy { it.verdict }.eachCount()}\n平均耗时=${recent.map { it.elapsedMs }.average().takeIf { it.isFinite() } ?: 0.0}ms"
    }
    private fun tick() = flow { while(currentCoroutineContext().isActive) { emit(Unit); delay(5000) } }
    companion object {
        private inline fun <reified T: Enum<T>> enumValue(value: String?, fallback: T) = enumValues<T>().firstOrNull { it.name == value } ?: fallback
        fun create(context: Context, db: CaptureDatabase, scope: CoroutineScope, me: suspend (ShortArray) -> Boolean, schedule: () -> Unit): LocalEnvSoundRepository {
            val model = EnvSoundModel(context)
            val reader = AsrAudioReader(context.cacheDir)
            // 单独的后台低优先级线程；调用者等待结束才启动 ASR，不抢本地转写 CPU。
            val dispatcher = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread({ android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND); r.run() }, "env-sound") }.asCoroutineDispatcher()
            val record: (String) -> Unit = { PipelineTimes.record(context, "${System.currentTimeMillis()} $it") }
            val analysis = EnvSoundAnalysis(reader::readPcm, { model.infer(it, record) }, me,
                phase = { app.resound.PipelineProgress.detail(it) }, log = record)
            return LocalEnvSoundRepository(db,context.getSharedPreferences("env-sound",0),scope,model.state,model::download,
                { file -> withContext(dispatcher) { analysis.analyze(file) } },schedule,record).also { repo ->
                scope.launch { model.checkReady(); if(repo.settings.value.enabled) repo.downloadModel() }
            }
        }
    }
}
