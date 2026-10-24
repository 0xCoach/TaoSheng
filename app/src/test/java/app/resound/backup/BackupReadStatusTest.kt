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
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class BackupReadStatusTest {
    private lateinit var context: Context
    private lateinit var db: CaptureDatabase
    private lateinit var folder: File
    private val passphrases = object : BackupPassphrases {
        override fun has() = false
        override fun read() = ""
        override fun write(value: String?) = Unit
    }
    @Before fun setup() {
        context = RuntimeEnvironment.getApplication()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().build())
        context.getSharedPreferences("backup",0).edit().clear().commit()
        context.deleteDatabase("capture.db")
        db = CaptureDatabase.open(context)
        db.openHelper.writableDatabase
        folder = File(context.cacheDir,"backup-status-test").apply { deleteRecursively(); mkdirs() }
    }
    @After fun cleanup() { db.close(); folder.deleteRecursively(); BackupScheduler(context).cancel() }

    @Test fun localReadsDoNotFlashRunningOrReplaceSuccessfulBackupAfterFailure() = runBlocking {
        var revoked = false
        lateinit var repo: AndroidBackupRepository
        val seen = mutableListOf<Boolean>()
        val files = LocalBackupFiles(context, resolveTree = {
            seen += repo.local.value.running
            if (revoked) throw SecurityException("synthetic revoked permission")
            DocumentFile.fromFile(folder)
        }, persist = {})
        repo = AndroidBackupRepository(context,db,passphrases=passphrases,localFiles=files)
        repo.setLocalFolder(Uri.parse("content://test/tree/one"))
        repo.runLocalBackup(false)
        val success = repo.local.value
        assertNotNull(success.lastAt); assertNull(success.lastError)
        revoked = true
        repo.runLocalBackup(false)
        val failure = repo.local.value
        assertEquals(success.lastAt,failure.lastAt)
        assertEquals(success.lastBytes,failure.lastBytes)
        assertNotNull(failure.lastError)
        seen.clear()
        assertTrue(repo.localBackups().isFailure)
        assertTrue(repo.localSummary().isFailure)
        assertEquals(failure,repo.local.value)
        revoked = false
        assertTrue(repo.localBackups().isSuccess)
        assertNotNull(repo.localSummary().getOrThrow())
        assertEquals("reading is not a successful backup",failure,repo.local.value)
        assertTrue(seen.isNotEmpty()); assertTrue(seen.none { it })
    }

    @Test fun cloudReadsPreserveStatusOnSuccessFailureAndReload() = runBlocking {
        context.getSharedPreferences("backup",0).edit().putLong("lastAt",123456L).putLong("lastBytes",999L)
            .putString("error","之前的上传失败").commit()
        val server = MockWebServer()
        var failing = true
        lateinit var repo: AndroidBackupRepository
        val seen = CopyOnWriteArrayList<BackupState>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                seen += repo.state.value
                return when {
                    failing -> MockResponse().setResponseCode(503)
                    request.method == "PROPFIND" -> MockResponse().setResponseCode(207).setBody("<d:multistatus xmlns:d='DAV:'/>")
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        try {
            val config = BackupConfig(server.url("/dav/").toString())
            fun newRepo() = AndroidBackupRepository(context,db,config,passphrases=passphrases,
                clientFactory={WebDavClient(config,"Resound","test@example.com","synthetic")})
            repo = newRepo()
            val before = repo.state.value
            assertTrue(repo.cloudSummary().isFailure)
            assertTrue(repo.remoteBackups().isFailure)
            assertEquals(before,repo.state.value)
            failing = false
            assertNull(repo.cloudSummary().getOrThrow())
            assertTrue(repo.remoteBackups().isSuccess)
            assertEquals(before,repo.state.value)
            assertEquals(before,newRepo().state.value)
            assertTrue(seen.isNotEmpty()); assertTrue(seen.none { it.running })
        } finally { server.shutdown() }
    }

    @Test fun changingFolderDoesNotClaimOldFoldersBackupAsNewFoldersSuccess() {
        context.getSharedPreferences("backup",0).edit().putString("localTree","content://test/tree/old")
            .putString("localFolderName","same-name").putLong("localLastAt",123456L).putLong("localLastBytes",900L).commit()
        val files = LocalBackupFiles(context,resolveTree={DocumentFile.fromFile(folder)},persist={})
        val repo = AndroidBackupRepository(context,db,passphrases=passphrases,localFiles=files)
        assertNotNull(repo.local.value.lastAt)
        repo.setLocalFolder(Uri.parse("content://test/tree/new"))
        assertNull(repo.local.value.lastAt); assertNull(repo.local.value.lastBytes)
    }
}
