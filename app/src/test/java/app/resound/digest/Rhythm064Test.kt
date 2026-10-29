package app.resound.digest

import android.app.Application
import androidx.room.Room
import app.resound.data.*
import app.resound.push.*
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, application = Application::class)
class Rhythm064Test {
    private val date = LocalDate.parse("2026-10-29")
    private val zone = ZoneId.of("Asia/Shanghai")
    private fun at(daysAgo: Long) = date.minusDays(daysAgo).atStartOfDay(zone).toInstant().toEpochMilli()
    private fun event(id: Long, start: Long, scene: String = "会议", people: String = "[]") =
        MemoryEventEntity(id, start, start + 60000, scene, "事件", "摘要", "[]", people, null, null, null, false, false, start, "test")
    private suspend fun fixture(block: suspend (CaptureDatabase, RoomRhythmStats) -> Unit) {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), CaptureDatabase::class.java).build()
        try {
            db.capture().segment(SegmentEntity(1, at(8), at(0), "mic", "/unused", 0, "Done"))
            block(db, RoomRhythmStats(db.memory(), zone))
        } finally { db.close() }
    }
    private suspend fun line(db: CaptureDatabase, daysAgo: Long, millis: Long = 60000) {
        db.transcribe().insertLines(listOf(TranscriptLineEntity(segmentId = 1, startAt = at(daysAgo),
            endAt = at(daysAgo) + millis, text = "原话", speakerTag = null)))
    }

    @Test fun fewerThanThreeRecordedDaysIsNullAndWindowExcludesTodayAndEighthDay() = runBlocking { fixture { db, stats ->
        listOf(0L, 1L, 2L, 8L).forEach { line(db, it) }
        db.memory().insertEvent(event(1, at(3))) // 只有事件不算有记录。
        assertNull(stats.baseline(date).first())
    } }

    @Test fun mediansIgnoreExtremeValueAndRoundEvenDays() = runBlocking { fixture { db, stats ->
        listOf(1L, 2L, 3L, 4L).forEachIndexed { index, day ->
            line(db, day, listOf(1L, 120000L, 180000L, 60000000L)[index])
            repeat(index + 1) { n ->
                db.memory().insertEvent(event(day * 10 + n, at(day), people = "[\"我\",\"\",\"小王\",\"小王\",\"小李\"]"))
            }
        }
        db.memory().insertEvent(event(100, at(1)).copy(deleted = true))
        assertEquals(RhythmBaseline(4, 3, 3, 2, 3), stats.baseline(date).first())
        db.transcribe().deleteLines(1)
        listOf(1L, 2L, 3L).forEachIndexed { index, day -> line(db, day, listOf(60000L, 120000L, 60000000L)[index]) }
        assertEquals(RhythmBaseline(3, 2, 2, 2, 2), stats.baseline(date).first())
    } }

    @Test fun overlappingMediaExcludedAndFlowUpdatesOnEventsAndLines() = runBlocking { fixture { db, stats ->
        listOf(1L, 2L, 3L).forEach { line(db, it) }
        // 媒体从前一天开始，仅部分重叠也排除整句，不依赖 eventId。
        db.memory().insertEvent(event(1, at(3) - 1000, "媒体").copy(endAt = at(3) + 1))
        coroutineScope {
            val values = Channel<RhythmBaseline?>(Channel.UNLIMITED)
            val job = launch { stats.baseline(date).collect { values.send(it) } }
            try {
                assertNull(withTimeout(5000) { values.receive() })
                db.memory().deleteEvent(1)
                assertEquals(RhythmBaseline(3, 1, 0, 0, 0), withTimeout(5000) { values.receive() })
                line(db, 4)
                assertEquals(RhythmBaseline(4, 1, 0, 0, 0), withTimeout(5000) { values.receive() })
            } finally { job.cancelAndJoin() }
        }
    } }

    @Test fun pushAndRemindersKeepBystanderAndExcludeMedia() {
        val events = listOf(event(1, at(1), "旁人"), event(2, at(1), "媒体"))
        val items = events.map { MemoryItemEntity(it.id, it.id, "待办", "行动", "办理", "2026-10-29 10:00",
            at(0) + 36000000, null, null, "Accepted", at(1)) }
        val snapshot = PushSnapshot(events, items)
        val subscription = PushSubscription(records = setOf(RecordKind.Timeline, RecordKind.Todos), excludeMedia = true)
        val selected = snapshot.select(subscription, null, date, zone)
        assertEquals(listOf(1L), selected.events.map { it.id })
        assertEquals(listOf(1L), selected.items.map { it.id })
        val reminders = SubscriptionReminders.candidates(snapshot, subscription, JSONObject(),
            AppointmentReminders(leadMinutes = null, eveningBefore = false), zone, at(0))
        assertEquals(listOf("item:1:${items[0].dueAt}"), reminders.map { it.key })
    }
}
