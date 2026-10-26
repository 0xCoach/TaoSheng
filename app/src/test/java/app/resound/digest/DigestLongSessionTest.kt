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

/** 一直在说、段落不结束时，先整理已稳定的前半截。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, application = Application::class)
class DigestLongSessionTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val minute = 60_000L

    /** 每分钟一句，第 25 分钟后停顿 3 分钟；[pendingAt] 处放一段还没转写的录音 */
    private suspend fun seed(db: CaptureDatabase, minutes: Int, pendingAt: Long? = null) {
        db.capture().segment(SegmentEntity(id = 1, startAt = 0, endAt = minutes * minute, sourceId = "mic", path = "/synthetic",
            sizeBytes = 0, status = "Done"))
        if (pendingAt != null) db.capture().segment(SegmentEntity(id = 2, startAt = pendingAt, endAt = pendingAt + 5000,
            sourceId = "mic", path = "/synthetic2", sizeBytes = 0, status = "Pending"))
        db.transcribe().insertLines((0 until minutes).filter { it !in 26..28 }.map {
            TranscriptLineEntity(id = it + 1L, segmentId = 1, startAt = it * minute, endAt = it * minute + 30_000, text = "合成测试", speakerTag = null)
        })
    }

    private fun runner(db: CaptureDatabase, now: Long, calls: MutableList<Int>): DigestRunner {
        val client = LlmClient()
        return DigestRunner(context, db.memory(), client, AndroidDigestSettings(context, client), { "测试" }, { true },
            now = { now }, readKey = { "synthetic" },
            request = { _, _, _, input -> calls += input.lines().count { it.contains("合成测试") }; LlmReply("""{"events":[],"items":[]}""", 1, 1) })
    }

    @Test fun nonStopTalkingDigestsTheSettledPartUpToTheLongestPause() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, CaptureDatabase::class.java).allowMainThreadQueries().build()
        try {
            seed(db, minutes = 70)
            val calls = mutableListOf<Int>()
            assertFalse(runner(db, now = 70 * minute, calls).run())
            // 停顿前的 0～25 分钟先整理；停顿后仍在继续的部分留着等
            val left = db.memory().undigestedLines()
            assertEquals(26, 67 - left.size)
            assertEquals(29 * minute, left.first().startAt)
            assertEquals(listOf(26), calls)
        } finally { db.close() }
    }

    @Test fun shortOngoingTalkOrUntranscribedAudioInsideStillWaits() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, CaptureDatabase::class.java).allowMainThreadQueries().build()
        try {
            seed(db, minutes = 70, pendingAt = 10 * minute)
            val calls = mutableListOf<Int>()
            assertFalse(runner(db, now = 70 * minute, calls).run())
            assertTrue(calls.isEmpty())
            assertEquals(67, db.memory().undigestedLines().size)
        } finally { db.close() }
        val short = Room.inMemoryDatabaseBuilder(context, CaptureDatabase::class.java).allowMainThreadQueries().build()
        try {
            seed(short, minutes = 35)
            val calls = mutableListOf<Int>()
            assertFalse(runner(short, now = 35 * minute, calls).run())
            assertTrue(calls.isEmpty())
        } finally { short.close() }
    }
}
