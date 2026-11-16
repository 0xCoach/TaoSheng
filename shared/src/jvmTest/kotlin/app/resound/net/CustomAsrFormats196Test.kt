package app.resound.net

import app.resound.transcribe.*
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class CustomAsrFormats196Test {
    private val cache = mutableMapOf<String, Long>()
    private var now = 100L
    private val formats = CustomAsrFormats({ "hash:$it" }, cache::get, { k, v -> cache[k] = v }, { now })
    private val url = "https://synthetic.invalid/asr"
    private suspend fun recognize(engine: MockEngine, target: String = url): AsrResult {
        val client = networkClient(engine, NetworkTimeouts(1000, 1000, 1000))
        return try {
            customRecognize(client, listOf(target), "", emptyList(), AudioUpload("a.wav", "wav", 2) { it(byteArrayOf(1,2)) },
                1000, "model", "热词", "boundary", {}, formats = formats)
        } finally { client.close() }
    }
    @Test fun unsupportedFormatRetriesSameAudioAndRemembers(): Unit = runBlocking {
        val bodies = mutableListOf<String>()
        var reads = 0
        val engine = MockEngine { request ->
            bodies += request.body.toByteArray().decodeToString()
            if (++reads == 1) respond("unsupported", HttpStatusCode.BadRequest) else respond("""{"text":"成功"}""")
        }
        assertEquals("成功", recognize(engine).sentences.single().text)
        assertEquals(2, bodies.size)
        assertTrue(bodies[0].contains("verbose_json")); assertTrue(bodies[1].contains("\r\njson\r\n"))
        assertTrue(bodies.all { it.contains("热词") && it.contains("\u0001\u0002") })
        assertEquals("json", formats.format(url))
        recognize(MockEngine { request ->
            assertTrue(request.body.toByteArray().decodeToString().contains("\r\njson\r\n"))
            respond("""{"text":"下一段"}""")
        })
    }
    @Test fun unrelatedFailuresNeverRetryOrCache() = runBlocking {
        for (status in listOf(401,403,408,429,500,503)) {
            var calls = 0
            val error = runCatching { recognize(MockEngine { calls++; respond("response_format", HttpStatusCode.fromValue(status)) }) }.exceptionOrNull()
            assertTrue(error is AsrException); assertEquals(1, calls); assertTrue(cache.isEmpty())
        }
        var calls = 0
        val error = runCatching { recognize(MockEngine { calls++; throw java.io.IOException("offline") }) }.exceptionOrNull()
        assertTrue(error is java.io.IOException); assertEquals(1, calls); assertTrue(cache.isEmpty())
        calls = 0
        val timeout = runCatching { recognize(MockEngine { calls++; throw java.net.SocketTimeoutException("timeout") }) }.exceptionOrNull()
        assertTrue(timeout is java.net.SocketTimeoutException); assertEquals(1, calls); assertTrue(cache.isEmpty())
        assertFalse(CustomAsrFormats.shouldFallback(500, "response_format"))
        assertTrue(CustomAsrFormats.shouldFallback(409, "unknown response_format"))
        assertTrue(CustomAsrFormats.shouldFallback(415, "")); assertTrue(CustomAsrFormats.shouldFallback(422, ""))
    }
    @Test fun newAddressAndThirtyDaysReprobe() {
        formats.remember(url)
        assertEquals("verbose_json", formats.format("$url/new"))
        now += CustomAsrFormats.REPROBE_MILLIS - 1
        assertEquals("json", formats.format(url))
        now++
        assertEquals("verbose_json", formats.format(url))
        val reloaded = CustomAsrFormats({ "hash:$it" }, cache::get, { k,v -> cache[k]=v }, { now })
        assertEquals("verbose_json", reloaded.format(url))
    }
    @Test fun failedJsonFallbackDoesNotRemember() = runBlocking {
        var calls = 0
        val error = runCatching { recognize(MockEngine {
            calls++
            respond("bad", if (calls == 1) HttpStatusCode.BadRequest else HttpStatusCode.InternalServerError)
        }) }.exceptionOrNull()
        assertTrue(error is AsrException); assertEquals(2, calls); assertTrue(cache.isEmpty())
    }
}
