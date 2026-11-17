package app.resound.importer

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 平台只提供读文件、入队、时钟和通知；排队、续做、去重、整轮完成由共用层决定。 */
data class FolderQueueProbe(val size: Long, val modified: Long, val fingerprint: String)
class FolderBackfillRunner(
    private val dao: FolderRecordingDao, private val imports: ImportRecordDao,
    private val now: () -> Long, private val paused: () -> Boolean, private val newFilesPending: () -> Boolean,
    private val enabledFolders: () -> Set<String>, private val running: () -> Boolean,
    private val queuedBackfill: () -> Boolean?, private val pendingExists: (String) -> Boolean,
    private val newId: () -> String, private val probe: suspend (FolderRecordingRow) -> FolderQueueProbe,
    private val enqueue: suspend (FolderRecordingRow,String,String) -> Boolean,
    private val complete: (String,Int) -> Unit,
) {
    private val gate = Mutex()
    suspend fun transcribe(ids: List<Long>) = gate.withLock { FolderHistoryStore(dao).transcribe(ids) }
    suspend fun pump() = gate.withLock {
        var current = dao.all()
        for (row in current.filter { it.pendingId != null && it.importRecordId == null }) {
            val pending = requireNotNull(row.pendingId)
            val record = imports.byPending(pending)
            if (record != null) dao.attached(pending,record.id)
            else if (!pendingExists(pending)) dao.update(row.copy(pendingId=null,state="Queued"))
        }
        current = dao.all()
        val records = current.mapNotNull { it.importRecordId }.distinct().associateWith { imports.get(it) }
        for ((folder, round) in current.filter { it.backfill && !it.notified }.groupBy { it.folderId }) {
            if (round.isNotEmpty() && round.all { records[it.importRecordId]?.status == "Done" }) {
                complete(folder,round.size)
                dao.notified(folder)
            }
        }
        val queued = queuedBackfill()
        val manual = current.any { it.state == "Queued" && !it.backfill && it.pendingId == null }
        if (running() || (queued != null && (!queued || !manual))) return@withLock
        // 未完成或失败的补转只占一个槽；失败等待用户处理，手动请求仍优先。
        if (!manual && current.any { it.backfill && !it.notified && it.importRecordId != null && records[it.importRecordId]?.status != "Done" }) return@withLock
        val row = FolderHistoryPolicy.next(current.filter { it.folderId in enabledFolders() },paused() || newFilesPending()) ?: return@withLock
        var admissionId: String? = null
        try {
            val file = probe(row)
            if (file.size <= 0 || file.size != row.size || file.modified != row.modified || file.modified >= now()-120_000) return@withLock
            val duplicate = current.firstOrNull { it.id != row.id && it.fingerprint == file.fingerprint && (it.importRecordId != null || it.pendingId != null) }
            if (duplicate != null) {
                dao.update(row.copy(fingerprint=file.fingerprint,pendingId=duplicate.pendingId,importRecordId=duplicate.importRecordId,
                    state=if (duplicate.importRecordId != null) "Imported" else "Queued",backfill=false,notified=true))
                return@withLock
            }
            val id = newId()
            dao.update(row.copy(fingerprint=file.fingerprint,pendingId=id))
            admissionId = id
            if (!enqueue(row,id,file.fingerprint)) dao.update(row.copy(state="Listed",pendingId=null))
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) {
            if (admissionId == null || !pendingExists(admissionId)) dao.update(row.copy(state="Missing",pendingId=null))
        }
    }
}
