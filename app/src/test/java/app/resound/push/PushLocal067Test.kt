package app.resound.push

import android.app.Application
import android.content.Context
import androidx.room.Room
import app.resound.data.*
import app.resound.skills.SkillCardRow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, application = Application::class)
class PushLocal067Test {
    @get:Rule val temp = TemporaryFolder()
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private fun prefs() = context.getSharedPreferences(UUID.randomUUID().toString(), 0)
    private val date = LocalDate.of(2026, 10, 29)
    private val zone = ZoneId.of("Asia/Shanghai")
    private val at = date.atTime(10, 0).atZone(zone).toInstant().toEpochMilli()
    private val sub = PushSubscription.ForAgents.copy(backfillDays = null, fullCheckAt = null)
    private fun event(id: Long = 1) = MemoryEventEntity(id, at, at + 1000, "工作", "正常内容", "摘要", "[]", "[]", null, null, null, false, false, at, "test")
    private fun card() = SkillCardRow(1, "test", 1, "技能", "card", date.toEpochDay(), "day", at, null, "秘密卡标题", "秘密摘要", "[]", "", "[]", at, false)
    private class Secrets : PushSecrets {
        val data = mutableMapOf<String, String>()
        override fun read(ref: String) = data[ref].orEmpty()
        override fun write(ref: String, value: String) { data[ref] = value }
        override fun remove(ref: String) { data.remove(ref) }
    }
    private fun transport(server: MockWebServer) = PushTransport(OkHttpClient.Builder().addInterceptor { chain ->
        chain.proceed(chain.request().newBuilder().url(server.url(chain.request().url.encodedPath)).build())
    }.build())
    private suspend fun repository(server: MockWebServer, settings: android.content.SharedPreferences = prefs(), outbox: android.content.SharedPreferences = prefs()): Pair<AndroidPushChannels, Long> {
        val repo = AndroidPushChannels(settings, outbox, Secrets(), transport(server), {}, stateDirectory = temp.newFolder())
        val id = repo.saveWithSubscription(null, PushKind.Webhook, "秘密通道名", mapOf("url" to "https://example.test/private-path", "authorization" to "Bearer secret-value"), sub, PushFormat.Json, false).getOrThrow()
        return repo to id
    }
    @Test fun brokenCardIsSkippedAndHealthyRecordDelivered() = runBlocking {
        MockWebServer().use { server ->
            server.start(); server.enqueue(MockResponse().setBody("<html>ignored</html>"))
            val (repo, id) = repository(server)
            repo.subscriptions.source = { _, _ -> PushSnapshot(events = listOf(event()), cards = listOf(card())) }
            assertEquals("已推送；有 1 条记录格式异常，已跳过", repo.pushNow(id).getOrThrow())
            val body = server.takeRequest().body.readUtf8()
            assertTrue(body.contains("正常内容")); assertFalse(body.contains("秘密卡标题"))
            assertTrue(repo.logs(1).first().single().ok)
        }
    }
    @Test fun allBrokenIsLocalFailureAndNoRequest() = runBlocking {
        MockWebServer().use { server ->
            server.start(); val (repo, id) = repository(server)
            repo.subscriptions.source = { _, _ -> PushSnapshot(cards = listOf(card())) }
            val error = repo.pushNow(id).exceptionOrNull()!!.message!!
            assertEquals("本地有 1 条记录格式异常，无法生成推送内容", error)
            assertFalse(error.contains("服务返回")); assertEquals(0, server.requestCount)
        }
    }
    @Test fun responseOnlyJsonErrorsAreRemote() = runBlocking {
        MockWebServer().use { server ->
            server.start(); server.enqueue(MockResponse().setBody("<html>bad</html>"))
            val client = OkHttpClient.Builder().addInterceptor { chain -> chain.proceed(chain.request().newBuilder().url(server.url("/send")).build()) }.build()
            PushTrace.run { trace ->
                val result = PushTransport(client).send(PushKind.PushPlus, PushFormat.Text, mapOf("token" to "fake-token"), PushMessage("测试", "内容", null, null, at))
                assertEquals(PushErrors.INVALID_RESPONSE, result.exceptionOrNull()!!.message)
                assertEquals("解析回复", trace.stage); assertEquals("JSONException", trace.exception)
            }
            assertFalse(PushErrors.failure(org.json.JSONException("local"), emptyMap()).message!!.contains("服务返回"))
        }
    }
    @Test fun corruptStateResetsAndContinues() = runBlocking {
        MockWebServer().use { server ->
            server.start(); server.enqueue(MockResponse().setBody("{}"))
            val settings = prefs(); val directory = temp.newFolder()
            val channel = PushChannel(1, PushKind.Webhook, "测试", true, Subscriptions.topics(sub), PushFormat.Json, "", subscription = sub)
            settings.edit().putString("subscription-1", "broken").commit()
            java.io.File(directory, "1.json").writeText("broken")
            val runtime = SubscriptionRuntime(settings, directory, { channel }, { channel to mapOf("url" to "https://example.test/") }, transport(server), { _, _, _ -> }, { _, _, _ -> }, { at }, { zone })
            runtime.source = { _, _ -> PushSnapshot(events = listOf(event())) }
            assertTrue(runtime.run(1).isSuccess)
            assertTrue(PushDiagnostics.dump(settings).contains("订阅状态损坏"))
            PushTrace.run { trace ->
                val file = java.io.File(directory, "1.json"); file.writeText("{\"records\":[]}")
                val state = PushSyncState(file)
                assertEquals(0, state.records.length()); assertTrue(trace.notices.single().contains("同步状态损坏"))
                state.save(); assertNotNull(JSONObject(file.readText()))
            }
        }
    }
    @Test fun diagnosticsHaveChannelsAndThirtyLogsWithoutSecretsOrContents() = runBlocking {
        MockWebServer().use { server ->
            server.start(); server.enqueue(MockResponse().setBody("{}"))
            val settings = prefs(); val (repo, id) = repository(server, settings)
            repo.subscriptions.source = { _, _ -> PushSnapshot(events = listOf(event()), cards = listOf(card())) }
            repo.pushNow(id).getOrThrow()
            val first = PushDiagnostics.dump(settings)
            assertTrue(first.contains("card")); assertTrue(first.contains("\"skipped\":1")); assertTrue(first.contains("stage"))
            repeat(31) { PushDiagnostics.record(settings, repo.channels.value.single(), PushTopic.DailyMemory, Result.success(Unit), PushTrace()) }
            val report = PushDiagnostics.dump(settings)
            assertEquals(30, org.json.JSONArray(settings.getString("diagnostics", "[]")).length())
            assertTrue(report.contains("类型=Webhook 格式=Json")); assertTrue(report.contains("上次结果=已推送"))
            listOf("private-path", "127.0.0.1", "secret-value", "秘密通道名", "正常内容", "秘密卡标题", "秘密摘要").forEach { assertFalse(it, report.contains(it)) }
        }
    }
    @Test fun corruptRetryIsDiscardedImmediately() = runBlocking {
        MockWebServer().use { server ->
            server.start(); val outbox = prefs(); val (repo, _) = repository(server, outbox = outbox)
            outbox.edit().putString("bad", "{\"channelId\":1,\"message\":{}}").commit()
            assertFalse(repo.retry("bad", 0)); assertFalse(outbox.contains("bad")); assertEquals(0, server.requestCount)
        }
    }
    @Test fun multipleBrokenFieldsCountOnceAndDoNotBecomeDeltaDeletes() = runBlocking {
        PushTrace.run { trace ->
            val snapshot = PushSnapshot(events = listOf(event().copy(pointsJson = "", peopleJson = "null"), event(2)), cards = listOf(card().copy(evidenceJson = "")), reviews = listOf(ReviewEntity(1, "daily", "$date", "$date", "秘密回顾", "", at, "test")))
            val data = snapshot.select(sub, null, date, zone)
            assertEquals(setOf("event:1", "card:1", "review:1"), trace.skipped)
            val records = RecordDelta.records(data, sub)
            val changes = RecordDelta.changes(records, JSONObject().put("event:1", "old")).getJSONArray("changes")
            assertEquals(1, changes.length()); assertEquals("upsert", changes.getJSONObject(0).getString("op"))
            assertFalse(MemoryFolder(date, zone).generate(data, sub).values.joinToString().contains("秘密回顾"))
            dailyMemoryJson(date, at, "", null, app.resound.data.ReviewInput(snapshot.events, emptyList()), emptyList(), null)
            assertEquals(3, trace.skipped.size)
        }
    }
    @Test fun editAndRestoreNormalizeOldBrokenPoints() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, CaptureDatabase::class.java).allowMainThreadQueries().build()
        try {
            val dao = db.memory()
            dao.insertEvent(event().copy(pointsJson = "null", originalPointsJson = "", editedAt = at, originalTitle = "原始", originalSummary = "原摘要"))
            dao.restoreEvent(1)
            assertEquals("[]", dao.event(1)!!.pointsJson)
            dao.editEvent(1, "编辑", "摘要", "", at)
            assertEquals("[]", dao.event(1)!!.pointsJson); assertEquals("[]", dao.event(1)!!.originalPointsJson)
        } finally { db.close() }
    }
}
