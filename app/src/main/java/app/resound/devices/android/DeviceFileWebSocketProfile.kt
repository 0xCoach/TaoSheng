package app.resound.devices.android

import app.resound.devices.driver.LocalFileNetworkConfig
import okhttp3.Interceptor
import okhttp3.*
import okio.ByteString
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.IOException
import javax.net.ssl.SSLException
import app.resound.devices.driver.DeviceException
import app.resound.devices.driver.ErrorCode

/** Driver-supplied compatibility options, scoped only to the device transfer client. */
internal fun deviceFileWebSocketProfile(config: LocalFileNetworkConfig) = Interceptor { chain ->
    val request = chain.request().newBuilder()
    config.webSocketUserAgent?.let { request.header("User-Agent",it) }
    // OkHttp 4 injects this in RealWebSocket before application interceptors run.
    if (!config.requestCompression) request.removeHeader("Sec-WebSocket-Extensions")
    chain.proceed(request.build())
}

/** Bounded transport retries never retry a rejected certificate or a deterministic HTTP 4xx. */
internal suspend fun connectDeviceFileWebSocket(client: OkHttpClient, request: Request, input: Channel<ByteArray>,
    config: LocalFileNetworkConfig, diagnose: (String) -> Unit): WebSocket {
    fun types(error: Throwable) = generateSequence(error) { it.cause }.take(8).map { it.javaClass.simpleName }.distinct().joinToString(" > ")
    for (attempt in 1..config.webSocketAttempts) {
        val ready=CompletableDeferred<Unit>();val opened=MutableStateFlow(false);val status=MutableStateFlow<Int?>(null)
        fun accept(socket: WebSocket, bytes: ByteArray) {
            if(bytes.size>262144) {
                input.close(DeviceException(ErrorCode.ProtocolError,"fast transfer message limit exceeded"));socket.cancel();return
            }
            for(offset in bytes.indices step 32768) if(input.trySend(bytes.copyOfRange(offset,minOf(offset+32768,bytes.size))).isFailure) {
                input.close(DeviceException(ErrorCode.ProtocolError,"fast transfer receive buffer exceeded"));socket.cancel();return
            }
        }
        diagnose("attempt=$attempt; connecting-WebSocket")
        val socket=client.newWebSocket(request,object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket,response: Response) { opened.value=true;ready.complete(Unit) }
            override fun onMessage(webSocket: WebSocket,bytes: ByteString) { accept(webSocket,bytes.toByteArray()) }
            override fun onMessage(webSocket: WebSocket,text: String) {
                val hex=text.trim()
                if(hex.length<=4 && hex.uppercase() in setOf("ACK","OK","PONG"))return
                if(hex.length in 20..524288 && hex.length%2==0 && hex.all { it in "0123456789abcdefABCDEF" })
                    accept(webSocket,hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray())
            }
            override fun onFailure(webSocket: WebSocket,t: Throwable,response: Response?) {
                status.value=response?.code;diagnose("attempt=$attempt; HTTP=${response?.code ?: 0}; ${types(t)}")
                ready.completeExceptionally(t)
                if(opened.value)input.close(DeviceException(ErrorCode.Disconnected,"device WebSocket failed",t))
            }
            override fun onClosing(webSocket: WebSocket,code: Int,reason: String) { webSocket.close(code,reason) }
            override fun onClosed(webSocket: WebSocket,code: Int,reason: String) {
                val closed=DeviceException(ErrorCode.Disconnected,"device WebSocket closed")
                ready.completeExceptionally(closed);input.close(closed)
            }
        })
        try { withTimeout(20_000) { ready.await() };return socket }
        catch(e: Exception) {
            socket.cancel();currentCoroutineContext().ensureActive()
            if(e is CancellationException && e !is TimeoutCancellationException)throw e
            val tls=generateSequence<Throwable>(e) { it.cause }.take(8).any { it is SSLException || it is java.security.cert.CertificateException }
            val retryable=e is IOException || e is TimeoutCancellationException
            if(attempt==config.webSocketAttempts || !retryable || tls || status.value?.let { it in 400..499 } == true)throw e
            delay(config.webSocketRetryMillis)
        }
    }
    error("no WebSocket attempt")
}
