package app.resound.review

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.work.*
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import app.resound.AppGraph
import app.resound.data.*
import app.resound.digest.*
import app.resound.push.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, application = Application::class)
class ManualReview058Test {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val date = LocalDate.parse("2026-10-29")
    private val zone = ZoneId.of("Asia/Shanghai")
    private val at = date.atStartOfDay(zone).toInstant().toEpochMilli()
    private lateinit var db: CaptureDatabase
    private lateinit var scope: CoroutineScope
    private val prefs get() = context.getSharedPreferences("review058", 0)
    private var key = "test"
    private var modelId = "test"
    private var calls = 0
    private var prepare: suspend () -> Boolean = { true }
    private var reply: suspend () -> LlmReply = {
        LlmReply("""{"brief":"完成方案","sections":[{"title":"一句话","lines":[{"text":"完成方案","eventId":1}]},{"title":"发生了什么","lines":[{"text":"讨论方案","eventId":1}]}]}""", 1, 1)
    }
    private fun repo(work: WorkManager? = null) = RoomReviewRepository(db.review(), db.memory(), prefs, scope,
        { LlmPreset(modelId, "测试", "https://example.invalid", "test") to key },
        { _, _, _, _ -> calls++; reply() }, { "" }, "涛声",
        object : Notifier {
            override suspend fun send(title: String, body: String, deepLink: String?, topic: PushTopic?) {
                error("手动不得推送")
            }
        }, {}, now = { at + 22 * 3600000 }, zone = { zone }, beforeReview = { prepare() }, work = work)

    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(context, CaptureDatabase::class.java).allowMainThreadQueries().build()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        prefs.edit().clear().commit()
    }
    @After fun close() = runBlocking { scope.coroutineContext[Job]!!.cancelAndJoin(); db.close() }
    private suspend fun seed(time: Long = at + 1000, id: Long = 1) {
        db.memory().insertEvent(MemoryEventEntity(id, time, time + 1000, "会议", "方案", "讨论方案",
            "[]", "[]", null, null, null, false, false, time, "test"))
    }
    private fun assertEnded(r: RoomReviewRepository, error: String?) {
        assertEquals(error, r.state.value.lastError)
        assertEquals(if (error == null) null else date, r.state.value.lastErrorDate)
        assertFalse(r.state.value.generating)
        assertNull(r.state.value.stage)
    }

    @Test fun emptyManualExplainsWindowButScheduledStaysSilent() = runBlocking {
        val r = repo()
        r.run(date, false)
        assertEnded(r, "10月28日 22:00 – 10月29日 22:00 没有可写进回顾的事")
        assertEquals(date.toString(), prefs.getString("lastErrorDate", null))
        assertEquals(date, repo().state.value.lastErrorDate)
        r.run(date, true)
        assertEnded(r, null)
        assertEquals(0, calls)
    }

    @Test fun malformedReplyHasSpecificSafeReason() = runBlocking {
        seed()
        reply = { LlmReply("敏感原文不是 JSON", 1, 1) }
        val r = repo()
        r.run(date, false)
        assertEnded(r, "模型返回的格式不对，换个模型或稍后再试")
        assertNull(r.daily(date).first())
    }

    @Test fun successClearsOldErrorAndReportsIncompletePreparation() = runBlocking {
        seed()
        prefs.edit().putString("lastError", "旧错误").putString("lastErrorDate", date.toString()).commit()
        val r = repo()
        prepare = {
            assertEquals("正在转写和整理还没处理的录音…", r.state.value.stage)
            true
        }
        val successReply = reply
        reply = { assertEquals("正在写回顾…", r.state.value.stage); successReply() }
        r.run(date, false)
        assertEnded(r, null)
        assertNull(prefs.getString("lastErrorDate", null))
        prepare = { ReviewPreparation.prepare(10, {}) { delay(1000); "完成" } }
        r.run(date, false)
        assertNotNull(r.daily(date).first())
        assertEnded(r, "已用现有内容生成；还有录音没整理完")
    }

    @Test fun materialUsesSameWindowAndReactsToEventChanges() = runBlocking {
        seed(at + 23 * 3600000)
        val r = repo()
        val updates = kotlinx.coroutines.channels.Channel<Boolean>(10)
        val job = launch { r.hasMaterial(date).collect { updates.send(it) } }
        assertFalse(withTimeout(5000) { updates.receive() })
        r.run(date, false)
        assertTrue(r.state.value.lastError!!.endsWith("没有可写进回顾的事"))
        seed(id = 2)
        assertTrue(withTimeout(5000) { updates.receive() })
        db.memory().deleteEvent(2)
        assertFalse(withTimeout(5000) { updates.receive() })
        job.cancelAndJoin()
        assertEquals(0, calls)
    }

    @Test fun windowTextHandlesMidnight() {
        val r = repo()
        assertEquals("10月28日 22:00 – 10月29日 22:00", r.windowText(date))
        r.setDailyAt(0)
        assertEquals("10月28日 0:00 – 10月29日 0:00", r.windowText(date))
    }

    @Test fun missingKeyAndModelErrorsUseSafeDatedReasons() = runBlocking {
        seed()
        val r = repo()
        key = ""
        r.run(date, false)
        assertEnded(r, "还没填写整理模型的密钥")
        modelId = "custom"
        r.run(date, false)
        assertEnded(r, null)
        reply = { throw LlmException(LlmError.Network, detail = "密钥和服务原文") }
        assertTrue(r.run(date, false))
        assertEnded(r, LlmError.Network.human + "，稍后再试")
        reply = { throw IllegalArgumentException("原文") }
        r.run(date, false)
        assertEnded(r, "生成失败：IllegalArgumentException")
    }

    @Test fun expeditedWorkDeduplicatesAndCompletesThroughWorker() = runBlocking {
        WorkManagerTestInitHelper.initializeTestWorkManager(context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).build())
        val work = WorkManager.getInstance(context)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        prepare = { entered.complete(Unit); release.await(); true }
        val r = repo(work)
        AppGraph.review = r
        try {
            r.generateNow(date)
            assertTrue(r.state.value.generating)
            withTimeout(5000) { entered.await() }
            r.generateNow(date)
            val infos = work.getWorkInfosForUniqueWork(ManualReviewGeneration.name(date)).get()
            assertEquals(1, infos.size)
            val spec = ManualReviewGeneration.request(date).workSpec
            assertTrue(spec.expedited)
            assertEquals(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST, spec.outOfQuotaPolicy)
            release.complete(Unit)
            withTimeout(5000) { r.state.first { !it.generating } }
            assertEnded(r, "10月28日 22:00 – 10月29日 22:00 没有可写进回顾的事")
        } finally {
            release.complete(Unit)
            scope.coroutineContext[Job]!!.cancelAndJoin()
            work.cancelAllWork().result.get()
            WorkManagerTestInitHelper.closeWorkDatabase()
        }
    }

    @Test fun preparationFailureUsesExistingMaterialAndCancellationPropagates() = runBlocking {
        seed()
        val r = repo()
        prepare = { throw IllegalStateException("内部原文") }
        r.run(date, false)
        assertEnded(r, "已用现有内容生成；还有录音没整理完")
        prepare = { throw CancellationException("系统停止") }
        try { r.run(date, false); fail("必须保留系统取消") }
        catch (_: CancellationException) { }
        assertFalse(r.state.value.generating)
        assertNull(r.state.value.stage)
    }
}
