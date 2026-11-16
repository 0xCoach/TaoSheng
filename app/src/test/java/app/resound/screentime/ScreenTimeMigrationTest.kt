package app.resound.screentime

import android.app.Application
import androidx.room.Room
import androidx.work.Configuration
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import app.resound.AppGraph
import app.resound.data.CaptureDatabase
import app.resound.data.testSql
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class ScreenTimeMigrationTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private lateinit var db: CaptureDatabase
    private val clock = 1_700_000_000_000L

    @Before fun setup() {
        // 真 SQLite 上的 34→35 全量迁移：库从导出的 34.json 逐表重建，用 MIGRATIONS 升到当前版本。
        val name = "screen-time-migration.db"; context.deleteDatabase(name)
        val schema = JSONObject(File("../shared/schemas/app.resound.data.CaptureDatabase/34.json").readText()).getJSONObject("database")
        context.openOrCreateDatabase(name, 0, null).use { old ->
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val e = entities.getJSONObject(i)
                fun sql(s: String) = s.replace("\u0024{TABLE_NAME}", e.getString("tableName"))
                old.execSQL(sql(e.getString("createSql")))
                val indices = e.optJSONArray("indices") ?: JSONArray()
                for (j in 0 until indices.length()) old.execSQL(sql(indices.getJSONObject(j).getString("createSql")))
            }
            val queries = schema.getJSONArray("setupQueries"); for (i in 0 until queries.length()) old.execSQL(queries.getString(i))
            old.version = 34
        }
        db = Room.databaseBuilder(context, CaptureDatabase::class.java, name)
            .setDriver(androidx.sqlite.driver.AndroidSQLiteDriver()).allowMainThreadQueries()
            .addMigrations(*CaptureDatabase.MIGRATIONS).build()
    }

    @After fun close() { db.close(); context.deleteDatabase("screen-time-migration.db") }

    @Test fun migrate34CreatesFourTablesAndUniqueIndex() = runBlocking(Dispatchers.IO) {
        assertEquals(CaptureDatabase.VERSION, db.testSql.version)
        val tables = db.testSql.query("SELECT name FROM sqlite_master WHERE type='table'").use { rows ->
            buildList { if (rows.moveToFirst()) do { add(rows.getString(0)) } while (rows.moveToNext()) }
        }
        for (table in listOf("screen_events", "screen_app_names", "screen_pull_state", "screen_coverage_gaps"))
            assertTrue("缺表 $table", table in tables)
        val index = db.testSql.query("SELECT sql FROM sqlite_master WHERE type='index' AND name='index_screen_events_at_pkg_type_cls'").use { rows ->
            if (rows.moveToFirst()) rows.getString(0) else ""
        }
        assertTrue("唯一索引缺失", index.contains("UNIQUE") && index.contains("screen_events"))
        // 冲突忽略：同一（at,pkg,type,cls）只落一行
        db.screenTime().insertEvents(listOf(ScreenEventRow(at = 5, pkg = "a", cls = "", type = 1),
            ScreenEventRow(at = 5, pkg = "a", cls = "", type = 1)))
        assertEquals(1, db.screenTime().events(0, 10).size)
        // 覆盖指针只增不减；floor 只升不降
        db.screenTime().advanceCoveredUntil(SCREEN_SOURCE, 1_000)
        db.screenTime().advanceCoveredUntil(SCREEN_SOURCE, 500)
        assertEquals(1_000L, db.screenTime().state(SCREEN_SOURCE)?.coveredUntil)
        db.screenTime().raiseFloor(SCREEN_SOURCE, 700)
        db.screenTime().raiseFloor(SCREEN_SOURCE, 300)
        assertEquals(700L, db.screenTime().state(SCREEN_SOURCE)?.floor)
    }

    /** 总开关打开 → Worker.doWork() → pull() → 假事件源的 RESUMED 出现在 events 且覆盖到注入时钟。 */
    @Test fun workerPullRoutesFakeResumedEventIntoDaoAndAdvancesCoverage() = runBlocking(Dispatchers.IO) {
        WorkManagerTestInitHelper.initializeTestWorkManager(context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).build())
        try {
            var permittedChecked = false
            var savedPull = 0L
            val source = object : ScreenEventSource {
                override fun permitted(): Boolean { permittedChecked = true; return true }
                override suspend fun since(from: Long, until: Long) = listOf(RawScreenEvent(clock - 1_000, "com.example.app", "com.example.MainActivity", 1))
                override suspend fun appNames(pkgs: Set<String>) = mapOf("com.example.app" to "示例")
            }
            val settings = ScreenTimeSettings(readBoolean = { _, _ -> true },
                persist = { _, longs, _ -> longs[ScreenTimeSettings.LAST_PULL_KEY]?.let { savedPull = it }; true },
                readLong = { _, _ -> savedPull }, now = { clock })
            val puller = ScreenTimePuller(settings, source, db.screenTime(), now = { clock },
                denied = { it is SecurityException }, diagnostic = {})
            AppGraph.screenTimePuller = puller
            val result = TestListenableWorkerBuilder<ScreenTimePullWorker>(context).build().doWork()
            assertTrue(result is androidx.work.ListenableWorker.Result.Success)
            assertTrue(permittedChecked)
            assertEquals(listOf(clock - 1_000), db.screenTime().events(0, clock).map { it.at })
            assertEquals(clock, db.screenTime().state(SCREEN_SOURCE)?.coveredUntil)
            assertEquals(clock, savedPull)
        } finally { WorkManagerTestInitHelper.closeWorkDatabase() }
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class PlaybackRowsMigrationTest {
    /** 必测 1：35→36 后 playback_rows 和两个索引存在。 */
    @Test fun migrate35CreatesPlaybackTableAndIndexes() = runBlocking(Dispatchers.IO) {
        val context = RuntimeEnvironment.getApplication()
        val name = "playback-rows-migration.db"; context.deleteDatabase(name)
        val schema = JSONObject(File("../shared/schemas/app.resound.data.CaptureDatabase/35.json").readText()).getJSONObject("database")
        context.openOrCreateDatabase(name, 0, null).use { old ->
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val e = entities.getJSONObject(i)
                fun sql(s: String) = s.replace("\u0024{TABLE_NAME}", e.getString("tableName"))
                old.execSQL(sql(e.getString("createSql")))
                val indices = e.optJSONArray("indices") ?: JSONArray()
                for (j in 0 until indices.length()) old.execSQL(sql(indices.getJSONObject(j).getString("createSql")))
            }
            val queries = schema.getJSONArray("setupQueries"); for (i in 0 until queries.length()) old.execSQL(queries.getString(i))
            old.version = 35
        }
        val db = Room.databaseBuilder(context, CaptureDatabase::class.java, name)
            .setDriver(androidx.sqlite.driver.AndroidSQLiteDriver()).allowMainThreadQueries()
            .addMigrations(*CaptureDatabase.MIGRATIONS).build()
        try {
            assertEquals(CaptureDatabase.VERSION, db.testSql.version)
            val tables = db.testSql.query("SELECT name FROM sqlite_master WHERE type='table'").use { rows ->
                buildList { if (rows.moveToFirst()) do { add(rows.getString(0)) } while (rows.moveToNext()) }
            }
            assertTrue("缺表 playback_rows", "playback_rows" in tables)
            val indexNames = db.testSql.query("SELECT name FROM sqlite_master WHERE type='index'").use { rows ->
                buildList { if (rows.moveToFirst()) do { add(rows.getString(0)) } while (rows.moveToNext()) }
            }
            assertTrue("缺 fromAt 索引", "index_playback_rows_fromAt" in indexNames)
            assertTrue("缺 sessionKey 索引", "index_playback_rows_sessionKey" in indexNames)
        } finally { db.close(); context.deleteDatabase(name) }
    }
}
