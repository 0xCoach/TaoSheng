package app.resound.transcribe

import android.app.Application
import androidx.room.Room
import app.resound.data.*
import app.resound.digest.DigestPrompt
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, application = Application::class)
class AsrSpeed073Test {
    private val context get() = RuntimeEnvironment.getApplication()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val prefs get() = context.getSharedPreferences("test-speed", 0)
    @Before fun prepare() { prefs.edit().clear().commit() }
    @After fun close() { scope.cancel() }

    @Test fun expiryAndRestartKeepTheRecordingStartDecision() {
        var time = 1000L
        val settings = AndroidAsrSpeedSettings(prefs, scope) { time }
        assertTrue(settings.separateSpeakers.value)
        settings.startClassMode(120)
        val end = 1000L + 120 * 60_000
        assertEquals(end, settings.classMode.value.endsAtMillis)
        time = end + 1
        val restored = AndroidAsrSpeedSettings(prefs, scope) { time }
        assertFalse(restored.classMode.value.enabled)
        assertTrue(restored.effectiveSeparateSpeakers(999))
        assertFalse(restored.effectiveSeparateSpeakers(end - 1))
        assertTrue(restored.effectiveSeparateSpeakers(end))
    }

    @Test fun manualStopAndPermanentSwitchDoNotRewriteEarlierSegments() {
        var time = 1000L
        val settings = AndroidAsrSpeedSettings(prefs, scope) { time }
        settings.startClassMode(null)
        time = 2000; settings.stopClassMode()
        time = 3000; settings.setSeparateSpeakers(false)
        time = 4000; settings.setSeparateSpeakers(true)
        assertFalse(settings.effectiveSeparateSpeakers(1500))
        assertTrue(settings.effectiveSeparateSpeakers(2500))
        assertFalse(settings.effectiveSeparateSpeakers(3500))
        assertTrue(settings.effectiveSeparateSpeakers(4500))
    }

    @Test fun concurrentResultsStayWithTheirSegmentsAndOffClearsIdentity() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, CaptureDatabase::class.java).allowMainThreadQueries().build()
        val files = (1..2).map { File.createTempFile("speed", ".ogg", context.cacheDir).apply { writeBytes(byteArrayOf(1)) } }
        try {
            files.forEachIndexed { i, file -> db.capture().segment(SegmentEntity(startAt = (i + 1) * 1000L,
                endAt = (i + 1) * 1000L + 500, sourceId = "mic", path = file.path, sizeBytes = 1)) }
            var entered = 0
            val both = CompletableDeferred<Unit>()
            val checked = mutableSetOf<String>()
            val flags = mutableListOf<Boolean>()
            val provider = object : AsrProvider {
                override suspend fun recognize(audio: AsrAudio, key: String, beforeAudio: suspend () -> Unit): AsrResult {
                    assertTrue(audio.file.path in checked) // 过滤先于上传
                    flags += audio.separateSpeakers
                    beforeAudio()
                    if (++entered == 2) both.complete(Unit)
                    withTimeout(3000) { both.await() }
                    if (audio.file == files[0]) delay(10) // 乱序完成
                    return AsrResult(listOf(AsrSentence(0, 500, audio.file.name, "0")), 500, null)
                }
            }
            val runner = TranscribeRunner(db.transcribe(), provider, { "key" }, { _, _ -> },
                defaultSource = { TranscribeSource.Doubao }, separateSpeakers = { it < 2000 },
                beforeTranscribe = { checked += it.path; false })
            assertFalse(runner.run())
            assertEquals(setOf(true, false), flags.toSet())
            val rows = db.transcribe().day(0, 4000).first().sortedBy { it.segment.startAt }
            assertEquals(files.map { it.name }, rows.map { it.lines.single().text })
            assertEquals(listOf(1, 1), rows.map { it.segment.attempts })
            assertEquals(listOf("0", null), rows.map { it.lines.single().speakerTag })
            assertNotNull(rows.last().segment.voiceprintProcessedAt)
            val text = DigestPrompt.transcript(rows.flatMap { it.lines }, unseparated = setOf(rows.last().segment.id)).first
            assertTrue(text.contains("本段未区分说话人"))
            val raw = JSONObject("""{"result":{"utterances":[{"text":"测试","additions":{"speaker_id":"0"}}]}}""")
            assertNull(DoubaoProtocol.result(raw, 500, null, false).sentences.single().speakerTag)
        } finally { db.close(); files.forEach { it.delete() } }
    }

    @Test fun migration24KeepsDataAndHistoricalSpeakerState() = runBlocking {
        val name = "migration073.db"; context.deleteDatabase(name)
        val schema = JSONObject(File("schemas/app.resound.data.CaptureDatabase/24.json").readText()).getJSONObject("database")
        context.openOrCreateDatabase(name, 0, null).use { old ->
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i)
                fun sql(text: String) = text.replace("\u0024{TABLE_NAME}", entity.getString("tableName"))
                old.execSQL(sql(entity.getString("createSql")))
                val indices = entity.getJSONArray("indices")
                for (j in 0 until indices.length()) old.execSQL(sql(indices.getJSONObject(j).getString("createSql")))
            }
            val queries = schema.getJSONArray("setupQueries")
            for (i in 0 until queries.length()) old.execSQL(queries.getString(i))
            old.execSQL("INSERT INTO segments(id,startAt,endAt,sourceId,path,sizeBytes,status) VALUES(1,1,2,'mic','/old',1,'Done')")
            old.execSQL("INSERT INTO segments(id,startAt,endAt,sourceId,path,sizeBytes,status) VALUES(2,3,4,'mic','/pending',1,'Pending')")
            old.version = 24
        }
        val db = Room.databaseBuilder(context, CaptureDatabase::class.java, name).allowMainThreadQueries().addMigrations(*CaptureDatabase.MIGRATIONS).build()
        try {
            assertEquals(CaptureDatabase.VERSION, db.openHelper.writableDatabase.version)
            assertEquals(true, db.transcribe().segment(1).separateSpeakers)
            assertNull(db.transcribe().segment(2).separateSpeakers)
        } finally { db.close(); context.deleteDatabase(name) }
    }
}
