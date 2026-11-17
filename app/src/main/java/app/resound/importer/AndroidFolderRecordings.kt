package app.resound.importer

import android.content.Context
import android.net.Uri
import app.resound.data.CaptureDatabase
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.UUID

internal class AndroidFolderRecordings(
    private val context: Context, private val db: CaptureDatabase, private val scope: CoroutineScope,
    private val importer: AndroidAudioImporter, private val documents: FolderDocuments,
    private val clock: () -> Long, private val folders: StateFlow<FolderImportState>, private val scan: () -> Unit,
    private val completed: (String,String,Int) -> Unit = { id,name,n -> FolderImportWork.notifyBackfill(context,id,name,n) },
    private val newFilesPending: () -> Boolean = { false },
) : FolderRecordings {
    private val dao = db.folderRecordings()
    private val prefs = context.getSharedPreferences("folder-imports",0)
    private val paused = MutableStateFlow(prefs.getBoolean("backfillPaused",false))
    private val rows = dao.observe().stateIn(scope,SharingStarted.Eagerly,emptyList())
    private val imports = db.imports().observe().stateIn(scope,SharingStarted.Eagerly,emptyList())
    override val backfill = combine(rows,imports,paused) { rows, imports, pause ->
        val round = rows.filter { it.backfill && !it.notified }
        val done = imports.filter { it.row.status == "Done" }.map { it.row.id }.toSet()
        BackfillState(round.count { it.importRecordId !in done },round.size,pause)
    }.stateIn(scope,SharingStarted.Eagerly,BackfillState(0,0,paused.value))
    internal fun start() {
        scope.launch { combine(rows,imports,importer.progress) { r,i,p -> Triple(r,i,p.running to p.queued) }
            .distinctUntilChanged().collect { scan() } }
    }
    private fun view(row: FolderRecordingRow, state: FolderImportState): FolderRecording {
        val available = state.folders.any { it.id == row.folderId && it.accessible }
        return FolderRecording(row.id,row.folderId,row.fileName,row.startAt,row.durationMillis,
            if (row.kind == ImportKind.Call.name) CallParty(row.number,row.partyName,row.direction?.let(CallDirection::valueOf)) else null,
            if (!available && row.state != "Imported") ListedState.Missing else ListedState.valueOf(row.state),row.importRecordId)
    }
    private fun query(filter: (FolderRecordingRow) -> Boolean): Flow<List<FolderRecording>> =
        combine(rows,folders) { rows,state -> rows.filter(filter).map { view(it,state) } }
    override fun between(start: Long,end: Long) = query { r -> r.startAt >= start && r.startAt < end }
    override fun byImport(importRecordId: Long) = query { r -> r.importRecordId == importRecordId }.map { it.firstOrNull() }
    override fun search(query: String) = query { r -> FolderHistoryPolicy.matches(r,query) }.map { it.take(200) }
    override fun transcribe(ids: List<Long>) { scope.launch { runner.transcribe(ids); scan() } }
    override fun pauseBackfill(paused: Boolean) {
        check(prefs.edit().putBoolean("backfillPaused",paused).commit()); this.paused.value=paused; scan()
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
        },{ folder,n -> completed(folder,folders.value.folders.firstOrNull { it.id == folder }?.name ?: "文件夹",n) })
    /** 调用方先扫描新文件，再给补转执行机会。 */
    suspend fun pump() = runner.pump()
}
