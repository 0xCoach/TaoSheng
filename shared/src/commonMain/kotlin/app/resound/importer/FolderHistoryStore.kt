package app.resound.importer

/** 平台读取媒体后传入的快照，不含 Uri、文件对象或系统时钟。 */
data class FolderMediaSnapshot(val uri: String, val name: String, val size: Long, val modified: Long,
    val duration: Long, val recordedAt: Long?, val call: CallFileInfo)
data class FolderPreviewTotals(val total: Int, val millis: Long, val last7: Int, val millis7: Long,
    val last30: Int, val millis30: Long, val oldest: Long?, val callLike: Int)

object FolderPreviewPolicy {
    fun count(files: List<FolderMediaSnapshot>, now: Long): FolderPreviewTotals {
        val seven = files.filter { FolderHistoryPolicy.queues(FolderHistory.Days7, it.call.startAt, now) }
        val thirty = files.filter { FolderHistoryPolicy.queues(FolderHistory.Days30, it.call.startAt, now) }
        return FolderPreviewTotals(files.size, files.sumOf { it.duration }, seven.size, seven.sumOf { it.duration },
            thirty.size, thirty.sumOf { it.duration }, files.mapNotNull { it.call.startAt }.minOrNull(),
            files.count { it.call.number != null || it.call.name != null })
    }
}

class FolderHistoryStore(private val dao: FolderRecordingDao) {
    suspend fun history(folder: String, kind: ImportKind, files: List<FolderMediaSnapshot>, history: FolderHistory, now: Long) {
        if (history == FolderHistory.OnlyNew) return
        for (file in files) {
            val start = if (kind == ImportKind.Call) file.call.startAt ?: file.recordedAt ?: file.modified else file.recordedAt ?: file.modified
            val queued = FolderHistoryPolicy.queues(history, start, now)
            val old = dao.find(folder,file.uri)
            if (old == null) dao.insert(row(folder,kind,file,now).copy(state=if (queued) "Queued" else "Listed",backfill=queued))
            else if (queued && old.state == "Listed" && old.pendingId == null && !old.notified) dao.update(old.copy(state="Queued",backfill=true,notified=false))
        }
    }
    fun row(folder: String, kind: ImportKind, file: FolderMediaSnapshot, now: Long): FolderRecordingRow = FolderRecordingRow(
        folderId=folder,documentUri=file.uri,fileName=file.name,size=file.size,modified=file.modified,
        startAt=(if (kind == ImportKind.Call) file.call.startAt else file.recordedAt) ?: file.modified,
        durationMillis=file.duration,number=file.call.number.takeIf { kind == ImportKind.Call },
        partyName=file.call.name.takeIf { kind == ImportKind.Call },direction=file.call.direction?.name.takeIf { kind == ImportKind.Call },
        createdAt=now,kind=kind.name)
    suspend fun transcribe(ids: List<Long>) {
        for (id in ids.distinct()) dao.get(id)?.takeIf { it.state in setOf("Listed","Missing","Queued") && it.pendingId == null }?.let {
            dao.update(it.copy(state="Queued",backfill=false,notified=true))
        }
    }
    /** 新设备 SAF 地址可能变；仅唯一匹配，避免同名同大小的多个录音被错误合并。 */
    suspend fun rebind(folder: String, files: List<FolderMediaSnapshot>, accessibleFolders: Set<String>) {
        val old = dao.all().filter { it.folderId !in accessibleFolders && it.state != "Imported" }
        for (file in files) {
            if (dao.find(folder,file.uri) != null) continue
            if (files.count { it.name == file.name && it.size == file.size } > 1) continue
            val match = old.filter { it.documentUri == file.uri || (it.fileName == file.name && it.size == file.size) }.singleOrNull() ?: continue
            dao.update(match.copy(folderId=folder,documentUri=file.uri,state="Listed",pendingId=null,backfill=false))
        }
    }
}
