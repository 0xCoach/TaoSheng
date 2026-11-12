package app.resound.net

import android.app.Application
import app.resound.digest.LlmClient
import app.resound.digest.LlmPreset
import kotlinx.coroutines.*
import io.ktor.client.plugins.websocket.*
import io.ktor.client.request.*
import io.ktor.websocket.*
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], application = Application::class)
class ConnectionReuse164Test {
    @Test fun consecutiveRequestsReuseConnectionAcrossClientsAndHeaders() = runBlocking {
        MockWebServer().use { server ->
            repeat(2) { server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"content":"ok"}}]}""")) }
            val preset = LlmPreset("test", "test", server.url("/v1").toString(), "test")
            LlmClient().chatJson(preset, "first", "s", "u")
            LlmClient().chatJson(preset.copy(baseUrl = server.url("/other/v1").toString()), "second", "s", "u")
            val first = server.takeRequest(); val second = server.takeRequest()
            assertEquals(0, first.sequenceNumber); assertEquals(1, second.sequenceNumber)
            assertEquals("Bearer first", first.getHeader("Authorization"))
            assertEquals("Bearer second", second.getHeader("Authorization"))
        }
    }
    @Test fun cancellingInFlightRequestLeavesOtherAndNextRequestsUsable() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            repeat(2) { server.enqueue(MockResponse().setBody("ok")) }
            val network = androidNetwork(defaultAndroidHttp)
            val pending = launch(Dispatchers.IO) { network.consume(server.url("/cancel").toString(), emptyList()) { it.limitedText(100, "large") } }
            assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
            assertEquals("ok", network.consume(server.url("/parallel").toString(), emptyList()) { it.limitedText(100, "large") })
            pending.cancelAndJoin()
            assertEquals("ok", network.consume(server.url("/next").toString(), emptyList()) { it.limitedText(100, "large") })
        }
    }
    @Test fun sameConfigurationReusesAndChangedTimeoutOrRedirectCreatesClient() = runBlocking {
        val base = defaultAndroidHttp.newBuilder().readTimeout(19, TimeUnit.SECONDS).build()
        val original = androidNetwork(base)
        assertSame(original, androidNetwork(base.newBuilder().build()))
        assertNotSame(original, androidNetwork(base.newBuilder().readTimeout(20, TimeUnit.SECONDS).build()))
        assertNotSame(original, androidNetwork(base.newBuilder().followRedirects(!base.followRedirects).build()))
        assertSame(original, androidNetwork(base))
        // 同一共享客户端上的两个 WebSocket；本地路由头不出网，自定义连接 ID 保持原值。
        MockWebServer().use { server ->
            repeat(2) { server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) { webSocket.send(text) }
            })) }
            withAndroidSocket(base, "first-local") { firstClient, firstControl, configureFirst ->
                val first = firstClient.webSocketSession {
                    url(server.url("/first").toString().replace("http://", "ws://")); header("X-Api-Connect-Id", "custom"); configureFirst()
                }
                withAndroidSocket(base, "second-local") { secondClient, _, configureSecond ->
                    assertSame(firstClient, secondClient)
                    val second = secondClient.webSocketSession {
                        url(server.url("/second").toString().replace("http://", "ws://")); header("X-Api-Connect-Id", "custom"); configureSecond()
                    }
                    firstControl.cancel(); first.cancel()
                    second.send(Frame.Text("still alive"))
                    assertEquals("still alive", withTimeout(5000) { (second.incoming.receive() as Frame.Text).readText() })
                    second.cancel()
                }
            }
            repeat(2) {
                val request = server.takeRequest()
                assertNull(request.getHeader("X-Resound-Local-Socket-Route"))
                assertEquals("custom", request.getHeader("X-Api-Connect-Id"))
            }
        }
    }
}
