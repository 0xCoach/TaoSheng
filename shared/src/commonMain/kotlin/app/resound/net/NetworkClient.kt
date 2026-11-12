package app.resound.net

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.observer.ResponseObserver
import io.ktor.client.plugins.api.*
import io.ktor.util.AttributeKey
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.*
import kotlinx.coroutines.Job

internal val HandshakeObserver = AttributeKey<(HttpResponse) -> Unit>("resound.handshake")

private val ExplicitHeaders = AttributeKey<Set<String>>("resound.explicitHeaders")
fun HttpRequestBuilder.wireHeaders(values: List<Pair<String, String>>) {
    attributes.put(ExplicitHeaders, values.map { it.first.lowercase() }.toSet())
    values.forEach { (k,v) -> headers.append(k,v) }
}
private val LegacyWireHeaders = createClientPlugin("LegacyWireHeaders") {
    on(Send) { request ->
        val explicit = request.attributes.getOrNull(ExplicitHeaders).orEmpty()
        if ("accept" !in explicit) request.headers.remove(HttpHeaders.Accept)
        if ("accept-charset" !in explicit) request.headers.remove(HttpHeaders.AcceptCharset)
        if ("user-agent" !in explicit) request.headers[HttpHeaders.UserAgent] = "okhttp/4.12.0"
        proceed(request)
    }
}

/** 毫秒；0 表示无限。平台引擎另负责 write timeout 和连接失败重试。 */
data class NetworkTimeouts(val connect: Long, val read: Long, val call: Long)
fun networkClient(engine: HttpClientEngine, timeouts: NetworkTimeouts, redirects: Boolean = false, engineOwnsSocketTimeout: Boolean = false): HttpClient = HttpClient(engine) {
    expectSuccess = false
    followRedirects = redirects
    install(ResponseObserver) { onResponse { response -> response.call.request.attributes.getOrNull(HandshakeObserver)?.invoke(response) } }
    install(LegacyWireHeaders)
    install(WebSockets)
    install(HttpTimeout) {
        connectTimeoutMillis = timeouts.connect.takeIf { it > 0 } ?: Long.MAX_VALUE
        if (!engineOwnsSocketTimeout) socketTimeoutMillis = timeouts.read.takeIf { it > 0 } ?: Long.MAX_VALUE
        requestTimeoutMillis = timeouts.call.takeIf { it > 0 } ?: Long.MAX_VALUE
    }
}.also { client -> client.coroutineContext[Job]!!.invokeOnCompletion { engine.close() } }

/** execute 的作用域覆盖正文消费；离开作用域（包括取消）即关闭响应。 */
suspend fun <T> HttpClient.consume(url: String, headers: List<Pair<String, String>>, body: ByteArray? = null,
    type: String = "application/json; charset=utf-8", read: suspend (HttpResponse) -> T): T = prepareRequest(url) {
    method = if (body == null) HttpMethod.Get else HttpMethod.Post
    wireHeaders(headers)
    if (body != null) { contentType(ContentType.parse(type)); setBody(body) }
}.execute(read)

suspend fun HttpResponse.limitedText(limit: Int, message: String): String {
    val input = bodyAsChannel()
    val out = ByteArray(limit + 1)
    var size = 0
    while (size < out.size) {
        val n = input.readAvailable(out, size, out.size - size)
        if (n < 0) break
        size += n
    }
    check(size <= limit) { message }
    return out.decodeToString(0, size)
}
