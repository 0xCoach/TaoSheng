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
    for (attempt in 1..config.webSocketAttempts) {
        diagnose("attempt=$attempt; connecting-WebSocket")
        val listener = FileSocketAttempt(attempt, input, diagnose)
        val socket = client.newWebSocket(request, listener)
        try {
            withTimeout(20_000) { listener.ready.await() }
            return socket
        } catch (error: Exception) {
            socket.cancel()
            currentCoroutineContext().ensureActive()
            val retryable = error is IOException || error is TimeoutCancellationException
            if (attempt == config.webSocketAttempts || !retryable || isDeviceTlsFailure(error) ||
                listener.rejectionCode?.let { it in 400..499 } == true) throw error
            delay(config.webSocketRetryMillis)
        }
    }
    error("no WebSocket attempt")
}

private const val MAX_MESSAGE_BYTES = 262_144
private const val CHUNK_BYTES = 32_768

private class FileSocketAttempt(
    private val attempt: Int,
    private val input: Channel<ByteArray>,
    private val diagnose: (String) -> Unit,
) : WebSocketListener() {
    val ready = CompletableDeferred<Unit>()
    private val opened = MutableStateFlow(false)
    private val status = MutableStateFlow<Int?>(null)
    val rejectionCode: Int? get() = status.value

    override fun onOpen(webSocket: WebSocket, response: Response) {
        opened.value = true
        ready.complete(Unit)
    }

    override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
        if (bytes.size > MAX_MESSAGE_BYTES) {
            reject(webSocket, "fast transfer message limit exceeded")
        } else {
            receive(webSocket, bytes.toByteArray())
        }
    }

    override fun onMessage(webSocket: WebSocket, text: String) {
        val value = text.trim()
        if (value.length !in 20..MAX_MESSAGE_BYTES * 2 || value.length % 2 != 0) return
        val digits = value.length / 2
        val bytes = ByteArray(digits)
        for (i in bytes.indices) {
            val high = hexadecimal(value[2 * i]) ?: return
            val low = hexadecimal(value[2 * i + 1]) ?: return
            bytes[i] = ((high shl 4) or low).toByte()
        }
        receive(webSocket, bytes)
    }

    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
        status.value = response?.code
        diagnose("attempt=$attempt; HTTP=${response?.code ?: 0}; ${deviceNetworkErrorTypes(t)}")
        ready.completeExceptionally(t)
        if (opened.value) input.close(DeviceException(ErrorCode.Disconnected, "device WebSocket failed", t))
    }

    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
        webSocket.close(code, reason)
    }

    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
        val closed = DeviceException(ErrorCode.Disconnected, "device WebSocket closed")
        ready.completeExceptionally(closed)
        input.close(closed)
    }

    private fun receive(socket: WebSocket, bytes: ByteArray) {
        var start = 0
        while (start < bytes.size) {
            val end = minOf(start + CHUNK_BYTES, bytes.size)
            if (input.trySend(bytes.copyOfRange(start, end)).isFailure) {
                reject(socket, "fast transfer receive buffer exceeded")
                return
            }
            start = end
        }
    }

    private fun reject(socket: WebSocket, reason: String) {
        input.close(DeviceException(ErrorCode.ProtocolError, reason))
        socket.cancel()
    }

    private fun hexadecimal(char: Char): Int? = when (char) {
        in '0'..'9' -> char - '0'
        in 'a'..'f' -> 10 + (char - 'a')
        in 'A'..'F' -> 10 + (char - 'A')
        else -> null
    }
}

internal fun isDeviceTlsFailure(error: Throwable): Boolean = deviceNetworkCauses(error).any {
    it is SSLException || it is java.security.cert.CertificateException
}

internal fun deviceNetworkErrorTypes(error: Throwable): String =
    deviceNetworkCauses(error).map { it.javaClass.simpleName }.distinct().joinToString(" > ")

private fun deviceNetworkCauses(error: Throwable) = generateSequence(error) { it.cause }.take(8)
