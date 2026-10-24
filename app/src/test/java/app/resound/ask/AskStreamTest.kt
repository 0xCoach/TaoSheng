package app.resound.ask

import app.resound.digest.*
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

class AskStreamTest {
    private fun frame(delta: JSONObject = JSONObject(), finish: String? = null): String = "data: " + JSONObject()
        .put("choices", JSONArray().put(JSONObject().put("index", 0).put("delta", delta).put("finish_reason", finish ?: JSONObject.NULL))) + "\r\n\r\n"
    private fun callPart(id: String?, name: String?, args: String): JSONObject = JSONObject().put("tool_calls", JSONArray().put(
        JSONObject().put("index", 0).apply { if (id != null) put("id", id) }.put("function", JSONObject().put("arguments", args).apply { if (name != null) put("name", name) })))
    private fun preset(server: MockWebServer) = LlmPreset("fake", "fake", server.url("/").toString(), "fake")

    @Test fun fragmentedUtf8ToolArgumentsUsageAndDone() = runBlocking {
        val server = MockWebServer()
        val body = ": keepalive\r\n\r\n" + frame(JSONObject().put("content", "原话")) +
            frame(callPart("call_", "search_", "{\"keywords\":")) + frame(callPart("1", "lines", "[\"老王\"]}")) +
            frame(finish = "tool_calls") + "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":8,\"completion_tokens\":3}}\r\n\r\ndata: [DONE]\r\n\r\n"
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(body).throttleBody(7, 1, TimeUnit.MILLISECONDS))
        server.start()
        try {
            var text = ""; var usage = 0L
            val reply = LlmClient().streamChat(preset(server), "test-only", JSONArray(), JSONArray().put(JSONObject()), { text += it }, { p, c -> usage = p+c })
            assertEquals("原话", text); assertEquals(11L, usage)
            assertEquals(LlmToolCall("call_1", "search_lines", "{\"keywords\":[\"老王\"]}"), reply.calls.single())
            val request = JSONObject(server.takeRequest().body.readUtf8())
            assertTrue(request.getBoolean("stream")); assertTrue(request.getJSONObject("stream_options").getBoolean("include_usage"))
        } finally { server.shutdown() }
    }

    @Test fun truncatedStreamAndAuthenticationNeverExposeServerText() = runBlocking {
        val server = MockWebServer(); server.start()
        try {
            server.enqueue(MockResponse().setResponseCode(401).setBody("secret raw server details"))
            server.enqueue(MockResponse().setBody(frame(JSONObject().put("content", "半句"))))
            for (expected in listOf(LlmError.Auth, LlmError.Network)) {
                try { LlmClient().streamChat(preset(server), "fake", JSONArray(), JSONArray(), {}, { _, _ -> }); fail("应失败") }
                catch (e: LlmException) { assertEquals(expected, e.type); assertFalse(e.message!!.contains("secret")) }
            }
        } finally { server.shutdown() }
    }

    @Test fun cancellingAfterHeadersClosesBodyPromptly() = runBlocking {
        val server = MockWebServer(); server.start()
        val client = OkHttpClient.Builder().readTimeout(30, TimeUnit.SECONDS).build()
        server.enqueue(MockResponse().setBody(frame(JSONObject().put("content", "正在输出")) + ": keepalive\n\n".repeat(2000) + frame(finish="stop") + "data: [DONE]\n\n")
            .throttleBody(1024, 100, TimeUnit.MILLISECONDS))
        try {
            val firstText = CompletableDeferred<Unit>()
            val job = launch { LlmClient(client).streamChat(preset(server), "fake", JSONArray(), JSONArray(), { firstText.complete(Unit) }, { _, _ -> }) }
            withTimeout(3000) { firstText.await() }
            withTimeout(2000) { job.cancelAndJoin() }
            withTimeout(3000) { while (client.dispatcher.runningCallsCount() != 0) delay(10) }
        } finally { server.shutdown() }
    }

    @Test fun chatJsonStillUsesJsonObject() = runBlocking {
        val server = MockWebServer(); server.start()
        server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"content":"{\"ok\":true}"}}],"usage":{"prompt_tokens":2,"completion_tokens":1}}"""))
        try {
            val result = LlmClient().chatJson(preset(server),"fake","系统","问题")
            assertEquals(2L,result.promptTokens)
            assertEquals("json_object",JSONObject(server.takeRequest().body.readUtf8()).getJSONObject("response_format").getString("type"))
        } finally { server.shutdown() }
    }
}
