@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package app.resound.net

import app.resound.digest.LlmPreset
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.ktor.utils.io.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

/** 卡 226：超时时区分"一个字都没输出"（silent）和"在输出但太慢"。 */
class SilentTimeout226Test {
    private fun TestScope.mock(handler: MockRequestHandler) = MockEngine(MockEngineConfig().apply {
        dispatcher = StandardTestDispatcher(testScheduler)
        addHandler(handler)
    })
    private val preset = LlmPreset("synthetic", "合成", "https://synthetic.invalid/v1", "model")
    private fun core(client: HttpClient) = NetLlmClient(client, listOf(preset.baseUrl), 200_000, null) { "网络失败" }
    private suspend fun chat(core: NetLlmClient) = core.chat(preset, "", emptyList(), "合成提示", "合成原话", false, null)
    private fun event(delta: String) = "data: {\"choices\":[{\"index\":0,\"delta\":$delta,\"finish_reason\":null}]}\n\n"
    private val sseHeaders = headersOf(HttpHeaders.ContentType, "text/event-stream; charset=utf-8")
    private suspend fun TestScope.timeout(client: HttpClient): NetLlmException =
        try { chat(core(client)); fail(); throw AssertionError() } catch (e: NetLlmException) { e }

    @Test fun noBytesAfterHeadersIsSilent() = runTest {
        val client = HttpClient(mock { respond(ByteChannel(autoFlush = true), headers = sseHeaders) })
        try {
            val e = timeout(client)
            assertEquals(NetLlmError.TooSlow, e.type); assertTrue(e.silent); assertEquals(90_000L, currentTime)
        } finally { client.close() }
    }

    @Test fun heartbeatsWithoutOutputUntilTotalDeadlineAreSilent() = runTest {
        val channel = ByteChannel(autoFlush = true)
        val client = HttpClient(mock { respond(channel, headers = sseHeaders) })
        val pings = launch { while (true) { channel.writeStringUtf8(": ping\n\n"); delay(60_000) } }
        try {
            val e = timeout(client)
            assertEquals(NetLlmError.TooSlow, e.type); assertTrue(e.silent); assertEquals(600_000L, currentTime)
        } finally { pings.cancel(); client.close() }
    }

    @Test fun reasoningThenStallIsNotSilent() = runTest {
        val channel = ByteChannel(autoFlush = true)
        val client = HttpClient(mock { respond(channel, headers = sseHeaders) })
        val writer = launch { channel.writeStringUtf8(event("""{"reasoning_content":"想"}""")) }
        try {
            val e = timeout(client)
            assertEquals(NetLlmError.TooSlow, e.type); assertFalse(e.silent)
        } finally { writer.cancel(); client.close() }
    }

    @Test fun contentThenStallIsNotSilent() = runTest {
        val channel = ByteChannel(autoFlush = true)
        val client = HttpClient(mock { respond(channel, headers = sseHeaders) })
        val writer = launch { channel.writeStringUtf8(event("""{"content":"{\"a\""}""")) }
        try {
            val e = timeout(client)
            assertEquals(NetLlmError.TooSlow, e.type); assertFalse(e.silent)
        } finally { writer.cancel(); client.close() }
    }
}
