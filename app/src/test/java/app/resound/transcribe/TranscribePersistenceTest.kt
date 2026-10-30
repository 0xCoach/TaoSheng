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
class TranscribePersistenceTest {
    private lateinit var db: CaptureDatabase
    private lateinit var context: Context
    private lateinit var dao: TranscribeDao
    private val created = mutableListOf<File>()
    @Before fun setup() {
        context = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(context, CaptureDatabase::class.java).allowMainThreadQueries().build()
        dao = db.transcribe()
    }
    @After fun close() { db.close(); created.forEach { it.delete() } }
    private suspend fun insert(start: Long, status: String = "Pending", attempts: Int = 0, complete: Long? = null, retry: Long? = null): SegmentEntity {
        val file = File.createTempFile("segment", ".ogg", context.cacheDir).apply { writeBytes(byteArrayOf(1)); created.add(this) }
        db.capture().segment(SegmentEntity(startAt = start, endAt = start + 3000, sourceId = "mic", path = file.path, sizeBytes = 1,
            status = status, attempts = attempts, completedAt = complete, nextRetryAt = retry))
        return dao.observeSegments().first().first { it.startAt == start }
    }
    private fun provider(action: suspend (AsrAudio, suspend () -> Unit) -> AsrResult) = object : AsrProvider {
        override suspend fun recognize(audio: AsrAudio, key: String, beforeAudio: suspend () -> Unit) = action(audio, beforeAudio)
    }
    @Test fun timeoutContinuesRoundAndYieldsToOtherSegmentsOnRetry() = runBlocking {
        val long = insert(1000)
        val short = insert(2000)
        val order = mutableListOf<String>()
        var clock = 10_000L
        val runner = TranscribeRunner(dao, provider { audio, _ ->
            order.add(audio.file.path)
            if (audio.file.path == long.path) throw AsrException(AsrError.Timeout)
            AsrResult(emptyList(), 3000, null)
        }, { "key" }, { _, _ -> }, now = { clock })
        assertTrue(runner.run())
        assertEquals(listOf(long.path, short.path), order)
        assertEquals("Empty", dao.segment(short.id).status)
        clock = dao.segment(long.id).nextRetryAt!!
        val fresh = insert(3000)
        val otherRetry = insert(4000, "Failed", attempts = 1, retry = clock)
        order.clear()
        assertTrue(runner.run())
        assertEquals(listOf(fresh.path, otherRetry.path, long.path), order)
        assertEquals(2, dao.segment(long.id).attempts)
        assertNotNull(dao.segment(long.id).nextRetryAt)
    }
    @Test fun progressCountsSuccessEmptyAndFailureFromFixedRoundSnapshot() = runBlocking {
        val success = insert(1000)
        val empty = insert(2000)
        insert(3000)
        val progress = mutableListOf<Pair<Int, Int>>()
        var added = false
        val runner = TranscribeRunner(dao, provider { audio, _ ->
            if (!added) { added = true; insert(4000) } // 中途新增留到下一轮，分母不跳动。
            when (audio.file.path) {
                success.path -> AsrResult(listOf(AsrSentence(500, 2500, "完成", "0")), 3000, null)
                empty.path -> AsrResult(emptyList(), 3000, null)
                else -> throw AsrException(AsrError.Audio)
            }
        }, { "key" }, { _, _ -> }, updateProgress = { total, done -> progress.add(total to done) })
        runner.run()
        assertEquals(listOf(3 to 0, 3 to 1, 3 to 2, 3 to 3, 0 to 0), progress)
        assertEquals("Pending", dao.observeSegments().first().single { it.startAt == 4000L }.status)
        val line = dao.day(0, 2000).first().single().lines.single()
        assertEquals(1500L, line.startAt); assertEquals(3500L, line.endAt)
    }
    @Test fun startupRecoversEvenFifthInterruptedAttemptWithoutUploadOrUsageLoss() = runBlocking {
        val row = insert(1000, "Running", attempts = 5)
        dao.reserveAudio("2026-10-23", 3000, row.id)
        val runner = TranscribeRunner(dao, provider { _, _ -> error("启动恢复不得上传") },
            { error("启动恢复不读取密钥") }, { _, _ -> }, now = { 10_000 })
        runner.recoverOnStartup()
        runner.recoverOnStartup() // 恢复本身幂等，不继续减少次数。
        val recovered = dao.segment(row.id)
        assertEquals("Failed", recovered.status)
        assertEquals(4, recovered.attempts)
        assertEquals(10_000L, recovered.nextRetryAt)
        assertEquals(3000L, recovered.sentMillis)
        assertEquals(listOf(row.id), dao.candidates(10_000).map { it.id })
    }
    @Test fun startupDoesNotResetLiveUploadAndCancellationClearsProgress() = runBlocking {
        val row = insert(1000)
        insert(2000)
        val entered = CompletableDeferred<Unit>()
        val progress = mutableListOf<Pair<Int, Int>>()
        val runner = TranscribeRunner(dao, provider { _, _ -> entered.complete(Unit); awaitCancellation() },
            { "key" }, { _, _ -> }, updateProgress = { total, done -> progress.add(total to done) })
        val job = launch { runner.run() }
        entered.await()
        try {
            runner.recoverOnStartup()
            assertEquals("Running", dao.segment(row.id).status)
            assertFalse(runner.run())
            assertEquals(listOf(2 to 0), progress)
        } finally { job.cancelAndJoin() }
        assertEquals(listOf(2 to 0, 2 to 1, 0 to 0), progress)
        assertEquals("Failed", dao.segment(row.id).status)
    }
    @Test fun migrationPreservesOldRowsAndRoomValidatesWholeSchema() = runBlocking {
        val name = "migration-003.db"
        context.deleteDatabase(name)
        val schema = JSONObject(File("schemas/app.resound.data.CaptureDatabase/1.json").readText()).getJSONObject("database")
        context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { old ->
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i)
                old.execSQL(entity.getString("createSql").replace("\u0024{TABLE_NAME}", entity.getString("tableName")))
                val indices = entity.getJSONArray("indices")
                for (j in 0 until indices.length()) old.execSQL(indices.getJSONObject(j).getString("createSql").replace("\u0024{TABLE_NAME}", entity.getString("tableName")))
            }
            val queries = schema.getJSONArray("setupQueries")
            for (i in 0 until queries.length()) old.execSQL(queries.getString(i))
            old.execSQL("INSERT INTO segments VALUES (7,1000,4000,'mic','/old/audio.ogg',123,'待转写')")
            old.execSQL("INSERT INTO watchdog (id,at,type) VALUES (1,1000,'Killed')")
            old.execSQL("INSERT INTO heartbeats VALUES (1,1000,900,1,'session')")
            old.execSQL("INSERT INTO battery VALUES (1,1000,80,0)")
            old.version = 1
        }
        val migrated = Room.databaseBuilder(context, CaptureDatabase::class.java, name).allowMainThreadQueries()
            .addMigrations(*CaptureDatabase.MIGRATIONS).build()
        try {
            val writable = migrated.openHelper.writableDatabase // Room 逐表、列、索引和外键校验真实迁移。
            val segment = migrated.transcribe().segment(7)
            assertEquals("Pending", segment.status)
            assertEquals("/old/audio.ogg", segment.path)
            assertEquals(0, segment.attempts); assertEquals(0L, segment.sentMillis)
            for (table in listOf("watchdog", "heartbeats", "battery")) writable.query("SELECT COUNT(*) FROM $table").use {
                assertTrue(it.moveToFirst()); assertEquals(1, it.getInt(0))
            }
            migrated.transcribe().reserveAudio("2026-10-23", 3000, 7)
            assertEquals(3000, migrated.transcribe().observeUsage().first().single().sentMillis)
        } finally { migrated.close(); context.deleteDatabase(name) }
    }
    @Test fun finalResultsReplaceLinesAndPreserveLedgerAcrossManualRetry() = runBlocking {
        val segment = insert(1000)
        var n = 0
        val runner = TranscribeRunner(dao, provider { _, send -> send(); n++; AsrResult(listOf(AsrSentence(0, 2000, "第${n}次", "0")), 3000, "log") },
            { "key" }, { _, _ -> }, now = { 10_000 }, today = { "2026-10-23" })
        assertFalse(runner.run())
        assertEquals("Done", dao.segment(segment.id).status)
        dao.update(dao.segment(segment.id).copy(status = "Failed"))
        dao.retryFailed()
        assertFalse(runner.run())
        val lines = dao.day(0, 5000).first().single().lines
        assertEquals(1, lines.size); assertEquals("第2次", lines.single().text)
        assertEquals(1000, lines.single().startAt); assertEquals(3000, lines.single().endAt)
        assertNull(lines.single().speakerName)
        assertEquals(6000, dao.observeUsage().first().single().sentMillis)
        assertEquals(6000, dao.segment(segment.id).sentMillis)
        assertEquals(2, dao.recentAttempts().size)
    }
    @Test fun authStopsRoundWithoutConsumingOtherSegments() = runBlocking {
        val earlier = insert(1000)
        val later = insert(5000)
        var lastError: String? = null
        var calls = 0
        val runner = TranscribeRunner(dao, provider { _, _ -> calls++; throw AsrException(AsrError.Auth) }, { "key" }, { _, error -> lastError = error })
        assertFalse(runner.run())
        assertEquals(1, calls)
        assertEquals(1, dao.segment(earlier.id).attempts)
        assertEquals("Pending", dao.segment(later.id).status)
        assertEquals(0, dao.segment(later.id).attempts)
        assertEquals("密钥无效或未开通", lastError)
        assertTrue(dao.observeUsage().first().isEmpty())
    }
    @Test fun timeoutBackoffStopsAfterFiveAndManualRetryResets() = runBlocking {
        val segment = insert(1000)
        var clock = 10_000L
        var calls = 0
        val runner = TranscribeRunner(dao, provider { _, send -> calls++; send(); throw AsrException(AsrError.Timeout) }, { "key" }, { _, _ -> },
            now = { clock }, today = { "2026-10-23" })
        repeat(5) { attempt ->
            assertEquals(attempt < 4, runner.run())
            val row = dao.segment(segment.id)
            assertEquals(attempt + 1, row.attempts)
            assertTrue(File(row.path).exists())
            if (attempt < 4) {
                assertTrue(runner.run()) // 尚未到重试时间，不多发。
                assertEquals(attempt + 1, calls)
                clock = row.nextRetryAt!!
            } else assertNull(row.nextRetryAt)
        }
        assertFalse(runner.run()); assertEquals(5, calls)
        assertEquals(15000, dao.observeUsage().first().single().sentMillis)
        dao.retryFailed(clock)
        assertEquals("Pending", dao.segment(segment.id).status)
        assertEquals(0, dao.segment(segment.id).attempts)
    }
    @Test fun networkFailureEndsRoundWithoutConsumingAttempts() = runBlocking {
        val first = insert(1000)
        val second = insert(2000)
        var calls = 0
        var clock = 10_000L
        val runner = TranscribeRunner(dao, provider { _, send -> calls++; send(); throw AsrException(AsrError.Network) }, { "key" }, { _, _ -> },
            now = { clock }, today = { "2026-10-23" })
        repeat(8) {
            assertTrue(runner.run())
            clock = dao.segment(first.id).nextRetryAt!!
        }
        assertEquals(8, calls) // 每轮只碰第一段，后面的段不陪跑
        assertEquals(0, dao.segment(first.id).attempts)
        assertEquals("Failed", dao.segment(first.id).status)
        assertEquals("Pending", dao.segment(second.id).status)
        assertEquals(0, dao.segment(second.id).attempts)
    }
    @Test fun cleanupDeletesOnlySuccessfulAudioAfterFullDayIncludingEmpty() = runBlocking {
        val day = TranscribePolicy.RETENTION_MILLIS
        val done = insert(1, "Done", complete = 100)
        val empty = insert(2, "Empty", complete = 100)
        val recent = insert(3, "Done", complete = 101)
        val pending = insert(4)
        val failed = insert(5, "Failed", complete = 1)
        val running = insert(6, "Running", attempts = 1)
        var error: String? = null
        val runner = TranscribeRunner(dao, provider { _, _ -> error("不应联网") }, { "" }, { _, message -> error = message }, now = { day + 100 }, ready = { false })
        runner.run()
        assertFalse(File(done.path).exists()); assertFalse(File(empty.path).exists())
        for (row in listOf(recent, pending, failed, running)) assertTrue(File(row.path).exists())
        assertEquals("Done", dao.segment(done.id).status)
        assertEquals("Failed", dao.segment(running.id).status)
        assertEquals("转写未就绪，请配置服务或下载本地模型", error)
    }
    @Test fun concurrentTriggersAreSkippedAndSegmentsUploadInTimeOrder() = runBlocking {
        insert(5000); insert(1000)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val order = mutableListOf<String>()
        val runner = TranscribeRunner(dao, provider { audio, send ->
            order.add(audio.file.path); entered.complete(Unit); release.await(); send(); AsrResult(emptyList(), 3000, null)
        }, { "key" }, { _, _ -> })
        val first = async { runner.run() }
        entered.await()
        assertFalse(runner.run())
        assertEquals(1, order.size)
        release.complete(Unit); first.await()
        val segments = dao.observeSegments().first().sortedBy { it.startAt }
        assertEquals(segments.map { it.path }, order)
        assertTrue(segments.all { it.status == "Empty" })
    }
    @Test fun cancellationPreservesAudioAndAccountsAttemptWithoutPartialLines() = runBlocking {
        val row = insert(1000)
        val sent = CompletableDeferred<Unit>()
        val runner = TranscribeRunner(dao, provider { _, send -> send(); sent.complete(Unit); awaitCancellation() }, { "key" }, { _, _ -> })
        val job = launch { runner.run() }
        sent.await(); job.cancelAndJoin()
        assertEquals("Failed", dao.segment(row.id).status)
        assertEquals("Interrupted", dao.recentAttempts().single().errorType)
        assertEquals(3000, dao.segment(row.id).sentMillis)
        assertTrue(File(row.path).exists())
        assertTrue(dao.day(0, 5000).first().single().lines.isEmpty())
    }
}
