package app.resound.diagnostics

import android.app.Application
import android.content.Context
import androidx.room.Room
import app.resound.ask.AskEngine
import app.resound.ask.AskStream
import app.resound.backup.BackupArchive
import app.resound.backup.BackupSettings
import app.resound.data.CaptureDatabase
import app.resound.data.TranscriptLineEntity
import app.resound.digest.*
import app.resound.usage.UsagePurpose
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], application = Application::class)
class LlmDiagnostics133Test {
    private val context get() = RuntimeEnvironment.getApplication() as Context
    private val preset = LlmPreset("fixture", "测试服务商", "https://fixture.invalid/v1", "test-model")
    private val original = "测试原话：周末去私人地点"
    private val lines get() = listOf(TranscriptLineEntity(1, 1, 1000, 2000, original, null))
    private val diagnostics get() = LlmDiagnostics(context)
    @Before fun reset() {
        listOf("digest", "digest-state", "llm-providers", LlmDiagnostics.PREFS).forEach {
            context.getSharedPreferences(it, 0).edit().clear().commit()
        }
    }
    private fun client(code: Int = 200, response: String): LlmClient = LlmClient(
        OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("fixture")
                .body(response.toResponseBody()).build()
        }.build(), diagnostics = diagnostics)
    private fun envelope(content: String, finish: String = "stop") = JSONObject().put("choices", JSONArray().put(
        JSONObject().put("message", JSONObject().put("content", content)).put("finish_reason", finish))).toString()

    @Test fun emptyReportHasAllSectionsAndNoInventedHistory() = runBlocking {
        val text = LlmProblemDiagnostics(context).dump()
        listOf("上次整理时间=暂无", "最近错误类型=暂无", "在途批次=false", "待整理句数=暂无", "格式失败计数", "本地保存失败计数",
            "最近放弃批次", "服务商=暂无", "备用切换", "备用列表", "最近 3 天用量", "最近大模型响应摘要", "备用切换记录：暂无", "冷却状态").forEach {
            assertTrue(it, text.contains(it))
        }
    }

    @Test fun ringKeepsTwentyAcrossInstancesAndNeverStoresBodyOrBacksUp() {
        repeat(23) { i -> diagnostics.start("整理", preset.copy(model = "model-$i")).also {
            it.content("{\"private\":\"$original sk-test-secret\"}"); it.finishReason("未知原因包含$original"); it.complete()
        } }
        val rows = diagnostics.rows()
        assertEquals(20, rows.size); assertEquals("model-3", rows.first().getString("model"))
        assertEquals("model-22", rows.last().getString("model"))
        val stored = context.getSharedPreferences(LlmDiagnostics.PREFS, 0).all.toString()
        assertFalse(stored.contains(original)); assertFalse(stored.contains("sk-test-secret"))
        assertFalse(BackupSettings.prefs.contains(LlmDiagnostics.PREFS))
        assertFalse(BackupArchive.allowed("shared_prefs/${LlmDiagnostics.PREFS}.xml"))
    }

    @Test fun requestRecordsTruncationNonJsonAndHttpErrorWithoutChangingResult() = runBlocking {
        val reply = client(response = envelope("不是JSON $original", "length")).chatJson(preset, "sk-private-key", original, original)
        assertEquals("不是JSON $original", reply.content)
        val parse = runCatching { DigestPrompt.parse(reply.content, lines, diagnostics = reply.diagnostics) }.exceptionOrNull()
        assertEquals(LlmError.Format, (parse as LlmException).type)
        val row = diagnostics.rows().last()
        assertEquals(200, row.getInt("http")); assertTrue(row.getBoolean("truncated"))
        assertFalse(row.getBoolean("json")); assertEquals("Format", row.getString("error"))
        assertEquals(reply.content.length, row.getInt("chars")); assertTrue(row.getLong("elapsedMs") >= 0)
        val error = runCatching { client(401, "{\"error\":\"$original\"}").chatJson(preset, "secret", "s", "u") }.exceptionOrNull()
        assertEquals(LlmError.Auth, (error as LlmException).type)
        assertEquals("Auth", diagnostics.rows().last().getString("error"))
        assertEquals(401, diagnostics.rows().last().getInt("http"))
        assertFalse(diagnostics.dumpResponses().contains(original))
    }

    @Test fun digestCountsDistinguishSuccessEmptyAndAllFiltered() = runBlocking {
        val inputs = listOf(
            """{"events":[{"from":1,"to":1}],"items":[{"category":"待办","content":"$original","line":1}]}""",
            """{"events":[],"items":[]}""",
            """{"events":[{"from":10,"to":20}],"items":[]}""",
        )
        for (input in inputs) {
            val reply = client(response = envelope(input)).chatJson(preset, "key", "s", original)
            DigestPrompt.parse(reply.content, lines, diagnostics = reply.diagnostics)
        }
        val rows = diagnostics.rows()
        assertEquals(3, rows.size)
        assertEquals(1, rows[0].getInt("events")); assertEquals(1, rows[0].getInt("items"))
        assertFalse(rows[0].getBoolean("empty")); assertFalse(rows[0].getBoolean("filtered"))
        assertTrue(rows[1].getBoolean("empty")); assertFalse(rows[1].getBoolean("filtered"))
        assertFalse(rows[2].getBoolean("empty")); assertTrue(rows[2].getBoolean("filtered"))
    }

    @Test fun streamTruncationAndAskEmptyAnswerAreRecorded() = runBlocking {
        val sse = "data: {\"choices\":[{\"delta\":{\"content\":\"$original\"}}]}\n\n" +
            "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"length\"}]}\n\n"
        val failure = runCatching { client(response = sse).streamChat(preset, "key", JSONArray(), JSONArray(), {}, { _, _ -> }) }.exceptionOrNull()
        assertEquals(LlmError.Format, (failure as LlmException).type)
        assertTrue(diagnostics.rows().last().getBoolean("truncated"))
        assertEquals(original.length, diagnostics.rows().last().getInt("chars"))
        val trace = diagnostics.start("问问", preset).also { it.content(""); it.complete() }
        val engine = AskEngine(AskStream { _, _, _, _ -> LlmStreamReply("", emptyList(), diagnostics = trace) }, JSONArray(),
            { _, _, _ -> JSONObject() }, { _, _ -> })
        var message: String? = null
        engine.answer("s", emptyList(), original) { message = it.error }
        assertEquals("没有收到回答，请重试", message)
        assertTrue(diagnostics.rows().last().getBoolean("empty"))
        assertEquals("Format", diagnostics.rows().last().getString("error"))
    }

    @Test fun reportAggregatesOnlyThreeDaysAndOmitsCredentialsHeadersAndOriginal() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, CaptureDatabase::class.java).setDriver(androidx.sqlite.driver.AndroidSQLiteDriver()).allowMainThreadQueries().build()
        try {
            val end = LocalDate.of(2026, 11, 10)
            db.memory().addUsage("2026-11-08", 10, 2, UsagePurpose.Digest)
            db.memory().addUsage("2026-11-10", 20, 3, UsagePurpose.Digest)
            db.memory().addUsage("2026-11-09", 4, 5, UsagePurpose.Ask)
            db.memory().addUsage("2026-11-07", 999, 999, UsagePurpose.Digest)
            context.getSharedPreferences("digest-state", 0).edit().putLong("lastRunAt", 1000)
                .putString("lastError", original).putString("inflight", "batch-private").putString("inflight-lines", "1,2")
                .putInt("fail-1", 2).putInt("fail-2", 3).putInt("local-batch", 4).commit()
            val primary = JSONObject().put("id", "p").put("name", "主服务商").put("model", "model-sk-private")
                .put("baseUrl", "https://user:password@api.example.com/v1?key=query-secret#fragment-secret")
                .put("key", "sk-private-key").put("headers", JSONObject().put("Private-Header", "header-secret"))
            context.getSharedPreferences("llm-providers", 0).edit().putString("activeId", "p")
                .putString("saved", JSONArray().put(primary).put(JSONObject().put("id", "b").put("name", "备用服务商").put("model", "backup-model")).toString()).commit()
            diagnostics.error(LlmError.Format, 1000); diagnostics.abandoned(3, 1000)
            val text = LlmProblemDiagnostics(context, db.memory(), today = { end }).dump()
            assertTrue(text.contains("整理 calls=2 prompt=30 completion=5")); assertTrue(text.contains("问问 calls=1 prompt=4 completion=5"))
            assertTrue(text.contains("在途句数=2")); assertTrue(text.contains("待整理句数=0"))
            assertTrue(text.contains("fail-*）=5")); assertTrue(text.contains("local-*）=4"))
            assertTrue(text.contains("句数=3")); assertTrue(text.contains("最近错误类型=Format"))
            assertTrue(text.contains("服务地址=https://api.example.com/v1")); assertTrue(text.contains("备用服务商 / backup-model"))
            listOf(original, "sk-private", "header-secret", "Private-Header", "query-secret", "password", "fragment-secret", "999").forEach { assertFalse(it, text.contains(it)) }
        } finally { db.close() }
    }

    @Test fun brokenDiagnosticStorageCannotFailSuccessfulRequest() = runBlocking {
        val broken = object : android.content.ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int): android.content.SharedPreferences =
                throw IllegalStateException("测试存储失败")
        }
        val client = LlmClient(OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                .body(envelope("{}").toResponseBody()).build()
        }.build(), diagnostics = LlmDiagnostics(broken))
        assertEquals("{}", client.chatJson(preset, "key", "s", "u").content)
    }

    @Test fun failoverPersistsSwitchAndReportsCurrentCooldown() = runBlocking {
        val keys = object : ProviderKeys {
            val values = mutableMapOf<String, String>()
            override fun has(id: String) = values.containsKey(id)
            override fun read(id: String) = values[id].orEmpty()
            override fun write(id: String, key: String) { values[id] = key }
            override fun remove(id: String) { values.remove(id) }
        }
        val providers = AndroidLlmProviders(context, LlmClient(), keys)
        val p = providers.saveNow(null, null, "主", preset.baseUrl, "primary", "key", "", "")
        providers.saveNow(null, null, "备", preset.baseUrl, "backup", "key", "", "")
        val failover = LlmFailover(context, providers) { 1000 }
        val c = LlmClient(OkHttpClient.Builder().addInterceptor { chain ->
            val body = okio.Buffer().also { chain.request().body!!.writeTo(it) }.readUtf8()
            val code = if (JSONObject(body).getString("model") == "primary") 503 else 200
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("fixture")
                .body(envelope("{}").toResponseBody()).build()
        }.build())
        val reply = failover.chat("技能", providers.credentials(p).first, "key", "s", "u", c::chatJson)
        assertEquals("backup", reply.actualModel)
        assertEquals(listOf("primary", "backup"), diagnostics.rows().map { it.getString("model") })
        assertTrue(diagnostics.rows().all { it.getString("purpose") == "技能" })
        val report = LlmProblemDiagnostics(context, failover = failover).dump()
        assertTrue(report.contains("主=主 / primary → 备=备 / backup")); assertTrue(report.contains("冷却=true"))
        assertTrue(LlmProblemDiagnostics(context).dump().contains("主=主 / primary → 备=备 / backup"))
    }
}
