package app.resound.digest

import android.app.Application
import androidx.room.Room
import app.resound.data.*
import app.resound.privacy.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, application = Application::class)
class RhythmDeletion099Test {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val today = LocalDate.parse("2026-11-03").atStartOfDay(zone).toInstant().toEpochMilli()
    private fun database() = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), CaptureDatabase::class.java).build()
    private fun event(id: Long, at: Long, scene: String = "会议", end: Long = at + 60_000) =
        MemoryEventEntity(id, at, end, scene, "合成事件", "摘要", "[]", "[\"测试员\"]", null, null, null, false, createdAt = at, model = "test")

    @Test fun lightweightSnapshotMatchesOriginalSqlIncludingCrossDayAndDeletedSources() = runBlocking {
        val db = database()
        try {
            db.capture().segment(SegmentEntity(1, today - 8 * 86_400_000, today + 100_000, "mic", "/synthetic", 0, "Done"))
            for (day in 1L..7L) {
                val at = today - day * 86_400_000
                db.memory().insertEvent(event(day, at))
                db.transcribe().insertLines((0L..15L).map { n ->
                    TranscriptLineEntity(segmentId = 1, startAt = at + n * 60_000, endAt = at + (n + 1) * 60_000,
                        text = "长原话".repeat(500), speakerTag = null, eventId = day)
                })
                // 相接边界、跨日、嵌套、相邻媒体与失效媒体。
                db.memory().insertEvent(event(100 + day, at - 1000, "媒体", at + 1))
                db.memory().insertEvent(event(200 + day, at + 120_000, "媒体", at + 240_000))
                db.memory().insertEvent(event(300 + day, at + 180_000, "媒体", at + 200_000))
                db.memory().insertEvent(event(400 + day, at + 240_000, "媒体", at + 300_000))
                db.memory().insertEvent(event(500 + day, at + 600_000, "媒体", at + 900_000).copy(deleted = true))
            }
            db.memory().hideEvent(7)
            db.transcribe().insertLines(listOf(TranscriptLineEntity(segmentId = 1, startAt = today - 1, endAt = today + 90_000,
                text = "跨窗口长句", speakerTag = null)))
            db.memory().insertEvent(event(900, today + 10_000, "媒体", today + 20_000))
            val start = today - 7 * 86_400_000
            val original = withContext(Dispatchers.IO) {
                db.openHelper.readableDatabase.query("""SELECT l.startAt,l.endAt FROM transcript_lines l
                    LEFT JOIN memory_events e ON e.id=l.eventId WHERE l.startAt >= $start AND l.startAt < $today
                    AND (e.id IS NULL OR e.deleted=0) AND NOT EXISTS (SELECT 1 FROM memory_events m WHERE m.deleted=0
                    AND m.scene='媒体' AND l.startAt<m.endAt AND l.endAt>m.startAt) ORDER BY l.startAt,l.id""").use { cursor ->
                    buildList { while (cursor.moveToNext()) add(RhythmSpan(cursor.getLong(0), cursor.getLong(1))) }
                }
            }
            val snapshot = db.memory().rhythmSnapshot(start, today)
            val stats = RoomRhythmStats(db, zone)
            assertNotNull(stats.calculate(snapshot))
            assertEquals(stats.calculate(snapshot.copy(lines = original, media = emptyList())), stats.calculate(snapshot))
            assertTrue(snapshot.media.any { it.startAt > today })
            assertFalse(snapshot.lines.any { it.startAt == start })
        } finally { db.close() }
    }

    @Test fun duplicateDeletionSharesOneOperationAndReportsWaitingThenClearsState() = runBlocking {
        val db = database()
        try {
            db.memory().insertEvent(event(1, today))
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            var calls = 0
            val actions = RoomPrivacyActions(db, voiceprintGuard = { block ->
                calls++; entered.complete(Unit); release.await(); block()
            })
            val first = async { actions.forgetEvent(1) }
            entered.await()
            assertEquals(EventDeletionPhase.WaitingForAudio, actions.eventProgress.value[1])
            val second = async(start = CoroutineStart.UNDISPATCHED) { actions.forgetEvent(1) }
            delay(50)
            assertFalse(second.isCompleted)
            release.complete(Unit)
            assertEquals(ForgetResult(0, 0, 1, 0), first.await())
            assertEquals(first.await(), second.await())
            assertEquals(1, calls)
            assertTrue(actions.eventProgress.value.isEmpty())
        } finally { db.close() }
    }
}
