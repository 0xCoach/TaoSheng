package app.resound.review

import android.app.Application
import android.content.Context
import androidx.room.Room
import app.resound.data.*
import app.resound.digest.LlmPreset
import app.resound.digest.LlmReply
import app.resound.push.Notifier
import app.resound.push.PushTopic
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, application = Application::class)
class ReviewTimingTest {
    private val date = LocalDate.parse("2026-10-23")
    private val zone = ZoneId.of("Asia/Shanghai")
    private fun at(day: LocalDate, time: String) = day.atTime(java.time.LocalTime.parse(time)).atZone(zone).toInstant().toEpochMilli()

    private class Calls {
        val inputs = mutableListOf<JSONObject>()
        val pushes = mutableListOf<String>()
    }

    private suspend fun fixture(block: suspend (CaptureDatabase, RoomReviewRepository, Calls) -> Unit) {
        val context = RuntimeEnvironment.getApplication() as Context
        val db = Room.inMemoryDatabaseBuilder(context, CaptureDatabase::class.java).build()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val calls = Calls()
        val prefs = context.getSharedPreferences("review-timing-test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val repo = RoomReviewRepository(db.review(), db.memory(), prefs, scope,
            { LlmPreset("test", "test", "https://example.invalid", "test-model") to "test" },
            { _, _, _, input ->
                val json = JSONObject(input)
                calls.inputs += json
                val id = json.getJSONArray("events").getJSONObject(0).getLong("id")
                LlmReply("""{"brief":"新回顾","sections":[{"title":"一句话","lines":[{"text":"讨论方案","eventId":$id}]},{"title":"发生了什么","lines":[{"text":"讨论方案","eventId":$id}]}]}""", 10, 5)
            }, { "{{APP_NAME}}" }, "回响", object : Notifier {
                override suspend fun send(title: String, body: String, deepLink: String?, topic: PushTopic?) {
                    assertEquals(PushTopic.DailyReview, topic)
                    assertEquals("resound://review?open=review&date=$date", deepLink)
                    calls.pushes += body
                }
            }, {}, { at(date, "23:00") }, { zone })
        try { block(db, repo, calls) } finally { scope.cancel(); db.close() }
    }

    private suspend fun seed(db: CaptureDatabase, id: Long, day: LocalDate, time: String) {
        val start = at(day, time)
        db.memory().insertEvent(MemoryEventEntity(id, start, start + 1000, "会议", "方案", "讨论方案",
            "[]", "[]", null, null, null, false, false, start, "test"))
        db.memory().insertItems(listOf(MemoryItemEntity(id, id, "待办", null, "跟进方案", null, null, null, null, "Pending", start)))
    }

    private suspend fun oldReview(db: CaptureDatabase, time: String) {
        db.review().save(ReviewEntity(startDate = date.toString(), endDate = date.toString(), brief = "旧回顾",
            sectionsJson = "[]", generatedAt = at(date, time), model = "old"))
    }

    private fun eventIds(input: JSONObject): List<Long> {
        val events = input.getJSONArray("events")
        return (0 until events.length()).map { i ->
            val event = events.getJSONObject(i)
            assertEquals(1, event.getJSONArray("items").length())
            event.getLong("id")
        }
    }

    @Test fun adjacentWindowsUseOpenStartAndClosedEndForEventsAndItems() = runBlocking { fixture { db, repo, calls ->
        seed(db, 1, date.minusDays(1), "22:00")
        seed(db, 2, date.minusDays(1), "22:01")
        seed(db, 3, date, "21:59")
        seed(db, 4, date, "22:00")
        seed(db, 5, date, "22:01")
        assertFalse(repo.run(date, scheduled = false))
        assertFalse(repo.run(date.plusDays(1), scheduled = false))
        assertEquals(listOf(2L, 3L, 4L), eventIds(calls.inputs[0]))
        assertEquals(listOf(5L), eventIds(calls.inputs[1]))
        val window = calls.inputs[0].getJSONObject("timeWindow")
        assertEquals(at(date.minusDays(1), "22:00"), ZonedDateTime.parse(window.getString("startExclusive")).toInstant().toEpochMilli())
        assertEquals(at(date, "22:00"), ZonedDateTime.parse(window.getString("endInclusive")).toInstant().toEpochMilli())
        assertTrue(window.getString("description").contains("昨晚 22:00 到今晚 22:00"))
        assertTrue(calls.pushes.isEmpty())
    } }

    @Test fun scheduledDateUsesLatestReachedCutoffEvenAfterMidnight() {
        fun target(day: LocalDate, time: String, minutes: Int = 22 * 60) =
            ReviewTime.targetDate(java.time.Instant.ofEpochMilli(at(day, time)).atZone(zone), minutes)
        assertEquals(date, target(date, "23:00"))
        assertEquals(date, target(date.plusDays(1), "00:30"))
        assertEquals(date, target(date.plusDays(1), "21:59"))
        assertEquals(date.plusDays(1), target(date.plusDays(1), "22:00"))
        assertEquals(date.minusDays(1), target(date, "20:29", 20 * 60 + 30))
        assertEquals(date, target(date, "20:30", 20 * 60 + 30))
    }

    @Test fun scheduledRunReplacesReviewGeneratedBeforeCutoff() = runBlocking { fixture { db, repo, calls ->
        seed(db, 1, date, "19:00")
        seed(db, 2, date, "21:59")
        oldReview(db, "19:35")
        assertFalse(repo.run(date, scheduled = true))
        assertEquals(listOf(1L, 2L), eventIds(calls.inputs.single()))
        assertEquals(listOf("新回顾"), calls.pushes)
        val saved = repo.recent().first().single()
        assertEquals("新回顾", saved.brief)
        assertEquals(at(date, "23:00"), saved.generatedAt)
        assertEquals(1, db.memory().observeUsage().first().single().calls)
    } }

    @Test fun scheduledRetryReusesReviewAtOrAfterCutoffWithoutModelUsage() = runBlocking { fixture { db, repo, calls ->
        seed(db, 1, date, "21:59")
        for (time in listOf("22:00", "22:05")) {
            oldReview(db, time)
            assertFalse(repo.run(date, scheduled = true))
            assertEquals(at(date, time), repo.daily(date).first()!!.generatedAt)
        }
        assertTrue(calls.inputs.isEmpty())
        assertTrue(db.memory().observeUsage().first().isEmpty())
        assertEquals(listOf("旧回顾", "旧回顾"), calls.pushes)
    } }

    @Test fun manualRunUsesConfiguredWindowAndAlwaysRegeneratesWithoutPush() = runBlocking { fixture { db, repo, calls ->
        repo.setDailyAt(20 * 60 + 30)
        seed(db, 1, date.minusDays(1), "20:31")
        seed(db, 2, date, "20:30")
        seed(db, 3, date, "20:31")
        oldReview(db, "22:05")
        assertFalse(repo.run(date, scheduled = false))
        assertEquals(listOf(1L, 2L), eventIds(calls.inputs.single()))
        assertTrue(calls.inputs.single().getJSONObject("timeWindow").getString("description").contains("20:30"))
        assertEquals("新回顾", repo.recent().first().single().brief)
        assertTrue(calls.pushes.isEmpty())
    } }
}
