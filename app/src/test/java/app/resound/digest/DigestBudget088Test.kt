package app.resound.digest

import app.resound.data.TranscriptLineEntity
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class DigestBudget088Test {
    @Test fun characterBatchesKeepEveryLineAndRejectOversizedSingleLine() {
        val lines = (1L..6).map { TranscriptLineEntity(it, 1, it, it + 1, "测".repeat(DigestRunner.MAX_BATCH_CHARS / 3), null) }
        val batches = DigestRunner.batches(lines)
        assertEquals(lines, batches.flatten())
        assertTrue(batches.size > 1)
        assertTrue(batches.all { batch -> batch.sumOf { it.text.length + DigestRunner.LINE_OVERHEAD_CHARS } <= DigestRunner.MAX_BATCH_CHARS })
        assertTrue(runCatching { DigestRunner.batches(listOf(lines.first().copy(text = "测".repeat(DigestRunner.MAX_BATCH_CHARS + 1)))) }
            .exceptionOrNull() is DigestBatchLimitException)
    }
    @Test fun jsonRequestsHaveOutputLimitAndOversizedInputNeverReachesServer() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"content":"{}"}}],"usage":{"prompt_tokens":1,"completion_tokens":2}}"""))
        server.start()
        try {
            val client = LlmClient()
            val preset = LlmPreset("custom", "audit", server.url("/v1").toString(), "synthetic")
            val reply = client.chatJson(preset, "", "合成", "合成")
            assertEquals(2L, reply.completionTokens)
            val request = JSONObject(server.takeRequest().body.readUtf8())
            assertEquals(LlmClient.MAX_JSON_OUTPUT_TOKENS, request.getInt("max_tokens"))
            assertTrue(runCatching { client.chatJson(preset, "", "x", "x".repeat(LlmInputBudget.inputChars(preset))) }
                .exceptionOrNull() is LlmInputLimitException)
            assertEquals(1, server.requestCount)
            val openai = JSONObject().put("max_tokens", LlmClient.MAX_JSON_OUTPUT_TOKENS)
            applyLlmOptions(preset.copy(baseUrl = "https://api.openai.com/v1", model = "gpt-5.6"), openai)
            assertFalse(openai.has("max_tokens"))
            assertEquals(LlmClient.MAX_JSON_OUTPUT_TOKENS, openai.getInt("max_completion_tokens"))
        } finally { server.shutdown() }
    }
}
