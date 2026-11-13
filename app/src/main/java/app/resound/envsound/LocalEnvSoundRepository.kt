package app.resound.envsound

import android.content.Context
import android.content.SharedPreferences
import app.resound.data.writeTransaction
import app.resound.data.*
import app.resound.transcribe.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File
import java.time.*
import kotlinx.datetime.toJavaLocalDate
import kotlinx.datetime.toKotlinLocalDate
import app.resound.platform.FileRef
import app.resound.platform.ZoneProvider

@OptIn(ExperimentalCoroutinesApi::class)
class LocalEnvSoundRepository(
    private val db: CaptureDatabase,
    private val prefs: SharedPreferences,
    private val scope: CoroutineScope,
    private val modelState: StateFlow<EnvModelState>,
    private val download: suspend () -> Unit,
    private val analyze: suspend (File) -> Pair<EnvSoundEvidence, Boolean>,
    private val schedule: () -> Unit,
    private val log: (String) -> Unit = {},
    private val now: () -> Long,
    private val zoneProvider: ZoneProvider,
    io: CoroutineDispatcher,
) : EnvSoundRepository {
    var beginBatch: () -> Unit = {}
    var endBatch: () -> Unit = {}
    private val dao = db.envSound()
    private val store = EnvSoundStoreRepository(db, scope, modelState, download, schedule, now, zoneProvider,
        { LocalDate.now(ZoneId.of(it)).toKotlinLocalDate() }, { File(it.id).isFile }, { path -> File(path).takeIf(File::isFile)?.let { FileRef(it.path) } }, io,
        prefs::getBoolean, { prefs.getString(it, null) }, { settings, enabledAt ->
            val edit = prefs.edit()
            if (enabledAt != null) edit.putLong("enabledAt", enabledAt)
            edit.putBoolean("enabled", settings.enabled).putString("skip", settings.skip.name).putString("media", settings.media.name)
                .putBoolean("tags", settings.tags).putBoolean("specialSounds", settings.specialSounds).putBoolean("showInSummaries", settings.showInSummaries).commit()
        })
    override val settings get() = store.settings
    override val model get() = store.model
    internal fun presentationChanges() = store.presentationChanges()
    internal suspend fun presentationSnapshot(start: Long, end: Long) = store.presentationSnapshot(start, end)
    internal suspend fun eventSound(id: Long) = store.eventSound(id)
    @Synchronized override fun update(settings: EnvSoundSettings) = store.update(settings)
    @Synchronized override fun downloadModel() = store.downloadModel()
    override fun flagged(date: kotlinx.datetime.LocalDate) = store.flagged(date)
    override fun eventSounds(date: kotlinx.datetime.LocalDate) = store.eventSounds(date)
    override fun eventsWith(tag: EnvTag?, sound: SpecialSound?) = store.eventsWith(tag, sound)
    override fun hourly(date: kotlinx.datetime.LocalDate) = store.hourly(date)
    override fun stats(days: Int) = store.stats(days)
    override suspend fun restore(segmentId: Long) = store.restore(segmentId)
    override fun reportWrong(segmentId: Long) = store.reportWrong(segmentId)
    override suspend fun audio(segmentId: Long) = store.audio(segmentId)
    suspend fun diagnostics() = store.diagnostics()
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
                decision.tags.encoded(),decision.sounds.encoded(),scores.json(),decision.skip,decision.markMedia,elapsedMs=elapsed)
            db.writeTransaction { if(db.transcribe().findSegment(segment.id) != null) dao.insert(row) }
            log("环境音 ${elapsed}ms ${decision.verdict.name}")
            return decision.skip
        } catch(e: CancellationException) { throw e }
        catch(_: Exception) { log("环境音 ${(now()-start).coerceAtLeast(0)}ms 判断失败，照常转写"); return false }
        catch(_: LinkageError) { log("环境音 JNI 不可用，照常转写"); return false }
    }
    companion object {
        fun create(context: Context, db: CaptureDatabase, scope: CoroutineScope, me: suspend (ShortArray) -> Boolean, schedule: () -> Unit, sharedPcm: SharedPcmFiles? = null, zoneProvider: ZoneProvider, now: () -> Long, io: CoroutineDispatcher): LocalEnvSoundRepository {
            val model = EnvSoundModel(context)
            val gaps = EnvSoundGapExtractor(context)
            val reader = AsrAudioReader(context.cacheDir)
            // 单独的后台低优先级线程；调用者等待结束才启动 ASR，不抢本地转写 CPU。
            val dispatcher = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread({ android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND); r.run() }, "env-sound") }.asCoroutineDispatcher()
            val record: (String) -> Unit = { PipelineTimes.record(context, "${System.currentTimeMillis()} $it") }
            val analysis = EnvSoundAnalysis({ file -> sharedPcm?.prepare(file) ?: reader.readPcm(file) }, { pcm ->
                val timing = currentCoroutineContext()[SegmentTimings]
                model.infer(pcm, record) { load, prepare, infer ->
                    timing?.add(AsrPart.EnvLoad, load); timing?.add(AsrPart.EnvPrepare, prepare); timing?.add(AsrPart.EnvInfer, infer)
                }
            }, me,
                phase = { app.resound.PipelineProgress.detail(it) }, log = record, extractGaps = gaps::extract)
            return LocalEnvSoundRepository(db,context.getSharedPreferences("env-sound",0),scope,model.state,model::download,
                { file -> withContext(dispatcher) { analysis.analyze(file) } },schedule,record,zoneProvider = zoneProvider, now = now, io = io).also { repo ->
                repo.beginBatch = { model.beginBatch(); gaps.beginBatch() }
                repo.endBatch = { try { gaps.endBatch() } finally { model.endBatch() } }
                scope.launch { model.checkReady(); if(repo.settings.value.enabled) repo.downloadModel() }
            }
        }
    }
}
