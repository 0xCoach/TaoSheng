package app.resound.diagnostics

import android.app.Application
import android.content.Context
import androidx.room.Room
import app.resound.ask.*
import app.resound.data.*
import app.resound.digest.*
import app.resound.search.SearchIndex
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
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
@Config(sdk = [32], application = Application::class)
@org.robolectric.annotation.SQLiteMode(org.robolectric.annotation.SQLiteMode.Mode.NATIVE)
class AskCompatibility136Test {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Test fun fallbackSurvivesRestartAppearsInDiagnosticsAndClearsOnEditOrToolCheckPass() = runBlocking {
        listOf("llm-providers", "ask-compatibility").forEach { context.getSharedPreferences(it, 0).edit().clear().commit() }
        val keys = object : ProviderKeys {
            val values = mutableMapOf<String, String>()
            override fun has(id: String) = values.containsKey(id)
            override fun read(id: String) = values[id].orEmpty()
            override fun write(id: String, key: String) { values[id] = key }
            override fun remove(id: String) { values.remove(id) }
        }
        var step = 0
        val client = LlmClient(OkHttpClient.Builder().addInterceptor { chain ->
            val body = okio.Buffer().also { chain.request().body!!.writeTo(it) }.readUtf8()
            val request = JSONObject(body)
            val response = if (!request.optBoolean("stream")) """{"choices":[{"message":{"content":"{}"}}]}""" else {
                val delta = when (++step) {
                    1 -> """{"content":"好"}"""
                    2 -> """{"tool_calls":[{"id":"a","function":{"name":"search_notes","arguments":"{}"}}]}"""
                    else -> """{"content":"蓝鲸[^1]"}"""
                }
                "data: {\"choices\":[{\"delta\":$delta}]}\n\ndata: {\"choices\":[{\"delta\":{},\"finish_reason\":\"${if (step == 2) "tool_calls" else "stop"}\"}]}\n\ndata: [DONE]\n\n"
            }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("test").body(response.toResponseBody()).build()
        }.build())
        val providers = AndroidLlmProviders(context, client, keys)
        val id = providers.saveNow(null, null, "测试", "https://test.invalid/v1", "model", "test-key", "", "")
        providers.activate(id)
        val preset = providers.credentials().first
        val store = providers.askCompatibility; val generation = store.generation(id)
        store.mark(preset, AskFailure.ToolFormat, generation)
        assertNotNull(AskCompatibility(context).state(preset).fallback)
        assertNull(store.state(preset.copy(model = "other")).fallback)
        val report = LlmProblemDiagnostics(context).dump()
        assertTrue(report.contains("问问程序先查=true")); assertTrue(report.contains(AskFailure.ToolFormat.human))
        assertFalse(report.contains("test-key")); assertTrue(report.contains("上次实际问答失败="))
        val settings = AndroidDigestSettings(context, client, providers)
        val checked = settings.check().getOrThrow()
        assertEquals(ModelCheckStatus.Passed, checked.first { it.feature == ModelCheck.TOOLS }.status)
        assertNull(store.state(preset).fallback)
        assertEquals(AskFailure.ToolFormat, checked.first().lastAskFailure!!.reason)
        assertTrue(checked.first().lastAskFailure!!.at > 0)
        store.mark(preset, AskFailure.NoLookup, generation)
        providers.saveNow(id, null, "测试", preset.baseUrl, "other", null, "", "")
        store.mark(preset, AskFailure.ToolRejected, generation) // 旧在途请求不能污染新配置。
        assertNull(store.state(preset).fallback)
        assertNull(store.state(preset.copy(model = "other")).fallback)
    }

    @Test fun markedBackupSkipsToolsBeforeNetworkAndDoesNotInventFailureOrUsage() = runBlocking {
        listOf("llm-providers", "ask-compatibility", "digest").forEach { context.getSharedPreferences(it, 0).edit().clear().commit() }
        val keys = object : ProviderKeys {
            val values = mutableMapOf<String, String>()
            override fun has(id: String) = values.containsKey(id)
            override fun read(id: String) = values[id].orEmpty()
            override fun write(id: String, key: String) { values[id] = key }
            override fun remove(id: String) { values.remove(id) }
        }
        val requests = mutableListOf<JSONObject>()
        val client = LlmClient(OkHttpClient.Builder().addInterceptor { chain ->
            val request = JSONObject(okio.Buffer().also { chain.request().body!!.writeTo(it) }.readUtf8())
            requests += request
            val primary = request.getString("model") == "primary"
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(if (primary) 401 else 200).message("test")
                .body((if (primary) "{}" else "data: {\"choices\":[{\"delta\":{\"content\":\"没找到\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n").toResponseBody()).build()
        }.build())
        val providers = AndroidLlmProviders(context, client, keys)
        val primary = providers.saveNow(null, null, "主", "https://test.invalid/v1", "primary", "test-key", "", "")
        val backup = providers.saveNow(null, null, "备", "https://test.invalid/v1", "backup", "test-key", "", "")
        val preset = providers.credentials(primary).first; val fallback = providers.credentials(backup).first
        val store = providers.askCompatibility
        store.mark(fallback, AskFailure.ToolFormat, store.generation(backup), actualFailure = false)
        val failover = LlmFailover(context, providers)
        val session = LlmFailover.StreamSession(preset, "test-key").also { it.askFallback = true }
        var prefetching = false; var usages = 0; var result: AskEngine.Update? = null
        val definitions = JSONArray().put(JSONObject())
        AskEngine(AskStream { messages, requested, delta, _ ->
            failover.stream("问答", session, messages, if (prefetching) JSONArray() else definitions, client, delta,
                { _, _ -> usages++ }, requested.length() > 0)
        }, definitions, { _, _, _ -> JSONObject() }, { _, _ -> }, markFallback = { _, actual ->
            assertFalse(actual); prefetching = true
        }).answer("", emptyList(), "今天说过什么") { result = it }
        assertEquals(listOf("primary", "backup"), requests.map { it.getString("model") })
        assertTrue(requests.first().has("tools")); assertFalse(requests.last().has("tools"))
        assertEquals(2, usages); assertEquals(AskMode.Prefetch, result!!.mode); assertNull(result!!.error)
        assertNull(store.state(fallback).lastFailure)
    }

    @Test fun realPrefetchFiltersTimeAndPersistsModeAndReferencesWithoutSchemaChange() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, CaptureDatabase::class.java)
            .addCallback(SearchIndex.callback).allowMainThreadQueries().build()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val at = AskTools.timestamp("2026-11-10 09:00", false, ZoneId.systemDefault())
        try {
            val sql = db.openHelper.writableDatabase
            sql.execSQL("INSERT INTO segments(id,startAt,endAt,sourceId,path,sizeBytes,status) VALUES(1,?,?,'mic','test',1,'Done')", arrayOf(at, at + 1000))
            for (id in 1..2) {
                val time = at + (id - 1) * 12 * 3600_000L
                sql.execSQL("INSERT INTO transcript_lines(id,segmentId,startAt,endAt,text) VALUES(?,1,?,?,?)", arrayOf<Any>(id, time, time + 1000, "给老王发方案"))
            }
            val tools = AskTools(db, JSONObject(File("src/main/assets/prompts/ask-sql.json").readText()))
            val prefetch = AskPrefetch(tools::execute) { LocalDate.of(2026, 11, 10) }
            val evidence = prefetch.read("今天上午方案", null)
            assertEquals(listOf(1L), evidence.sources.map { it.id })
            val prefs = context.getSharedPreferences("test-prefetch-suggestions", 0)
            fun repository() = RoomAskRepository(db.ask(), scope, { true }, engine = {
                AskEngine(AskStream { messages, definitions, _, _ ->
                    assertEquals(0, definitions.length()); assertTrue(messages.toString().contains("给老王发方案"))
                    LlmStreamReply("给老王发方案[^1]", emptyList())
                }, JSONArray(), { _, _, _ -> error("模型不能再查") }, { _, _ -> }, mode = { AskMode.Prefetch }, prefetch = prefetch::read)
            }, system = { "" }, recommended = AskSuggestions(prefs, { false }, { "" }, { emptyList() }))
            val repo = repository(); repo.ask("今天上午方案")
            val result = withTimeout(5000) { repo.messages.first { it.size == 2 && !it.last().streaming } }.last()
            assertNull(result.error); assertEquals(AskMode.Prefetch, result.mode)
            assertEquals(listOf(1L), result.sources.map { it.id })
            val loaded = withTimeout(5000) { repository().messages.first { it.size == 2 } }.last()
            assertEquals(result, loaded)
            assertEquals(AskMode.Tools, AskCitations.mode("[]"))
            assertEquals(CaptureDatabase.VERSION, db.openHelper.writableDatabase.version)
        } finally { scope.cancel(); scope.coroutineContext[Job]!!.join(); db.close() }
    }
}
