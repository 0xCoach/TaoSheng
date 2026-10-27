package app.resound.digest

import android.app.Application
import android.content.Context
import androidx.room.Room
import app.resound.data.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, application = Application::class)
class DigestProgressTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private fun db() = Room.inMemoryDatabaseBuilder(context, CaptureDatabase::class.java).allowMainThreadQueries().build()
    private suspend fun seed(db: CaptureDatabase) {
        db.capture().segment(SegmentEntity(id = 1, startAt = 0, endAt = 301000, sourceId = "test", path = "/synthetic", sizeBytes = 0, status = "Done"))
        db.transcribe().insertLines((1L..301).map { TranscriptLineEntity(id = it, segmentId = 1, startAt = it * 1000, endAt = it * 1000 + 900, text = "合成测试", speakerTag = null) })
    }
    private fun runner(db: CaptureDatabase, action: suspend () -> LlmReply): DigestRunner {
        val client = LlmClient()
        return DigestRunner(context, db.memory(), client, AndroidDigestSettings(context, client), { "测试" }, { true }, now = { 9_000_000 }, readKey = { "synthetic" }, request = { _, _, _, _ -> action() })
    }
    private fun success() = LlmReply("""{"events":[],"items":[]}""", 1, 1)
    @Test fun elapsedShowsHundredthsAndFreezesAtCompletion() {
        val progress=DigestProgress(total=1,done=0,batch=1,batches=1,batchStartedAt=1000,startedAt=1000)
        assertEquals("第 1/1 批 · 等待模型 1.25 秒",progress.detail(2259))
        assertEquals("第 1/1 批 · 等待模型 1.26 秒",progress.detail(2260))
        val finished=progress.copy(done=1,finishedAt=2260)
        assertEquals("本轮整理完成 · 用时 1.26 秒",finished.completion)
        assertEquals("第 1/1 批 · 等待模型 1.26 秒",finished.detail(9000))
        assertNull(progress.copy(finishedAt=2260).completion)
    }
    @Test fun countsOnlySavedBatchesAndFinishesAtTotal() = runBlocking {
        val db = db()
        try {
            seed(db); var calls = 0
            lateinit var runner: DigestRunner
            runner = runner(db) {
                calls++
                assertTrue(runner.running.value)
                assertEquals(301, runner.progress.value.total)
                assertEquals(calls, runner.progress.value.batch)
                assertEquals(if (calls == 1) 0 else 300, runner.progress.value.done)
                success()
            }
            assertFalse(runner.run()); assertEquals(2, calls)
            assertEquals(301, runner.progress.value.done)
            assertEquals("整理 301/301 句", runner.progress.value.label)
            assertFalse(runner.running.value); assertTrue(db.memory().undigestedLines().isEmpty())
        } finally { db.close() }
    }
    @Test fun failedBatchDoesNotAdvanceOrEraseUnprocessedLines() = runBlocking {
        val db = db()
        try {
            seed(db); var calls = 0
            val runner = runner(db) { if (++calls == 2) throw LlmException(LlmError.Network); success() }
            assertTrue(runner.run())
            assertEquals(300, runner.progress.value.done); assertEquals(301, runner.progress.value.total)
            assertEquals(1, db.memory().undigestedLines().size)
            assertFalse(runner.running.value); assertEquals(LlmError.Network.human, runner.last.value.second)
        } finally { db.close() }
    }
    @Test fun cancellationClearsRunningWithoutFakeProgress() = runBlocking {
        val db = db()
        try {
            seed(db); val entered = CompletableDeferred<Unit>()
            val runner = runner(db) { entered.complete(Unit); awaitCancellation() }
            val job = launch { runner.run() }
            entered.await(); job.cancelAndJoin()
            assertFalse(runner.running.value); assertEquals(0, runner.progress.value.done)
            assertEquals(301, db.memory().undigestedLines().size)
        } finally { db.close() }
    }
}
