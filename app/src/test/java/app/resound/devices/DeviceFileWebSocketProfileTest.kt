package app.resound.devices

import app.resound.devices.android.deviceFileWebSocketProfile
import app.resound.devices.android.connectDeviceFileWebSocket
import app.resound.devices.driver.LocalFileNetworkConfig
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import okhttp3.*
import okhttp3.mockwebserver.*
import org.junit.Test
import org.junit.Assert.*
import java.util.concurrent.TimeUnit

class DeviceFileWebSocketProfileTest {
    @Test fun transientHandshakeDisconnectRetriesWithoutClosingTheDataChannel() = runBlocking {
        val server=MockWebServer()
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onClosing(webSocket: WebSocket,code: Int,reason: String) { webSocket.close(code,reason) }
        }))
        server.start()
        val config=LocalFileNetworkConfig("test-device","test-password","192.168.4.1",443,32003,byteArrayOf(),
            webSocketUserAgent="okhttp/3.12.13.18",requestCompression=false,webSocketAttempts=2,webSocketRetryMillis=0)
        val client=OkHttpClient.Builder().retryOnConnectionFailure(false).addInterceptor(deviceFileWebSocketProfile(config)).build()
        val input=Channel<ByteArray>(64);val events=mutableListOf<String>();var socket: WebSocket?=null
        try {
            socket=connectDeviceFileWebSocket(client,Request.Builder().url(server.url("/")).build(),input,config,events::add)
            assertEquals(2,server.requestCount)
            assertTrue(events.any { it.startsWith("attempt=2") })
            assertTrue(input.trySend(byteArrayOf(1)).isSuccess)
            assertArrayEquals(byteArrayOf(1),input.receive())
        } finally {
            input.cancel();socket?.close(1000,"done");socket?.cancel();server.close()
            client.connectionPool.evictAll();client.dispatcher.executorService.shutdown()
        }
    }
    @Test fun deviceProfileMatchesSdkUpgradeWithoutCompressionNegotiation() = runBlocking {
        val server=MockWebServer()
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onClosing(webSocket: WebSocket,code: Int,reason: String) { webSocket.close(code,reason) }
        }))
        server.start()
        val config=LocalFileNetworkConfig("test-device","test-password","192.168.4.1",443,32003,byteArrayOf(),
            webSocketUserAgent="okhttp/3.12.13.18",requestCompression=false)
        val client=OkHttpClient.Builder().addInterceptor(deviceFileWebSocketProfile(config)).build()
        val ready=CompletableDeferred<WebSocket>()
        var socket: WebSocket?=null
        try {
            socket=client.newWebSocket(Request.Builder().url(server.url("/")).build(),object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket,response: Response) { ready.complete(webSocket) }
                override fun onFailure(webSocket: WebSocket,t: Throwable,response: Response?) { ready.completeExceptionally(t) }
            })
            withTimeout(5000) { ready.await() }
            val request=requireNotNull(server.takeRequest(5,TimeUnit.SECONDS))
            assertEquals("okhttp/3.12.13.18",request.getHeader("User-Agent"))
            assertEquals("websocket",request.getHeader("Upgrade"))
            assertNull(request.getHeader("Sec-WebSocket-Extensions"))
        } finally {
            socket?.close(1000,"done");socket?.cancel();server.close()
            client.connectionPool.evictAll();client.dispatcher.executorService.shutdown()
        }
    }
}
