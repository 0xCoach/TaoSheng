package app.resound.backup

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.documentfile.provider.TestFolder102
import androidx.work.Configuration
import androidx.work.testing.WorkManagerTestInitHelper
import app.resound.data.CaptureDatabase
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileNotFoundException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, application = Application::class)
class LocalBackupAccess102Test {
    private lateinit var context: Context
    private lateinit var root: File
    private lateinit var db: CaptureDatabase
    private val tree = Uri.parse("content://provider/tree/private-folder")
    @Before fun setup() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("backup", 0).edit().clear().commit()
        context.getSharedPreferences("backup-diagnostics", 0).edit().clear().commit()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().build())
        db = CaptureDatabase.open(context)
        root = File(context.cacheDir, "access102").apply { deleteRecursively(); mkdirs() }
    }
    @After fun cleanup() { db.close(); root.deleteRecursively(); BackupScheduler(context).cancel() }
    private fun repository(files: LocalBackupFiles) = AndroidBackupRepository(context, db, localFiles = files,
        passphrases = object : BackupPassphrases {
            override fun has() = false
            override fun read() = ""
            override fun write(value: String?) = Unit
        })
    @Test fun unsupportedWtFallsBackToWAndVerifiesExport() {
        val modes = mutableListOf<String>()
        val target = File(root, "target")
        val source = File(root, "source").apply { writeText("backup") }
        val files = LocalBackupFiles(context, openOutput = { _, mode ->
            modes += mode
            if (mode == "wt") throw FileNotFoundException("Unsupported mode wt content://private/document/secret")
            target.outputStream()
        })
        files.export(source, Uri.fromFile(target))
        assertEquals(listOf("wt", "w"), modes)
        assertEquals("backup", target.readText())
    }
    @Test fun inaccurateCanWriteDoesNotRejectWorkingFolderAndPickerRequestsFlags() {
        val folder = TestFolder102(DocumentFile.fromFile(root))
        assertFalse(folder.canWrite())
        val files = LocalBackupFiles(context, { folder }, {}, { true })
        repository(files).let { repo ->
            repo.setLocalFolder(tree)
            assertEquals(LocalFolderAccess.Ok, repo.local.value.folderAccess)
            assertNull(repo.local.value.lastError)
        }
        assertTrue(root.listFiles()!!.isEmpty())
        val flags = LocalBackupFolderContract().createIntent(context, null).flags
        val expected = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
            Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
        assertEquals(expected, flags and expected)
    }
    @Test fun temporaryGrantWorksButNewProcessRequiresAuthorization() {
        fun files() = LocalBackupFiles(context, { DocumentFile.fromFile(root) },
            { throw SecurityException("No persistable permission grants") }, { false })
        val repo = repository(files())
        repo.setLocalFolder(tree)
        assertEquals(LocalFolderAccess.Ok, repo.local.value.folderAccess)
        assertTrue(repo.local.value.sessionOnly)
        val restarted = repository(files())
        assertEquals(LocalFolderAccess.NeedsReauthorize, restarted.local.value.folderAccess)
        assertEquals(LocalBackupFiles.REAUTHORIZE, restarted.local.value.lastError)
    }
    @Test fun lostPersistedGrantAndSecurityExceptionRequireAuthorization() {
        var granted = true
        val files = LocalBackupFiles(context, { DocumentFile.fromFile(root) }, {}, { granted })
        val repo = repository(files)
        repo.setLocalFolder(tree)
        granted = false
        repo.setLocalKeep(3)
        assertEquals(LocalFolderAccess.NeedsReauthorize, repo.local.value.folderAccess)
        assertEquals(LocalBackupFiles.REAUTHORIZE, repo.local.value.lastError)
        val denied = repository(LocalBackupFiles(context, { throw SecurityException("permission denied") }, {}, { true }))
        denied.setLocalFolder(tree)
        assertEquals(LocalFolderAccess.NeedsReauthorize, denied.local.value.folderAccess)
    }
    @Test fun unwritableLocationAndExhaustedModesAreUnsupported() {
        val repo = repository(LocalBackupFiles(context, { TestFolder102(DocumentFile.fromFile(root), true) }, {}, { true }))
        repo.setLocalFolder(tree)
        assertEquals(LocalFolderAccess.Unsupported, repo.local.value.folderAccess)
        assertEquals(LocalBackupFiles.UNSUPPORTED, repo.local.value.lastError)
        val modes = mutableListOf<String>()
        val files = LocalBackupFiles(context, { DocumentFile.fromFile(root) }, {}, { true }, openOutput = { _, mode -> modes += mode; null })
        val exhausted = repository(files)
        exhausted.setLocalFolder(tree)
        assertEquals(listOf("wt", "w", "rwt"), modes)
        assertEquals(LocalFolderAccess.Unsupported, exhausted.local.value.folderAccess)
        assertTrue(root.listFiles()!!.isEmpty())
    }
    @Test fun diagnosticsContainOnlyClassAndRecognizedReason() {
        val repo = repository(LocalBackupFiles(context, { throw SecurityException("permission denied content://secret/tree/name /storage/private/customer.txt customer content") }, {}, { true }))
        repo.setLocalFolder(tree)
        val log = BackupDiagnostics.dump(context)
        assertTrue(log.contains("SecurityException permission denied"))
        assertTrue(log.contains("setLocalFolder"))
        for (secret in listOf("content://", "/storage", "customer", "private", "secret")) assertFalse(log.contains(secret))
    }
    @Test fun permissionErrorDoesNotTryOtherModes() {
        val modes = mutableListOf<String>()
        val source = File(root, "source").apply { writeText("backup") }
        val files = LocalBackupFiles(context, openOutput = { _, mode -> modes += mode; throw SecurityException("permission denied") })
        val error = runCatching { files.export(source, tree) }.exceptionOrNull()
        assertEquals(listOf("wt"), modes)
        assertEquals("本机备份写入失败，请检查目标位置和授权", error?.message)
    }
    @Test fun writtenButUnreadableHasDifferentErrorAndDoesNotClaimSuccess() {
        val source = File(root, "source").apply { writeText("backup") }
        val target = File(root, "target")
        val files = LocalBackupFiles(context, openInput = { throw FileNotFoundException("read denied") })
        val error = runCatching { files.export(source, Uri.fromFile(target)) }.exceptionOrNull()
        assertEquals("backup", target.readText())
        assertEquals("备份已写入，但无法读回校验，请换一个位置重试", error?.message)
    }
}
