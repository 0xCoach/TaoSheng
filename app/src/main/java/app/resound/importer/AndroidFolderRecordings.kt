package app.resound.importer

import android.content.Context
import android.net.Uri
import app.resound.data.CaptureDatabase
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
internal class AndroidFolderRecordings(
    private val context: Context, private val db: CaptureDatabase, private val scope: CoroutineScope,
    private val importer: AndroidAudioImporter, private val documents: FolderDocuments,
    private val clock: () -> Long, private val folders: StateFlow<FolderImportState>, private val scan: () -> Unit,
    private val completed: (String,String,Int) -> Unit = { id,name,n -> FolderImportWork.notifyBackfill(context,id,name,n) },
    private val newFilesPending: () -> Boolean = { false },
    private val resumeQueue: () -> Unit = {},
) : FolderRecordings {
    private val dao = db.folderRecordings()
    private val prefs = context.getSharedPreferences("folder-imports",0)
    private val paused = MutableStateFlow(prefs.getBoolean("backfillPaused",false))
    /** 卡 210：点“转写”失败的原因，按录音 id；重新排队时清掉 */
    private val problems = MutableStateFlow(prefs.all.mapNotNull { (k, v) ->
        k.removePrefix("problem-").takeIf { k.startsWith("problem-") }?.toLongOrNull()?.let { it to v.toString() } }.toMap())
    private val configured = folders.map { it.folders.isNotEmpty() }.distinctUntilChanged()
    private val counts = configured.flatMapLatest { active ->
        if (active) dao.roundCounts() else flowOf(FolderRoundCounts(0, 0))
    }.distinctUntilChanged().shareIn(scope, SharingStarted.Eagerly, replay = 1)
    override val backfill = combine(counts, paused) { round, pause ->
        BackfillState(round.remaining, round.total, pause)
    }.stateIn(scope, SharingStarted.Eagerly, BackfillState(0, 0, paused.value))

    internal fun start() {
        // 进度只给队列执行机会，不枚举系统目录。设置修改由文件夹仓储显式唤醒。
        scope.launch {
            configured.flatMapLatest { active ->
                if (!active) emptyFlow() else combine(counts, importer.progress) { round, progress ->
                    round to (progress.running || progress.queued > 0)
                // 首轮由恢复设置的扫描或 addHistory 推进；避免初始进度再泵一次。
                }.distinctUntilChanged().drop(1)
            }.collect { (round, busy) -> if (round.total > 0 || !busy) resumeQueue() }
        }
        if (folders.value.folders.any { it.enabled && it.accessible }) scan()
    }

    private fun view(row: FolderRecordingRow, state: FolderImportState, problems: Map<Long, String>): FolderRecording {
        val available = state.folders.any { it.id == row.folderId && it.accessible }
        return FolderRecording(row.id,row.folderId,row.fileName,row.startAt,row.durationMillis,
            if (row.kind == ImportKind.Call.name) CallParty(row.number,row.partyName,row.direction?.let(CallDirection::valueOf)) else null,
            if (!available && row.state != "Imported") ListedState.Missing else ListedState.valueOf(row.state),row.importRecordId,
            when { !available && row.state != "Imported" -> "文件夹需要重新授权"; row.state == "Missing" -> problems[row.id]; else -> null })
    }
    /** 卡 210：失败分类。只给人看的原因与异常类别进诊断；不记文件名（通话录音名里有姓名和号码） */
    private fun failed(row: FolderRecordingRow, e: Exception) {
        val reason = FolderImportProblems.reason(e)
        check(prefs.edit().putString("problem-${row.id}", reason).commit())
        problems.value = problems.value + (row.id to reason)
        FolderImportProblems.record(context, row.fileName.substringAfterLast('.', "?"), reason, e)
    }
    // 移除最后一个目录后仍可按需查看保留的历史；此时只读快照，不注册表订阅。
    private fun <T> displayed(observe: () -> Flow<T>, snapshot: suspend () -> T): Flow<T> =
        configured.flatMapLatest { active -> if (active) observe() else flow { emit(snapshot()) } }

    override fun between(start: Long, end: Long) = combine(
        displayed({ dao.observeBetween(start, end) }, { dao.between(start, end) }), folders, problems
    ) { rows, state, p -> rows.map { view(it, state, p) } }

    override fun byImport(importRecordId: Long) = combine(
        displayed({ dao.observeImport(importRecordId) }, { dao.byImport(importRecordId) }), folders, problems
    ) { row, state, p -> row?.let { view(it, state, p) } }

    override fun search(query: String) = combine(displayed(dao::observe, dao::all), folders, problems) { rows, state, p ->
        rows.asSequence().filter { FolderHistoryPolicy.matches(it, query) }.take(200).map { view(it, state, p) }.toList()
    }

    override fun transcribe(ids: List<Long>) { scope.launch {
        // 重试时先清掉上次的失败原因
        if (ids.any { it in problems.value }) {
            check(prefs.edit().apply { ids.forEach { remove("problem-$it") } }.commit())
            problems.value = problems.value - ids.toSet()
        }
        runner.transcribe(ids); resumeQueue()
    } }
    override fun pauseBackfill(paused: Boolean) {
        check(prefs.edit().putBoolean("backfillPaused", paused).commit())
        this.paused.value = paused
        resumeQueue()
    }
    private val runner = FolderBackfillRunner(dao,db.imports(),clock,{ paused.value },newFilesPending,
        { folders.value.folders.filter { it.enabled && it.accessible }.map { it.id }.toSet() },
        { importer.progress.value.running },{ importer.next()?.backfill },{ importer.entry(it) != null },
        { UUID.randomUUID().toString() },{ row ->
            val document = documents.stat(Uri.parse(row.documentUri))
            FolderQueueProbe(document.size,document.modified,documents.fingerprint(document))
        },{ row,id,fingerprint ->
            val document = documents.stat(Uri.parse(row.documentUri))
            importer.receiveFolder(document,ImportKind.valueOf(row.kind),fingerprint,row.startAt,id,true,row.backfill) {
                documents.stat(document.uri) == document && documents.granted(Uri.parse(row.folderId))
            }
        }, { folder, n ->
            completed(folder, folders.value.folders.firstOrNull { it.id == folder }?.name ?: "文件夹", n)
            scan()
        }, ::failed)
    /** 调用方先扫描新文件，再给补转执行机会。 */
    suspend fun pump() = runner.pump()
}
