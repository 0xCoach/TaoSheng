package app.resound.digest

import android.app.Application
import android.content.Context
import androidx.room.Room
import app.resound.data.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, application = Application::class)
class DigestForce053Test {
    @Test fun forceClosesOngoingConversationButOnlyManualBypassesSkillGap() = runBlocking {
        val context: Context = RuntimeEnvironment.getApplication()
        val db = Room.inMemoryDatabaseBuilder(context, CaptureDatabase::class.java).allowMainThreadQueries().build()
        try {
            val calls = mutableListOf<Boolean>()
            val client = LlmClient()
            val runner = DigestRunner(context, db.memory(), client, AndroidDigestSettings(context, client),
                { "测试" }, { true }, now = { 2000 }, readKey = { "synthetic" }, afterDigest = { calls += it },
                request = { _, _, _, _ -> LlmReply("""{"events":[{"from":1,"to":1,"title":"测试","summary":"内容"}],"items":[]}""", 1, 1) })
            suspend fun seed(id: Long) {
                db.capture().segment(SegmentEntity(id, 1000, 1500, "mic", "/synthetic-$id", 0, "Done"))
                db.transcribe().insertLines(listOf(TranscriptLineEntity(id = id, segmentId = id, startAt = 1000,
                    endAt = 1500, text = "还在说话", speakerTag = null)))
            }
            seed(1)
            runner.run(force = false, manual = false)
            assertTrue(calls.isEmpty()); assertEquals(1, db.memory().undigestedLines().size)
            runner.run(force = true, manual = false)
            assertEquals(listOf(false), calls); assertTrue(db.memory().undigestedLines().isEmpty())
            seed(2); runner.run(force = true, manual = true)
            assertEquals(listOf(false, true), calls)
        } finally { db.close() }
    }
}
