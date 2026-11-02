package app.resound.digest

import android.app.Application
import androidx.room.Room
import app.resound.data.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** 087 审计问题的修复回归。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, application = Application::class)
class DigestAudit087Test {
    @Test fun sourceDeletedDuringRequestCannotReappearInPendingResponses() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val db = Room.inMemoryDatabaseBuilder(context, CaptureDatabase::class.java).allowMainThreadQueries().build()
        val prefs = context.getSharedPreferences("digest-state", 0)
        prefs.edit().clear().commit()
        try {
            db.capture().segment(SegmentEntity(1, 0, 1000, "mic", "/synthetic", 0, "Done"))
            db.transcribe().insertLines(listOf(TranscriptLineEntity(1, 1, 0, 1000, "合成原话", null)))
            val client = LlmClient()
            val runner = DigestRunner(context, db.memory(), client, AndroidDigestSettings(context, client),
                { "合成" }, { true }, now = { 3_600_000 }, readKey = { "synthetic" }, request = { _, _, _, _ ->
                    db.privacy().deleteLine(1)
                    LlmReply("""{"events":[],"items":[]}""", 1, 1)
                })
            assertFalse(runner.run(force = true))
            assertTrue(db.memory().pendingDigests().isEmpty())
            assertTrue(db.memory().undigestedLines().isEmpty())
        } finally { db.close(); prefs.edit().clear().commit() }
    }
    @Test fun localFailureReplaysPersistedReplyWithoutAnotherRequestOrUsage() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val file = java.io.File(context.cacheDir, "digest-replay-${java.util.UUID.randomUUID()}.db")
        var db = CaptureDatabase.open(context, file.path)
        val prefs = context.getSharedPreferences("digest-state", 0)
        prefs.edit().clear().commit()
        try {
            db.capture().segment(SegmentEntity(id = 1, startAt = 0, endAt = 1000,
                sourceId = "mic", path = "/synthetic-audit", sizeBytes = 0, status = "Done"))
            db.transcribe().insertLines(listOf(TranscriptLineEntity(id = 1, segmentId = 1, startAt = 0,
                endAt = 1000, text = "合成审计数据", speakerTag = null)))
            val client = LlmClient()
            var requests = 0
            var failLocal = true
            fun runner() = DigestRunner(context, db.memory(), client, AndroidDigestSettings(context, client),
                { "合成审计模板" }, { true }, now = { 3_600_000 }, readKey = { "synthetic" },
                isMedia = { _, _ -> if (failLocal) throw android.database.sqlite.SQLiteException("synthetic local read failure") else false },
                request = { _, _, _, _ ->
                    requests++
                    LlmReply("""{"events":[{"from":1,"to":1,"scene":"工作","title":"合成","summary":"合成"}],"items":[]}""", 100, 20)
                })
            // 每次关闭、重开数据库并重建 runner，不能依靠内存缓存。
            repeat(DigestRunner.MAX_FORMAT_FAILURES + 1) {
                assertEquals(it < 2, runner().run(force = true))
                db.close(); db = CaptureDatabase.open(context, file.path)
            }
            assertEquals(1, requests)
            assertEquals(1, db.memory().undigestedLines().size)
            assertEquals(1, db.memory().pendingDigests().size)
            failLocal = false
            assertFalse(runner().run(force = true))
            assertTrue(db.memory().undigestedLines().isEmpty())
            assertTrue(db.memory().pendingDigests().isEmpty())
            assertEquals(1, requests)
            db.openHelper.readableDatabase.query("SELECT SUM(calls), SUM(promptTokens) FROM llm_usage_v2").use {
                assertTrue(it.moveToFirst()); assertEquals(1, it.getInt(0)); assertEquals(100, it.getInt(1))
            }
        } finally { db.close(); file.delete(); prefs.edit().clear().commit() }
    }

    @Test fun uncertainRequestStopsAutomaticResendAndDeletingSourceClearsReplay() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val db = Room.inMemoryDatabaseBuilder(context, CaptureDatabase::class.java)
            .addCallback(PendingDigestSchema.callback).allowMainThreadQueries().build()
        val prefs = context.getSharedPreferences("digest-state", 0)
        prefs.edit().clear().putString("inflight", "unknown-result").putString("inflight-lines", "1").commit()
        try {
            db.capture().segment(SegmentEntity(1, 0, 1000, "mic", "/synthetic", 0, "Done"))
            db.transcribe().insertLines(listOf(TranscriptLineEntity(1, 1, 0, 1000, "合成原话", null)))
            val client = LlmClient()
            var requests = 0
            val runner = DigestRunner(context, db.memory(), client, AndroidDigestSettings(context, client),
                { "合成模板" }, { true }, now = { 3_600_000 }, readKey = { "synthetic" },
                isMedia = { _, _ -> error("synthetic local failure") },
                request = { _, _, _, _ -> requests++; LlmReply("""{"events":[{"from":1,"to":1}],"items":[]}""", 1, 1) })
            assertFalse(runner.run(force = true)); assertEquals(0, requests)
            assertTrue(runner.run(force = true, manual = true)); assertEquals(1, requests)
            assertEquals(1, db.memory().pendingDigests().size)
            db.privacy().deleteLine(1)
            assertTrue(db.memory().pendingDigests().isEmpty())
            assertFalse(runner.run(force = true)); assertEquals(1, requests)
        } finally { db.close(); prefs.edit().clear().commit() }
    }
}
