package app.resound.transcribe

import android.app.Application
import android.content.Context
import androidx.room.Room
import app.resound.data.*
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
class Failure065Test {
    private val context get() = RuntimeEnvironment.getApplication() as Context
    private lateinit var db: CaptureDatabase
    private val dao get() = db.transcribe()
    private lateinit var directory: File
    private val day = 86_400_000L
    private var clock = 10 * day
    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(context, CaptureDatabase::class.java).allowMainThreadQueries().build()
        directory = File(context.cacheDir, "failure065").apply { mkdirs() }
    }
    @After fun close() { db.close(); directory.deleteRecursively() }
    private suspend fun row(id: Long, status: String = "Pending", first: Long? = null, retry: Long? = null,
        error: String? = null, completed: Long? = null): SegmentEntity {
        val file = File(directory, "$id.ogg").apply { writeBytes(byteArrayOf(1)) }
        val row = SegmentEntity(id, id, id + 1000, "mic", file.path, 1, status,
            nextRetryAt = retry, error = error, completedAt = completed, firstFailedAt = first)
        db.capture().segment(row)
        return row
    }
    private fun runner(type: AsrError = AsrError.Audio) = TranscribeRunner(dao, object : AsrProvider {
        override suspend fun recognize(audio: AsrAudio, key: String, beforeAudio: suspend () -> Unit): AsrResult {
            throw AsrException(type)
        }
    }, { "key" }, { _, _ -> }, now = { clock }, deferAudioCleanup = true)
    private suspend fun failedCount() = dao.observeSegments().first().count { it.status == "Failed" }

    @Test fun audioFailureIsAbandonedAndMissingFileHasReadableReason() = runBlocking {
        row(1); val missing = row(2); File(missing.path).delete()
        assertFalse(runner().run())
        assertEquals("Abandoned", dao.segment(1).status)
        assertEquals("录音损坏，无法转写", dao.segment(1).error)
        assertEquals("录音文件已不存在", dao.segment(2).error)
        assertEquals(clock, dao.segment(1).completedAt)
        assertEquals(0, failedCount()); assertTrue(dao.candidates(clock).isEmpty())
    }
    @Test fun startupConvertsOldAudioFailuresOnceAndKeepsRecoverableFailures() = runBlocking {
        row(1, "Failed", error = AsrError.Audio.human)
        row(2, "Failed", error = "录音文件已不存在")
        row(3, "Failed", retry = clock, error = AsrError.Network.human)
        runner().recoverOnStartup()
        val judged = dao.segment(1).completedAt
        clock += day; runner().recoverOnStartup()
        assertEquals("Abandoned", dao.segment(1).status)
        assertEquals("Abandoned", dao.segment(2).status)
        assertEquals(judged, dao.segment(1).completedAt)
        assertEquals(1, failedCount())
    }
    @Test fun ignoreThenIndividualRetryResetsAndMissingAudioBecomesAbandoned() = runBlocking {
        val old = row(1, "Failed", first = clock - day, retry = clock)
        dao.update(old.copy(attempts = 4))
        dao.ignoreFailed(clock)
        assertEquals(0, failedCount()); assertEquals("Quiet", dao.segment(1).status)
        assertTrue(dao.candidates(clock).isEmpty()); assertNull(dao.earliestRetry())
        dao.retryFailed(clock); assertEquals("Quiet", dao.segment(1).status)
        File(old.path).delete()
        assertEquals(1, dao.retrySegment(1))
        val reset = dao.segment(1)
        assertEquals("Pending", reset.status); assertEquals(0, reset.attempts)
        assertNull(reset.firstFailedAt); assertNull(reset.completedAt)
        runner().run(); assertEquals("Abandoned", dao.segment(1).status)
    }
    @Test fun agingUsesFirstFailureAcrossBulkRetryAndStopsAfterThreeDays() = runBlocking {
        row(1)
        val r = runner(AsrError.Network)
        r.run(); val first = dao.segment(1).firstFailedAt
        clock += day; dao.retryFailed(clock); r.run()
        assertEquals(first, dao.segment(1).firstFailedAt)
        clock = first!! + 3 * day
        dao.refreshFailures(clock); assertEquals("Failed", dao.segment(1).status)
        clock++
        assertTrue(dao.candidates(clock).isEmpty())
        assertEquals("Quiet", dao.segment(1).status)
        assertEquals("多次转写失败", dao.segment(1).error)
        assertEquals(0, failedCount()); assertNull(dao.earliestRetry())
        dao.retryFailed(clock); assertEquals("Quiet", dao.segment(1).status)
    }
    @Test fun retentionStartsAtJudgmentButExpiredImportsStopWaiting() = runBlocking {
        val abandoned = row(1, "Abandoned", completed = clock - 3 * day)
        val quiet = row(2, "Quiet", completed = clock - 3 * day)
        val recent = row(3, "Abandoned", completed = clock - 3 * day + 1)
        val waiting = row(4, "Failed", first = clock - day, retry = clock + 1000)
        val imported = row(5, "Quiet", completed = clock - 3 * day)
        val importedRecent = row(6, "Abandoned", completed = clock - day)
        val micWaiting = row(7, "Failed", first = clock - day, retry = clock + 1000)
        for (s in listOf(imported, importedRecent, waiting)) {
            dao.update(s.copy(sourceId = "import"))
            File(s.path).setLastModified(clock - 5 * day)
        }
        cleanupImportedAudio(dao, clock)
        // 卡 078：失败导入按文件生成时间硬限三天；麦克风录音仍按判定时间保留。
        listOf(imported, importedRecent, waiting).forEach {
            assertFalse(File(it.path).exists()); assertEquals("Abandoned", dao.segment(it.id).status)
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val prefs = context.getSharedPreferences("failure065", 0)
            prefs.edit().clear().commit()
            val repo = LocalOriginalAudio(dao, prefs, scope, playback = PlaybackCaptureHold(scope, {}), now = { clock })
            assertEquals(2, repo.cleanup().segments)
            assertFalse(File(abandoned.path).exists()); assertFalse(File(quiet.path).exists())
            assertTrue(File(recent.path).exists()); assertTrue(File(micWaiting.path).exists())
        } finally { scope.cancel() }
    }
    @Test fun deletionRestrictsStatesAndRemovesDerivedRowsWithoutRefundingUsage() = runBlocking {
        for ((index, state) in listOf("Failed", "Quiet", "Abandoned", "Pending", "Running", "Done", "Empty", "SkippedPlayback").withIndex()) {
            val id = index + 1L
            val segment = row(id, state)
            dao.insertLines(listOf(TranscriptLineEntity(segmentId = id, startAt = id, endAt = id + 1, text = "测试", speakerTag = null)))
            dao.attempt(AsrAttemptEntity(segmentId = id, at = clock, result = "Failed", errorType = "Audio", elapsedMillis = 1, sentMillis = 1, logId = null))
            dao.reserveAudio("2026-10-29", 1, id)
            assertEquals(index < 3, dao.deleteFailed(id))
            assertEquals(index >= 3, File(segment.path).exists())
            assertEquals(index >= 3, dao.findSegment(id) != null)
        }
        assertEquals(5, dao.day(0, 100).first().sumOf { it.lines.size })
        assertEquals(5, dao.recentAttempts().size)
        assertEquals(8L, dao.observeUsage().first().single().sentMillis)
    }
    @Test fun bulkRetryOnlyRequeuesFailuresStillNeedingAttention() = runBlocking {
        row(1, "Failed", first = clock - day)
        row(2, "Failed", first = clock - 4 * day)
        row(3, "Failed", error = AsrError.Audio.human)
        row(4, "Quiet", completed = clock)
        row(5, "Abandoned", completed = clock)
        dao.retryFailed(clock)
        assertEquals(listOf("Pending", "Quiet", "Abandoned", "Quiet", "Abandoned"), (1L..5L).map { dao.segment(it).status })
        assertEquals(listOf(1L), dao.candidates(clock).map { it.id })
    }
    @Test fun version20MigratesAndRecoversFirstFailureFromLedger() = runBlocking {
        val name = "migration065.db"; context.deleteDatabase(name)
        val schema = JSONObject(File("schemas/app.resound.data.CaptureDatabase/20.json").readText()).getJSONObject("database")
        context.openOrCreateDatabase(name, 0, null).use { old ->
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i)
                fun sql(s: String) = s.replace("\u0024{TABLE_NAME}", entity.getString("tableName"))
                old.execSQL(sql(entity.getString("createSql")))
                val indices = entity.optJSONArray("indices") ?: org.json.JSONArray()
                for (j in 0 until indices.length()) old.execSQL(sql(indices.getJSONObject(j).getString("createSql")))
            }
            val queries = schema.getJSONArray("setupQueries")
            for (i in 0 until queries.length()) old.execSQL(queries.getString(i))
            old.execSQL("INSERT INTO segments (id,startAt,endAt,sourceId,path,sizeBytes,status) VALUES (1,1,2,'mic','/old.ogg',1,'Failed')")
            old.execSQL("INSERT INTO asr_attempts (segmentId,at,result,errorType,elapsedMillis,sentMillis) VALUES (1,100,'Failed','Network',1,1)")
            old.version = 20
        }
        val migrated = Room.databaseBuilder(context, CaptureDatabase::class.java, name).allowMainThreadQueries()
            .addMigrations(*CaptureDatabase.MIGRATIONS).build()
        try {
            assertEquals(CaptureDatabase.VERSION, migrated.openHelper.writableDatabase.version)
            assertEquals(100L, migrated.transcribe().segment(1).firstFailedAt)
            migrated.transcribe().refreshFailures(clock)
            assertEquals("Quiet", migrated.transcribe().segment(1).status)
        } finally { migrated.close(); context.deleteDatabase(name) }
    }
}
