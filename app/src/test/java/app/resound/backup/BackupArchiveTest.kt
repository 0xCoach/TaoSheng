package app.resound.backup

import app.resound.data.open

import app.resound.data.testSql
import android.app.Application
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import app.resound.data.CaptureDatabase
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.io.File
import kotlinx.coroutines.*
import app.resound.data.writeTransaction
import app.resound.data.writeSql
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class BackupArchiveTest {
    private lateinit var context: Context
    private lateinit var db: CaptureDatabase
    private lateinit var root: File
    @Before fun setup() {
        context = RuntimeEnvironment.getApplication()
        context.deleteDatabase("capture.db")
        db = Room.databaseBuilder(context, CaptureDatabase::class.java, "capture.db").setDriver(androidx.sqlite.driver.AndroidSQLiteDriver()).allowMainThreadQueries().build()
        db.testSql.version
        root = File(context.cacheDir, "backup-test").apply { deleteRecursively(); mkdirs() }
        File(context.noBackupFilesDir, "backup-restore").deleteRecursively()
    }
    @After fun close() { db.close(); root.deleteRecursively() }
    private fun archive(): File {
        val stage = File(root, "content").apply { mkdirs() }
        return File(root, "backup.zip").also { BackupArchive.create(context, db, stage, it, 12345) }
    }
    @Test fun previousVersionArchiveVerifiesAndMigratesWithoutLosingVoiceprints() {
        val stage = File(root,"previous").apply { mkdirs() }
        val file = File(stage,BackupArchive.DATABASE).apply { parentFile!!.mkdirs() }
        val schema = org.json.JSONObject(File("../shared/schemas/app.resound.data.CaptureDatabase/8.json").readText()).getJSONObject("database")
        SQLiteDatabase.openOrCreateDatabase(file,null).use { old ->
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val e = entities.getJSONObject(i)
                fun sql(value: String) = value.replace("\u0024{TABLE_NAME}",e.getString("tableName"))
                old.execSQL(sql(e.getString("createSql")))
                val indices = e.optJSONArray("indices") ?: org.json.JSONArray()
                for (j in 0 until indices.length()) old.execSQL(sql(indices.getJSONObject(j).getString("createSql")))
            }
            val setup = schema.getJSONArray("setupQueries")
            for (i in 0 until setup.length()) old.execSQL(setup.getString(i))
            old.execSQL("INSERT INTO voiceprint_samples(source,vector,durationMillis,createdAt) VALUES('enroll',?,30000,1)",arrayOf(ByteArray(768)))
            old.version=8
        }
        val zip = File(root,"previous.zip")
        BackupArchive.pack(stage,zip,12345,"0.1.6",8)
        val verified=File(root,"verified-previous")
        BackupArchive.verify(zip,verified,db.testSql.version)
        val restored=CaptureDatabase.open(context, File(verified,BackupArchive.DATABASE).path)
        try {
            assertEquals(db.testSql.version,restored.testSql.version)
            kotlinx.coroutines.runBlocking { assertEquals(0L,restored.voiceprint().samples().single().personId) }
        } finally { restored.close() }
    }

    @Test fun archiveExcludesAllSecretsAudioAndUnknownSettingsAndRestoresVerifiedContent() {
        listOf("capture", "transcribe", "digest", "diary", "push", "backup-secrets", "digest-secrets", "asr-secrets", "unknown").forEach {
            context.getSharedPreferences(it, 0).edit().putString("test", it).commit()
        }
        val features = File(context.getDatabasePath("capture.db").path + ".voiceprint-features").apply { mkdirs() }
        File(features, "a".repeat(64)).writeText("feature")
        File(context.filesDir, "audio.ogg").writeText("audio")
        val zip = archive()
        ZipFile(zip).use { file ->
            val names = file.entries().asSequence().map { it.name }.toSet()
            assertTrue(BackupArchive.DATABASE in names)
            assertTrue(BackupArchive.FEATURES + "a".repeat(64) in names)
            assertEquals(BackupArchive.safePrefs, names.filter { it.startsWith("shared_prefs/") }.map { it.substringAfter('/') }.toSet())
            val hotwords = file.getInputStream(file.getEntry("shared_prefs/transcribe.xml")).bufferedReader().readText()
            assertTrue(hotwords.contains("hotwords")); assertFalse(hotwords.contains("test"))
            assertFalse(names.any { "secret" in it || "audio" in it || "unknown" in it })
        }
        val verified = File(root, "verified")
        BackupArchive.verify(zip, verified, db.testSql.version)
        db.close()
        BackupRestore.stage(context, verified)
        BackupRestore.applyPending(context)
        assertEquals("digest-secrets", context.getSharedPreferences("digest-secrets", 0).getString("test", null))
        assertTrue(File(features, "a".repeat(64)).exists())
        BackupRestore.applyPending(context) // 已完成的恢复不会再次覆盖。
    }
    @Test fun tamperingAndTraversalAreRejectedBeforeReplacement() {
        val original = archive()
        val tampered = File(root, "tampered.zip")
        ZipFile(original).use { input -> ZipOutputStream(tampered.outputStream()).use { output ->
            input.entries().asSequence().forEach { entry ->
                output.putNextEntry(ZipEntry(entry.name))
                if (entry.name == BackupArchive.DATABASE) output.write("bad".toByteArray())
                else input.getInputStream(entry).use { it.copyTo(output) }
                output.closeEntry()
            }
        } }
        val target = File(root, "verify-bad")
        try { BackupArchive.verify(tampered, target, db.testSql.version); fail() } catch (e: BackupFailure) { assertFalse(target.exists()) }
        val traversal = File(root, "traversal.zip")
        ZipOutputStream(traversal.outputStream()).use { it.putNextEntry(ZipEntry("../escape")); it.write(1); it.closeEntry() }
        try { BackupArchive.verify(traversal, target, db.testSql.version); fail() } catch (_: BackupFailure) { assertFalse(File(root, "escape").exists()) }
        try { BackupArchive.verify(original, target, db.testSql.version - 1); fail() } catch (_: BackupFailure) { assertFalse(target.exists()) }
        assertEquals(CaptureDatabase.VERSION, db.testSql.version)
    }
    @Test fun restoreDownloadsSelectedVerifiesThenClosesAndRequestsRestart() {
        val zip = archive()
        val server = okhttp3.mockwebserver.MockWebServer()
        server.start()
        try {
            val client = WebDavClient(BackupConfig(server.url("/dav/").toString()), "回响", "me@example.com", "password")
            val names = listOf("回响-2026-10-22-0310.zip", "回响-2026-10-23-0310.zip")
            server.enqueue(okhttp3.mockwebserver.MockResponse().setBody(okio.Buffer().write(zip.readBytes())))
            val calls = mutableListOf<String>()
            BackupRestore.restore(context, client, names.last(), db, closeDatabase = { calls += "close"; db.close() }, restartProcess = { calls += "restart" })
            assertEquals(listOf("close", "restart"), calls)
            val get = server.takeRequest(); assertEquals("GET", get.method); assertTrue(get.path!!.endsWith("23-0310.zip"))
            BackupRestore.applyPending(context)
            assertFalse(File(context.noBackupFilesDir, "backup-restore").exists())
            db = CaptureDatabase.open(context) // 模拟恢复后新进程；关闭的 Room 实例不可复用。
            server.enqueue(okhttp3.mockwebserver.MockResponse().setBody("corrupt zip"))
            calls.clear()
            try { BackupRestore.restore(context, client, names.last(), db, closeDatabase = { calls += "close" }, restartProcess = { calls += "restart" }); fail() }
            catch (_: BackupFailure) { assertTrue(calls.isEmpty()) }
        } finally { server.shutdown() }
    }
    @Test fun snapshotRemainsConsistentDuringConcurrentTransactions() = runBlocking {
        val sql = db.testSql
        sql.execSQL("CREATE TABLE backup_probe (id INTEGER PRIMARY KEY, value INTEGER NOT NULL)")
        sql.execSQL("INSERT INTO backup_probe VALUES (1,0),(2,0)")
        sql.execSQL("INSERT INTO lines_fts(rowid,text) VALUES(42,'resound')")
        val writer = launch(Dispatchers.IO) {
            while (isActive) {
                db.writeTransaction {
                    db.writeSql("UPDATE backup_probe SET value=value+1 WHERE id=1")
                    yield()
                    db.writeSql("UPDATE backup_probe SET value=value+1 WHERE id=2")
                }
                delay(1)
            }
        }
        try {
            repeat(3) { index ->
                val copy = File(root, "snapshot-$index.db")
                if (index == 2) BackupArchive.logicalSnapshot(requireNotNull(sql.path), copy)
                else BackupArchive.snapshot(db, copy)
                SQLiteDatabase.openDatabase(copy.path, null, SQLiteDatabase.OPEN_READONLY).use { snapshot ->
                    snapshot.rawQuery("SELECT COUNT(DISTINCT value),COUNT(*) FROM backup_probe", null).use {
                        assertTrue(it.moveToFirst()); assertEquals(1, it.getInt(0)); assertEquals(2, it.getInt(1))
                    }
                    snapshot.rawQuery("SELECT rowid FROM lines_fts WHERE lines_fts MATCH 'resound'", null).use {
                        assertTrue(it.moveToFirst()); assertEquals(42L, it.getLong(0))
                    }
                    snapshot.rawQuery("PRAGMA integrity_check", null).use { assertTrue(it.moveToFirst()); assertEquals("ok", it.getString(0)) }
                }
            }
        } finally { writer.cancelAndJoin() }
    }
    @Test fun sampleAudioIsExcludedAndRestoreRetainsVectorsWithoutStalePlayback() = kotlinx.coroutines.runBlocking {
        val dir = app.resound.voiceprint.SampleAudioStore.directory(context)
        dir.deleteRecursively()
        val store = app.resound.voiceprint.SampleAudioStore(dir)
        val vector = app.resound.voiceprint.VoiceprintMath.encode(FloatArray(192) { if (it == 0) 1f else 0f })
        val id = db.voiceprint().saveSample(app.resound.data.VoiceprintSampleEntity(
            source = "enroll", sourceLineId = null, segmentId = null, groupKey = null,
            vector = vector, durationMillis = 10000, createdAt = 1))
        store.save("local-$id", ShortArray(160000) { 1000 })
        val zip = archive()
        ZipFile(zip).use { z -> assertFalse(z.entries().asSequence().any { it.name.contains("voiceprint-samples") || it.name.endsWith(".wav") }) }
        val verified = File(root, "samples-verified")
        BackupArchive.verify(zip, verified, CaptureDatabase.VERSION)
        db.close()
        BackupRestore.stage(context, verified)
        BackupRestore.applyPending(context)
        val restored = CaptureDatabase.open(context)
        try {
            assertArrayEquals(vector, restored.voiceprint().samples().single().vector)
            val freshStore = app.resound.voiceprint.SampleAudioStore(dir)
            assertEquals(0L, freshStore.bytes.value)
            assertNull(freshStore.file("local-$id"))
            assertFalse(freshStore.fallbackAllowed())
        } finally { restored.close(); dir.deleteRecursively() }
    }

}
