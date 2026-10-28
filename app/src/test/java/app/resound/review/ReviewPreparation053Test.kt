package app.resound.review

import android.app.Application
import android.content.Context
import androidx.room.Room
import app.resound.data.*
import app.resound.digest.*
import app.resound.push.*
import app.resound.skills.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.time.LocalDate
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, application = Application::class)
class ReviewPreparation053Test {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val day = LocalDate.parse("2026-10-28")
    private val zone = ZoneId.of("Asia/Shanghai")
    private val at = day.atStartOfDay(zone).toInstant().toEpochMilli() + 12 * 3_600_000
    private val notifier = object : Notifier {
        override suspend fun send(title: String, body: String, deepLink: String?, topic: PushTopic?) {}
    }
    private val preset = LlmPreset("test", "测试", "https://example.invalid", "test")
    private suspend fun seed(db: CaptureDatabase) {
        db.memory().insertEvent(MemoryEventEntity(1, at, at + 1000, "会议", "方案", "讨论方案",
            "[]", "[]", null, null, null, false, false, at, "test"))
    }
    @Test fun timeoutWhileWaitingForPipelineStillGeneratesDailyReview() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, CaptureDatabase::class.java).allowMainThreadQueries().build()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val lock = Mutex(locked = true)
        val logs = mutableListOf<String>()
        var generated = false
        try {
            seed(db)
            val repo = RoomReviewRepository(db.review(), db.memory(), context.getSharedPreferences("review053", 0), scope,
                { preset to "test" }, { _, _, _, _ ->
                    generated = true
                    LlmReply("""{"brief":"新回顾","sections":[{"title":"一句话","lines":[{"text":"讨论方案","eventId":1}]},{"title":"发生了什么","lines":[{"text":"讨论方案","eventId":1}]}]}""", 1, 1)
                }, { "测试" }, "涛声", notifier, {}, now = { at }, zone = { zone },
                beforeReview = { ReviewPreparation.prepare(30, logs::add) { lock.withLock { error("不应拿到锁") } } })
            assertFalse(repo.run(day, scheduled = false))
            assertTrue(generated); assertNotNull(repo.daily(day).first())
            assertEquals(listOf("超时，使用已有内容"), logs)
            assertTrue(lock.isLocked)
        } finally { scope.cancel(); db.close() }
    }

    @Test fun successfulPreparationSuppliesFreshEventsToWeeklyReview() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, CaptureDatabase::class.java).allowMainThreadQueries().build()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val root = File(context.cacheDir, "weekly053")
        root.deleteRecursively()
        var prepared = false
        var generated = false
        try {
            val dir = File("src/main/assets/skills/weekly")
            val files = SkillFiles(root) { listOf(SkillJson.parse(File(dir, "skill.json").readText(), SkillOrigin.Builtin) to File(dir, "prompt.md").readText()) }
            val repo = LocalSkillRepository(files, db.skills(), db.memory(), scope, { preset to "test" },
                { _, _, _, input ->
                    assertTrue(prepared); assertTrue(input.contains("讨论方案")); generated = true
                    LlmReply("""{"cards":[{"title":"本周回顾","summary":"讨论方案","values":{"one_line":"推进方案"}}]}""", 1, 1)
                }, { emptyList() }, notifier, { _, _ -> }, now = { at + 1000 },
                beforeWeeklyReview = { ReviewPreparation.prepare(record = {}) { seed(db); prepared = true; "完成" } })
            assertTrue(repo.runNow("weekly", day) is SkillRunResult.Cards)
            assertTrue(generated)
        } finally { scope.coroutineContext[Job]!!.cancelAndJoin(); db.close(); root.deleteRecursively() }
    }

    @Test fun failureFallsThroughButParentCancellationPropagates() = runBlocking {
        val logs = mutableListOf<String>()
        ReviewPreparation.prepare(record = logs::add) { error("synthetic") }
        assertEquals(listOf("失败，使用已有内容"), logs)
        val entered = CompletableDeferred<Unit>()
        var continued = false
        val job = launch {
            ReviewPreparation.prepare(record = logs::add) { entered.complete(Unit); awaitCancellation() }
            continued = true
        }
        entered.await(); job.cancelAndJoin()
        assertFalse(continued); assertEquals(1, logs.size)
    }
}
