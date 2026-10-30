package app.resound.importer

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.work.*
import app.resound.AppGraph
import app.resound.data.CaptureDatabase
import app.resound.digest.*
import app.resound.skills.*
import app.resound.transcribe.TranscribeSource
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.time.LocalDate
import java.util.concurrent.TimeUnit

class AndroidImportKinds internal constructor(
    private val context: Context,
    private val db: CaptureDatabase,
    private val importer: AndroidAudioImporter,
    private val scope: CoroutineScope,
    internal val engine: ImportKindEngine,
    private val transcribe: () -> Unit,
) : ImportKinds {
    private val prefs = context.getSharedPreferences("import-kinds", Context.MODE_PRIVATE)
    private val last = MutableStateFlow(prefs.getString("last", null)?.let { runCatching { ImportKind.valueOf(it) }.getOrNull() })
    override val lastKind = last.asStateFlow()
    init {
        scope.launch(Dispatchers.IO) {
            // 查询关系表会同时观察转写/认人状态；进程重启也恢复 Summarizing。
            enqueue(context)
            db.imports().observe().map { rows -> rows.filter { group ->
                val row = group.row
                row.ingestComplete && row.summaryRequested && row.status != "Summarizing" &&
                    group.segments.none { it.status in setOf("Pending", "Running") || (it.status == "Done" && it.voiceprintProcessedAt == null) }
            }.map { group -> Triple(group.row.id, group.row.revision, group.segments.map { it.status }) } }
                .distinctUntilChanged().collect { if (it.isNotEmpty()) enqueue(context) }
        }
    }
    override suspend fun suggest(pendingId: String): KindSuggestion = withContext(Dispatchers.IO) {
        val row = importer.entry(pendingId) ?: throw ImportFailure("导入文件已不存在")
        val clue = ImportKindPolicy.suggest(row.pending.info.displayName, row.source, false, last.value)
        if (clue.reason == KindReason.CallFolder) clue else ImportKindPolicy.suggest(row.pending.info.displayName, row.source, importer.dual(pendingId), last.value)
    }
    override fun start(pendingId: String, startAt: Long, asr: TranscribeSource?, kind: ImportKind) {
        if (importer.entry(pendingId)?.startAt != null || importer.entry(pendingId) == null) return
        check(prefs.edit().putString("last", kind.name).commit())
        last.value = kind
        importer.startKind(pendingId, startAt, asr, kind)
    }
    override fun startBatch(pendingIds: List<String>, startAts: List<Long>, asr: TranscribeSource?, kind: ImportKind, merge: Boolean) {
        require(pendingIds.size == startAts.size && pendingIds.distinct().size == pendingIds.size)
        val files = pendingIds.mapIndexed { index, id ->
            val entry = requireNotNull(importer.entry(id)) { "导入文件已不存在" }
            require(entry.startAt == null) { "文件已经开始导入" }
            ImportKindPolicy.BatchFile(id, startAts[index], entry.pending.info.durationMillis, entry.pending.info.suggestedStartAt != null)
        }
        check(prefs.edit().putString("last", kind.name).commit()); last.value = kind
        importer.startBatch(ImportKindPolicy.batch(files, merge), asr, kind)
    }
    override fun records(date: LocalDate): Flow<List<ImportRecord>> = db.imports().day(
        date.atStartOfDay(SKILL_ZONE).toInstant().toEpochMilli(), date.plusDays(1).atStartOfDay(SKILL_ZONE).toInstant().toEpochMilli()
    ).map { rows -> rows.map { it.record() } }
    override fun record(id: Long): Flow<ImportRecord?> = db.imports().observe(id).map { it?.record() }
    override fun changeKind(recordId: Long, kind: ImportKind) {
        scope.launch(Dispatchers.IO) { engine.change(recordId, kind); transcribe(); enqueue(context) }
    }
    override fun regenerate(recordId: Long) {
        scope.launch(Dispatchers.IO) { engine.regenerate(recordId); enqueue(context) }
    }
    companion object {
        internal fun enqueue(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork("import-summaries", ExistingWorkPolicy.APPEND_OR_REPLACE,
                OneTimeWorkRequestBuilder<ImportSummaryWork>().setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build())
        }
        internal fun create(context: Context, db: CaptureDatabase, importer: AndroidAudioImporter, scope: CoroutineScope,
            settings: AndroidDigestSettings, llm: LlmClient, speakers: ImportSpeakers,
            idle: suspend (suspend () -> Unit) -> Unit, transcribe: () -> Unit, ready: suspend () -> Unit): AndroidImportKinds {
            val summary = ImportSummary(template = { kind ->
                val id = "import_${kind.name.lowercase()}"
                SkillJson.parse(context.assets.open("skills/$id/skill.json").bufferedReader().use { it.readText() }, SkillOrigin.Builtin).skill to
                    context.assets.open("skills/$id/prompt.md").bufferedReader().use { it.readText() }
            }, request = { system, user ->
                val (preset, key) = settings.skillCredentials(null)
                if (key.isBlank() && !preset.allowsEmptyKey()) throw LlmException(LlmError.Auth)
                val reply = llm.chatJson(preset, key, system, user)
                withContext(NonCancellable) { db.memory().addUsage(LocalDate.now().toString(), reply.promptTokens, reply.completionTokens, app.resound.usage.UsagePurpose.Skill) }
                reply
            })
            return AndroidImportKinds(context, db, importer, scope,
                ImportKindEngine(db, summary, speakers::prepare, idle, ready), transcribe)
        }
    }
}

class ImportSummaryWork(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun getForegroundInfo(): ForegroundInfo {
        applicationContext.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("import", "导入录音", NotificationManager.IMPORTANCE_LOW))
        return ForegroundInfo(3077, NotificationCompat.Builder(applicationContext, "import")
            .setSmallIcon(app.resound.R.drawable.ic_notify).setContentTitle("正在整理导入录音").setOngoing(true).build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            try { setForeground(getForegroundInfo()) } catch (e: CancellationException) { throw e } catch (_: Exception) { /* 前台受限时仍可用普通 Worker。 */ }
            (AppGraph.importKinds as AndroidImportKinds).engine.run()
            Result.success()
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { Result.retry() }
    }
}
