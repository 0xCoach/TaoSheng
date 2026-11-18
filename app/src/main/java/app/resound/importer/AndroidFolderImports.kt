package app.resound.importer

import app.resound.diagnostics.FailureNotes

import android.content.Context
import android.net.Uri
import app.resound.data.writeTransaction
import app.resound.data.CaptureDatabase
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

/** 设置变更和扫描串行，暂停/删除不会与同一轮扫描交错；扫描只读 SAF。 */
internal class AndroidFolderImports(
    private val context: Context,
    private val db: CaptureDatabase,
    private val scope: CoroutineScope,
    private val importer: AndroidAudioImporter,
    private val documents: FolderDocuments = AndroidFolderDocuments(context),
    private val clock: () -> Long = System::currentTimeMillis,
    private val notify: (String, String, Int) -> Unit = { id, name, n -> FolderImportWork.notify(context, id, name, n) },
    private val schedule: (Boolean) -> Unit = { enabled -> FolderImportWork.schedule(context, enabled) },
) : FolderImports {
    internal val recordings by lazy { AndroidFolderRecordings(context,db,scope,importer,documents,clock,state,::scanNow,newFilesPending={ pendingNewFiles },
        resumeQueue=::resumeQueue) }
    private val historyStore = FolderHistoryStore(db.folderRecordings())
    private data class PreviewCache(val at: Long, val files: List<FolderMediaSnapshot>)
    private val previews = mutableMapOf<String,PreviewCache>()
    private val previewLock = Mutex()
    private val prefs = context.getSharedPreferences("folder-imports", 0)
    private data class Setting(val folder: WatchedFolder, val baseline: Boolean)
    private val folders = mutableListOf<Setting>()
    private val mutex = Mutex()
    private val commands = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    private val _state = MutableStateFlow(FolderImportState(emptyList(), false))
    override val state = _state.asStateFlow()
    private var pendingNewFiles = false
    private var scanning = false
    private var importRunning = false
    private var lastImported: Int? = if (prefs.contains("lastImported")) prefs.getInt("lastImported", 0) else null
    private var lastImportedAt: Long? = if (prefs.contains("lastImportedAt")) prefs.getLong("lastImportedAt", 0) else null
    private var lastHeartbeat = 0L
    init {
        val rows = JSONArray(prefs.getString("folders", "[]"))
        repeat(rows.length()) { i ->
            val row = rows.getJSONObject(i)
            val id = row.getString("id")
            val accessible = row.optBoolean("accessible", true) && runCatching { documents.granted(Uri.parse(id)) }.onFailure {
                FailureNotes.record("importer/AndroidFolderImports.kt:43", it)
            }.getOrDefault(false)
            folders += Setting(WatchedFolder(id, row.getString("name"), ImportKind.valueOf(row.getString("kind")),
                row.getBoolean("enabled"), accessible, if (row.isNull("lastScanAt")) null else row.getLong("lastScanAt"),
                row.optInt("imported"), if (!accessible) PERMISSION_ERROR else row.optString("error").takeIf { it.isNotBlank() },
                history = runCatching { FolderHistory.valueOf(row.optString("history", "OnlyNew")) }.getOrDefault(FolderHistory.OnlyNew)), row.optBoolean("baseline"))
        }
        publish()
        scope.launch(Dispatchers.IO) {
            for (command in commands) mutex.withLock {
                try { command() }
                catch (e: CancellationException) { throw e }
                catch (caught: Exception) {
                    FailureNotes.record("importer/AndroidFolderImports.kt:53", caught)

                    // 文件夹级错误由具体操作填写；这里只保护应用协程不因存储异常退出。
                    _state.value = _state.value.copy(folders = _state.value.folders.map { it.copy(lastError = "无法保存文件夹设置，请检查存储空间后重试") })
                }
            }
        }
        scope.launch {
            importer.progress.collect { progress -> mutex.withLock {
                importRunning = progress.running || progress.queued > 0; publish()
            } }
        }
        schedule(hasEnabled())
    }
    private fun resumeQueue() {
        commands.trySend {
            if (pendingNewFiles && hasEnabled()) scanLocked()
            else if (folders.isNotEmpty()) { recordings.pump(); updateListed() }
        }
    }
    private fun hasEnabled() = folders.any { it.folder.enabled && it.folder.accessible }
    private fun publish() { _state.value = FolderImportState(folders.map { it.folder }, scanning || importRunning, lastImported, lastImportedAt) }
    private fun save() {
        val rows = JSONArray(folders.map { s -> JSONObject().apply {
            val f = s.folder
            put("id", f.id); put("name", f.name); put("kind", f.kind.name); put("enabled", f.enabled)
            put("accessible", f.accessible); put("lastScanAt", f.lastScanAt ?: JSONObject.NULL); put("imported", f.imported)
            put("error", f.lastError ?: ""); put("baseline", s.baseline); put("history", f.history.name)
        } })
        val edit = prefs.edit().putString("folders", rows.toString())
        lastImported?.let { edit.putInt("lastImported", it) }; lastImportedAt?.let { edit.putLong("lastImportedAt", it) }
        check(edit.commit()); publish()
    }
    override fun add(tree: Uri, kind: ImportKind, importExisting: Boolean) = add(tree,kind,if (importExisting) FolderHistory.Days30 else FolderHistory.OnlyNew)
    override fun add(tree: Uri, kind: ImportKind, history: FolderHistory) { commands.trySend { addHistory(tree,kind,history) } }
    internal suspend fun addHistory(tree: Uri, kind: ImportKind, history: FolderHistory) {
        val files = snapshot(tree)
        val accessible = folders.filter { it.folder.accessible }.map { it.folder.id }.toSet()
        historyStore.rebind(tree.toString(),files,accessible)
        addFolder(tree,kind,false)
        val index = folders.indexOfFirst { it.folder.id == tree.toString() }
        folders[index] = folders[index].copy(folder=folders[index].folder.copy(history=history))
        historyStore.history(tree.toString(),kind,files,history,clock())
        save(); recordings.pump(); updateListed()
    }
    override fun setHistory(id: String, history: FolderHistory) { commands.trySend {
        val index = folders.indexOfFirst { it.folder.id == id }
        if (index >= 0) {
            val setting = folders[index]
            if (FolderHistoryPolicy.expands(setting.folder.history,history)) {
                val baseline = db.folderImports().observations(id).filter { it.ignored }.map { it.documentId }.toSet()
                val files = snapshot(Uri.parse(id)).filter { it.uri in baseline }
                historyStore.history(id,setting.folder.kind,files,history,clock())
            }
            folders[index] = setting.copy(folder=setting.folder.copy(history=history)); save()
            recordings.pump(); updateListed()
        }
    } }
    override suspend fun preview(tree: Uri): FolderPreview {
        val files = snapshot(tree)
        val totals = FolderPreviewPolicy.count(files,clock())
        val name = withContext(Dispatchers.IO) { documents.root(tree).name }
        return FolderPreview(name,totals.total,totals.millis,totals.last7,totals.millis7,totals.last30,totals.millis30,
            totals.oldest,totals.callLike,CallRecordingFolders.looksLikeCallFolder(Uri.decode(tree.toString())) || (totals.callLike >= 1 && totals.callLike * 2 >= totals.total))
    }
    private suspend fun snapshot(tree: Uri): List<FolderMediaSnapshot> = withContext(Dispatchers.IO) { previewLock.withLock {
        documents.acquire(tree)
        check(documents.granted(tree)) { PERMISSION_ERROR }
        val files = documents.files(tree).filter { it.size > 0 }
        val cached = previews[tree.toString()]?.takeIf { clock()-it.at in 0..600_000 }?.files.orEmpty().associateBy { it.uri }
        val permits = kotlinx.coroutines.sync.Semaphore(8)
        val result = coroutineScope { files.map { file -> async {
            val old = cached[file.uri.toString()]
            if (old != null && old.size == file.size && old.modified == file.modified) old else {
                permits.acquire()
                try { inspect(file) } finally { permits.release() }
            }
        } }.awaitAll() }
        previews[tree.toString()] = PreviewCache(clock(),result)
        result
    } }
    private fun inspect(file: FolderDocument): FolderMediaSnapshot {
        val audio = try { documents.metadata(file) } catch (e: CancellationException) { throw e } catch (_: Exception) { AudioMetadata(0,null) }
        val recorded = suggestedStart(audio.date,null,audio.duration)
        val call = CallFileNames.parse(file.name,file.modified.takeIf { it > 0 },recorded,audio.duration,kotlinx.datetime.TimeZone.currentSystemDefault())
        return FolderMediaSnapshot(file.uri.toString(),file.name,file.size,file.modified,audio.duration,recorded,call)
    }
    private suspend fun updateListed() {
        val counts = db.folderRecordings().all().filter { it.state != "Imported" }.groupingBy { it.folderId }.eachCount()
        folders.indices.forEach { i -> folders[i] = folders[i].copy(folder=folders[i].folder.copy(listed=counts[folders[i].folder.id] ?: 0)) }
        publish()
    }
    internal suspend fun addFolder(tree: Uri, kind: ImportKind, importExisting: Boolean) {
        val id = tree.toString()
        val old = folders.firstOrNull { it.folder.id == id }
        var next = old?.copy(folder = old.folder.copy(kind = kind, enabled = true, accessible = true, lastError = null))
            ?: Setting(WatchedFolder(id, "所选文件夹", kind, true, true, null, 0), !importExisting)
        try {
            documents.acquire(tree)
            check(documents.granted(tree)) { PERMISSION_ERROR }
            next = next.copy(folder = next.folder.copy(name = documents.root(tree).name))
        } catch (e: Exception) {
            next = next.copy(folder = next.folder.copy(accessible = false, lastError = PERMISSION_ERROR))
        }
        folders.removeAll { it.folder.id == id }; folders += next
        save()
        if (next.folder.accessible) {
            scanning = true; publish()
            try { scanFolder(folders.lastIndex) } finally { scanning = false; publish() }
        }
        schedule(hasEnabled())
    }
    override fun remove(id: String) { commands.trySend {
        db.folderRecordings().all().filter { it.folderId == id && it.state != "Imported" }.mapNotNull { it.pendingId }.forEach(importer::cancel)
        folders.removeAll { it.folder.id == id }; save(); db.folderImports().clearObservations(id); db.folderRecordings().removeFolder(id); schedule(hasEnabled())
        // 不主动释放读授权：同一目录也可能被备份功能使用。
    } }
    override fun setKind(id: String, kind: ImportKind) { change(id) { it.copy(kind = kind) } }
    override fun setEnabled(id: String, enabled: Boolean) { change(id) { it.copy(enabled = enabled) } }
    private fun change(id: String, transform: (WatchedFolder) -> WatchedFolder) { commands.trySend {
        val index = folders.indexOfFirst { it.folder.id == id }
        if (index >= 0) {
            val previous = folders[index]
            val next = transform(previous.folder)
            if (next != previous.folder) {
                folders[index] = previous.copy(folder = next)
                save()
                schedule(hasEnabled())
                if (next.enabled && next.accessible) scanLocked()
            }
        }
    } }
    override fun scanNow() { commands.trySend { if (hasEnabled()) scanLocked() } }
    internal fun foreground() {
        commands.trySend {
            val due = folders.any { (f) -> f.enabled && f.accessible &&
                (f.lastScanAt == null || clock() - (f.lastScanAt ?: 0L) >= 15 * 60_000L) }
            if (hasEnabled() && (pendingNewFiles || due)) scanLocked()
        }
    }
    internal suspend fun scan() = withContext(Dispatchers.IO) { mutex.withLock { scanLocked() } }
    private suspend fun scanLocked() {
        pendingNewFiles = false
        scanning = true; publish()
        try {
            importer.rememberQueued()
            val before = folders.sumOf { it.folder.imported }
            for (index in folders.indices) if (folders[index].folder.enabled && folders[index].folder.accessible) scanFolder(index)
            val count = folders.sumOf { it.folder.imported } - before
            if (count > 0) { lastImported = count; lastImportedAt = clock(); save() }
            recordings.pump(); updateListed()
        } finally { scanning = false; publish(); schedule(hasEnabled()) }
    }
    private suspend fun scanFolder(index: Int) {
        var setting = folders[index]
        val f = setting.folder
        val tree = Uri.parse(f.id)
        val now = clock()
        var imported = 0
        var error: String? = null
        try {
            if (!documents.granted(tree)) throw SecurityException(PERMISSION_ERROR)
            documents.root(tree)
            val files = documents.files(tree)
            val present = files.map { it.uri.toString() }.toSet()
            db.folderRecordings().all().filter { it.folderId == f.id && it.state in setOf("Listed","Queued","Missing") && it.pendingId == null }.forEach { row ->
                val next = if (row.documentUri !in present) "Missing" else if (row.state == "Missing") "Listed" else row.state
                if (next != row.state) db.folderRecordings().update(row.copy(state=next))
            }
            val dao = db.folderImports()
            var previous = dao.observations(f.id).associateBy { it.documentId }
            if (setting.baseline) {
                // 先持久化加入瞬间的文件清单，再读指纹。某个旧文件暂时读不了时，
                // 后续新文件仍是新文件；空快照也留标记，进程中断不会扩大“旧文件”范围。
                if (BASELINE_MARKER !in previous) {
                    dao.observe(files.map { FolderObservation(f.id, it.uri.toString(), it.size, it.modified, true) } +
                        FolderObservation(f.id, BASELINE_MARKER, 0, 0, true))
                    previous = dao.observations(f.id).associateBy { it.documentId }
                }
                setting = setting.copy(baseline = false)
                folders[index] = setting; save()
            }
            val observations = mutableListOf<FolderObservation>()
            previous[BASELINE_MARKER]?.let { observations += it }
            for (file in files) {
                currentCoroutineContext().ensureActive()
                val old = previous[file.uri.toString()]
                val same = old != null && old.size == file.size && old.modified == file.modified
                var row = FolderObservation(f.id, file.uri.toString(), file.size, file.modified,
                    old?.ignored == true, if (same) old?.fingerprint else null)
                try {
                    if (row.ignored && file.size > 0 && row.fingerprint == null) {
                        val key = documents.fingerprint(file)
                        dao.remember(ImportFingerprint(key, now)); row = row.copy(fingerprint = key)
                    } else if (!row.ignored && same && file.size > 0 && file.modified > 0 && file.modified < now - 120_000 && row.fingerprint == null) {
                        val key = documents.fingerprint(file)
                        if (!documents.granted(tree)) throw SecurityException(PERMISSION_ERROR)
                        if (documents.stat(file.uri) != file) throw ImportFailure("文件仍在写入，稍后重试")
                        if (!dao.contains(key)) {
                            val media = inspect(file)
                            val pending = java.util.UUID.randomUUID().toString()
                            val recording = historyStore.row(f.id,f.kind,media,now).copy(fingerprint=key,pendingId=pending,state="Queued")
                            val rowId = db.folderRecordings().insert(recording)
                            if (importer.receiveFolder(file, f.kind, key, recording.startAt, pending) { documents.stat(file.uri) == file && documents.granted(tree) }) {
                                imported++
                                if (rowId > 0) db.folderRecordings().get(rowId)?.let { db.folderRecordings().update(it.copy(state="Imported", startAt=importer.entry(pending)?.startAt ?: it.startAt,
                                    durationMillis=importer.entry(pending)?.pending?.info?.durationMillis ?: it.durationMillis)) }
                            }
                        }
                        row = row.copy(fingerprint = key)
                    }
                    if (!row.ignored && row.fingerprint == null && file.size > 0) pendingNewFiles = true
                    observations += row
                } catch (e: CancellationException) { throw e }
                catch (e: SecurityException) { throw e }
                catch (e: Exception) {
                    if (!row.ignored) pendingNewFiles = true
                    observations += row.copy(fingerprint = null)
                    error = "${file.name}：${e.message ?: "读取失败，下次扫描重试"}"
                }
            }
            db.writeTransaction { dao.clearObservations(f.id); dao.observe(observations) }
            setting = setting.copy(baseline = false, folder = f.copy(lastScanAt = now, imported = f.imported + imported, lastError = error))
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            val lost = e is SecurityException || e is java.io.FileNotFoundException || !runCatching { documents.granted(tree) }.onFailure {
                FailureNotes.record("importer/AndroidFolderImports.kt:179", it)
            }.getOrDefault(false)
            setting = setting.copy(folder = f.copy(accessible = !lost, lastScanAt = now, imported = f.imported + imported,
                lastError = if (lost) PERMISSION_ERROR else "扫描失败：${e.message ?: "请稍后重试"}"))
        } finally {
            folders[index] = setting.copy(folder = setting.folder.copy(imported = f.imported + imported))
            if (imported > 0) { lastImported = imported; lastImportedAt = now }
            save()
            if (imported > 0) runCatching { notify(f.id, f.name, imported) }.onFailure {
                FailureNotes.record("importer/AndroidFolderImports.kt:186", it)
            }
        }
    }
    /** 复用录音服务现有心跳，不创建计时器/闹钟/额外唤醒锁。 */
    internal fun heartbeat(now: Long) {
        if (state.value.folders.none { it.enabled && it.accessible }) return
        synchronized(this) {
            if (now - lastHeartbeat < 15 * 60_000) return
            lastHeartbeat = now
        }
        scanNow()
    }
    internal fun recordingChanged() { commands.trySend { schedule(hasEnabled()) } }
    companion object {
        private const val BASELINE_MARKER = "" // SAF document URI 不会为空。
        const val PERMISSION_ERROR = "文件夹读取授权已失效或目录已删除，请重新选择文件夹" }
}
