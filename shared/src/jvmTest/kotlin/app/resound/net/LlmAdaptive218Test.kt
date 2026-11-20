@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package app.resound.net

import app.resound.digest.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.ktor.utils.io.*
import kotlinx.coroutines.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

class LlmAdaptive218Test {
    private fun TestScope.mock(handler: MockRequestHandler) = MockEngine(MockEngineConfig().apply {
        dispatcher = StandardTestDispatcher(testScheduler)
        addHandler(handler)
    })
    private val preset = LlmPreset("synthetic", "合成", "https://synthetic.invalid/v1", "model")
    private fun core(client: HttpClient, support: JsonStreamSupport? = null) = NetLlmClient(client, listOf(preset.baseUrl), 200_000, support) { "网络失败" }
    private suspend fun chat(core: NetLlmClient) = core.chat(preset, "", emptyList(), "合成提示", "合成原话", false, null)
    private fun event(delta: String, finish: String = "null") = "data: {\"choices\":[{\"index\":0,\"delta\":$delta,\"finish_reason\":$finish}]}\n\n"
    private val end = event("{}", "\"stop\"") + "data: [DONE]\n\n"
    private val sseHeaders = headersOf(HttpHeaders.ContentType, "text/event-stream; charset=utf-8")

    @Test fun accumulatesContentReasoningUsageAndKeepsJsonOutputBudget() = runTest {
        val reports = mutableListOf<Pair<Int, Int>>()
        val client = HttpClient(mock { request ->
            val body = Obj(request.body.toByteArray().decodeToString())
            assertTrue(body.optBoolean("stream")); assertEquals(8192, body.getInt("max_tokens"))
            respond(event("""{"reasoning_content":"思考"}""") + event("""{"reasoning":"继续","content":"{"}""") +
                event("""{"content":"}"}""") + event("{}", "\"stop\"") +
                "data: {\"usage\":{\"prompt_tokens\":12,\"completion_tokens\":7},\"choices\":[]}\n\ndata: [DONE]\n\n", headers = sseHeaders)
        })
        try {
            val reply = withContext(LlmReceiveProgress { r, c -> reports += r to c }) { chat(core(client)) }
            assertEquals(NetReply("{}", 12, 7), reply); assertEquals(4 to 2, reports.last())
        } finally { client.close() }
    }

    @Test fun reasoningHeartbeatAndPartialLineBytesResetIdleButTotalDeadlineStillApplies() = runTest {
        val channel = ByteChannel(autoFlush = true)
        val client = HttpClient(mock { respond(channel, headers = sseHeaders) })
        val writer = launch {
            channel.writeStringUtf8(event("""{"reasoning_content":"想"}"""))
            repeat(3) { delay(60_000); channel.writeStringUtf8(": ping\n\n") }
            // 未完成 SSE 行也必须刷新空闲计时。
            channel.writeStringUtf8("data: "); delay(60_000)
            channel.writeStringUtf8("{\"choices\":[{\"delta\":{\"content\":\"{}\"}}]}\n\n$end")
            channel.close()
        }
        try { assertEquals("{}", chat(core(client)).content); assertEquals(240_000L, currentTime) }
        finally { writer.cancel(); client.close() }
        val endless = ByteChannel(autoFlush = true)
        val second = HttpClient(mock { respond(endless, headers = sseHeaders) })
        val heartbeat = launch { while (true) { endless.writeStringUtf8(": ping\n\n"); delay(60_000) } }
        try {
            val start = currentTime
            try { chat(core(second)); fail() } catch (e: NetLlmException) { assertEquals(NetLlmError.TooSlow, e.type) }
            assertEquals(600_000L, currentTime - start)
        } finally { heartbeat.cancel(); second.close() }
    }

    @Test fun ninetySecondsWithoutBytesIsTooSlowAndCancellationIsNotAnError() = runTest {
        val client = HttpClient(mock { respond(ByteChannel(autoFlush = true), headers = sseHeaders) })
        try {
            try { chat(core(client)); fail() } catch (e: NetLlmException) { assertEquals(NetLlmError.TooSlow, e.type) }
            assertEquals(90_000L, currentTime)
            val pending = async { chat(core(client)) }
            runCurrent(); pending.cancel()
            try { pending.await(); fail() } catch (_: CancellationException) { }
        } finally { client.close() }
    }

    @Test fun acceptsFullJsonWithoutSseAndMissingUsage() = runTest {
        val client = HttpClient(mock { respond("""{"choices":[{"message":{"content":"{}"},"finish_reason":"stop"}]}""", headers = headersOf(HttpHeaders.ContentType, "application/json")) })
        try { assertEquals(NetReply("{}", 0, 0), chat(core(client))) } finally { client.close() }
    }

    @Test fun streamRejectionRetriesOnceAndRemembersModel() = runTest {
        val states = mutableSetOf<String>()
        val support = object : JsonStreamSupport {
            override fun nonStreaming(preset: LlmPreset) = llmModelKey(preset) in states
            override fun rememberNonStreaming(preset: LlmPreset) { states += llmModelKey(preset) }
        }
        val sent = mutableListOf<Boolean>()
        val client = HttpClient(mock { req ->
            val streaming = Obj(req.body.toByteArray().decodeToString()).optBoolean("stream")
            sent += streaming
            if (streaming) respond("stream not supported", HttpStatusCode.BadRequest)
            else { delay(100_000); respond("""{"choices":[{"message":{"content":"{}"}}]}""") }
        })
        try { chat(core(client, support)); chat(core(client, support)); assertEquals(listOf(true, false, false), sent) }
        finally { client.close() }
    }

    @Test fun lengthIsTruncatedWithUsageAndLastFinishReason() = runTest {
        var reason: String? = null
        val trace = object : NetworkTrace {
            override fun http(status: Int) {}
            override fun content(text: String) {}
            override fun finishReason(value: String?) { reason = value }
        }
        val client = HttpClient(mock { respond(event("""{"content":"{"}""") + event("{}", "\"length\"") +
            "data: {\"choices\":[],\"usage\":{\"completion_tokens\":9}}\n\ndata: [DONE]\n\n", headers = sseHeaders) })
        try {
            try { core(client).chat(preset, "", emptyList(), "", "", false, trace); fail() }
            catch (e: NetLlmException) { assertEquals(NetLlmError.Truncated, e.type); assertEquals(9L, e.usage!!.completionTokens) }
            assertEquals("length", reason)
        } finally { client.close() }
    }

    @Test fun completedRequestsDoNotRaceIdleWatchdogCleanup() = runBlocking {
        val client = HttpClient(MockEngine { respond("""{"choices":[{"message":{"content":"{}"}}]}""") })
        try {
            withTimeout(10_000) {
                withContext(Dispatchers.Default) {
                    List(32) { async {
                        repeat(16) {
                            try { assertEquals("{}", chat(core(client)).content) }
                            catch (e: NetLlmException) { throw AssertionError(app.resound.diagnostics.FailureNotes.dump(), e) }
                        }
                    } }.awaitAll()
                }
            }
        } finally { client.close() }
        Unit
    }

    private class Store : LlmAdaptiveStore {
        val values = mutableMapOf<String, String>()
        override fun read(key: String) = values[key]
        override fun write(key: String, value: String) { values[key] = value }
    }
    @Test fun budgetLearnsThinkingShrinksRecoversAndSeparatesModels() = runTest {
        val store = Store(); val budget = LlmBatchBudget(store)
        assertEquals(40_000, budget.current(preset))
        budget.observedThinking(preset, false); assertEquals(20_000, budget.current(preset))
        budget.failed(preset, true); assertEquals(10_000, budget.current(preset))
        repeat(8) { budget.failed(preset, true) }; assertEquals(4_000, budget.current(preset))
        repeat(4) { budget.succeeded(preset) }; assertEquals(4_000, budget.current(preset))
        budget.failed(preset, false); repeat(4) { budget.succeeded(preset) }; assertEquals(4_000, budget.current(preset))
        budget.succeeded(preset); assertEquals(5_000, budget.current(preset))
        repeat(100) { budget.succeeded(preset) }; assertEquals(40_000, budget.current(preset))
        assertEquals(40_000, budget.current(preset.copy(model = "new")))
        assertEquals(40_000, budget.current(preset.copy(id = "new")))
        val thinking = preset.copy(model = "explicit")
        budget.observedThinking(thinking, true); assertEquals(40_000, budget.current(thinking, userThinking = true))
        assertEquals(20_000, budget.current(thinking, userThinking = false))
        assertEquals(budget.current(preset), LlmBatchBudget(store).current(preset))
        val streak = LlmSlowStreak(Store())
        assertNull(streak.result(preset, true)); assertEquals(SLOW_MODEL_TEXT, streak.result(preset, true))
        streak.result(preset, false); assertNull(streak.result(preset, true))
    }

    @Test fun onlyConfirmedHostGetsLowEffortAndExplicitThinkingKeepsExistingOptions() {
        val p = preset.copy(baseUrl = "https://open.bigmodel.cn/api/coding/paas/v4", model = "glm-5.3-flash")
        val low = Obj(); applyNetOptions(p, low, false); assertEquals("low", low.getString("reasoning_effort"))
        assertTrue(low.getInt("max_tokens") >= 16384)
        val full = Obj(); applyNetOptions(p, full, true); assertFalse(full.has("reasoning_effort"))
        val other = Obj(); applyNetOptions(p.copy(baseUrl = preset.baseUrl), other, false); assertFalse(other.has("reasoning_effort"))
        val ask = Obj().put("stream", true); applyNetOptions(p, ask, adaptiveJson = false); assertFalse(ask.has("reasoning_effort"))
    }

    @Test fun speedCheckAndProgressTextUseOnlySyntheticDataAndMeasuredCounts() = runTest {
        assertTrue(LlmSpeedCheck.SAMPLE.length in 2800..4200)
        var clock = 0L
        val result = LlmSpeedCheck.run({ clock }, { _, _ -> clock = 18_000; LlmSpeedCheck.Reply("""{"events":[],"items":[]}""", 5) }, { "失败" })
        assertTrue(result.ok); assertEquals("约 18 秒一批（约 3000 字），会思考", result.detail)
        val failure = LlmSpeedCheck.run({ clock }, { _, _ -> LlmSpeedCheck.Reply("{}", 0) }, { "失败" })
        assertFalse(failure.ok); assertTrue(failure.detail.contains("不可解析"))
        assertEquals("模型思考中 · 已收到 3,200 字", formatLlmReceiveProgress(3200, 0))
        assertEquals("正在写结果 · 已收到 1,800 字", formatLlmReceiveProgress(3200, 1800))
    }
}
