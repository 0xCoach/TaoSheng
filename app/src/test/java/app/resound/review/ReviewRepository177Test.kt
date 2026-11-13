package app.resound.review

import android.app.Application
import androidx.room.Room
import app.resound.data.*
import app.resound.digest.*
import app.resound.push.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.time.LocalDate
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, application = Application::class)
class ReviewRepository177Test {
    @Test fun queriesSettingsAndGenerationMatchOldRepository() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val db = Room.inMemoryDatabaseBuilder(context, CaptureDatabase::class.java).setDriver(androidx.sqlite.driver.AndroidSQLiteDriver()).build()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val zone = ZoneId.of("America/New_York"); val date = LocalDate.of(2026, 11, 1)
            val at = date.atStartOfDay(zone).toInstant().toEpochMilli()
            val prefs = context.getSharedPreferences("synthetic177-review", 0)
            prefs.edit().clear().putString("lastError", "生成失败：旧内部文本").putString("lastErrorDate", "bad").commit()
            val calls = mutableListOf<String>()
            val repo = RoomReviewRepository(db.review(), db.memory(), prefs, scope,
                { LlmPreset("test", "test", "https://example.invalid", "test") to "synthetic" },
                { _, _, _, _ -> calls += "chat"; LlmReply("""{"brief":"合成回顾","sections":[]}""", 10, 3) }, { "模板" }, "回响",
                object : Notifier { override suspend fun send(title: String, body: String, deepLink: String?, topic: PushTopic?) { calls += "$title|$body|$deepLink|$topic" } },
                { calls += "reschedule=$it" }, now = { at + 90_000_000 }, zone = { zone }, dailyMemory = { calls += "daily=$it" }, io = kotlinx.coroutines.Dispatchers.IO)
            val text = StringBuilder(); fun save(key: String, value: Any?) { text.appendLine("$key=$value") }
            save("initial", repo.state.value); save("empty", repo.daily(date).first()); save("material.empty", repo.hasMaterial(date).first())
            repo.setDailyAt(5000); repo.setPushEnabled(false); save("settings", repo.state.value); save("window", repo.windowText(date)); save("prefs", prefs.all.toSortedMap())
            repo.generateNow(date); save("manual.noScheduler", repo.state.value)
            db.memory().insertEvent(MemoryEventEntity(1, at + 60_000, at + 120_000, "会议", "合成事件", "合成摘要", "[]", "[]", null, null, null, false, createdAt = at, model = "synthetic"))
            save("material", repo.hasMaterial(date).first()); save("manual.retry", repo.run(date, false)); save("daily", repo.daily(date).first()); save("recent", repo.recent().first()); save("state", repo.state.value)
            repo.setPushEnabled(true); save("scheduled.retry", repo.run(date, true)); save("calls", calls); save("usage", db.memory().observeUsage().first()); save("prefs.final", prefs.all.toSortedMap())
            val fixture = File("src/test/resources/repo177/RoomReviewRepository/all.txt")
            assertEquals(fixture.readText(), text.toString())
        } finally { scope.coroutineContext[Job]!!.cancelAndJoin(); db.close() }
    }
}
