package app.resound.net

import app.resound.digest.HeaderEntry
import app.resound.transcribe.*
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.*
import io.ktor.client.request.*
import io.ktor.websocket.*
import kotlinx.coroutines.*

class NetworkHandshakeFailure(val status: Int?, val code: Long?, val logId: String?, val limited: Boolean, val timeout: Boolean) : Exception()
interface NetworkSocketControl { suspend fun drain(maxQueuedBytes: Long, stop: () -> Boolean); fun cancel() }
fun doubaoRateLimited(code: Long?, http: Int? = null, message: String? = null): Boolean =
    http == 429 || code == 55000031L || message?.lowercase()?.let { text -> listOf("concurrent","concurrency","qps","rate limit","并发","限流").any { it in text } } == true
interface NetworkAsrMetrics { suspend fun <T> measure(part: String, block: suspend () -> T): T }

/** 音频已由平台准备；chunks 顺序输出原始音频字节，last 表示结束包。 */
class NetDoubao(private val client: HttpClient, private val config: DoubaoConfig,
    private val gunzip: (ByteArray, Int) -> ByteArray, private val metrics: NetworkAsrMetrics, private val socket: NetworkSocketControl,
    private val configureSocketRequest: HttpRequestBuilder.() -> Unit = {}) {
    suspend fun recognize(duration: Long, format: String, separateSpeakers: Boolean, words: List<String>, key: String,
        connectId: String, headers: List<HeaderEntry>, beforeAudio: suspend () -> Unit,
        chunks: suspend (suspend (ByteArray, Boolean) -> Unit) -> Unit): AsrResult = coroutineScope {
        var logId: String? = null
        fun safe(value: String?) = TranscribePolicy.safeLogId(value, key)?.takeIf { id -> headers.none { it.value.isNotEmpty() && id.contains(it.value, true) } }
        try {
            withTimeout(config.timeoutFor(duration)) {
                // 非 101 响应由 Ktor 抛出 WebSocketException；保留响应头以按旧规则分类。
                var handshakeStatus: Int? = null
                var handshakeCode: Long? = null
                var handshakeMessage: String? = null
                val session = try {
                    metrics.measure("Connect") { client.webSocketSession {
                        url(config.url)
                        wireHeaders(networkHeaders("", listOf(HeaderEntry("X-Api-Key",key), HeaderEntry("X-Api-Resource-Id",config.resourceId), HeaderEntry("X-Api-Connect-Id",connectId)) + headers))
                        configureSocketRequest()
                        attributes.put(HandshakeObserver, { response ->
                            handshakeStatus = response.status.value
                            handshakeCode = response.headers["X-Api-Status-Code"]?.toLongOrNull()
                            handshakeMessage = response.headers["X-Api-Message"]
                            logId = safe(response.headers["X-Tt-Logid"])
                        })
                    } }
                } catch(e: CancellationException) { throw e }
                catch(e: Exception) {
                    val failure = generateSequence(e as Throwable?) { it.cause }.take(8).filterIsInstance<NetworkHandshakeFailure>().firstOrNull()
                    if(failure != null) throw AsrException(if(failure.limited) AsrError.Server else if(failure.status != null) NetDoubaoProtocol.error(failure.code,failure.status) else if(failure.timeout) AsrError.Timeout else AsrError.Network,safe(failure.logId),concurrencyLimited=failure.limited)
                    val limited = doubaoRateLimited(handshakeCode, handshakeStatus, handshakeMessage)
                    throw AsrException(if(limited) AsrError.Server else if(handshakeStatus != null) NetDoubaoProtocol.error(handshakeCode,handshakeStatus) else AsrError.Network,logId,concurrencyLimited=limited)
                }
                try {
                    val final = CompletableDeferred<AsrResult>()
                    val receiver = launch {
                        val incremental = NetDoubaoIncremental()
                        try {
                            for(frame in session.incoming) {
                                if(frame !is Frame.Binary) throw AsrException(AsrError.Protocol,logId)
                                val packet = NetDoubaoProtocol.parse(frame.data,gunzip)
                                if(logId == null) logId = safe(packet.payload.optJSONObject("result")?.optJSONObject("additions")?.optString("log_id"))
                                val code = packet.code ?: packet.payload.optLong("code",20000000)
                                if(packet.kind == 15 || code !in setOf(0L,20000000L)) {
                                    val limited = doubaoRateLimited(code,message=packet.payload.optString("message"))
                                    throw AsrException(if(limited) AsrError.Server else NetDoubaoProtocol.error(code),logId,concurrencyLimited=limited)
                                }
                                val body = if(config.incrementalResults) incremental.accept(packet.payload,packet.final) else packet.payload
                                if(packet.final) { final.complete(NetDoubaoProtocol.result(body,duration,logId,separateSpeakers)); return@launch }
                            }
                            final.completeExceptionally(AsrException(AsrError.Network,logId))
                        } catch(e: CancellationException) { throw e }
                        catch(e: AsrException) { final.completeExceptionally(e) }
                        catch(e: NetworkHandshakeFailure) {
                            final.completeExceptionally(AsrException(if(e.limited) AsrError.Server else if(e.status != null) NetDoubaoProtocol.error(e.code,e.status) else if(e.timeout) AsrError.Timeout else AsrError.Network,safe(e.logId) ?: logId,concurrencyLimited=e.limited))
                        }
                        catch(_: Exception) { final.completeExceptionally(AsrException(AsrError.Protocol,logId)) }
                    }
                    try {
                        session.send(Frame.Binary(true,NetDoubaoProtocol.frame(1,0,NetDoubaoProtocol.config(format,words,separateSpeakers,config.incrementalResults),true)))
                        var accounted = false
                        metrics.measure("Upload") { chunks { bytes,last ->
                            ensureActive()
                            if(final.isCompleted) { final.await(); throw AsrException(AsrError.Protocol,logId) }
                            if(!accounted) {
                                try { beforeAudio() } catch(e: CancellationException) { throw e }
                                catch(_: Exception) { throw AsrException(AsrError.Storage) }
                                accounted = true
                            }
                            socket.drain(256 * 1024L) { final.isCompleted }
                            session.send(Frame.Binary(true,NetDoubaoProtocol.frame(2,if(last) 2 else 0,bytes)))
                            // Ktor flush 将帧交给引擎；其队列背压替代旧 queueSize 轮询。
                            session.flush()
                            if(config.paceMillis > 0 && !last) delay(config.paceMillis)
                        }
                        }
                        socket.drain(0) { final.isCompleted }
                        metrics.measure("Wait") { final.await() }
                    } finally { receiver.cancelAndJoin() }
                } finally { socket.cancel(); session.cancel() }
            }
        } catch(e: TimeoutCancellationException) { currentCoroutineContext().ensureActive(); throw AsrException(AsrError.Timeout,logId) }
        catch(e: CancellationException) { throw e }
        catch(e: AsrException) { throw e }
        catch(_: Exception) { throw AsrException(AsrError.Protocol,logId) }
    }
}
