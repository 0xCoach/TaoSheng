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

/** 卡 134：服务方 400 拒收时不再无限重试卡住整条队列。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, application = Application::class)
class DigestRejected134Test {
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

    private val prefs get() = context.getSharedPreferences("digest-state", 0)
    private fun inflight() = prefs.getString("inflight-lines", "")!!.split(',').map(String::toLong)
    private fun reject() = LlmException(LlmError.Server, 400, LlmClient.rejectionDetail("Content Exists Risk"))

    @Test fun contentRejectionSkipsOnlyTheRejectedFewLines() = runBlocking {
        val calls = mutableListOf<String>()
        val runner = runner { _, _, system, _ ->
            if (system == DigestRunner.PROBE_SYSTEM) { calls += "probe"; return@runner LlmReply("""{"ok":true}""", 1, 1) }
            val ids = inflight()
            calls += "${ids.first()}-${ids.last()}"
            if (7L in ids) throw reject()
            LlmReply("""{"events":[{"from":1,"to":1,"title":"事件${ids.first()}"}],"items":[]}""", 1, 1)
        }
        assertFalse(runner.run())
        assertEquals(listOf("1-40", "probe", "1-20", "1-10", "1-5", "6-10", "11-20", "21-40"), calls)
        assertTrue(db.memory().undigestedLines().isEmpty())
        assertEquals(listOf("事件1", "事件11", "事件21"), db.redoData().events(0, 50_000).sortedBy { it.startAt }.map { it.title })
        assertNull(runner.last.value.second)
    }

    @Test fun configRejectionStopsWithoutDroppingAnything() = runBlocking {
        var calls = 0
        val runner = runner { _, _, _, _ -> calls++; throw reject() }
        assertFalse(runner.run())
        assertEquals(2, calls)
        assertEquals(40, db.memory().undigestedLines().size)
        assertTrue(runner.last.value.second!!.contains("检测"))
    }

    @Test fun networkTroubleDuringProbeKeepsNormalRetry() = runBlocking {
        val runner = runner { _, _, system, _ ->
            if (system == DigestRunner.PROBE_SYSTEM) throw LlmException(LlmError.Network)
            throw reject()
        }
        assertTrue(runner.run())
        assertEquals(40, db.memory().undigestedLines().size)
    }

    @Test fun rejectionReasonUsesFixedWords() {
        assertEquals("大模型服务认为内容不合规，拒收了这批原话", LlmClient.rejectionDetail("""{"error":{"message":"Content Exists Risk"}}"""))
        assertEquals("这批原话太长，大模型服务拒收", LlmClient.rejectionDetail("This model's maximum context length is 65536 tokens"))
        assertEquals("大模型服务拒收了这次请求", LlmClient.rejectionDetail("bad"))
        assertTrue(LlmException(LlmError.Server, 400).rejected)
        assertFalse(LlmException(LlmError.Server, 500).rejected)
    }
}
