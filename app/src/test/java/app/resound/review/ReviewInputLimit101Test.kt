package app.resound.review

import android.app.Application
import android.content.Context
import androidx.room.Room
import app.resound.data.*
import app.resound.digest.*
import app.resound.push.Notifier
import app.resound.push.PushTopic
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
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
class ReviewInputLimit101Test {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val date = LocalDate.parse("2026-11-07")
    private val zone = ZoneId.of("Asia/Shanghai")
    private val at = date.atStartOfDay(zone).toInstant().toEpochMilli()
    private val unknown = LlmPreset("custom", "自定义", "https://example.invalid/v1", "unknown")
    private val response = """{"brief":"讨论方案","sections":[{"title":"一句话","lines":[{"text":"讨论方案","eventId":1}]},{"title":"发生了什么","lines":[{"text":"讨论方案","eventId":1}]}]}"""
    private fun assets() = context.assets.open("llm-providers.json").bufferedReader().use { it.readText() }
    private fun event(id: Long = 1, summary: String = "摘要", points: String = "要点") = MemoryEventEntity(
        id, at + id, at + id + 1, "会议", "方案", summary, JSONArray(listOf(points)).toString(), "[]",
        null, null, null, false, createdAt = at, model = "test")
    private fun item(id: Long, eventId: Long?) = MemoryItemEntity(id, eventId, "待办", "行动", "未完成内容",
        null, null, null, null, "Pending", at + 1)
    private fun input(source: ReviewInput) = ReviewPrompt.input(date, source, zone)

    private suspend fun repository(preset: LlmPreset, e: MemoryEventEntity,
        chat: suspend (LlmPreset, String, String, String) -> LlmReply,
        check: suspend (RoomReviewRepository) -> Unit) {
        val db = Room.inMemoryDatabaseBuilder(context, CaptureDatabase::class.java).build()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val prefs = context.getSharedPreferences("review101", 0).apply { edit().clear().commit() }
        val repo = RoomReviewRepository(db.review(), db.memory(), prefs, scope, { preset to "test" }, chat,
            { "只输出 JSON" }, "涛声", object : Notifier {
                override suspend fun send(title: String, body: String, deepLink: String?, topic: PushTopic?) = Unit
            }, {}, now = { at + 22 * 3600000 }, zone = { zone })
        try { db.memory().insertEvent(e); check(repo) }
        finally { scope.cancel(); db.close() }
    }

    @Test fun deepseekSends150kIntactThroughRepositoryAndClient() = runBlocking {
        LlmInputBudget.configure(assets())
        val preset = SavedProvider("saved-deepseek", "deepseek", "DeepSeek", "https://api.deepseek.com", "deepseek-flash", true).preset()
        val e = event(summary = "摘".repeat(100_000), points = "点".repeat(50_000))
        var sent: JSONObject? = null
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val body = Buffer().also { chain.request().body!!.writeTo(it) }.readUtf8()
            sent = JSONObject(body)
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(JSONObject().put("choices", JSONArray().put(JSONObject().put("message", JSONObject().put("content", response))))
                    .toString().toResponseBody("application/json".toMediaType())).build()
        }.build()
        try { repository(preset, e, LlmClient(http)::chatJson) { repo ->
            assertFalse(repo.run(date, false)); assertNull(repo.state.value.lastError)
            val text = sent!!.getJSONArray("messages").getJSONObject(1).getString("content")
            assertEquals(input(ReviewInput(listOf(e), emptyList())), text)
            assertFalse(JSONObject(text).has("compressed"))
            assertEquals(941_808, LlmInputBudget.inputChars(preset))
        } } finally { http.dispatcher.executorService.shutdown(); http.connectionPool.evictAll() }
    }

    @Test fun unknownAndCustomDefaultAndCompressionPreservesProtectedMaterial() {
        LlmInputBudget.configure(assets())
        val custom = SavedProvider("custom-known-name", null, "自填", "https://api.deepseek.com", "deepseek-flash", true).preset()
        assertEquals(128_000, LlmInputBudget.capacity(custom).contextTokens)
        assertEquals(113_408, LlmInputBudget.inputChars(unknown))
        val source = ReviewInput(listOf(event(1, "摘".repeat(110_000), "点".repeat(40_000)),
            event(2).copy(important = true), event(3).copy(starred = true, starredAt = at), event(4)),
            listOf(item(41, 4), item(42, null)), listOf(event(5).copy(starred = true, starredAt = at)))
        val original = JSONObject(input(source))
        val text = ReviewInputBudget.fit(original.toString(), "系统".repeat(2500), unknown)
        val json = JSONObject(text)
        assertTrue(text.length + 5000 <= LlmInputBudget.inputChars(unknown))
        assertTrue(json.has("compressed"))
        val events = json.getJSONArray("events")
        assertEquals(listOf(1L, 2L, 3L, 4L), (0 until events.length()).map { events.getJSONObject(it).getLong("id") })
        assertFalse(events.getJSONObject(0).has("points"))
        assertTrue(events.getJSONObject(0).getString("summary").length < 110_000)
        for (i in 1..3) assertEquals(original.getJSONArray("events").getJSONObject(i).toString(), events.getJSONObject(i).toString())
        for (field in listOf("standalone_items", "starred_stale")) assertEquals(original.getJSONArray(field).toString(), json.getJSONArray(field).toString())
    }

    @Test fun pointsRemovedBeforeAnySummaryIsShortened() {
        val source = ReviewInput(listOf(event(summary = "完整摘要", points = "点".repeat(120_000))), emptyList())
        val json = JSONObject(ReviewInputBudget.fit(input(source), "系统", unknown))
        assertEquals("完整摘要", json.getJSONArray("events").getJSONObject(0).getString("summary"))
        assertFalse(json.getJSONArray("events").getJSONObject(0).has("points"))
    }

    @Test fun protectedOverflowShowsChineseWithoutSending() = runBlocking {
        repository(unknown, event(summary = "重".repeat(120_000)).copy(important = true),
            { _, _, _, _ -> error("不得发请求") }) { repo ->
            assertFalse(repo.run(date, false))
            assertEquals("内容超出所选模型的容量，请换用容量更大的模型", repo.state.value.lastError)
        }
    }

    @Test fun capacitiesParseFromAssetsAndOldFormatDefaults() {
        val capacities = LlmInputBudget.parse(assets())
        assertEquals(1_000_000, capacities.getValue("deepseek" to "deepseek-flash").contextTokens)
        assertEquals(192_000, capacities.getValue("hunyuan" to "hy3").inputChars)
        val old = LlmInputBudget.parse("""[{"id":"old","models":["legacy"]}]""")
        assertEquals(128_000, old.getValue("old" to "legacy").contextTokens)
        assertEquals(113_408, old.getValue("old" to "legacy").inputChars)
        assertEquals(12, AndroidLlmProviders.parseTemplates(assets()).size)
    }

    @Test fun fallbackAndMissingWorkerShowChinese() = runBlocking {
        repository(unknown, event(), { _, _, _, _ -> throw IllegalStateException("private failure") }) { repo ->
            assertFalse(repo.run(date, false))
            assertEquals("生成失败，请稍后重试", repo.state.value.lastError)
            repo.generateNow(date)
            assertEquals("生成失败，请稍后重试", repo.state.value.lastError)
        }
    }
}
