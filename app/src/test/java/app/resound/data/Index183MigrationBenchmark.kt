package app.resound.data

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.sqlite.*
import androidx.sqlite.driver.AndroidSQLiteDriver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.io.File

/** 显式运行：真实Room升级两档文件库。种子/计时均不接触真实数据。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class Index183MigrationBenchmark {
    @Test fun twoSizeRoomMigrations() = runBlocking(Dispatchers.IO) {
        assumeTrue(System.getenv("TAOSHENG_183_MIGRATION_BENCHMARK") == "1")
        val context = RuntimeEnvironment.getApplication()
        val schema = JSONObject(File("../shared/schemas/app.resound.data.CaptureDatabase/33.json").readText()).getJSONObject("database")
        val seeds = JSONObject(File("src/test/resources/index183/synthetic-seeds.json").readText())
        val report = JSONArray()
        for (size in listOf("small", "large")) {
            val name = "index183-benchmark-$size.db"; context.deleteDatabase(name)
            val file = context.getDatabasePath(name).apply { parentFile!!.mkdirs() }
            AndroidSQLiteDriver().open(file.path).use { connection ->
                connection.execSQL("PRAGMA journal_mode=WAL"); connection.execSQL("PRAGMA foreign_keys=ON")
                connection.execSQL("BEGIN IMMEDIATE")
                val entities = schema.getJSONArray("entities")
                repeat(entities.length()) { i ->
                    val e = entities.getJSONObject(i)
                    fun sql(text: String) = text.replace("\u0024{TABLE_NAME}", e.getString("tableName"))
                    connection.execSQL(sql(e.getString("createSql")))
                    val indexes = e.optJSONArray("indices") ?: JSONArray()
                    repeat(indexes.length()) { connection.execSQL(sql(indexes.getJSONObject(it).getString("createSql"))) }
                }
                val setup = schema.getJSONArray("setupQueries"); repeat(setup.length()) { connection.execSQL(setup.getString(it)) }
                val sql = seeds.getJSONArray(size); repeat(sql.length()) { connection.execSQL(sql.getString(it)) }
                File("src/test/resources/driver175/fresh-install.txt").forEachLine { row ->
                    if (row.startsWith("[\"trigger\",")) connection.execSQL(JSONArray(row).getString(3))
                }
                connection.userVersion = 33
                connection.execSQL("COMMIT")
                connection.prepare("PRAGMA wal_checkpoint(TRUNCATE)").use { assertTrue(it.step()); assertEquals(0L,it.getLong(0)) }
            }
            val before = file.length()
            val indexTimes = JSONObject(); val driver = AndroidSQLiteDriver()
            val timed = object : SQLiteDriver {
                override val hasConnectionPool = driver.hasConnectionPool
                override fun open(fileName: String): SQLiteConnection {
                    val c = driver.open(fileName)
                    return object : SQLiteConnection by c {
                        override fun prepare(sql: String): SQLiteStatement {
                            val statement = c.prepare(sql)
                            return object : SQLiteStatement by statement {
                                override fun step(): Boolean {
                                    val start = System.nanoTime()
                                    return statement.step().also {
                                        if (sql.startsWith("CREATE INDEX IF NOT EXISTS") && (sql.contains("index_segments_status_voiceprintProcessedAt") || sql.contains("index_transcript_lines_startAt")))
                                            indexTimes.put(sql, (System.nanoTime()-start)/1_000_000.0)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            val db = Room.databaseBuilder(context, CaptureDatabase::class.java, file.path).setDriver(timed)
                .addMigrations(*CaptureDatabase.MIGRATIONS).build()
            try {
                val start = System.nanoTime()
                val version = db.readSql("PRAGMA user_version") { assertTrue(it.step()); it.getLong(0).toInt() }
                val openMs = (System.nanoTime()-start)/1_000_000.0
                assertEquals(CaptureDatabase.VERSION, version)
                assertEquals(2, indexTimes.length())
                db.readSql("SELECT identity_hash FROM room_master_table WHERE id=42") { assertTrue(it.step()); assertEquals(Index183FixtureDelta.spec.getString("newIdentityHash"),it.getText(0)) }
                db.readSql("PRAGMA foreign_key_check") { assertFalse(it.step()) }
                db.readSql("SELECT count(*) FROM sqlite_master WHERE type='trigger'") { assertTrue(it.step()); assertEquals(15L,it.getLong(0)) }
                db.checkpoint("TRUNCATE")
                report.put(JSONObject().put("size",size).put("segments",if(size=="small")50000 else 200000)
                    .put("transcript_lines",if(size=="small")500000 else 2000000).put("room_open_ms",openMs)
                    .put("index_step_ms",indexTimes).put("before_bytes",before).put("after_bytes",file.length()))
                File("build/index183-migrations.json").writeText(report.toString(2))
                assertTrue("大档实际开库超过5秒，必须停止交审：$openMs ms",openMs<=5000)
            } finally { db.close() }
            // AndroidSQLiteDriver不支持EXPLAIN分类，用同一个Robolectric系统引擎只读查看计划。
            if (size == "small") SQLiteDatabase.openDatabase(file.path,null,SQLiteDatabase.OPEN_READONLY).use { raw ->
                val queries = JSONObject(File("../docs/任务卡/183-证据/final-queries.json").readText())
                val plans = JSONObject()
                for (key in listOf("data/TranscribeDao.kt#observeQueueCounts","data/TranscribeDao.kt#observeVoiceprintCounts","data/TranscribeDao.kt#observeFailedSegments","topic/TopicData.kt#observeLateTranscriptIds")) {
                    raw.rawQuery("EXPLAIN QUERY PLAN "+queries.getJSONObject(key).getString("sql"),null).use { c ->
                        val rows=JSONArray(); while(c.moveToNext())rows.put(c.getString(3));plans.put(key,rows)
                    }
                }
                File("build/index183-android-plans.json").writeText(plans.toString(2))
            }
            context.deleteDatabase(name)
        }
    }
}
