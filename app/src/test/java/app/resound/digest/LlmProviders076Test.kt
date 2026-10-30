package app.resound.digest

import android.content.Context
import app.resound.backup.BackupSettings
import app.resound.skills.SkillFiles
import app.resound.skills.SkillJson
import app.resound.skills.SkillOrigin
import app.resound.usage.LlmBalanceChecker
import kotlinx.coroutines.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], application = android.app.Application::class)
class LlmProviders076Test {
    private val context get() = RuntimeEnvironment.getApplication() as Context
    private fun prefs(name: String) = context.getSharedPreferences(name, 0)
    private class Keys : ProviderKeys {
        val values = mutableMapOf<String, String>()
        var reads = 0; var writes = 0
        override fun has(id: String) = id in values
        override fun read(id: String): String { reads++; return values[id].orEmpty() }
        override fun write(id: String, key: String) { writes++; values[id] = key }
        override fun remove(id: String) { values.remove(id) }
    }
    private lateinit var keys: Keys
    private fun providers(client: LlmClient = LlmClient()) = AndroidLlmProviders(context, client, keys)
    private suspend fun add(p: AndroidLlmProviders, base: String = "https://example.com/v1", name: String = "服务", key: String? = "fixture-key", balance: String = "") =
        p.save(null, null, name, base, "model", key, balance, if (balance.isBlank()) "" else "data.balance")
    @Before fun reset() {
        listOf("digest", "digest-secrets", "llm-providers", "llm-balance").forEach { prefs(it).edit().clear().commit() }
        File(context.filesDir, "skills").deleteRecursively()
        keys = Keys()
    }

    @Test fun assetsContainDocumentedTemplatesAndBalanceContracts() {
        val list = providers().templates
        assertEquals(12, list.size); assertEquals(12, list.map { it.id }.toSet().size)
        assertTrue(list.all { it.models.size in 2..4 && it.name.isNotBlank() && it.keyUrl?.startsWith("https://") == true })
        assertEquals(setOf("deepseek", "kimi"), list.filter { it.balance != null }.map { it.id }.toSet())
        assertNull(LlmBalanceChecker.suggest("https://api.siliconflow.cn/v1"))
        assertFalse(LlmBalanceChecker.isDeepSeek("https://evil-deepseek.com"))
    }

    @Test fun migrationPreservesBothCiphertextsIdsSelectionAndCustomBalance() {
        val secrets = prefs("digest-secrets")
        secrets.edit().putString("key-deepseek", "original-encrypted-deepseek").putString("key-custom", "original-encrypted-custom").commit()
        prefs("digest").edit().putString("preset", "custom").putString("customEndpoint", "https://local.example/v1").putString("customModel", "legacy-model").commit()
        prefs("llm-balance").edit().putString("customUrl", "https://local.example/balance").putString("customPath", "data.balance").commit()
        val before = secrets.all.toMap()
        val p = AndroidLlmProviders(context, LlmClient())
        assertEquals(listOf("deepseek", "custom"), p.saved.value.map { it.id })
        assertEquals("custom", p.activeId.value)
        assertEquals("https://local.example/balance", p.selected().balanceUrl)
        assertTrue(p.saved.value.all { it.hasKey }); assertEquals(before, secrets.all)
        assertEquals(p.saved.value, AndroidLlmProviders(context, LlmClient()).saved.value)
        assertEquals(before, secrets.all)
    }

    @Test fun migrationWithOnlyCustomUsesOriginalKeyWithoutRewriting() {
        keys.values["custom"] = "original-key"
        prefs("digest").edit().putString("preset", "custom").putString("customEndpoint", "https://example.com/v1").putString("customModel", "old").commit()
        val p = providers()
        assertEquals(listOf("custom"), p.saved.value.map { it.id }); assertEquals("custom", p.activeId.value)
        assertEquals(0, keys.reads); assertEquals(0, keys.writes)
        assertEquals("original-key", AndroidDigestSettings(context, LlmClient(), p).readKey())
    }

    @Test fun emptyMigrationCreatesDefaultAndFirstSaveBecomesActive() = runBlocking {
        val p = providers()
        assertEquals("deepseek", p.activeId.value); assertFalse(p.saved.value.single().hasKey)
        val id = add(p, "http://127.0.0.1:11434", key = "")
        val settings = AndroidDigestSettings(context, LlmClient(), p)
        assertEquals(id, settings.presetId.value); assertTrue(settings.hasKey.value)
        assertTrue(app.resound.redo.llmOptions(settings).first { it.presetId == id }.ready)
    }

    @Test fun saveSwitchReloadAndNullKeyKeepCredentialsAndBackupMetadata() = runBlocking {
        val p = providers(); val settings = AndroidDigestSettings(context, LlmClient(), p)
        val a = add(p, name = "甲", key = "key-a"); val b = add(p, name = "乙", key = "key-b")
        assertNotEquals(a, b)
        p.activate(b); assertEquals(b, settings.presetId.value); assertEquals("key-b", settings.readKey())
        p.save(b, null, "乙修改", "https://other.example/v1", "new-model", null, "", "")
        assertEquals("key-b", providers().credentials(b).second)
        assertEquals("new-model", settings.presets.first { it.id == b }.model)
        assertEquals("key-a", settings.skillCredentials(a).second)
        assertEquals(b, settings.presetId.value)
        assertFalse(prefs("llm-providers").getString("saved", "")!!.contains("key-b"))
        val dir = File(context.cacheDir, "backup076").apply { mkdirs() }
        BackupSettings.capture(context, dir) { _, _ -> "fixture" }
        assertEquals(b, JSONObject(File(dir, "settings.json").readText()).getJSONObject("llm-providers").getJSONObject("activeId").getString("value"))
        settings.clearKey(); assertEquals("", settings.readKey()); assertFalse(settings.hasKey.value)
    }

    @Test fun deletionRejectsActiveAndResetsSkillReferenceOnDisk() = runBlocking {
        val p = providers(); val a = add(p, key = "key-a"); val b = add(p, key = "key-b")
        val root = File(context.filesDir, "skills")
        fun skillFiles() = SkillFiles(root) {
            File("src/main/assets/skills").listFiles()!!.filter { it.isDirectory }.map { dir ->
                SkillJson.parse(File(dir, "skill.json").readText(), SkillOrigin.Builtin) to File(dir, "prompt.md").readText()
            }
        }
        val files = skillFiles()
        files.change("meeting") { it.put("presetId", b) }
        p.beforeDelete = { files.resetProviders(setOf(it)) }
        assertTrue(runCatching { p.delete(a) }.isFailure); assertEquals("key-a", p.credentials(a).second)
        p.delete(b)
        assertNull(skillFiles().skills.value.first { it.id == "meeting" }.presetId)
        assertEquals(a, AndroidDigestSettings(context, LlmClient(), p).skillCredentials(b).first.id)
        assertFalse(keys.has(b)); assertEquals(a, providers().activeId.value)
    }

    @Test fun draftTestAndModelsUseSelectedKeyWithoutSavingOrActivating() = runBlocking {
        MockWebServer().use { server ->
            val p = providers(); val id = add(p, server.url("/v1").toString())
            val before = prefs("llm-providers").all.toMap(); val secretBefore = keys.values.toMap()
            server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"content":"{\"ok\":true}"}}]}"""))
            assertTrue(p.test(id, server.url("/v1").toString(), "draft-model", null).isSuccess)
            val request = server.takeRequest(); assertEquals("Bearer fixture-key", request.getHeader("Authorization"))
            assertEquals("draft-model", JSONObject(request.body.readUtf8()).getString("model"))
            server.enqueue(MockResponse().setBody("""{"data":[{"id":"b"},{"id":"a"}]}"""))
            assertEquals(listOf("a", "b"), p.models(id, server.url("/v1").toString(), null))
            assertEquals("/v1/models", server.takeRequest().path)
            server.enqueue(MockResponse().setResponseCode(401).setBody("never expose server detail"))
            assertEquals(emptyList<String>(), p.models(id, server.url("/v1").toString(), null))
            assertEquals(before, prefs("llm-providers").all); assertEquals(secretBefore, keys.values)
        }
    }

    @Test fun balanceCachesAndLowAlertsAreIndependentAndEditsInvalidate() = runBlocking {
        MockWebServer().use { server ->
            val p = providers(); val a = add(p, balance = server.url("/a").toString()); val b = add(p, balance = server.url("/b").toString())
            val alerts = mutableListOf<Double>()
            val checker = LlmBalanceChecker(prefs("llm-balance"), p, { balance, _ -> alerts += balance.total })
            suspend fun refresh(value: Int) { server.enqueue(MockResponse().setBody("""{"data":{"balance":$value}}""")); checker.refresh() }
            refresh(1); p.activate(b); assertNull(checker.state.value.balance); refresh(2)
            p.activate(a); assertEquals(1.0, checker.state.value.balance!!.total, 0.0); checker.refresh()
            assertEquals(2, server.requestCount); assertEquals(listOf(1.0, 2.0), alerts)
            p.activate(b); checker.refresh(); assertEquals(2, alerts.size)
            p.setKey("new-key"); assertNull(checker.state.value.balance); refresh(3)
            assertEquals(listOf(1.0, 2.0, 3.0), alerts)
            assertEquals(3.0, LlmBalanceChecker(prefs("llm-balance"), p, { _, _ -> }).state.value.balance!!.total, 0.0)
        }
    }

    @Test fun balanceResponseFromPreviousSelectionCannotOverwriteOrAlert() = runBlocking {
        MockWebServer().use { server ->
            val arrived = java.util.concurrent.CountDownLatch(1); val release = java.util.concurrent.CountDownLatch(1)
            server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                    arrived.countDown(); check(release.await(5, TimeUnit.SECONDS))
                    return MockResponse().setBody("""{"data":{"balance":1}}""")
                }
            }
            val p = providers(); val a = add(p, balance = server.url("/a").toString()); val b = add(p)
            var alerts = 0
            val checker = LlmBalanceChecker(prefs("llm-balance"), p, { _, _ -> alerts++ })
            val job = launch(Dispatchers.IO) { checker.refresh() }
            try {
                assertTrue(arrived.await(3, TimeUnit.SECONDS)); p.activate(b)
            } finally { release.countDown() }
            withTimeout(5000) { job.join() }
            assertFalse(checker.state.value.supported); assertNull(checker.state.value.balance); assertEquals(0, alerts)
            assertFalse(prefs("llm-balance").contains("cached-$a"))
        }
    }

    @Test fun invalidEditsDoNotAlterMetadataOrKeysAndLegacyCustomStillWorks() = runBlocking {
        val p = providers(); val id = add(p)
        val before = prefs("llm-providers").all.toMap()
        assertTrue(runCatching { p.save(id, "dashscope", "百炼", "https://{WorkspaceId}.cn-beijing.maas.aliyuncs.com/compatible-mode/v1", "qwen-flash", "replacement", "", "") }.isFailure)
        assertEquals(before, prefs("llm-providers").all); assertEquals("fixture-key", p.credentials(id).second)
        val settings = AndroidDigestSettings(context, LlmClient(), p)
        settings.setPreset("custom"); settings.saveCustomAll("http://localhost:11434", "qwen", "")
        assertTrue(settings.hasKey.value); assertEquals("custom", settings.presetId.value)
        assertTrue(AndroidDigestSettings(context, LlmClient(), providers()).hasKey.value)
    }

    @Test fun realClientUsesDocumentedParametersForTemplateModels() = runBlocking {
        val seen = mutableListOf<JSONObject>()
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val buffer = okio.Buffer(); chain.request().body!!.writeTo(buffer); seen += JSONObject(buffer.readUtf8())
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""{"choices":[{"message":{"content":"{}"}}]}""".toResponseBody("application/json".toMediaType())).build()
        }.build()
        val client = LlmClient(http); val p = providers()
        for (id in listOf("deepseek", "kimi", "bigmodel", "minimax", "openai", "siliconflow", "hunyuan")) {
            val t = p.templates.first { it.id == id }
            client.chatJson(LlmPreset("arbitrary-id", t.name, t.baseUrl, t.models.first()), "fixture", "只输出 JSON", "{}")
        }
        assertEquals("disabled", seen[0].getJSONObject("thinking").getString("type"))
        assertFalse(seen[1].has("temperature")); assertEquals("low", seen[1].getString("reasoning_effort")); assertTrue(seen[1].has("max_completion_tokens"))
        assertEquals("enabled", seen[2].getJSONObject("thinking").getString("type"))
        assertTrue(seen[3].getBoolean("reasoning_split")); assertFalse(seen[3].has("response_format"))
        assertEquals("none", seen[4].getString("reasoning_effort"))
        assertFalse(seen[5].getBoolean("enable_thinking"))
        assertEquals(16_384, seen[6].getInt("max_tokens"))
    }

    @Test fun streamingReasoningIsRetainedForToolRoundButNeverEmittedAsAnswer() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""
                data: {"choices":[{"delta":{"reasoning_content":"private reasoning"}}]}

                data: {"choices":[{"delta":{"content":"查到了","tool_calls":[{"index":0,"id":"call-1","function":{"name":"search_events","arguments":"{}"}}]},"finish_reason":"tool_calls"}]}

                data: [DONE]

            """.trimIndent() + "\n\n"))
            var answer = ""
            val reply = LlmClient().streamChat(LlmPreset("new-id", "服务", server.url("/").toString(), "model"), "fixture", JSONArray(), JSONArray().put(JSONObject()), { answer += it }, { _, _ -> })
            assertEquals("查到了", answer)
            assertEquals("private reasoning", reply.message().getString("reasoning_content"))
            assertEquals("call-1", reply.calls.single().id)
            assertEquals("/v1/chat/completions", server.takeRequest().path)
        }
    }
}
