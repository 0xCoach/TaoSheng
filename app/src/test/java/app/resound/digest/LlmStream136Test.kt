package app.resound.digest

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class LlmStream136Test {
    private fun event(delta: String, finish: String? = null) = "data: " + JSONObject().put("choices", JSONArray().put(
        JSONObject().put("delta", JSONObject(delta)).put("finish_reason", finish ?: JSONObject.NULL))) + "\n\n"

    @Test fun geminiWithoutIndexMergesByIdAndReturnsWholeExtraContentOnSecondRequest() = runBlocking {
        val server = MockWebServer(); server.start()
        val extra = """{"google":{"thought_signature":"opaque-signature","other":7},"vendor":{"value":true}}"""
        server.enqueue(MockResponse().setBody(
            event("""{"tool_calls":[{"id":"a","function":{"name":"search_lines","arguments":"{\"keywords\":"},"extra_content":$extra},{"id":"b","function":{"name":"search_events","arguments":"{}"}}]}""") +
            event("""{"tool_calls":[{"id":"a","function":{"arguments":"[]}"},"extra_content":{"google":{"more":true}}}]}""") +
            event("{}", "tool_calls") + "data: [DONE]\n\n"))
        server.enqueue(MockResponse().setBody(event("""{"content":"已找到[^1]"}""") + event("{}", "stop") + "data: [DONE]\n\n"))
        try {
            val client = LlmClient(); val preset = LlmPreset("test", "test", server.url("/").toString(), "gemini")
            val reply = client.streamChat(preset, "fake", JSONArray(), JSONArray().put(JSONObject()), {}, { _, _ -> })
            assertEquals(listOf("a", "b"), reply.calls.map { it.id })
            assertEquals("{\"keywords\":[]}", reply.calls.first().arguments)
            val messages = JSONArray().put(reply.message()).put(JSONObject().put("role", "tool").put("tool_call_id", "a").put("content", "{}"))
            client.streamChat(preset, "fake", messages, JSONArray(), {}, { _, _ -> })
            val request = JSONObject(server.takeRequest().body.readUtf8())
            assertEquals(4096, request.getInt("max_tokens"))
            val second = JSONObject(server.takeRequest().body.readUtf8())
            assertFalse(second.has("tools")); assertFalse(second.has("tool_choice"))
            val calls = second.getJSONArray("messages").getJSONObject(0).getJSONArray("tool_calls")
            val expected = JSONObject(extra).also { it.getJSONObject("google").put("more", true) }
            assertEquals(expected.toString(), calls.getJSONObject(0).getJSONObject("extra_content").toString())
            assertFalse(calls.getJSONObject(1).has("extra_content"))
        } finally { server.shutdown() }
    }

    @Test fun absentIdUsesPositionAndOpenAiIndexedReasoningShapeStaysUnchanged() {
        val parser = LlmSseParser({}, { _, _ -> })
        val frames = event("""{"tool_calls":[{"id":"a","function":{"name":"first","arguments":"{"}},{"id":"b","function":{"name":"second","arguments":"{"}}]}""") +
            event("""{"tool_calls":[{"function":{"arguments":"}"}},{"function":{"arguments":"}"}}]}""") + event("{}", "tool_calls") + "data: [DONE]\n\n"
        frames.lineSequence().forEach(parser::line)
        assertEquals(listOf("{}", "{}"), parser.finish().calls.map { it.arguments })
        val noIds = LlmSseParser({}, { _, _ -> })
        (event("""{"tool_calls":[{"function":{"name":"first","arguments":"{}"}},{"function":{"name":"second","arguments":"{}"}}]}""") +
            event("{}", "tool_calls") + "data: [DONE]\n\n").lineSequence().forEach(noIds::line)
        assertEquals(2, noIds.finish().calls.map { it.id }.filter { it.isNotBlank() }.distinct().size)
        val old = LlmSseParser({}, { _, _ -> })
        (event("""{"reasoning_content":"思考","tool_calls":[{"index":0,"id":"call_","function":{"name":"search_","arguments":"{"}}]}""") +
            event("""{"tool_calls":[{"index":0,"id":"1","function":{"name":"lines","arguments":"}"}}]}""") + event("{}", "tool_calls") + "data: [DONE]\n\n").lineSequence().forEach(old::line)
        val reply = old.finish()
        assertEquals(LlmToolCall("call_1", "search_lines", "{}"), reply.calls.single())
        assertEquals("思考", reply.message().getString("reasoning_content"))
        assertFalse(reply.calls.single().json().has("extra_content"))
    }

    @Test fun lengthIsSpecificAndNeverContainsModelText() {
        val failure = runCatching {
            val parser = LlmSseParser({}, { _, _ -> })
            (event("""{"content":"私人内容"}""") + event("{}", "length")).lineSequence().forEach(parser::line)
        }.exceptionOrNull() as LlmException
        assertTrue(failure.truncated); assertEquals(LlmException.TRUNCATED, failure.detail)
        assertFalse(failure.detail.contains("私人内容"))
    }
}
