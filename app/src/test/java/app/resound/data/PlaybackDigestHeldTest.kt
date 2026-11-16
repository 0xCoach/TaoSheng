package app.resound.data

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.io.File

/** 「仍要转写」的外放片段置 playbackDigest=1，转写行保留但暂不进整理取数口，放行（=2）后恢复。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class PlaybackDigestHeldTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private lateinit var db: CaptureDatabase
    private val at = 1_700_000_000_000L

    @Before fun setup() {
        context.deleteDatabase("playback-digest-held.db")
        db = Room.inMemoryDatabaseBuilder(context, CaptureDatabase::class.java)
            .setDriver(AndroidSQLiteDriver()).allowMainThreadQueries().build()
    }

    @After fun close() { db.close() }

    /** 必测1：36→37 迁移后 segments.playbackDigest 存在，旧行默认 0。 */
    @Test fun migration36To37AddsPlaybackDigestWithZeroDefault() = runBlocking(Dispatchers.IO) {
        val name = "playback-digest-migration.db"; context.deleteDatabase(name)
        val schema = JSONObject(File("../shared/schemas/app.resound.data.CaptureDatabase/36.json").readText()).getJSONObject("database")
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
            old.execSQL("INSERT INTO segments(id,startAt,endAt,sourceId,path,sizeBytes,status) VALUES(1,?,?,'mic','synthetic-1',1,'Done')", arrayOf<Any?>(at, at + 1000))
            old.version = 36
        }
        val migrated = Room.databaseBuilder(context, CaptureDatabase::class.java, name)
            .setDriver(AndroidSQLiteDriver()).allowMainThreadQueries()
            .addMigrations(*CaptureDatabase.MIGRATIONS).build()
        try {
            assertEquals(CaptureDatabase.VERSION, migrated.testSql.version)
            val rows = migrated.testSql.query("SELECT playbackDigest FROM segments")
            assertTrue(rows.moveToFirst())
            assertEquals(1, rows.count)
            assertEquals(0, rows.getInt(0))
        } finally { migrated.close(); context.deleteDatabase(name) }
    }

    private suspend fun insertSegment(id: Long, status: String, startAt: Long) {
        db.capture().segment(SegmentEntity(id, startAt, startAt + 1000, "mic", "synthetic-$id", 1, status))
    }

    /** 必测2+3：仍要转写置 1，转写完成（Done）后它的行不出现在 undigestedLines / undigestedCount / observeUndigestedCount；
     *  同时段普通片段照常出现；放行（=2）后恢复出现。 */
    @Test fun transcribeAnywayHoldsLinesOutOfDigestUntilAllowed() = runBlocking(Dispatchers.IO) {
        insertSegment(1, "SkippedPlayback", at)
        insertSegment(2, "Done", at + 10_000)
        db.transcribe().insertLines(listOf(
            TranscriptLineEntity(1, 1, at + 100, at + 200, "外放节目原话", null),
            TranscriptLineEntity(2, 2, at + 10_100, at + 10_200, "当面说的原话", null),
            TranscriptLineEntity(3, 2, at + 10_300, at + 10_400, "当面说的另一句", null),
        ))
        assertEquals(1, db.transcribe().transcribeAnyway(1))
        var held = db.transcribe().segment(1)
        assertEquals("Pending", held.status)
        assertEquals(1, held.playbackDigest)
        db.transcribe().update(held.copy(status = "Done", completedAt = at + 5000))

        val onlySegment2 = listOf(2L, 2L)
        assertEquals(onlySegment2, db.memory().undigestedLines().map { it.segmentId })
        assertEquals(2, db.memory().undigestedCount())
        assertEquals(2, db.memory().observeUndigestedCount().first())

        assertEquals(1, db.transcribe().allowPlaybackDigest(1))
        assertEquals(2, db.transcribe().segment(1).playbackDigest)
        assertTrue(db.memory().undigestedLines().map { it.segmentId }.containsAll(onlySegment2 + 1L))
        assertEquals(3, db.memory().undigestedCount())
        assertEquals(3, db.memory().observeUndigestedCount().first())

        // 放行只对暂不整理（=1）的行生效；普通片段不受影响。
        assertEquals(0, db.transcribe().allowPlaybackDigest(2))
        assertEquals(0, db.transcribe().segment(2).playbackDigest)
    }

    @Test fun transcribeAnywayWritesDigestIntentInSameUpdate() = runBlocking(Dispatchers.IO) {
        insertSegment(1, "SkippedPlayback", at)
        insertSegment(2, "SkippedPlayback", at + 1_000)
        assertEquals(1, db.transcribe().transcribeAnyway(1, 1))
        val only = db.transcribe().segment(1)
        assertEquals("Pending", only.status)
        assertEquals(1, only.playbackDigest)
        assertEquals(1, db.transcribe().transcribeAnyway(2, 2))
        val both = db.transcribe().segment(2)
        assertEquals("Pending", both.status)
        assertEquals(2, both.playbackDigest)
    }
}
