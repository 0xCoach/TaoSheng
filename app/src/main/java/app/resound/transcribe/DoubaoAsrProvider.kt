package app.resound.transcribe

import kotlinx.coroutines.*
import okhttp3.*
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.net.SocketTimeoutException
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

class DoubaoAsrProvider(
    private val reader: AsrAudioReader,
    private val config: DoubaoConfig = DoubaoConfig(),
    private val client: OkHttpClient = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build(),
    private val hotwords: () -> List<String> = { emptyList() },
) : AsrProvider {
    override suspend fun recognize(audio: AsrAudio, key: String, beforeAudio: suspend () -> Unit): AsrResult =
        DoubaoConcurrency.global.withPermit {
            try { recognizeRequest(audio, key, beforeAudio) }
            catch (e: AsrException) { if (e.concurrencyLimited) DoubaoConcurrency.global.reduce(); throw e }
        }
    private suspend fun recognizeRequest(audio: AsrAudio, key: String, beforeAudio: suspend () -> Unit): AsrResult = withContext(Dispatchers.IO) {
        var socket: WebSocket? = null
        var logId: String? = null
        try {
            withTimeout(config.timeoutFor(audio.durationMillis)) {
                measureAsr(AsrPart.Prepare) { reader.prepare(audio) }.use { prepared ->
                    val opened = CompletableDeferred<Unit>()
                    val final = CompletableDeferred<AsrResult>()
                    val incremental = DoubaoIncremental()
                    val request = Request.Builder().url(config.url).header("X-Api-Key", key)
                        .header("X-Api-Resource-Id", config.resourceId).header("X-Api-Connect-Id", UUID.randomUUID().toString()).build()
                    val listener = object : WebSocketListener() {
                        override fun onOpen(webSocket: WebSocket, response: Response) {
                            logId = TranscribePolicy.safeLogId(response.header("X-Tt-Logid"), key)
                            opened.complete(Unit)
                        }
                        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                            try {
                                val packet = DoubaoProtocol.parse(bytes.toByteArray())
                                if (logId == null) logId = TranscribePolicy.safeLogId(packet.payload.optJSONObject("result")
                                    ?.optJSONObject("additions")?.optString("log_id"), key)
                                val code = packet.code ?: packet.payload.optLong("code", 20000000)
                                if (packet.kind == 15 || code !in setOf(0L, 20000000L)) {
                                    val limited = doubaoLimited(code, message = packet.payload.optString("message"))
                                    final.completeExceptionally(AsrException(if (limited) AsrError.Server else DoubaoProtocol.error(code), logId, concurrencyLimited = limited))
                                } else {
                                    val body = if (config.incrementalResults) incremental.accept(packet.payload, packet.final) else packet.payload
                                    if (packet.final) final.complete(DoubaoProtocol.result(body, audio.durationMillis, logId, audio.separateSpeakers))
                                }
                            } catch (_: Exception) { final.completeExceptionally(AsrException(AsrError.Protocol, logId)) }
                        }
                        override fun onMessage(webSocket: WebSocket, text: String) {
                            final.completeExceptionally(AsrException(AsrError.Protocol, logId))
                        }
                        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                            final.completeExceptionally(AsrException(AsrError.Network, logId))
                            webSocket.close(code, null)
                        }
                        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                            val type = if (response != null) DoubaoProtocol.error(response.header("X-Api-Status-Code")?.toLongOrNull(), response.code)
                                else if (t is SocketTimeoutException) AsrError.Timeout else AsrError.Network
                            val limited = doubaoLimited(response?.header("X-Api-Status-Code")?.toLongOrNull(), response?.code, response?.header("X-Api-Message"))
                            val error = AsrException(if (limited) AsrError.Server else type, TranscribePolicy.safeLogId(response?.header("X-Tt-Logid"), key) ?: logId, concurrencyLimited = limited)
                            opened.completeExceptionally(error); final.completeExceptionally(error)
                            response?.close()
                        }
                    }
                    val ws = client.newWebSocket(request, listener)
                    socket = ws
                    measureAsr(AsrPart.Connect) { opened.await() }
                    checkSend(ws.send(DoubaoProtocol.frame(1, 0, DoubaoProtocol.config(prepared.format, hotwords(), audio.separateSpeakers, config.incrementalResults), true).toByteString()))
                    var accounted = false
                    measureAsr(AsrPart.Upload) {
                    AsrAudioReader.chunks(prepared) { bytes, last ->
                        coroutineContext.ensureActive()
                        if (final.isCompleted) { final.await(); throw AsrException(AsrError.Protocol, logId) }
                        // 整段保守预记，进程在发送后死亡也不会漏计已发出的用量。
                        if (!accounted) {
                            try { beforeAudio() } catch (e: CancellationException) { throw e }
                            catch (_: Exception) { throw AsrException(AsrError.Storage) }
                            accounted = true
                        }
                        while (ws.queueSize() > 256 * 1024 && !final.isCompleted) delay(10)
                        checkSend(ws.send(DoubaoProtocol.frame(2, if (last) 2 else 0, bytes).toByteString()))
                        if (config.paceMillis > 0 && !last) delay(config.paceMillis)
                    }
                    // 等待本地发送队列排空；该值仍不是服务端确认收到的时刻。
                    while (ws.queueSize() > 0 && !final.isCompleted) delay(10)
                    }
                    measureAsr(AsrPart.Wait) { final.await() }
                }
            }
        } catch (e: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            throw AsrException(AsrError.Timeout, logId)
        }
        catch (e: CancellationException) { throw e }
        catch (e: AsrException) { throw e }
        catch (_: Exception) { throw AsrException(AsrError.Protocol, logId) }
        finally { socket?.cancel() }
    }
    private fun checkSend(sent: Boolean) { if (!sent) throw AsrException(AsrError.Network) }
}
