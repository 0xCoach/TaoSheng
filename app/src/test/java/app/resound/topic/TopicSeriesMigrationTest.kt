package app.resound.topic

import android.app.Application
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.sqlite.execSQL
import app.resound.data.CaptureDatabase
import app.resound.data.open
import app.resound.data.readSql
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

/** v41→v42：topics.seriesKey 补列幂等、存量名字按同口径回填、匿名专题不建系列。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class TopicSeriesMigrationTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val name = "topic-series-test.db"
    private var db: CaptureDatabase? = null

    @After fun tearDown() {
        db?.close(); db = null; context.deleteDatabase(name)
    }

    @Test fun migration41to42BackfillsSeriesKey() = runBlocking<Unit>(Dispatchers.IO) {
        context.deleteDatabase(name)
        val file = context.getDatabasePath(name)
        file.parentFile?.mkdirs()
        AndroidSQLiteDriver().open(file.path).use { conn ->
            conn.execSQL("""CREATE TABLE topics (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                kind TEXT NOT NULL, name TEXT, status TEXT NOT NULL, createdAt INTEGER NOT NULL,
                plannedStartAt INTEGER, plannedEndAt INTEGER, repeatRule INTEGER, eventId INTEGER, cardId INTEGER,
                error TEXT, revision INTEGER NOT NULL, quietOverride INTEGER NOT NULL, retainedItemIds TEXT NOT NULL,
                starred INTEGER NOT NULL, quietNotifiedAfter INTEGER, endingNotifiedAt INTEGER)""")
            conn.execSQL("INSERT INTO topics (kind,name,status,createdAt,revision,quietOverride,retainedItemIds,starred) VALUES ('Meeting','产品 周会','Done',1,0,0,'[]',0)")
            conn.execSQL("INSERT INTO topics (kind,name,status,createdAt,revision,quietOverride,retainedItemIds,starred) VALUES ('Meeting','Sprint Planning','Done',2,0,0,'[]',0)")
            conn.execSQL("INSERT INTO topics (kind,name,status,createdAt,revision,quietOverride,retainedItemIds,starred) VALUES ('Meeting',NULL,'Done',3,0,0,'[]',0)")
            val migration = CaptureDatabase.MIGRATIONS.single { it.startVersion == 41 }
            repeat(2) { migration.migrate(conn) }
            conn.prepare("SELECT name, seriesKey FROM topics ORDER BY id").use {
                assertTrue(it.step()); assertEquals("产品周会", it.getText(1))
                assertTrue(it.step()); assertEquals("sprintplanning", it.getText(1))
                assertTrue(it.step()); assertTrue(it.isNull(1))
            }
        }
        file.delete()
    }

    @Test fun freshDbHasSeriesKeyColumn() = runBlocking<Unit>(Dispatchers.IO) {
        val d = CaptureDatabase.open(context, name).also { db = it }
        val version = d.readSql("PRAGMA user_version") { assertTrue(it.step()); it.getLong(0).toInt() }
        assertEquals(CaptureDatabase.VERSION, version)
        val hasSeries = d.readSql("SELECT 1 FROM pragma_table_info('topics') WHERE name = 'seriesKey'") { it.step() }
        assertTrue(hasSeries)
    }
}
