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

    private fun view(row: FolderRecordingRow, state: FolderImportState): FolderRecording {
        val available = state.folders.any { it.id == row.folderId && it.accessible }
        return FolderRecording(row.id,row.folderId,row.fileName,row.startAt,row.durationMillis,
            if (row.kind == ImportKind.Call.name) CallParty(row.number,row.partyName,row.direction?.let(CallDirection::valueOf)) else null,
            if (!available && row.state != "Imported") ListedState.Missing else ListedState.valueOf(row.state),row.importRecordId)
    }
    // 移除最后一个目录后仍可按需查看保留的历史；此时只读快照，不注册表订阅。
    private fun <T> displayed(observe: () -> Flow<T>, snapshot: suspend () -> T): Flow<T> =
        configured.flatMapLatest { active -> if (active) observe() else flow { emit(snapshot()) } }

    override fun between(start: Long, end: Long) = combine(
        displayed({ dao.observeBetween(start, end) }, { dao.between(start, end) }), folders
    ) { rows, state -> rows.map { view(it, state) } }

    override fun byImport(importRecordId: Long) = combine(
        displayed({ dao.observeImport(importRecordId) }, { dao.byImport(importRecordId) }), folders
    ) { row, state -> row?.let { view(it, state) } }

    override fun search(query: String) = combine(displayed(dao::observe, dao::all), folders) { rows, state ->
        rows.asSequence().filter { FolderHistoryPolicy.matches(it, query) }.take(200).map { view(it, state) }.toList()
    }

    override fun transcribe(ids: List<Long>) { scope.launch { runner.transcribe(ids); resumeQueue() } }
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
        })
    /** 调用方先扫描新文件，再给补转执行机会。 */
    suspend fun pump() = runner.pump()
}
