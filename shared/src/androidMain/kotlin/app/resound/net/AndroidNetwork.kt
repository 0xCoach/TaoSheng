package app.resound.net

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.HttpRequestBuilder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.WebSocket

/** 默认传输模板；派生配置共享 TLS 等对象，地址与请求头始终留在单次请求。 */
val defaultAndroidHttp: OkHttpClient by lazy { OkHttpClient() }

/** 平台传输配置也参与键，避免代理、证书、拦截器或 cookie 配置串用。 */
private fun OkHttpClient.transportKey(): List<Any?> = listOf(
    connectTimeoutMillis, readTimeoutMillis, writeTimeoutMillis, callTimeoutMillis,
    followRedirects, followSslRedirects, retryOnConnectionFailure, pingIntervalMillis,
    proxy, proxySelector, proxyAuthenticator, authenticator, dns, socketFactory,
    if (connectionSpecs.any { it.isTls }) sslSocketFactory else null,
    hostnameVerifier, certificatePinner, connectionSpecs, protocols, interceptors,
    networkInterceptors, cookieJar, cache, eventListenerFactory, connectionPool, dispatcher,
)

private const val SOCKET_ROUTE = "X-Resound-Local-Socket-Route"
private data class SocketBinding(val control: AndroidSocketControl, val originalHeaders: List<String>)

private class AndroidNetworkGroup(val transport: OkHttpClient) {
    val sockets = MutableStateFlow<Map<String, SocketBinding>>(emptyMap())
    val client = networkClient(OkHttp.create {
        preconfigured = transport
        webSocketFactory = WebSocket.Factory { request, listener ->
            val binding = sockets.value[request.header(SOCKET_ROUTE)]
                ?: error("WebSocket request is not registered")
            val original = request.newBuilder().removeHeader(SOCKET_ROUTE).apply {
                binding.originalHeaders.forEach { addHeader(SOCKET_ROUTE, it) }
            }.build()
            binding.control.newWebSocket(original, listener)
        }
    }, NetworkTimeouts(transport.connectTimeoutMillis.toLong(), transport.readTimeoutMillis.toLong(),
        transport.callTimeoutMillis.toLong()), redirects = transport.followRedirects, engineOwnsSocketTimeout = true)
}
private val networkGate = Mutex()
private val networkGroups = mutableMapOf<List<Any?>, AndroidNetworkGroup>()
private suspend fun group(client: OkHttpClient): AndroidNetworkGroup = networkGate.withLock {
    networkGroups.getOrPut(client.transportKey()) { AndroidNetworkGroup(client) }
}

/** 借用进程级客户端：调用方不得 close；execute/session 负责释放本次响应。 */
suspend fun androidNetwork(client: OkHttpClient): HttpClient = group(client).client

/** 以已有连接 ID 分派到本次控制器；并发会话的取消和背压互不影响。 */
suspend fun <T> withAndroidSocket(client: OkHttpClient, connectId: String,
    block: suspend (HttpClient, AndroidSocketControl, HttpRequestBuilder.() -> Unit) -> T): T {
    val group = group(client)
    val control = AndroidSocketControl(group.transport)
    val configure: HttpRequestBuilder.() -> Unit = {
        val original = headers.getAll(SOCKET_ROUTE).orEmpty()
        group.sockets.update { it + (connectId to SocketBinding(control, original)) }
        headers.remove(SOCKET_ROUTE)
        headers.append(SOCKET_ROUTE, connectId)
    }
    try { return block(group.client, control, configure) }
    finally {
        control.cancel()
        group.sockets.update { it - connectId }
    }
}
