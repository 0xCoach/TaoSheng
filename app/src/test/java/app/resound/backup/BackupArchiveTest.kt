package app.resound.backup

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
import java.util.concurrent.atomic.AtomicBoolean
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
        db = Room.databaseBuilder(context, CaptureDatabase::class.java, "capture.db").allowMainThreadQueries().build()
        db.openHelper.writableDatabase
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
        val schema = org.json.JSONObject(File("schemas/app.resound.data.CaptureDatabase/8.json").readText()).getJSONObject("database")
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
        BackupArchive.verify(zip,verified,db.openHelper.writableDatabase.version)
        val restored=CaptureDatabase.open(context, File(verified,BackupArchive.DATABASE).path)
        try {
            assertEquals(db.openHelper.writableDatabase.version,restored.openHelper.writableDatabase.version)
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
        BackupArchive.verify(zip, verified, db.openHelper.writableDatabase.version)
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
        try { BackupArchive.verify(tampered, target, db.openHelper.writableDatabase.version); fail() } catch (e: BackupFailure) { assertFalse(target.exists()) }
        val traversal = File(root, "traversal.zip")
        ZipOutputStream(traversal.outputStream()).use { it.putNextEntry(ZipEntry("../escape")); it.write(1); it.closeEntry() }
        try { BackupArchive.verify(traversal, target, db.openHelper.writableDatabase.version); fail() } catch (_: BackupFailure) { assertFalse(File(root, "escape").exists()) }
        try { BackupArchive.verify(original, target, db.openHelper.writableDatabase.version - 1); fail() } catch (_: BackupFailure) { assertFalse(target.exists()) }
        assertTrue(db.isOpen)
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
            server.enqueue(okhttp3.mockwebserver.MockResponse().setBody("corrupt zip"))
            calls.clear()
            try { BackupRestore.restore(context, client, names.last(), db, closeDatabase = { calls += "close" }, restartProcess = { calls += "restart" }); fail() }
            catch (_: BackupFailure) { assertTrue(calls.isEmpty()) }
        } finally { server.shutdown() }
    }
    @Test fun snapshotRemainsConsistentDuringConcurrentTransactions() {
        val sql = db.openHelper.writableDatabase
        sql.execSQL("CREATE TABLE backup_probe (id INTEGER PRIMARY KEY, value INTEGER NOT NULL)")
        sql.execSQL("INSERT INTO backup_probe VALUES (1,0),(2,0)")
        sql.execSQL("INSERT INTO lines_fts(rowid,text) VALUES(42,'resound')")
        val running = AtomicBoolean(true)
        val writer = Thread {
            while (running.get()) {
                sql.beginTransaction()
                try { sql.execSQL("UPDATE backup_probe SET value=value+1 WHERE id=1")
                    Thread.yield(); sql.execSQL("UPDATE backup_probe SET value=value+1 WHERE id=2"); sql.setTransactionSuccessful()
                } finally { sql.endTransaction() }
                Thread.sleep(1)
            }
        }
        writer.start()
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
        } finally { running.set(false); writer.join(5000); assertFalse(writer.isAlive) }
    }
}
