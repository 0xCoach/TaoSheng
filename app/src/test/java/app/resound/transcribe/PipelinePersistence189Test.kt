package app.resound.transcribe

import android.app.Application
import androidx.room.Room
import app.resound.data.*
import app.resound.digest.*
import app.resound.scheduling.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, application = Application::class)
class PipelinePersistence189Test {
    @Test fun historyTicketsAndRoundRecordsSurviveRecreation() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val now = System.currentTimeMillis()
        val store = PipelineScheduleStore(context, now)
        val history = PipelineDue.History(now, null, PipelineDue.Failure.Blocked, 1)
        store.save(PipelineDue.Mode.IntervalDigest, history)
        assertEquals(history, PipelineScheduleStore(context, now + 1).history(PipelineDue.Mode.IntervalDigest))
        val timing = AndroidDigestTiming(context)
        timing.addTimePoint(0); timing.setMode(DigestMode.TimePoints)
        context.getSharedPreferences("digest", 0).edit().putLong("timedSince", now - 2 * 86_400_000).commit()
        val ticket = timing.pendingTimed(now)!!
        assertEquals(ticket, AndroidDigestTiming(context).pendingTimed(now))
        timing.completeTimed(ticket)
        assertNull(AndroidDigestTiming(context).pendingTimed(now))
        val records = PipelineDiagnostics(context)
        val round = records.round("心跳", "digest")
        records.record(round)
        round.result = "成功"
        round.stages["转写"] = 20
        round.stages["整理"] = 30
        records.record(round)
        val dump = PipelineDiagnostics(context).dump()
        assertEquals(1, dump.lines().count { it.startsWith("时间=") })
        assertTrue(dump.contains("来源=心跳")); assertTrue(dump.contains("结果=成功"))
        assertTrue(dump.contains("转写=20ms")); assertTrue(dump.contains("整理=30ms"))
    }

    @Test fun replyNotPersistedIsReplayedInMemoryWithoutSecondPayment() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val db = Room.inMemoryDatabaseBuilder(context, CaptureDatabase::class.java)
            .setDriver(androidx.sqlite.driver.AndroidSQLiteDriver()).allowMainThreadQueries().build()
        val prefs = context.getSharedPreferences("digest-state", 0)
        prefs.edit().clear().commit()
        try {
            db.capture().segment(SegmentEntity(1, 0, 1000, "mic", "/synthetic", 0, "Done"))
            db.transcribe().insertLines(listOf(TranscriptLineEntity(1, 1, 0, 1000, "测试原话", null)))
            db.testSql.execSQL("CREATE TRIGGER fail_pending BEFORE INSERT ON digest_pending BEGIN SELECT RAISE(ABORT, 'test storage failure'); END")
            var calls = 0
            val client = LlmClient()
            val runner = DigestRunner(context, db.memory(), client, AndroidDigestSettings(context, client),
                { "测试" }, { true }, now = { 3_600_000 }, readKey = { "synthetic" },
                request = { _, _, _, _ -> calls++; LlmReply("""{"events":[],"items":[]}""", 1, 1) })
            assertTrue(runner.run(force = true))
            assertEquals(1, calls); assertTrue(prefs.contains("inflight"))
            assertTrue(db.memory().pendingDigests().isEmpty())
            db.testSql.execSQL("DROP TRIGGER fail_pending")
            assertFalse(runner.run(force = true))
            assertEquals(1, calls); assertFalse(prefs.contains("inflight"))
            assertTrue(db.memory().undigestedLines().isEmpty())
        } finally { db.close(); prefs.edit().clear().commit() }
    }
}
