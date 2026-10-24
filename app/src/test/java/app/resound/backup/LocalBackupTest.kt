package app.resound.backup

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.work.Configuration
import androidx.work.testing.WorkManagerTestInitHelper
import app.resound.data.CaptureDatabase
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class LocalBackupTest {
    private lateinit var context: Context
    private lateinit var db: CaptureDatabase
    private lateinit var root: File
    private lateinit var folder: File
    private lateinit var files: LocalBackupFiles
    private lateinit var repository: AndroidBackupRepository
    private val tree = Uri.parse("content://test/tree/resound")
    private var granted: Uri? = null
    private var revoked = false
    private var restarted = false
    private val passphrases = object : BackupPassphrases {
        var value: String? = null
        override fun has() = value != null
        override fun read() = value.orEmpty()
        override fun write(value: String?) { this.value = value }
    }
    @Before fun setup() {
        context = RuntimeEnvironment.getApplication()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().build())
        context.getSharedPreferences("backup", 0).edit().clear().commit()
        context.deleteDatabase("capture.db")
        db = CaptureDatabase.open(context)
        db.openHelper.writableDatabase
        root = File(context.cacheDir, "local-test").apply { deleteRecursively(); mkdirs() }
        folder = File(root, "Resound").apply { mkdirs() }
        listOf("backup-restore", "before-restore").forEach { File(context.noBackupFilesDir, it).deleteRecursively() }
        files = LocalBackupFiles(context, resolveTree = {
            if (revoked) throw SecurityException("revoked") else DocumentFile.fromFile(folder)
        }, persist = { granted = it })
        repository = newRepository()
    }
    private fun newRepository(client: (() -> WebDavClient)? = null) = AndroidBackupRepository(context, db,
        passphrases = passphrases, localFiles = files, restartProcess = { restarted = true }, clientFactory = client)
    @After fun cleanup() { db.close(); root.deleteRecursively(); BackupScheduler(context).cancel() }

    @Test fun writesRetainsOnlyRecognizedEnglishFilesAndRestoresLocal() = runBlocking {
        repository.setLocalFolder(tree)
        assertEquals(tree, granted); assertEquals("Resound", repository.local.value.folder)
        assertEquals(3, repository.local.value.keep)
        val foreign = File(folder, "notes.zip").apply { writeText("keep") }
        val legacy = File(folder, "回响-2026-01-01-0310.zip").apply { writeText("keep") }
        val invalid = File(folder, "resound-2026-02-30-0310.zip").apply { writeText("keep") }
        val old = (1..4).map { File(folder, "resound-2026-01-0$it-0310.zip").apply { writeText("old") } }
        assertFalse(repository.runLocalBackup(false))
        assertEquals(0, repository.local.value.count) // 空库没有日分卷；旧备份迁移期保留
        assertTrue(old.all { it.exists() })
        assertEquals("keep", foreign.readText()); assertEquals("keep", legacy.readText()); assertEquals("keep", invalid.readText())
        assertTrue(repository.local.value.lastBytes!! > 0); assertNotNull(repository.local.value.lastAt)
        assertFalse(repository.local.value.running); assertNull(repository.local.value.lastError)
        repository.setLocalKeep(1)
        repository.setLocalAuto(true)
        assertFalse(repository.runLocalBackup(true))
        assertEquals(0, repository.local.value.count) // keep 不影响日分卷
        val reopened = newRepository()
        assertEquals(repository.local.value, reopened.local.value)
        assertEquals(5, repository.localBackups().getOrThrow().size)
        assertTrue(repository.restoreAllFromLocal().isSuccess)
        assertTrue(restarted)
        assertTrue(File(context.noBackupFilesDir, "before-restore").listFiles()!!.isNotEmpty())
    }

    @Test fun lostFolderPermissionHasStableErrorAndKeepsPreviousBackups() = runBlocking {
        repository.setLocalFolder(tree)
        repository.runLocalBackup(false)
        val existing = folder.listFiles()!!.single().readBytes()
        revoked = true
        assertFalse(repository.runLocalBackup(false))
        assertEquals(LocalBackupFiles.INACCESSIBLE, repository.local.value.lastError)
        assertTrue(repository.localBackups().isFailure)
        assertArrayEquals(existing, folder.listFiles()!!.single().readBytes())
        assertFalse(repository.local.value.running)
        repository.setLocalFolder(tree)
        assertEquals(LocalBackupFiles.INACCESSIBLE, repository.local.value.lastError)
    }

    @Test fun exportEncryptedFileDetectsContentWithoutSuffixAndRequestsPassphrase() = runBlocking {
        repository.setEncryption("test-passphrase")
        val export = File(root, "user-selected.zip")
        assertTrue(repository.exportTo(Uri.fromFile(export)).isSuccess)
        assertTrue(DayVolumes.isBundle(export)) // 单文件外层 ZIP，内部各分卷独立加密
        repository.setEncryption(null)
        assertEquals("这份备份加了密，请输入口令", repository.restoreFrom(Uri.fromFile(export)).exceptionOrNull()!!.message)
        assertTrue(db.isOpen); assertFalse(restarted)
        assertFalse(File(context.noBackupFilesDir, "before-restore").exists())
        assertTrue(repository.restoreFrom(Uri.fromFile(export), "test-passphrase").isSuccess)
        assertTrue(restarted)
        BackupRestore.applyPending(context)
        assertFalse(File(context.noBackupFilesDir, "backup-restore").exists())
    }

    @Test fun localArchiveCanBeDownloadedAndRestoredThroughCloudFlow() = runBlocking {
        repository.setLocalFolder(tree); repository.runLocalBackup(false)
        val backup = folder.listFiles()!!.single()
        val server = MockWebServer().apply { start() }
        try {
            server.enqueue(MockResponse().setResponseCode(404)) // 没有加密状态
            server.enqueue(MockResponse().setBody(okio.Buffer().write(backup.readBytes())))
            val client = WebDavClient(BackupConfig(server.url("/dav/").toString()), "改名后", "test@example.com", "test")
            val cloud = newRepository { client }
            assertTrue(cloud.restoreAllFromCloud().isSuccess)
            assertTrue(restarted)
            server.takeRequest()
            assertEquals(listOf("dav", WebDavClient.FOLDER, backup.name), server.takeRequest().requestUrl!!.pathSegments)
        } finally { server.shutdown() }
    }

    @Test fun cloudArchiveCanBeImportedAndCloudFailureDoesNotBlockLocal() = runBlocking {
        val server = MockWebServer().apply { start() }
        var uploaded: ByteArray? = null
        var fail = true
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                fail -> MockResponse().setResponseCode(503)
                request.method == "GET" || request.method == "HEAD" -> MockResponse().setResponseCode(404)
                request.method == "PROPFIND" -> MockResponse().setResponseCode(207).setBody("<d:multistatus xmlns:d='DAV:'/>")
                request.method == "PUT" -> { uploaded = request.body.readByteArray(); MockResponse().setResponseCode(201) }
                else -> MockResponse().setResponseCode(204)
            }
        }
        try {
            val cloud = newRepository { WebDavClient(BackupConfig(server.url("/dav/").toString()), "回响", "test@example.com", "test") }
            cloud.setEnabled(true); cloud.setLocalFolder(tree); cloud.setLocalAuto(true)
            assertTrue(cloud.runBackup(true))
            assertFalse(cloud.runLocalBackup(true)); assertNotNull(cloud.local.value.lastAt)
            assertNotNull(cloud.state.value.lastError); assertNull(cloud.local.value.lastError)
            fail = false; revoked = true
            cloud.runLocalBackup(true)
            assertFalse(cloud.runBackup(true)); assertNotNull(cloud.state.value.lastBackupAt)
            assertEquals(LocalBackupFiles.INACCESSIBLE, cloud.local.value.lastError)
            assertNotNull(uploaded)
            val imported = File(root, "from-cloud.zip")
            assertTrue(cloud.exportTo(Uri.fromFile(imported)).isSuccess)
            assertTrue(cloud.restoreFrom(Uri.fromFile(imported)).isSuccess)
            assertTrue(restarted)
        } finally { server.shutdown() }
    }

    @Test fun failedWriteNeverPrunesOrClearsRestoreSnapshot() = runBlocking {
        repository.setLocalFolder(tree)
        val snapshot = File(context.noBackupFilesDir, "before-restore/keep.zip").apply { parentFile!!.mkdirs(); writeText("snapshot") }
        // 无法打开目的文件时，导出失败不能冒报成功或清掉恢复快照。
        val target = File(root, "missing/backup.zip")
        assertTrue(repository.exportTo(Uri.fromFile(target)).isFailure)
        assertTrue(snapshot.exists()); assertNull(repository.local.value.lastAt)
        assertFalse(repository.local.value.running)
    }
}
