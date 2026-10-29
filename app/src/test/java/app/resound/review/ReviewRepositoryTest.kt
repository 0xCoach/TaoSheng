package app.resound.review

import android.app.Application
import android.content.Context
import androidx.room.Room
import app.resound.data.*
import app.resound.digest.LlmPreset
import app.resound.digest.LlmReply
import app.resound.push.Notifier
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
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
class ReviewRepositoryTest {
    private val date = LocalDate.parse("2026-10-23")
    private val zone = ZoneId.of("Asia/Shanghai")
    private val at = date.atStartOfDay(zone).toInstant().toEpochMilli()
    private val context: Context get() = RuntimeEnvironment.getApplication()

    private suspend fun fixture(block: suspend (CaptureDatabase, RoomReviewRepository, MutableList<String>) -> Unit) {
        val db = Room.inMemoryDatabaseBuilder(context, CaptureDatabase::class.java).build()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val actions = mutableListOf<String>()
        val prefs = context.getSharedPreferences("review-test", Context.MODE_PRIVATE).apply { edit().clear().commit() }
        val repo = RoomReviewRepository(db.review(), db.memory(), prefs, scope,
            { LlmPreset("test", "test", "https://example.invalid", "test-model") to prefs.getString("test-key", "test")!! },
            { _, _, _, input ->
                actions += "chat"
                assertFalse(input.contains("原话"))
                LlmReply("""{"brief":"今天讨论方案","sections":[{"title":"一句话","lines":[{"text":"今天讨论方案","eventId":1}]},{"title":"发生了什么","lines":[{"text":"讨论了方案","eventId":1}]}]}""", 10, 5)
            }, { "{{APP_NAME}}" }, "回响", object : Notifier {
                override suspend fun send(title: String, body: String, deepLink: String?, topic: app.resound.push.PushTopic?) {
                    assertEquals("今日回顾", title)
                    assertEquals("resound://review?open=review&date=2026-10-23", deepLink)
                    actions += "push"
                }
            }, {}, { at + 22 * 3600000 }, { zone })
        try { block(db, repo, actions) } finally { scope.cancel(); db.close() }
    }
    private suspend fun seed(db: CaptureDatabase, scene: String = "会议", deleted: Boolean = false, id: Long = 1) {
        db.memory().insertEvent(MemoryEventEntity(id, at + 1000, at + 2000, scene, "方案", "讨论方案", "[]", "[]", null, null, null, false, deleted, at, "test"))
    }

    @Test fun emptyAndExcludedDayNeverCallsOrPushes() = runBlocking { fixture { db, repo, actions ->
        repo.run(date, true)
        seed(db, scene = "媒体"); seed(db, scene = "旁人", id = 2); seed(db, deleted = true, id = 3)
        repo.run(date, true)
        assertTrue(actions.isEmpty()); assertNull(repo.daily(date).first())
    } }

    @Test fun disabledPushGeneratesAndScheduledReuseDoesNotSpendAgain() = runBlocking { fixture { db, repo, actions ->
        seed(db); repo.setPushEnabled(false); repo.run(date, true)
        assertEquals(listOf("chat"), actions); assertNotNull(repo.daily(date).first())
        repo.setPushEnabled(true); repo.run(date, true)
        assertEquals(listOf("chat", "push"), actions)
        assertEquals(1, db.memory().observeUsage().first().single().calls)
        assertEquals(10, db.memory().observeUsage().first().single().promptTokens.toInt())
        db.memory().deleteEvent(1); repo.run(date, true)
        assertEquals(listOf("chat", "push"), actions) // 删空当天后不推旧缓存。
    } }

    @Test fun manualRegeneratesWithoutPushAndMissingKeyIsSafe() = runBlocking { fixture { db, repo, actions ->
        seed(db)
        val prefs = context.getSharedPreferences("review-test", Context.MODE_PRIVATE)
        prefs.edit().putString("test-key", "").commit()
        repo.run(date, true)
        assertEquals("还没填写整理模型的密钥", repo.state.value.lastError); assertTrue(actions.isEmpty())
        prefs.edit().putString("test-key", "test").commit()
        repo.run(date, false); repo.run(date, false)
        assertEquals(listOf("chat", "chat"), actions); assertEquals(1, repo.recent().first().size)
        assertNull(repo.state.value.lastError); assertFalse(repo.state.value.generating)
    } }
}
