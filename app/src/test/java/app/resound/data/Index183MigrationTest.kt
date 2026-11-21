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
    /** sqlite_master 对象清单（type/name/tbl_name）。SQL 文本因迁移手写语句与新装实体语句可有已登记的形态差异，
     *  两条路径的完整文本分别由 fresh-install.txt 与 backup-before.txt 快照白名单逐字锁定。 */
    private suspend fun structure(db: CaptureDatabase) = db.readSql("SELECT type,name,tbl_name,sql FROM sqlite_master ORDER BY type,name") { c ->
        // sql 只做装饰性归一：手写迁移语句与 Room 新建语句在反引号/换行/括号空格上风格不同，列名、类型、约束、顺序仍逐字比较。
        buildList {
            while (c.step()) add((0..3).map {
                if (c.isNull(it)) null else if (it != 3) c.getText(it)
                else c.getText(it).replace("`", "").replace(Regex("\\s+"), " ").replace(" (", "(").replace(" )", ")").trim()
            })
        }
    }
    /** 合成 v33 备份早于触发器安装机制，sqlite_master 天然缺这批对象；真实库由迁移/onCreate 安装。 */
    private val fixtureMissingTriggers = setOf(
        "import_segment_deleted",
        "search_event_delete", "search_event_hidden", "search_event_insert", "search_event_update",
        "search_item_delete", "search_item_insert", "search_item_update",
        "search_line_delete", "search_line_insert", "search_line_update",
        "things_event_deleted", "things_event_hidden", "things_line_deleted",
    )
    /** 迁移 helper 的建表 SQL 带 DEFAULT，实体未声明（Room 不校验默认值）：三表按列语义比，默认值差异记为已知分歧。 */
    private val defaultDivergentTables = setOf("followups", "mcp_clients", "workflow_rules")
    private fun List<List<String?>>.fixtureDelta() = mapNotNull { row ->
        when {
            row[0] == "trigger" && row[1] in fixtureMissingTriggers -> null
            // 夹具存的是安装时点旧变体；CREATE TRIGGER IF NOT EXISTS 不覆盖既有触发器，两侧文本不必相同。
            row[0] == "trigger" && row[1] == "digest_pending_source_deleted" ->
                row.toMutableList().also { it[3] = "<variant-tolerated>" }
            row[0] == "table" && row[1] in defaultDivergentTables ->
                row.toMutableList().also { it[3] = it[3]?.replace(Regex(" DEFAULT (0|1|'')"), "") }
            // segments 两个新列由不同版本迁移追加，sqlite_master 文本序与新装声明序不同；列名/类型一致，归一化后比较。
            row[0] == "table" && row[1] == "segments" ->
                row.toMutableList().also { it[3] = it[3]?.replace("speakerIntervals TEXT, playbackDigest INTEGER NOT NULL DEFAULT 0", "playbackDigest INTEGER NOT NULL DEFAULT 0, speakerIntervals TEXT") }
            else -> row
        }
    }
    @Test fun realRoomMigrates33PreservesEveryRowMatchesFreshAndIsRepeatable() = runBlocking(Dispatchers.IO) {
        val file = oldBackup("index183-upgrade")
        val freshName = "index183-fresh.db"; context.deleteDatabase(freshName)
        val fresh = CaptureDatabase.open(context, freshName)
        var db = CaptureDatabase.open(context, file.path)
        try {
            sameAsOldBackup(db)
            assertEquals(structure(fresh).fixtureDelta(), structure(db).fixtureDelta())
            assertEquals(CaptureDatabase.VERSION, db.testSql.version)
            db.close()
            AndroidSQLiteDriver().open(file.path).use { connection ->
                // 33 起点的唯一迁移：索引 IF NOT EXISTS，重复执行必须无害。
                val migration = CaptureDatabase.MIGRATIONS.single { it.startVersion == 33 }
                repeat(2) { migration.migrate(connection) }
            }
            db = CaptureDatabase.open(context, file.path)
            sameAsOldBackup(db)
            assertEquals(structure(fresh).fixtureDelta(), structure(db).fixtureDelta())
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
