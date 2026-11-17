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
) : FolderRecordings, PhoneLabels {
    private val dao = db.folderRecordings()
    private val prefs = context.getSharedPreferences("folder-imports",0)
    private val paused = MutableStateFlow(prefs.getBoolean("backfillPaused",false))
    override val labels = dao.labels().map { rows -> rows.associate { it.number to it.name } }
        .stateIn(scope,SharingStarted.Eagerly,emptyMap())
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
    private fun view(row: FolderRecordingRow, labels: Map<String,String>, state: FolderImportState): FolderRecording {
        val available = state.folders.any { it.id == row.folderId && it.accessible }
        return FolderRecording(row.id,row.folderId,row.fileName,row.startAt,row.durationMillis,
            if (row.kind == ImportKind.Call.name) CallParty(row.number,row.partyName,labels[row.number],row.direction?.let(CallDirection::valueOf)) else null,
            if (!available && row.state != "Imported") ListedState.Missing else ListedState.valueOf(row.state),row.importRecordId)
    }
    private fun query(filter: (FolderRecordingRow,Map<String,String>) -> Boolean): Flow<List<FolderRecording>> =
        combine(rows,labels,folders) { rows,labels,state -> rows.filter { filter(it,labels) }.map { view(it,labels,state) } }
    override fun between(start: Long,end: Long) = query { r,_ -> r.startAt >= start && r.startAt < end }
    override fun byImport(importRecordId: Long) = query { r,_ -> r.importRecordId == importRecordId }.map { it.firstOrNull() }
    override fun search(query: String) = query { r,l -> FolderHistoryPolicy.matches(r,l[r.number],query) }.map { it.take(200) }
    override fun withNumber(number: String) = query { r,_ -> r.number != null && r.number == CallFileNames.normalizeNumber(number) }
    override fun transcribe(ids: List<Long>) { scope.launch { runner.transcribe(ids); scan() } }
    override fun pauseBackfill(paused: Boolean) {
        check(prefs.edit().putBoolean("backfillPaused",paused).commit()); this.paused.value=paused; scan()
    }
    override fun setLabel(number: String,name: String?) {
        val normalized = CallFileNames.normalizeNumber(number) ?: return
        scope.launch {
            val clean = name?.trim()?.takeIf { it.isNotEmpty() }
            if (clean == null) dao.deleteLabel(normalized) else dao.label(PhoneLabelRow(normalized,clean,clock()))
        }
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
