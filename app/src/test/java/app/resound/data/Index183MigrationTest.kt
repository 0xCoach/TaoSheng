package app.resound.data

import android.app.Application
import androidx.room.Room
import androidx.room.useReaderConnection
import androidx.sqlite.*
import androidx.sqlite.driver.AndroidSQLiteDriver
import app.resound.backup.BackupArchive
import app.resound.backup.BackupFailure
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
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
class Index183MigrationTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private fun oldBackup(name: String): File {
        val folder = File(context.cacheDir, name).apply { deleteRecursively() }
        BackupArchive.verify(File("src/test/resources/driver175/synthetic-backup.zip"), folder, CaptureDatabase.VERSION)
        return File(folder, BackupArchive.DATABASE).also { file ->
            AndroidSQLiteDriver().open(file.path).use { assertEquals(33, it.userVersion) }
        }
    }
    private suspend fun sameAsOldBackup(db: CaptureDatabase) {
        val fixtures = Driver175FixturesTest()
        db.useReaderConnection { fixtures.golden("backup-before.txt", fixtures.snapshot(it)) }
    }
    private suspend fun structure(db: CaptureDatabase) = db.readSql("SELECT type,name,tbl_name,sql FROM sqlite_master ORDER BY type,name") { c ->
        buildList { while (c.step()) add((0..3).map { if (c.isNull(it)) null else c.getText(it) }) }
    }
    @Test fun realRoomMigrates33PreservesEveryRowMatchesFreshAndIsRepeatable() = runBlocking(Dispatchers.IO) {
        val file = oldBackup("index183-upgrade")
        val freshName = "index183-fresh.db"; context.deleteDatabase(freshName)
        val fresh = CaptureDatabase.open(context, freshName)
        var db = CaptureDatabase.open(context, file.path)
        try {
            sameAsOldBackup(db)
            assertEquals(structure(fresh), structure(db))
            assertEquals(CaptureDatabase.VERSION, db.testSql.version)
            db.close()
            AndroidSQLiteDriver().open(file.path).use { connection ->
                val migration = CaptureDatabase.MIGRATIONS.single { it.startVersion == 33 && it.endVersion == CaptureDatabase.VERSION }
                repeat(2) { migration.migrate(connection) }
            }
            db = CaptureDatabase.open(context, file.path)
            sameAsOldBackup(db)
            assertEquals(structure(fresh), structure(db))
        } finally { db.close(); fresh.close(); file.parentFile!!.parentFile!!.deleteRecursively(); context.deleteDatabase(freshName) }
    }
    @Test fun failureAfterFirstIndexRollsBackAndReopeningCompletes() = runBlocking(Dispatchers.IO) {
        val file = oldBackup("index183-interrupted")
        val base = AndroidSQLiteDriver(); var injected = false
        val broken = object : SQLiteDriver {
            override val hasConnectionPool = base.hasConnectionPool
            override fun open(fileName: String): SQLiteConnection {
                val connection = base.open(fileName)
                return object : SQLiteConnection by connection {
                    override fun prepare(sql: String): SQLiteStatement {
                        val statement = connection.prepare(sql)
                        return object : SQLiteStatement by statement {
                            override fun step(): Boolean {
                                val value = statement.step()
                                if (!injected && sql.startsWith("CREATE INDEX") && sql.contains("index_segments_status_voiceprintProcessedAt")) {
                                    injected = true
                                    throw IllegalStateException("index183-injected-after-first-create")
                                }
                                return value
                            }
                        }
                    }
                }
            }
        }
        val interrupted = Room.databaseBuilder(context, CaptureDatabase::class.java, file.path)
            .setDriver(broken).addMigrations(*CaptureDatabase.MIGRATIONS).build()
        try {
            val failure = runCatching { interrupted.readSql("PRAGMA user_version") { it.step() } }.exceptionOrNull()
            assertTrue("必须真正执行到索引后注入失败", injected)
            assertNotNull(failure)
            assertTrue(generateSequence(failure) { it.cause }.any { it.message?.contains("index183-injected") == true })
        } finally { interrupted.close() }
        try {
            AndroidSQLiteDriver().open(file.path).use { connection ->
                assertEquals(33, connection.userVersion)
                connection.prepare("SELECT count(*) FROM sqlite_master WHERE name IN ('index_segments_status_voiceprintProcessedAt','index_transcript_lines_startAt')").use {
                    assertTrue(it.step()); assertEquals(0L, it.getLong(0))
                }
            }
            val recovered = CaptureDatabase.open(context, file.path)
            try { sameAsOldBackup(recovered) } finally { recovered.close() }
        } finally { file.parentFile!!.parentFile!!.deleteRecursively() }
    }
    @Test fun newBackupRecordsCurrentVersionAndFutureBackupIsRejected() = runBlocking(Dispatchers.IO) {
        val file = oldBackup("index183-new-backup")
        val db = CaptureDatabase.open(context, file.path)
        val root = File(context.cacheDir, "index183-archive").apply { deleteRecursively(); mkdirs() }
        try {
            sameAsOldBackup(db)
            val stage = File(root, "stage").apply { mkdirs() }; val zip = File(root, "current.zip")
            BackupArchive.create(context, db, stage, zip, 123L)
            ZipFile(zip).use { archive ->
                val manifest = JSONObject(archive.getInputStream(archive.getEntry("manifest.json")).bufferedReader().use { it.readText() })
                assertEquals(CaptureDatabase.VERSION, manifest.getInt("databaseVersion"))
            }
            BackupArchive.verify(zip, File(root, "verified"), CaptureDatabase.VERSION)
            AndroidSQLiteDriver().open(File(stage, BackupArchive.DATABASE).path).use { it.userVersion = CaptureDatabase.VERSION + 1 }
            val future = File(root, "future.zip")
            BackupArchive.pack(stage, future, 123L, "synthetic", CaptureDatabase.VERSION + 1)
            assertThrows(BackupFailure::class.java) { BackupArchive.verify(future, File(root, "rejected"), CaptureDatabase.VERSION) }
            assertFalse(File(root, "rejected").exists())
        } finally { db.close(); root.deleteRecursively(); file.parentFile!!.parentFile!!.deleteRecursively() }
    }
}
