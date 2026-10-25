package app.resound.push

import android.app.Application
import android.content.Context
import androidx.room.Room
import app.resound.data.CaptureDatabase
import app.resound.data.MemoryEventEntity
import app.resound.data.ReviewEntity
import app.resound.review.DailyReview
import app.resound.review.ReviewPrompt
import app.resound.review.ReviewSection
import app.resound.review.RoomReviewRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneId
import java.util.Collections

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, application = Application::class)
class ReviewPushTest {
    private val date = LocalDate.parse("2026-10-23")
    private val zone = ZoneId.of("Asia/Shanghai")
    private val at = date.atStartOfDay(zone).toInstant().toEpochMilli()
    private val review = DailyReview(date, "简洁摘要", listOf(
        ReviewSection("一句话", listOf("今天完成了方案，明确下一步。"), listOf(1L)),
        ReviewSection("要紧的", listOf("确认方案"), listOf(1L)),
        ReviewSection("待办", listOf("", "  "), listOf(null, null)),
        ReviewSection("发生了什么", listOf("讨论了细节", "确定了时间"), listOf(1L, 1L)),
        ReviewSection("灵感", emptyList(), emptyList()),
    ), at + 22 * 3600000)
    private val markdown = "**10月23日 星期五 · 回顾**\n\n今天完成了方案，明确下一步。\n\n### 要紧的\n- 确认方案\n\n### 发生了什么\n- 讨论了细节\n- 确定了时间"
    private val plain = "10月23日 星期五 · 回顾\n\n今天完成了方案，明确下一步。\n\n【要紧的】\n- 确认方案\n\n【发生了什么】\n- 讨论了细节\n- 确定了时间"
    private val context: Context get() = RuntimeEnvironment.getApplication()

    private class FakeDelivery {
        val requests = Collections.synchronizedList(mutableListOf<JSONObject>())
        var status = 200
        // 直接在应用拦截器返回响应，绝不访问网络或真实推送服务。
        val transport = PushTransport(OkHttpClient.Builder().addInterceptor { chain ->
            requests += JSONObject(Buffer().also { chain.request().body!!.writeTo(it) }.readUtf8())
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(status)
                .message("fake").body("".toResponseBody()).build()
        }.build())
        val queued = mutableListOf<String>()
    }

    private suspend fun fixture(block: suspend (CaptureDatabase, RoomReviewRepository, AndroidPushChannels, FakeDelivery, MutableList<PushMessage>) -> Unit) {
        val db = Room.inMemoryDatabaseBuilder(context, CaptureDatabase::class.java).build()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val fake = FakeDelivery()
        val local = mutableListOf<PushMessage>()
        lateinit var reviews: RoomReviewRepository
        fun prefs(name: String) = context.getSharedPreferences(name, Context.MODE_PRIVATE).apply { edit().clear().commit() }
        val channels = AndroidPushChannels(prefs("push-030"), prefs("outbox-030"), object : PushSecrets {
            override fun read(ref: String) = ""
            override fun write(ref: String, value: String) = error("测试不保存密钥")
            override fun remove(ref: String) = Unit
        }, fake.transport, { fake.queued += it }, { reviews.recent().first().firstOrNull() })
        val notifier = ChannelNotifier(object : Notifier {
            override suspend fun send(title: String, body: String, deepLink: String?, topic: PushTopic?) {
                local += PushMessage(title, body, topic, deepLink, 0)
            }
        }, channels)
        reviews = RoomReviewRepository(db.review(), db.memory(), prefs("review-030"), scope,
            { error("不应调用大模型") }, { _, _, _, _ -> error("不应调用大模型") }, { "" }, "回响", notifier, {},
            { review.generatedAt }, { zone })
        try { block(db, reviews, channels, fake, local) } finally { scope.cancel(); db.close() }
    }

    private suspend fun seed(db: CaptureDatabase, value: DailyReview = review) {
        db.review().save(ReviewEntity(startDate = value.date.toString(), endDate = value.date.toString(), brief = value.brief,
            sectionsJson = ReviewPrompt.encode(value.sections), generatedAt = value.generatedAt, model = "fake"))
    }

    private suspend fun AndroidPushChannels.add(format: PushFormat = PushFormat.Json, topics: Set<PushTopic> = setOf(PushTopic.DailyReview)) =
        save(null, PushKind.Webhook, format.name, mapOf("url" to "https://example.invalid/hook"), topics, format).getOrThrow()

    @Test fun formatsAndJsonKeepFullReviewAndRetryPayload() {
        assertEquals("10月23日回顾", review.pushTitle())
        assertEquals(markdown, review.pushBody(PushFormat.Markdown))
        assertEquals(plain, review.pushBody(PushFormat.Text))
        val message = PushMessage(review.pushTitle(), plain, PushTopic.DailyReview, "resound://review?date=$date", 1234, review)
        val json = message.json()
        assertEquals(plain, json.getString("body"))
        assertEquals(setOf("title", "body", "topic", "deepLink", "at", "review"), json.keys().asSequence().toSet())
        val details = json.getJSONObject("review")
        assertEquals("2026-10-23", details.getString("date"))
        assertEquals(review.brief, details.getString("brief"))
        assertEquals("一句话", details.getJSONArray("sections").getJSONObject(0).getString("title"))
        assertEquals("确认方案", details.getJSONArray("sections").getJSONObject(1).getJSONArray("lines").getString(0))
        val restored = PushMessage.from(JSONObject(json.toString()))
        assertEquals(markdown, restored.rendered(PushFormat.Markdown))
        assertEquals(plain, restored.rendered(PushFormat.Json))
        assertEquals(message.deepLink, restored.deepLink)
        assertEquals(1234L, restored.at)
        assertNull(PushMessage.from(message.copy(review = null).json()).review)
    }

    @Test fun testUsesLatestReviewDespiteSwitchesAndKeepsItOnRetry() = runBlocking {
        fixture { db, reviews, channels, fake, local ->
            seed(db)
            seed(db, review.copy(date = date.minusDays(1), brief = "旧摘要"))
            reviews.setPushEnabled(false)
            val id = channels.add(topics = emptySet())
            channels.setEnabled(id, false)
            assertEquals("已发送 10月23日的回顾", channels.test(id).getOrThrow())
            val sent = fake.requests.single()
            assertEquals("测试 · 10月23日回顾", sent.getString("title"))
            assertEquals(plain, sent.getString("body"))
            assertEquals("2026-10-23", sent.getJSONObject("review").getString("date"))
            assertEquals(sent.getString("title"), channels.logs().first().first().title)
            assertTrue(local.isEmpty())
            fake.status = 500
            assertTrue(channels.test(id).isFailure)
            fake.status = 200
            assertFalse(channels.retry(fake.queued.single(), 0))
            assertEquals(sent.getJSONObject("review").toString(), fake.requests.last().getJSONObject("review").toString())
            assertEquals(plain, fake.requests.last().getString("body"))
        }
    }

    @Test fun testWithoutReviewKeepsExistingMessage() = runBlocking {
        fixture { _, _, channels, fake, local ->
            assertEquals("测试消息已提交，请查看接收端", channels.test(channels.add()).getOrThrow())
            val sent = fake.requests.single()
            assertEquals("测试推送", sent.getString("title"))
            assertEquals("如果你看到这条，说明通道可用", sent.getString("body"))
            assertFalse(sent.has("review"))
            assertTrue(sent.isNull("topic"))
            assertEquals(sent.getString("title"), channels.logs().first().single().title)
            assertTrue(local.isEmpty())
        }
    }

    @Test fun scheduledReviewUsesBriefLocallyAndFullBodyPerChannel() = runBlocking {
        fixture { db, reviews, channels, fake, local ->
            seed(db)
            db.memory().insertEvent(MemoryEventEntity(1, at + 1000, at + 2000, "会议", "方案", "讨论方案", "[]", "[]",
                null, null, null, false, false, at, "fake"))
            channels.add(PushFormat.Markdown)
            channels.add(PushFormat.Text)
            assertFalse(reviews.run(date, scheduled = true))
            assertNull(reviews.state.value.lastError)
            assertEquals(review.brief, local.single().body)
            assertEquals("今日回顾", local.single().title)
            assertEquals("resound://review?open=review&date=$date", local.single().deepLink)
            assertEquals(setOf(markdown, plain), fake.requests.map { it.getString("body") }.toSet())
            assertEquals(2, fake.requests.size)
            assertTrue(fake.requests.all { it.getString("title") == review.pushTitle() })
        }
    }
}
