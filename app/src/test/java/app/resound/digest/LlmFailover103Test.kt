package app.resound.digest

import android.content.Context
import androidx.room.Room
import app.resound.backup.BackupSettings
import app.resound.data.*
import kotlinx.coroutines.*
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
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], application = android.app.Application::class)
class LlmFailover103Test {
    private val context get() = RuntimeEnvironment.getApplication() as Context
    private class Keys : ProviderKeys {
        val data = mutableMapOf<String, String>()
        override fun has(id: String) = id in data
        override fun read(id: String) = data[id].orEmpty()
        override fun write(id: String, key: String) { data[id] = key }
        override fun remove(id: String) { data.remove(id) }
    }
    private lateinit var keys: Keys
    private lateinit var providers: AndroidLlmProviders
    private lateinit var failover: LlmFailover
    private lateinit var primary: LlmPreset
    private val reply = LlmReply("{}", 20, 3)
    private var time = 1000L
    @Before fun setup() {
        listOf("digest", "digest-state", "llm-providers", "digest-secrets").forEach { context.getSharedPreferences(it, 0).edit().clear().commit() }
        keys = Keys(); providers = AndroidLlmProviders(context, LlmClient(), keys)
        val id = providers.saveNow(null, null, "主", "https://fixture.invalid/v1", "primary", "fixture", "", "")
        primary = providers.credentials(id).first
        failover = LlmFailover(context, providers) { time }
    }
    private fun backup(model: String = "backup", template: String? = null): LlmPreset {
        val id = providers.saveNow(null, template, model, "https://fixture.invalid/v1", model, "fixture", "", "")
        return providers.credentials(id).first
    }
    private suspend fun chat(call: suspend (LlmPreset, String, String, String) -> LlmReply,
        usage: suspend (LlmReply) -> Unit = {}) = failover.chat("整理", primary, "fixture", "s", "u", call, usage)
    private fun client(respond: (String) -> Pair<Int, String>) = LlmClient(OkHttpClient.Builder().addInterceptor { chain ->
        val buffer = okio.Buffer(); chain.request().body!!.writeTo(buffer)
        val (status, body) = respond(JSONObject(buffer.readUtf8()).getString("model"))
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(status).message("fixture")
            .body(body.toResponseBody()).build()
    }.build())

    @Test fun serverFailureUsesFirstBackupAndCountsReturnedFailureUsage() = runBlocking {
        val b = backup(); backup("second")
        val seen = mutableListOf<String>(); val charged = mutableListOf<LlmReply>()
        val c = client { model ->
            seen += model
            if (model == primary.model) 503 to """{"usage":{"prompt_tokens":7,"completion_tokens":1}}"""
            else 200 to """{"choices":[{"message":{"content":"{}"}}],"usage":{"prompt_tokens":20,"completion_tokens":3}}"""
        }
        val result = chat(c::chatJson) { charged += it }; charged += result
        assertEquals(listOf(primary.model, b.model), seen)
        assertEquals(b.model, result.actualModel); assertEquals(27L, charged.sumOf { it.promptTokens })
        assertEquals(primary.model, charged.first().actualModel)
        assertTrue(failover.latestSwitch.value!!.actualModel.contains(b.model))
    }
    @Test fun quotaAndAuthSwitchButFormatAndCancellationDoNot() = runBlocking {
        backup()
        for (status in listOf(402, 401, 429)) {
            failover.clear(primary.id)
            val c = client { model -> if (model == primary.model) status to "{}" else 200 to """{"choices":[{"message":{"content":"{}"}}]}""" }
            assertEquals("backup", chat(c::chatJson).actualModel)
        }
        for (error in listOf(LlmException(LlmError.Format), CancellationException("fixture"))) {
            failover.clear(primary.id); var calls = 0
            val caught = runCatching { chat({ _, _, _, _ -> calls++; throw error }) }.exceptionOrNull()
            assertSame(error, caught); assertEquals(1, calls)
        }
    }
    @Test fun onlyThreeAttemptsAndPrimaryErrorSurvivesCooldownOrder() = runBlocking {
        backup("b"); backup("c"); backup("d")
        repeat(2) {
            val seen = mutableListOf<String>()
            val error = runCatching { chat({ p, _, _, _ ->
                seen += p.model
                throw LlmException(if (p.id == primary.id) LlmError.Auth else LlmError.Server, if (p.id == primary.id) 401 else 503)
            }) }.exceptionOrNull() as LlmException
            assertEquals(3, seen.size); assertFalse("d" in seen); assertEquals(401, error.status)
            assertTrue(error.detail.endsWith("备用模型也没成功"))
            // 下轮只让主模型继续冷却。
            providers.saved.value.filter { it.id != primary.id }.forEach { p -> failover.clear(p.id) }
            if (it == 1) assertEquals("primary", seen.last())
        }
    }
    @Test fun cooldownExpiresAndKeyEditClearsImmediately() = runBlocking {
        backup(); val seen = mutableListOf<String>()
        val call: suspend (LlmPreset, String, String, String) -> LlmReply = { p, _, _, _ ->
            seen += p.model; if (p.id == primary.id) throw LlmException(LlmError.Network) else reply
        }
        chat(call); seen.clear(); chat(call); assertEquals(listOf("backup"), seen)
        providers.setKey("changed"); seen.clear(); chat(call); assertEquals(listOf("primary", "backup"), seen)
        time += 5 * 60_000L; seen.clear(); chat(call); assertEquals(listOf("primary", "backup"), seen)
    }
    @Test fun successfulManualTestClearsCooldown() = runBlocking {
        val c = client { 200 to """{"choices":[{"message":{"content":"{}"}}]}""" }
        val p = AndroidLlmProviders(context, c, keys)
        val f = LlmFailover(context, p)
        val seen = mutableListOf<String>()
        suspend fun run() = f.chat("整理", primary, "fixture", "s", "u", { model, _, _, _ ->
            seen += model.model; if (model.id == primary.id) throw LlmException(LlmError.Auth) else reply
        })
        // p 的配置快照必须包含备用。
        val id = p.saveNow(null, null, "备用", "https://fixture.invalid", "b", "fixture", "", "")
        run(); seen.clear(); run(); assertEquals(listOf("b"), seen)
        assertTrue(p.test(primary.id, primary.baseUrl, primary.model, null).isSuccess)
        seen.clear(); run(); assertEquals(listOf("primary", "b"), seen)
        assertNotNull(id)
    }
    @Test fun inputLimitOnlyTriesLargerCapacity() = runBlocking {
        backup("same"); backup("also-small"); val big = backup("big")
        // 使用合成目录，容量固定可重复，无外部模型请求。
        providers.saveNow(big.id, "openai", "大", big.baseUrl, "gpt-4.1", null, "", "")
        LlmInputBudget.configure("""[{"id":"openai","models":["gpt-4.1"],"contextTokens":{"gpt-4.1":1000000}}]""")
        val seen = mutableListOf<String>()
        val result = chat({ p, _, _, _ -> seen += p.model; if (p.id == primary.id) throw LlmInputLimitException() else reply })
        assertEquals(listOf("primary", "gpt-4.1"), seen); assertEquals("gpt-4.1", result.actualModel)
    }
    @Test fun disabledIsPersistentAndNeverSwitches() = runBlocking {
        backup(); failover.setEnabled(false); var calls = 0
        val error = LlmException(LlmError.Server, 503)
        assertSame(error, runCatching { chat({ _, _, _, _ -> calls++; throw error }) }.exceptionOrNull())
        assertEquals(1, calls); assertFalse(LlmFailover(context, providers).enabled.value)
    }
    @Test fun moveAndSwitchSettingSurviveBackupRestore() = runBlocking {
        val b = backup("b"); val c = backup("c")
        providers.move(c.id, 0); failover.setEnabled(false)
        val expected = providers.saved.value.map { it.id }
        val dir = File(context.cacheDir, "backup103").apply { mkdirs() }
        BackupSettings.capture(context, dir) { _, _ -> "fixture" }
        providers.move(b.id, 0); failover.setEnabled(true)
        BackupSettings.apply(context, dir) { _, _, _ -> }
        val restored = AndroidLlmProviders(context, LlmClient(), keys)
        assertEquals(expected, restored.saved.value.map { it.id }); assertFalse(LlmFailover(context, restored).enabled.value)
        assertEquals(primary.id, restored.activeId.value)
    }
    private val streamOk = "data: {\"choices\":[{\"delta\":{\"content\":\"你好\"},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":4,\"completion_tokens\":2}}\n\ndata: [DONE]\n\n"
    @Test fun streamSwitchesBeforeFirstTextAndAccountsEachAttempt() = runBlocking {
        backup(); val seen = mutableListOf<String>(); val usage = mutableListOf<Long>()
        val c = client { model -> seen += model; if (model == "primary") 503 to """{"usage":{"prompt_tokens":3}}""" else 200 to streamOk }
        val session = LlmFailover.StreamSession(primary, "fixture"); var text = ""
        failover.stream("问答", session, JSONArray(), JSONArray(), c, { text += it }, { p, _ -> usage += p }, false)
        assertEquals("你好", text); assertEquals(listOf("primary", "backup"), seen); assertEquals(listOf(3L, 4L), usage)
        assertEquals("backup", session.primary.model)
    }
    @Test fun streamNeverSwitchesAfterTextIncludingLaterToolRound() = runBlocking {
        backup(); var calls = 0; val usage = mutableListOf<Long>()
        val c = client { calls++; 200 to "data: {\"choices\":[{\"delta\":{\"content\":\"半句\"}}],\"usage\":{\"prompt_tokens\":5}}\n\n" }
        val session = LlmFailover.StreamSession(primary, "fixture")
        assertTrue(runCatching { failover.stream("问答", session, JSONArray(), JSONArray(), c, {}, { p, _ -> usage += p }, false) }.isFailure)
        assertEquals(1, calls); assertEquals(listOf(5L), usage)
        val broken = client { calls++; 503 to "{}" }
        assertTrue(runCatching { failover.stream("问答", session, JSONArray(), JSONArray(), broken, {}, { _, _ -> }, false) }.isFailure)
        assertEquals(2, calls)
    }
    @Test fun digestFallbackReplayAndInflightNeverRepeatRequestOrUsage() = runBlocking {
        backup(); val client = LlmClient(); val settings = AndroidDigestSettings(context, client, providers)
        val db = Room.inMemoryDatabaseBuilder(context, CaptureDatabase::class.java).allowMainThreadQueries().build()
        val state = context.getSharedPreferences("digest-state", 0)
        try {
            db.capture().segment(SegmentEntity(1, 0, 1000, "mic", "/fixture", 0, "Done"))
            db.transcribe().insertLines(listOf(TranscriptLineEntity(1, 1, 0, 1000, "合成", null)))
            var calls = 0; var failSave = true
            fun runner() = DigestRunner(context, db.memory(), client, settings, { "s" }, { true }, now = { 3_600_000 },
                isMedia = { _, _ -> if (failSave) error("fixture") else false }, request = { p, _, _, _ ->
                    calls++; if (p.id == primary.id) throw LlmException(LlmError.Server, 503, usage = LlmReply("", 7, 1))
                    LlmReply("""{"events":[{"from":1,"to":1,"scene":"工作","title":"合成","summary":"合成"}],"items":[]}""", 20, 3)
                })
            val live = runner(); assertTrue(live.run(force = true))
            val pending = db.memory().pendingDigests().single(); assertTrue(pending.id.startsWith("batch-v2:"))
            state.edit().putString("inflight", pending.id).putString("inflight-lines", "1").commit()
            failSave = false; assertFalse(live.run(force = true)); assertFalse(runner().run(force = true))
            assertEquals(2, calls); assertTrue(db.memory().pendingDigests().isEmpty())
            db.openHelper.readableDatabase.query("SELECT model FROM memory_events").use { assertTrue(it.moveToFirst()); assertEquals("backup", it.getString(0)); assertFalse(it.moveToNext()) }
            db.openHelper.readableDatabase.query("SELECT SUM(calls), SUM(promptTokens) FROM llm_usage_v2").use { assertTrue(it.moveToFirst()); assertEquals(2, it.getInt(0)); assertEquals(27, it.getInt(1)) }
        } finally { db.close() }
    }
    @Test fun legacyPendingAndNewProcessRecoveryUsePrimaryWithoutRebilling() = runBlocking {
        val client = LlmClient(); val settings = AndroidDigestSettings(context, client, providers)
        val db = Room.inMemoryDatabaseBuilder(context, CaptureDatabase::class.java).allowMainThreadQueries().build()
        try {
            db.capture().segment(SegmentEntity(1, 0, 1000, "mic", "/fixture", 0, "Done"))
            val line = TranscriptLineEntity(1, 1, 0, 1000, "合成", null); db.transcribe().insertLines(listOf(line))
            val old = java.security.MessageDigest.getInstance("SHA-256").digest((primary.toString() + JSONArray(listOf(line.toString()))).toByteArray()).joinToString("") { "%02x".format(it) }
            db.memory().rememberDigest(PendingDigest(old, "1", """{"events":[{"from":1,"to":1,"scene":"工作","title":"合成","summary":"合成"}],"items":[]}""", "2026-11-07", 20, 3, null))
            context.getSharedPreferences("digest-state", 0).edit().putString("inflight", old).putString("inflight-lines", "1").commit()
            val runner = DigestRunner(context, db.memory(), client, settings, { "s" }, { false }, now = { 3_600_000 }, request = { _, _, _, _ -> error("must not call") })
            assertFalse(runner.run(force = true)); assertTrue(db.memory().undigestedLines().isEmpty())
            db.openHelper.readableDatabase.query("SELECT model FROM memory_events").use { assertTrue(it.moveToFirst()); assertEquals("primary", it.getString(0)) }
            db.openHelper.readableDatabase.query("SELECT SUM(calls) FROM llm_usage_v2").use { assertTrue(it.moveToFirst()); assertEquals(1, it.getInt(0)) }
        } finally { db.close() }
    }
}
