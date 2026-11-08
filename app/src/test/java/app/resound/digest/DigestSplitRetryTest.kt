package app.resound.digest

import android.app.Application
import android.content.Context
import androidx.room.Room
import app.resound.data.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, application = Application::class)
class DigestSplitRetryTest {
    private lateinit var context: Context
    private lateinit var db: CaptureDatabase
    private val lines = (1L..40L).map { i -> TranscriptLineEntity(i, 1, i * 1000, i * 1000 + 500, "合成原话$i", null) }

    @Before fun setup() {
        context = RuntimeEnvironment.getApplication()
        listOf("digest-state", "digest").forEach { context.getSharedPreferences(it, 0).edit().clear().commit() }
        db = Room.inMemoryDatabaseBuilder(context, CaptureDatabase::class.java).allowMainThreadQueries().build()
        runBlocking {
            db.capture().segment(SegmentEntity(1, 1000, 41_000, "mic", "/synthetic", 0, "Done"))
            db.transcribe().insertLines(lines)
        }
    }

    @After fun finish() { db.close() }

    private fun runner(request: suspend (LlmPreset, String, String, String) -> LlmReply): DigestRunner {
        val client = LlmClient()
        return DigestRunner(context, db.memory(), client, AndroidDigestSettings(context, client),
            { "合成模板" }, { true }, now = { 3_600_000 }, readKey = { "synthetic" }, request = request)
    }

    private fun reply(title: String) = LlmReply("""{"events":[{"from":1,"to":20,"title":"$title"}],"items":[]}""", 1, 1)

    @Test fun malformedBatchRetriesBothHalvesWithSeparateIdentitiesInSameRun() = runBlocking {
        val prefs = context.getSharedPreferences("digest-state", 0)
        val calls = mutableListOf<List<Long>>()
        val identities = mutableListOf<String>()
        val runner = runner { _, _, _, _ ->
            val ids = prefs.getString("inflight-lines", "")!!.split(',').map(String::toLong)
            calls += ids
            identities += prefs.getString("inflight", null)!!
            assertEquals(0, prefs.getInt("fail-1", 0))
            assertEquals(0, prefs.getInt("fail-21", 0))
            assertTrue(db.memory().pendingDigests().isEmpty())
            if (ids.size == 40) LlmReply("""{"events":[""", 1, 1) else reply("新事件${ids.first()}")
        }
        assertFalse(runner.run())
        assertEquals(listOf(lines, lines.take(20), lines.drop(20)).map { batch -> batch.map { it.id } }, calls)
        assertEquals(3, identities.distinct().size)
        assertTrue(identities.all { it.startsWith("batch-v2:") })
        assertEquals(listOf("新事件1", "新事件21"), db.redoData().events(0, 50_000).sortedBy { it.startAt }.map { it.title })
        assertTrue(db.memory().undigestedLines().isEmpty())
        assertTrue(db.memory().pendingDigests().isEmpty())
        assertFalse(prefs.contains("inflight"))
        assertNull(runner.last.value.second)
    }

    @Test fun onlyUnsplittableHalfCountsTowardThreeFailuresBeforeMovingOn() = runBlocking {
        val prefs = context.getSharedPreferences("digest-state", 0)
        val calls = mutableListOf<List<Long>>()
        val runner = runner { _, _, _, _ ->
            val ids = prefs.getString("inflight-lines", "")!!.split(',').map(String::toLong)
            calls += ids
            if (ids.first() == 1L) throw LlmException(LlmError.Format)
            reply("后一半成功")
        }
        repeat(2) { attempt ->
            assertTrue(runner.run())
            assertEquals(attempt + 1, prefs.getInt("fail-1", 0))
            assertEquals(40, db.memory().undigestedLines().size)
            assertTrue(db.redoData().events(0, 50_000).isEmpty())
        }
        assertFalse(runner.run())
        assertEquals(listOf(40, 20, 40, 20, 40, 20, 20), calls.map { it.size })
        assertEquals(lines.drop(20).map { it.id }, calls.last())
        assertFalse(prefs.contains("fail-1"))
        assertFalse(prefs.contains("inflight"))
        assertTrue(db.memory().pendingDigests().isEmpty())
        assertTrue(db.memory().undigestedLines().isEmpty())
        val event = db.redoData().events(0, 50_000).single()
        assertEquals("后一半成功", event.title)
        val saved = db.memory().refreshLines(lines.map { it.id })
        assertTrue(saved.take(20).all { it.eventId == null })
        assertTrue(saved.drop(20).all { it.eventId == event.id })
    }
}
