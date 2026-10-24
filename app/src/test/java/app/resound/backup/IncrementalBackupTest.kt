package app.resound.backup

import android.app.Application
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.work.Configuration
import androidx.work.testing.WorkManagerTestInitHelper
import app.resound.data.CaptureDatabase
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.io.File
import java.util.zip.ZipFile

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class IncrementalBackupTest {
    private lateinit var context: Context
    private lateinit var db: CaptureDatabase
    private lateinit var root: File
    private var serial = 0
    private val version get() = db.openHelper.writableDatabase.version
    @Before fun setup() {
        context = RuntimeEnvironment.getApplication()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().build())
        context.getSharedPreferences("backup", 0).edit().clear().commit()
        context.deleteDatabase("capture.db")
        db = CaptureDatabase.open(context)
        val sql = db.openHelper.writableDatabase
        sql.execSQL("CREATE TABLE backup_probe(id INTEGER PRIMARY KEY, payload BLOB)")
        sql.beginTransaction()
        try {
            repeat(120) { sql.execSQL("INSERT INTO backup_probe VALUES(?,randomblob(2000))", arrayOf(it)) }
            sql.setTransactionSuccessful()
        } finally { sql.endTransaction() }
        root = File(context.cacheDir, "incremental-test").apply { deleteRecursively(); mkdirs() }
        File(context.filesDir, "backup-base").deleteRecursively()
        listOf("backup-restore", "before-restore").forEach { File(context.noBackupFilesDir, it).deleteRecursively() }
    }
    @After fun cleanup() { db.close(); root.deleteRecursively(); BackupScheduler(context).cancel() }
    private fun next(label: String) = File(root, "$label-${serial++}")
    private fun snapshot() = next("snapshot").also { BackupArchive.snapshot(db, it) }
    private fun generated(available: List<GeneratedBackup> = emptyList(), pass: String? = null, channel: String = "local") =
        generateBackup(context, db, next("generate"), pass, channel, available.map { BackupNames.metadata(it.name)!! })
    private fun info(zip: File): JSONObject = ZipFile(zip).use { archive ->
        JSONObject(archive.getInputStream(archive.getEntry("manifest.json")).bufferedReader().readText())
    }
    private fun verify(backup: GeneratedBackup, base: GeneratedBackup? = null, pass: String? = null): File {
        val target = next("verified"); val work = next("restore").apply { mkdirs() }
        IncrementalBackup.verify(backup.file, target, work, version, null, pass,
            fetchBase = { name, file -> assertEquals(base!!.name, name); base.file.copyTo(file) }, expectedName = backup.name)
        return target
    }
    private fun change(id: Int = 0) = db.openHelper.writableDatabase.execSQL("UPDATE backup_probe SET payload=randomblob(2000) WHERE id=?", arrayOf(id))

    @Test fun fewChangedPagesAreCumulativeAndReconstructExactSnapshot() {
        val base = snapshot()
        change(); val day1 = snapshot()
        val patch = next("pages")
        val first = DatabasePages.diff(base, day1, patch)
        change(100); val day2 = snapshot()
        val changed = DatabasePages.diff(base, day2, patch)
        assertTrue(changed.size < DatabasePages.header(day2).pages / 10)
        assertTrue(changed.containsAll(first))
        val header = DatabasePages.header(day2)
        val metadata = JSONObject().put("pageSize", header.pageSize).put("totalPages", header.pages)
            .put("changedPages", org.json.JSONArray(changed)).put("resultSha256", BackupArchive.sha(day2))
        val restored = next("restored")
        DatabasePages.apply(base, patch, restored, metadata)
        assertEquals(BackupArchive.sha(day2), BackupArchive.sha(restored))
    }

    @Test fun growingAndShrinkingDatabaseRestoreExactLengthAndHash() {
        val base = snapshot()
        db.openHelper.writableDatabase.execSQL("INSERT INTO backup_probe VALUES(999,randomblob(100000))")
        val longer = snapshot()
        db.openHelper.writableDatabase.execSQL("DELETE FROM backup_probe WHERE id > 10")
        db.openHelper.writableDatabase.execSQL("VACUUM")
        val shorter = snapshot()
        assertTrue(longer.length() > base.length()); assertTrue(shorter.length() < base.length())
        for (today in listOf(longer, shorter)) {
            val patch = next("patch"); val pages = DatabasePages.diff(base, today, patch)
            val h = DatabasePages.header(today)
            val meta = JSONObject().put("pageSize", h.pageSize).put("totalPages", h.pages)
                .put("changedPages", org.json.JSONArray(pages)).put("resultSha256", BackupArchive.sha(today))
            val result = next("result"); DatabasePages.apply(base, patch, result, meta)
            assertEquals(today.length(), result.length()); assertEquals(BackupArchive.sha(today), BackupArchive.sha(result))
        }
    }

    @Test fun ageSchemaLostCorruptBaseAndLargeDeltaStartNewFullSet() {
        fun base() = generated().also { it.committed() }
        var full = base()
        val cache = File(context.filesDir, "backup-base")
        var meta = File(cache, "${full.name}.json")
        meta.writeText(JSONObject(meta.readText()).put("createdAt", full.at - 7L * 86400000).toString())
        assertTrue(BackupNames.metadata(generated(listOf(full)).name)!!.full)
        full = base(); File(cache, "${full.name}.db").delete()
        assertTrue(BackupNames.metadata(generated(listOf(full)).name)!!.full)
        full = base(); File(cache, "${full.name}.db").appendBytes(byteArrayOf(1))
        assertTrue(BackupNames.metadata(generated(listOf(full)).name)!!.full)
        full = base()
        db.openHelper.writableDatabase.execSQL("PRAGMA user_version=${version + 1}")
        assertTrue(BackupNames.metadata(generated(listOf(full)).name)!!.full)
        full = base()
        db.openHelper.writableDatabase.execSQL("UPDATE backup_probe SET payload=randomblob(2000)")
        assertTrue(BackupNames.metadata(generated(listOf(full)).name)!!.full)
    }

    @Test fun destinationMustContainItsOwnCommittedFullAndFailedGenerationDoesNotReplaceIt() {
        val local = generated().also { it.committed() }
        assertFalse(BackupNames.metadata(generated(listOf(local)).name)!!.full)
        assertTrue(BackupNames.metadata(generated(emptyList()).name)!!.full)
        val cloud = generated(channel = "cloud").also { it.committed() }
        assertNotEquals(local.name, cloud.name)
        assertFalse(BackupNames.metadata(generated(listOf(local)).name)!!.full)
        assertFalse(BackupNames.metadata(generated(listOf(cloud), channel = "cloud").name)!!.full)
        assertEquals(2, File(context.filesDir, "backup-base").listFiles()!!.count { it.extension == "db" })
    }

    @Test fun featureAddModifyDeleteAndCumulativeDaysRestoreWithoutIntermediate() {
        val features = File(context.applicationInfo.dataDir, BackupArchive.FEATURES).apply { mkdirs() }
        val a = File(features, "a".repeat(64)).apply { writeText("before") }
        val b = File(features, "b".repeat(64)).apply { writeText("deleted") }
        val full = generated().also { it.committed() }
        a.writeText("after"); b.delete(); File(features, "c".repeat(64)).writeText("new")
        change(); val yesterday = generated(listOf(full)); assertFalse(BackupNames.metadata(yesterday.name)!!.full)
        change(99); val today = generated(listOf(full))
        yesterday.file.delete()
        val verified = verify(today, full)
        assertEquals("after", File(verified, BackupArchive.FEATURES + a.name).readText())
        assertFalse(File(verified, BackupArchive.FEATURES + b.name).exists())
        assertEquals("new", File(verified, BackupArchive.FEATURES + "c".repeat(64)).readText())
        assertEquals(info(today.file).getString("resultSha256"), BackupArchive.sha(File(verified, BackupArchive.DATABASE)))
    }

    @Test fun wrongOrMissingBaseRejectedBeforeAnyLiveDatabaseReplacement() {
        val full = generated().also { it.committed() }; change(); val delta = generated(listOf(full))
        change(100); val wrong = generated()
        val target = next("verified")
        val work = next("work").apply { mkdirs() }
        try {
            IncrementalBackup.verify(delta.file, target, work, version, null, null, { _, file -> wrong.file.copyTo(file) })
            fail()
        } catch (e: BackupFailure) { assertEquals(IncrementalBackup.MISSING_BASE, e.human) }
        assertFalse(target.exists()); assertTrue(db.isOpen)
        try {
            IncrementalBackup.verify(delta.file, target, next("work").apply { mkdirs() }, version, null, null)
            fail()
        } catch (e: BackupFailure) { assertEquals(IncrementalBackup.MISSING_BASE, e.human) }
    }

    @Test fun encryptedFullAndEncryptedDeltaRestoreTogether() {
        val full = generated(pass = "test-pass").also { it.committed() }
        change(); val delta = generated(listOf(full), pass = "test-pass")
        assertFalse(BackupNames.metadata(delta.name)!!.full)
        assertTrue(BackupEncryption.isEncrypted(full.file)); assertTrue(BackupEncryption.isEncrypted(delta.file))
        assertTrue(File(verify(delta, full, "test-pass"), BackupArchive.DATABASE).exists())
    }

    @Test fun namesAndRetentionKeepDependenciesAndTreatLegacyAsFull() {
        val full = BackupNames.metadata(BackupNames.create(1000000000000, true))!!
        val inc = BackupNames.metadata(BackupNames.create(full.at + 10000, true, full.name))!!
        val newer = BackupNames.metadata(BackupNames.create(full.at + 20000, false))!!
        val old = BackupNames.metadata("回响-2000-01-01-0310.zip")!!
        assertFalse(inc.full); assertEquals(full.name, inc.baseName); assertTrue(old.full)
        assertEquals(setOf(full.name, inc.name, newer.name), BackupRetention.names(listOf(old, inc, newer, full), 2))
        assertNull(BackupNames.metadata("resound-inc-2026-10-24-0310-00000-12345678.zip"))
    }

    @Test fun tamperedPageFailsResultHashAndNeverStagesRestore() {
        val full = generated().also { it.committed() }; change(); val delta = generated(listOf(full))
        val corrupt = next("corrupt.zip")
        ZipFile(delta.file).use { input -> java.util.zip.ZipOutputStream(corrupt.outputStream()).use { out ->
            input.entries().asSequence().forEach { entry ->
                out.putNextEntry(java.util.zip.ZipEntry(entry.name))
                val bytes = input.getInputStream(entry).readBytes()
                if (entry.name == "database.pages") bytes[100] = (bytes[100].toInt() xor 1).toByte()
                out.write(bytes); out.closeEntry()
            }
        } }
        try { verify(delta.copy(file = corrupt), full); fail() } catch (e: BackupFailure) {
            assertEquals("备份校验失败，未恢复数据", e.human)
        }
        assertFalse(File(context.noBackupFilesDir, "backup-restore").exists()); assertTrue(db.isOpen)
    }

    @Test fun localRepositoryWritesRetainsAndAutomaticallyRestoresIncrementalPair() = runBlocking {
        val folder = next("folder").apply { mkdirs() }
        val local = LocalBackupFiles(context, { DocumentFile.fromFile(folder) }, {})
        var restarted = false
        val repository = AndroidBackupRepository(context, db, localFiles = local, restartProcess = { restarted = true })
        repository.setLocalFolder(Uri.parse("content://test/tree")); repository.setLocalKeep(1)
        // 新写入入口已经改用日分卷；这里显式放入旧 full/inc，验证旧恢复入口。
        val full = generated().also { it.committed() }; change(); val delta = generated(listOf(full))
        local.write(Uri.parse("content://test/tree"), full); local.write(Uri.parse("content://test/tree"), delta)
        val backups = repository.localBackups().getOrThrow()
        assertEquals(2, backups.size); assertFalse(backups.first().full)
        assertTrue(repository.restoreLocal(backups.first().name).isSuccess); assertTrue(restarted)
        BackupRestore.applyPending(context)
    }

    @Test fun cloudRepositoryUsesSameGenerationRetentionAndPairRestore() = runBlocking {
        val server = MockWebServer().apply { start() }
        val storage = linkedMapOf<String, ByteArray>(); val gets = mutableListOf<String>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val name = request.requestUrl!!.pathSegments.last()
                return when (request.method) {
                    "PROPFIND" -> if (request.requestUrl!!.pathSegments.contains("回响备份")) MockResponse().setResponseCode(404)
                    else MockResponse().setResponseCode(207).setBody("<d:multistatus xmlns:d='DAV:'>" + storage.filterKeys { BackupNames.metadata(it) != null }.map { (key, data) ->
                        "<d:response><d:href>/dav/Resound-Backup/$key</d:href><d:propstat><d:prop><d:getcontentlength>${data.size}</d:getcontentlength></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>"
                    }.joinToString("") + "</d:multistatus>")
                    "PUT" -> { storage[name] = request.body.readByteArray(); MockResponse().setResponseCode(201) }
                    "MOVE" -> { storage[request.getHeader("Destination")!!.substringAfterLast('/')] = storage.remove(name)!!; MockResponse().setResponseCode(201) }
                    "DELETE" -> { storage.remove(name); MockResponse().setResponseCode(204) }
                    "GET" -> { gets += name; storage[name]?.let { MockResponse().setBody(okio.Buffer().write(it)) } ?: MockResponse().setResponseCode(404) }
                    else -> MockResponse().setResponseCode(500)
                }
            }
        }
        try {
            val client = WebDavClient(BackupConfig(server.url("/dav/").toString()), "回响", "test@example.com", "test")
            var restarted = false
            val repository = AndroidBackupRepository(context, db, clientFactory = { client }, restartProcess = { restarted = true })
            val full = generated().also { it.committed() }; change(); val delta = generated(listOf(full))
            storage[full.name] = full.file.readBytes(); storage[delta.name] = delta.file.readBytes()
            val backups = repository.remoteBackups().getOrThrow()
            assertEquals(2, backups.size); assertFalse(backups.first().full)
            assertEquals(full.name, backups.first().baseName)
            assertTrue(repository.restore(backups.first().name).isSuccess); assertTrue(restarted)
            assertEquals(listOf(delta.name, full.name), gets)
        } finally { server.shutdown() }
    }
}
