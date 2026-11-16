package app.resound.marks

import android.app.Application
import androidx.room.Room
import app.resound.data.*
import app.resound.topic.*
import app.resound.privacy.RoomPrivacyActions
import app.resound.backup.*
import app.resound.transcribe.originalAudioRemainingDays
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class Marks195Test {
    private val context get() = RuntimeEnvironment.getApplication()
    private lateinit var db: CaptureDatabase
    private var at = 200_000L
    @Before fun setup() { context.deleteDatabase("marks195.db"); db = Room.databaseBuilder(context, CaptureDatabase::class.java, "marks195.db")
        .setDriver(androidx.sqlite.driver.AndroidSQLiteDriver()).allowMainThreadQueries().build() }
    @After fun close() { db.close(); context.deleteDatabase("marks195.db") }
    private fun repository(range: () -> MarkRange = { MarkRange() }) = MarkRepository(db, { at }, range) { topicId, markId -> RoomTopicStore(db).unmark(topicId, markId) }
    private suspend fun segment(id: Long, start: Long) { db.capture().segment(SegmentEntity(id, start, start + 10_000, "mic", "/missing/mark195-$id", 0)) }
    private suspend fun topic(): Long = db.topics().insert(TopicRow(kind = "Meeting", name = null, status = "Running", createdAt = at))
    @Test fun recordingPlaybackUndoAndRangeSnapshot() = runBlocking {
        var range = MarkRange(); val repo = repository { range }
        val first = repo.recording("重要")
        range = MarkRange(30_000, 600_000)
        val second = repo.playback(100_000, 5_000, 10_000)
        assertEquals(at, first.at); assertEquals(105_000L, second.at)
        assertEquals(120_000L, db.marks().get(first.id)!!.beforeMillis)
        assertEquals(30_000L, second.beforeMillis)
        repo.undo(first.id); repo.undo(first.id)
        assertNull(db.marks().get(first.id)); assertNotNull(db.marks().get(second.id))
    }
    @Test fun settingsPersistAndClassifyAsBackedUp() {
        val prefs = context.getSharedPreferences("marks", 0); prefs.edit().clear().commit()
        val settings = AndroidMarkSettings(prefs)
        settings.setRange(30_000, 600_000)
        assertEquals(MarkRange(30_000, 600_000), AndroidMarkSettings(prefs).range.value)
        assertTrue(PreferenceBackupPolicy.setting("marks", "beforeMillis"))
        assertTrue(runCatching { settings.setRange(0, 60_000) }.isFailure)
    }
    @Test fun topicDoubleWriteAndUndoAreAtomicAndScoped() = runBlocking {
        val id = topic(); val store = RoomTopicStore(db) { MarkRange(30_000, 60_000) }
        val mark = store.mark(app.resound.topic.core.TopicMarkRow(topicId = id, at = at))
        assertEquals(mark, db.marks().overlapping(0, 500_000).single().topicMarkId)
        store.unmark(id + 1, mark); assertEquals(1, db.marks().overlapping(0, 500_000).size)
        repository().undo(db.marks().overlapping(0, 500_000).single().id)
        assertTrue(db.topics().get(id)!!.marks.isEmpty()); assertTrue(db.marks().overlapping(0, 500_000).isEmpty())
    }
    @Test fun overlapQueriesAndOriginalRetentionShareStarredPolicy() = runBlocking {
        segment(1, 100_000); segment(2, 260_000)
        val mark = repository().recording()
        assertEquals(listOf(mark), repository().forSegment(1)); assertTrue(repository().forSegment(2).isEmpty())
        val event = db.memory().insertEvent(MemoryEventEntity(startAt=100_000, endAt=110_000, scene="其他", title="测试", summary="摘要", pointsJson="[]", peopleJson="[]", place=null, emotion=null, intensity=null, important=false, createdAt=at, model="test"))
        assertEquals(listOf(mark), repository().forEvent(event))
        assertEquals(listOf(1L), db.transcribe().starredAudio())
        assertNull(originalAudioRemainingDays(0, "Done", 0, true, at))
        assertEquals(0, originalAudioRemainingDays(0, "Done", 0, false, at))
    }
    @Test fun privacyRangeRecentSegmentBatchAndAllClearMarks() = runBlocking {
        val privacy = RoomPrivacyActions(db, now = { 300_000 })
        suspend fun add() = repository().recording()
        add(); privacy.deleteRange(199_000, 201_000); assertTrue(db.marks().overlapping(0, 900_000).isEmpty())
        add(); privacy.deleteRecent(2); assertTrue(db.marks().overlapping(0, 900_000).isEmpty())
        segment(1, at); add(); privacy.forgetSegment(1); assertTrue(db.marks().overlapping(0, 900_000).isEmpty())
        segment(2, at); add(); privacy.deleteSegments(listOf(2)); assertTrue(db.marks().overlapping(0, 900_000).isEmpty())
        val id = topic(); RoomTopicStore(db).mark(app.resound.topic.core.TopicMarkRow(topicId = id, at = at))
        privacy.deleteAll(); assertTrue(db.marks().overlapping(0, 900_000).isEmpty()); assertTrue(db.topics().get(id)!!.marks.isEmpty())
    }
    @Test fun forgetEventWithoutAudioStillRemovesItsMarks() = runBlocking {
        repository().recording()
        val id = db.memory().insertEvent(MemoryEventEntity(startAt=199_000, endAt=201_000, scene="其他", title="测试", summary="摘要", pointsJson="[]", peopleJson="[]", place=null, emotion=null, intensity=null, important=false, createdAt=at, model="test"))
        RoomPrivacyActions(db).forgetEvent(id)
        assertTrue(db.marks().overlapping(0, 900_000).isEmpty())
    }
    @Test fun migrations37And33PreserveLegacyMarks() = runBlocking {
        for (version in listOf(37, 33)) {
            val name = "marks195-$version.db"; context.deleteDatabase(name)
            val schema = JSONObject(File("../shared/schemas/app.resound.data.CaptureDatabase/$version.json").readText()).getJSONObject("database")
            context.openOrCreateDatabase(name, 0, null).use { old ->
                val entities = schema.getJSONArray("entities")
                for (i in 0 until entities.length()) {
                    val e = entities.getJSONObject(i)
                    fun sql(s: String) = s.replace("\u0024{TABLE_NAME}", e.getString("tableName"))
                    old.execSQL(sql(e.getString("createSql")))
                    val indices = e.optJSONArray("indices") ?: org.json.JSONArray()
                    for (j in 0 until indices.length()) old.execSQL(sql(indices.getJSONObject(j).getString("createSql")))
                }
                val setup = schema.getJSONArray("setupQueries")
                for (i in 0 until setup.length()) old.execSQL(setup.getString(i))
                old.execSQL("INSERT INTO topics(id,kind,status,createdAt,revision,quietOverride,retainedItemIds,starred) VALUES(1,'Meeting','Done',200000,0,0,'[]',0)")
                old.execSQL("INSERT INTO topic_marks(id,topicId,at) VALUES(7,1,200000)")
                old.version = version
            }
            val upgraded = Room.databaseBuilder(context, CaptureDatabase::class.java, name).setDriver(androidx.sqlite.driver.AndroidSQLiteDriver())
                .addMigrations(*CaptureDatabase.MIGRATIONS).build()
            try {
                val mark = upgraded.marks().overlapping(0, 300_000).single()
                assertEquals(CaptureDatabase.VERSION, upgraded.testSql.version)
                assertEquals(7L, mark.topicMarkId); assertEquals(120_000L, mark.beforeMillis)
                assertEquals(7L, upgraded.topics().get(1)!!.marks.single().id)
            } finally { upgraded.close(); context.deleteDatabase(name) }
        }
    }
    @Test fun dayVolumeRoundTripAndLegacyWithoutMarks() = runBlocking {
        val root = File(context.cacheDir, "marks195-backup").apply { deleteRecursively(); mkdirs() }
        try {
            val topic = topic(); RoomTopicStore(db).mark(app.resound.topic.core.TopicMarkRow(topicId=topic, at=at))
            repository().recording("通用")
            val snapshot = File(root, "snapshot.db"); BackupArchive.snapshot(db, snapshot)
            val (version, volumes) = DayVolumeRows.write(snapshot, File(root, "volumes"))
            for (legacy in listOf(false, true)) {
                val days = volumes.filterKeys { it != "state" }.values.toList()
                if (legacy) days.forEach { file -> file.writeText(file.readLines().filterNot { JSONObject(it).getString("table") == "marks" }.joinToString("\n", postfix="\n")) }
                val restored = File(root, "restored-$legacy.db")
                DayVolumeRows.rebuild(context, restored, version, volumes.getValue("state"), days)
                val copy = CaptureDatabase.open(context, restored.absolutePath)
                try { assertEquals(if (legacy) 1 else 2, copy.marks().overlapping(0, 500_000).size) } finally { copy.close() }
            }
        } finally { root.deleteRecursively() }
    }
}
