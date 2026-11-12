package app.resound.net

import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class KtorMock151Test {
    @Test fun injectedMockEngineUsesSharedRequestsAndCloses() = runBlocking {
        val engine = MockEngine { request ->
            assertEquals("/v1/models",request.url.encodedPath)
            assertNull(request.headers[HttpHeaders.AcceptCharset])
            respond("""{"data":[{"id":"test"}]}""",HttpStatusCode.OK)
        }
        val client = networkClient(engine,NetworkTimeouts(1000,1000,1000))
        try { assertEquals(listOf("test"),queryModels(client,listOf("https://synthetic.invalid/v1"),"",emptyList()).ids) }
        finally { client.close() }
        withTimeout(1000) { client.coroutineContext[Job]!!.join() }
        assertFalse(engine.coroutineContext[Job]!!.isActive)
    }
}
