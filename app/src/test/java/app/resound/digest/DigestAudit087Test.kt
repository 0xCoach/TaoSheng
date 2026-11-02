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

/** 审计复现，不应把当前缺陷当作后续实现约定。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, application = Application::class)
class DigestAudit087Test {
    @Test fun localFailureAfterModelReplyDoesNotConsumeRetryBudget() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val db = Room.inMemoryDatabaseBuilder(context, CaptureDatabase::class.java).allowMainThreadQueries().build()
        val prefs = context.getSharedPreferences("digest-state", 0)
        prefs.edit().clear().commit()
        try {
            db.capture().segment(SegmentEntity(id = 1, startAt = 0, endAt = 1000,
                sourceId = "mic", path = "/synthetic-audit", sizeBytes = 0, status = "Done"))
            db.transcribe().insertLines(listOf(TranscriptLineEntity(id = 1, segmentId = 1, startAt = 0,
                endAt = 1000, text = "合成审计数据", speakerTag = null)))
            val client = LlmClient()
            var requests = 0
            val runner = DigestRunner(context, db.memory(), client, AndroidDigestSettings(context, client),
                { "合成审计模板" }, { true }, now = { 3_600_000 }, readKey = { "synthetic" },
                isMedia = { _, _ -> throw android.database.sqlite.SQLiteException("synthetic local read failure") },
                request = { _, _, _, _ ->
                    requests++
                    LlmReply("""{"events":[{"from":1,"to":1,"scene":"工作","title":"合成","summary":"合成"}],"items":[]}""", 100, 20)
                })
            repeat(DigestRunner.MAX_FORMAT_FAILURES + 1) { assertTrue(runner.run(force = true)) }
            assertEquals(4, requests)
            assertEquals(1, db.memory().undigestedLines().size)
            assertEquals("已确认缺陷：本地异常未记入预算", 0, prefs.getInt("fail-1", 0))
        } finally { db.close(); prefs.edit().clear().commit() }
    }
}
