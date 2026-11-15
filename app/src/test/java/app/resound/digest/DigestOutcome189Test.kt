package app.resound.digest

import android.app.Application
import androidx.room.Room
import app.resound.data.*
import app.resound.digest.core.DigestOutcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, application = Application::class)
class DigestOutcome189Test {
    @Test fun explicitOutcomesDistinguishEmptyRetryBlockedDoneAndCancellation() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val db = Room.inMemoryDatabaseBuilder(context, CaptureDatabase::class.java)
            .setDriver(androidx.sqlite.driver.AndroidSQLiteDriver()).allowMainThreadQueries().build()
        val prefs = context.getSharedPreferences("digest-state", 0)
        prefs.edit().clear().commit()
        try {
            var key = ""
            var online = false
            var cancel = false
            val client = LlmClient()
            val runner = DigestRunner(context, db.memory(), client, AndroidDigestSettings(context, client),
                { "测试" }, { online }, now = { 3_600_000 }, readKey = { key }, request = { _, _, _, _ ->
                    if (cancel) throw CancellationException("测试取消")
                    LlmReply("""{"events":[],"items":[]}""", 1, 1)
                })
            assertTrue(runner.runOutcome() is DigestOutcome.NeedsUser)
            key = "synthetic"
            assertEquals(DigestOutcome.NothingToDo, runner.runOutcome())
            suspend fun seed(id: Long) {
                db.capture().segment(SegmentEntity(id, 0, 1000, "mic", "/synthetic-$id", 0, "Done"))
                db.transcribe().insertLines(listOf(TranscriptLineEntity(id, id, 0, 1000, "测试", null)))
            }
            seed(1)
            assertTrue(runner.runOutcome(force = true) is DigestOutcome.Retryable)
            online = true
            assertEquals(DigestOutcome.Done, runner.runOutcome(force = true))
            seed(2); cancel = true
            try { runner.runOutcome(force = true); fail("取消不得正常返回") }
            catch (_: CancellationException) { }
            assertTrue(prefs.contains("inflight")); assertFalse(runner.running.value)
            assertEquals(1, db.memory().undigestedLines().size)
        } finally { db.close(); prefs.edit().clear().commit() }
    }
}
