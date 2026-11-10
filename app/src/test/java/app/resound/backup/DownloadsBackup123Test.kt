package app.resound.backup

import android.app.Application
import android.content.ContentProvider
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.os.Bundle
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import androidx.work.Configuration
import androidx.work.testing.WorkManagerTestInitHelper
import app.resound.data.CaptureDatabase
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import org.robolectric.shadows.ShadowContentResolver
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class DownloadsBackup123Test {
    private lateinit var context: Context
    private lateinit var db: CaptureDatabase
    private lateinit var root: File
    private lateinit var provider: DownloadsProvider123
    private lateinit var store: DownloadsBackupFiles
    private var restarted = false
    private fun work() = File(root, UUID.randomUUID().toString()).apply { mkdirs() }
    private val passphrases = object : BackupPassphrases {
        override fun has() = false
        override fun read() = ""
        override fun write(value: String?) = Unit
    }
    private fun repository(files: LocalBackupFiles = LocalBackupFiles(context)) = AndroidBackupRepository(
        context, db, passphrases = passphrases, localFiles = files, restartProcess = { restarted = true })
    @Before fun setup() {
        context = RuntimeEnvironment.getApplication()
        root = File(context.cacheDir, "downloads123").apply { deleteRecursively(); mkdirs() }
        (BackupSettings.prefs + BackupSettings.stores.keys + "backup-diagnostics").forEach {
            context.getSharedPreferences(it, 0).edit().clear().commit()
        }
        listOf("backup-restore", "before-restore").forEach { File(context.noBackupFilesDir, it).deleteRecursively() }
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().build())
        context.deleteDatabase("capture.db")
        db = CaptureDatabase.open(context)
        db.openHelper.writableDatabase.execSQL("INSERT INTO ask_conversations(id,createdAt,title) VALUES(1,1792800000000,'before')")
        provider = DownloadsProvider123(File(root, "provider").apply { mkdirs() })
        provider.attachInfo(context, ProviderInfo().apply { authority = "media" })
        ShadowContentResolver.registerProviderInternal("media", provider)
        store = DownloadsBackupFiles(context)
        provider.backupRoot = store.rootPath
    }
    @After fun cleanup() { db.close(); provider.shutdown(); root.deleteRecursively(); BackupScheduler(context).cancel() }
    private fun title(verified: File): String {
        val restored = CaptureDatabase.open(context, File(verified, BackupArchive.DATABASE).path)
        return try { restored.openHelper.writableDatabase.query("SELECT title FROM ask_conversations WHERE id=1").use {
            assertTrue(it.moveToFirst()); it.getString(0)
        } } finally { restored.close() }
    }
    @Test fun downloadsBackupListsAndRestoresSelectedPointWithSettings() = runBlocking {
        val repo = repository()
        repo.useDownloadsFolder()
        assertEquals("下载/备份", repo.local.value.folder)
        assertEquals(LocalFolderAccess.Ok, repo.local.value.folderAccess)
        assertFalse(repo.local.value.sessionOnly)
        repo.setLocalAuto(true)
        repo.runLocalBackup(true)
        assertNull(repo.local.value.lastError)
        val point = repo.localBackups().getOrThrow().single()
        db.openHelper.writableDatabase.execSQL("UPDATE ask_conversations SET title='after'")
        assertTrue(repo.restoreLocal(point.name).isSuccess)
        assertTrue(restarted)
        // 走完整应用恢复入口；恢复前先改变当前选择，确认备份里的选择确实生效。
        context.getSharedPreferences("backup", 0).edit().putBoolean("localDownloads", false).commit()
        BackupRestore.applyPending(context)
        db = CaptureDatabase.open(context)
        db.openHelper.writableDatabase.query("SELECT title FROM ask_conversations WHERE id=1").use {
            assertTrue(it.moveToFirst()); assertEquals("before", it.getString(0))
        }
        assertEquals("下载/备份", repository().local.value.folder)
        assertTrue(repository().local.value.auto)
    }
    @Test fun retentionDeletesOnlyOwnedRecognizedFiles() = runBlocking {
        val foreign = provider.seed("history/resound-point-1792800000000-1-aaaaaaaaaaaa-p.json", "other.app")
        val otherFolder = provider.seed("history/resound-point-1792800000001-1-bbbbbbbbbbbb-p.json", context.packageName, "Download/elsewhere/")
        val unrelated = provider.seed("notes.zip", context.packageName)
        val legacy = provider.seed("resound-2026-01-01-0310.zip", context.packageName)
        val repo = repository().apply { useDownloadsFolder(); setLocalKeep(3) }
        repeat(4) { repo.runLocalBackup(false); assertNull(repo.local.value.lastError) }
        assertEquals(3, repo.localBackups().getOrThrow().size)
        assertTrue(provider.has(foreign)); assertTrue(provider.has(otherFolder)); assertTrue(provider.has(unrelated))
        assertFalse(provider.has(legacy))
    }
    @Test fun dailyVolumesWriteReadReplaceAndRestoreAll() = runBlocking {
        val repo = repository().apply { useDownloadsFolder() }
        DayVolumes.sync(context, db, store, work(), null)
        assertTrue(store.exists("days/resound-day-2026-10-24.zip"))
        db.openHelper.writableDatabase.execSQL("UPDATE ask_conversations SET title='changed'")
        DayVolumes.sync(context, db, store, work(), null)
        val target = File(work(), "verified")
        DayVolumes.restore(context, store, work(), target, null, null)
        assertEquals("changed", title(target))
        assertEquals(1, repo.localSummary().getOrThrow()!!.days)
        assertTrue(repo.restoreAllFromLocal().isSuccess)
        assertTrue(restarted)
    }
    @Test fun choosingTreeAgainAndRestoringOldTreeSettingsKeepOriginalBehavior() {
        val treeFolder = work()
        val tree = Uri.parse("content://test/tree/folder")
        val repo = repository(LocalBackupFiles(context, { DocumentFile.fromFile(treeFolder) }, {}, { true }))
        repo.useDownloadsFolder(); repo.setLocalFolder(tree)
        assertEquals(treeFolder.name, repo.local.value.folder)
        assertFalse(context.getSharedPreferences("backup", 0).getBoolean("localDownloads", true))
        // 模拟 0.2.19 只存 localTree 的备份。
        context.getSharedPreferences("backup", 0).edit().remove("localDownloads").commit()
        val captured = work(); BackupSettings.capture(context, captured)
        repo.useDownloadsFolder(); BackupSettings.apply(context, captured)
        val restored = repository(LocalBackupFiles(context, { DocumentFile.fromFile(treeFolder) }, {}, { true }))
        assertEquals(treeFolder.name, restored.local.value.folder)
        assertEquals(LocalFolderAccess.Ok, restored.local.value.folderAccess)
    }
    @Test fun newInstallationSeesEmptyListWithoutError() = runBlocking {
        val repo = repository().apply { useDownloadsFolder() }
        repo.runLocalBackup(false); assertTrue(repo.localBackups().getOrThrow().isNotEmpty())
        // 系统卸载后撤销旧安装文件的所有权，文件本身仍留在下载目录。
        provider.forgetOwners()
        File(context.noBackupFilesDir, "downloads-backup-installation").delete()
        val fresh = repository()
        assertTrue(fresh.localBackups().getOrThrow().isEmpty())
        assertNull(fresh.localSummary().getOrThrow()); assertNull(fresh.local.value.lastError)
        assertEquals(LocalFolderAccess.Ok, fresh.local.value.folderAccess)
        fresh.runLocalBackup(false)
        assertNull(fresh.local.value.lastError)
        assertEquals(1, fresh.localBackups().getOrThrow().size)
    }
    @Test fun failedPublishKeepsOldVolumeAndCleansPendingFile() {
        val path = "resound-state.zip"
        val source = File(work(), "input").apply { writeText("old") }
        store.put(path, source)
        source.writeText("new"); provider.failPublish = true
        assertTrue(runCatching { store.put(path, source) }.isFailure)
        val target = File(work(), "output")
        assertTrue(store.read(path, target)); assertEquals("old", target.readText())
        assertEquals(0, provider.pendingCount())
        store.copy(path, "days/resound-day-2026-10-24.zip")
        store.delete(path); assertFalse(store.exists(path))
    }
    @Test fun treeProviderErrorsKeepTheActualOperationStep() {
        context.applicationInfo.targetSdkVersion = 35
        val broken = object : ContentProvider() {
            override fun onCreate() = true
            private fun rejected(): Nothing = throw IllegalStateException("bridge rejected content://tree123/private/" + "a".repeat(80))
            override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor = rejected()
            override fun call(method: String, arg: String?, extras: Bundle?): Bundle = rejected()
            override fun insert(uri: Uri, values: ContentValues?): Uri = rejected()
            override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?): Int = rejected()
            override fun delete(uri: Uri, selection: String?, args: Array<out String>?): Int = rejected()
            override fun getType(uri: Uri): String = rejected()
        }
        broken.attachInfo(context, ProviderInfo().apply { authority = "tree123" })
        ShadowContentResolver.registerProviderInternal("tree123", broken)
        val tree = Uri.parse("content://tree123/tree/root")
        val doc = BackupTreeDocument(context, DocumentFile.fromTreeUri(context, tree)!!)
        val operations: Map<String, () -> Unit> = mapOf(
            "resolve-directory" to { doc.isDirectory; Unit },
            "create-probe" to { doc.createFile("application/octet-stream", "resound-probe-test.tmp"); Unit },
            "rename" to { doc.renameTo("renamed"); Unit },
            "delete" to { doc.delete(); Unit },
            "list-directory" to { doc.listFiles(); Unit },
            "persist" to { LocalBackupFiles(context, persist = { throw IllegalStateException("persist bridge rejected") }).select(tree); Unit },
        )
        operations.forEach { (step, action) ->
            context.getSharedPreferences("backup-diagnostics", 0).edit().clear().commit()
            assertTrue(step, runCatching(action).isFailure)
            val log = BackupDiagnostics.dump(context)
            assertTrue(log, log.contains("$step IllegalStateException"))
            assertFalse(log.contains("content://"))
        }
    }
    @Test fun diagnosticKeepsStepAndReasonButRedactsAddressesAndCredentials() {
        val address = "content://provider/tree/" + "abcdef".repeat(50)
        val message = "provider bridge rejected $address token=short-key Authorization: Bearer abc123456 /storage/private/file " + "x".repeat(250)
        val files = LocalBackupFiles(context, { throw IllegalStateException(message) }, {}, { true })
        val repo = repository(files); repo.setLocalFolder(Uri.parse("content://test/tree/folder"))
        val log = BackupDiagnostics.dump(context)
        assertTrue(log.contains("resolve-directory IllegalStateException provider bridge rejected"))
        assertFalse(log.contains(address)); assertFalse(log.contains("short-key")); assertFalse(log.contains("abc123456"))
        assertFalse(log.contains("/storage")); assertTrue(BackupDiagnostics.safeReason(IllegalStateException(message)).length <= 200)
        assertEquals("这个文件夹在本机用不了，可以改存到“下载/备份”", repo.local.value.lastError)
    }
}

/** 用真实 ContentResolver 流和 SQL selection 验证 MediaStore 适配；不冒充设备上的系统提供方。 */
private class DownloadsProvider123(private val root: File) : ContentProvider() {
    private lateinit var db: SQLiteDatabase
    var failPublish = false
    var backupRoot = DownloadsBackupFiles.ROOT
    private val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    override fun onCreate(): Boolean {
        db = SQLiteDatabase.create(null)
        db.execSQL("CREATE TABLE downloads (_id INTEGER PRIMARY KEY, _display_name TEXT, relative_path TEXT, owner_package_name TEXT, is_pending INTEGER, _size INTEGER DEFAULT 0, date_modified INTEGER, mime_type TEXT)")
        return true
    }
    private fun id(uri: Uri) = uri.lastPathSegment?.toLongOrNull()
    private fun data(uri: Uri) = File(root, ContentUris.parseId(uri).toString())
    private fun where(uri: Uri, selection: String?): String? = listOfNotNull(id(uri)?.let { "_id = $it" }, selection?.let { "($it)" }).joinToString(" AND ").ifEmpty { null }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        root.listFiles().orEmpty().forEach { db.execSQL("UPDATE downloads SET _size=? WHERE _id=?", arrayOf<Any>(it.length(), it.name)) }
        return db.query("downloads", projection, where(uri, selection), selectionArgs, null, null, sortOrder)
    }
    override fun insert(uri: Uri, values: ContentValues?): Uri {
        assertEquals(collection, uri)
        val v = ContentValues(values!!).apply {
            put("owner_package_name", context!!.packageName); put("date_modified", System.currentTimeMillis() / 1000)
        }
        return ContentUris.withAppendedId(collection, db.insertOrThrow("downloads", null, v)).also { data(it).writeBytes(byteArrayOf()) }
    }
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int {
        if (failPublish && values?.getAsString("_display_name") == "resound-state.zip") {
            failPublish = false; throw IllegalStateException("simulated publish failure")
        }
        return db.update("downloads", values, where(uri, selection), selectionArgs)
    }
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
        // 生产代码只能通过已经筛选出的单文件 URI 删除。
        check(id(uri) != null)
        return db.delete("downloads", where(uri, selection), selectionArgs).also { if (it > 0) data(uri).delete() }
    }
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor = ParcelFileDescriptor.open(data(uri), ParcelFileDescriptor.parseMode(mode))
    override fun getType(uri: Uri) = "application/octet-stream"
    fun seed(path: String, owner: String, base: String = backupRoot): Uri {
        val uri = insert(collection, ContentValues().apply {
            put("_display_name", path.substringAfterLast('/'))
            put("relative_path", base + path.substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" })
            put("is_pending", 0)
        })
        db.execSQL("UPDATE downloads SET owner_package_name=? WHERE _id=?", arrayOf(owner, ContentUris.parseId(uri)))
        data(uri).writeText("keep")
        return uri
    }
    fun has(uri: Uri) = db.rawQuery("SELECT _id FROM downloads WHERE _id=?", arrayOf(ContentUris.parseId(uri).toString())).use { it.moveToFirst() }
    fun forgetOwners() = db.execSQL("UPDATE downloads SET owner_package_name=NULL")
    fun pendingCount() = db.rawQuery("SELECT count(*) FROM downloads WHERE is_pending=1", null).use { it.moveToFirst(); it.getInt(0) }
    override fun shutdown() { db.close() }
}
