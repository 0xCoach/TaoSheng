package app.resound.net

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.*
import okio.ByteString

/** Ktor 3.3 的引擎忽略非 401 握手响应，并把取消变成慢关闭；在平台边界补齐。 */
class AndroidSocketControl(private val client: OkHttpClient) : WebSocket.Factory, NetworkSocketControl {
    private val current = MutableStateFlow<WebSocket?>(null)
    override fun newWebSocket(request: Request, listener: WebSocketListener): WebSocket {
        val socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) = listener.onOpen(webSocket,response)
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) = listener.onMessage(webSocket,bytes)
            override fun onMessage(webSocket: WebSocket, text: String) = listener.onMessage(webSocket,text)
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) = listener.onClosing(webSocket,code,reason)
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = listener.onClosed(webSocket,code,reason)
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val code = response?.header("X-Api-Status-Code")?.toLongOrNull()
                val error = NetworkHandshakeFailure(response?.code,code,response?.header("X-Tt-Logid"),doubaoRateLimited(code,response?.code,response?.header("X-Api-Message")),t is java.net.SocketTimeoutException)
                listener.onFailure(webSocket,error,response)
                response?.close()
            }
        })
        current.value = socket
        return object : WebSocket by socket {
            override fun close(code: Int, reason: String?): Boolean { socket.cancel(); return true }
        }
    }
    override suspend fun drain(maxQueuedBytes: Long, stop: () -> Boolean) {
        while ((current.value?.queueSize() ?: 0) > maxQueuedBytes && !stop()) delay(10)
    }
    override fun cancel() { current.value?.cancel(); current.value = null }
}
