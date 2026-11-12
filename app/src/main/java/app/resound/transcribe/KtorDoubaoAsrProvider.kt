package app.resound.transcribe

import app.resound.digest.HeaderEntry
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class DoubaoAsrProvider(
    private val reader: AsrAudioReader,
    private val config: DoubaoConfig = DoubaoConfig(),
    private val client: OkHttpClient = (if (app.resound.AppGraph.legacyNetwork) OkHttpClient.Builder() else app.resound.net.defaultAndroidHttp.newBuilder()).connectTimeout(15,TimeUnit.SECONDS)
        .readTimeout(0,TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build(),
    private val headers: () -> List<HeaderEntry> = { emptyList() },
    private val hotwords: () -> List<String> = { emptyList() },
) : AsrProvider {
    private val legacy = if(app.resound.AppGraph.legacyNetwork) LegacyDoubaoAsrProvider(reader,config,client,headers,hotwords) else null
    override suspend fun recognize(audio: AsrAudio,key: String,beforeAudio: suspend () -> Unit): AsrResult {
        legacy?.let { return it.recognize(audio,key,beforeAudio) }
        return DoubaoConcurrency.global.withPermit {
            try {
                withContext(Dispatchers.IO) {
                    // 外层计时也覆盖音频准备，与旧实现一致。
                    try { kotlinx.coroutines.withTimeout(config.timeoutFor(audio.durationMillis)) {
                        measureAsr(AsrPart.Prepare) { reader.prepare(audio) }.use { prepared ->
                            val connectId = java.util.UUID.randomUUID().toString()
                            app.resound.net.withAndroidSocket(client, connectId) { network, sockets, configureSocket ->
                                app.resound.net.NetDoubao(network,config,::gunzip,object : app.resound.net.NetworkAsrMetrics {
                                    override suspend fun <T> measure(part: String, block: suspend () -> T): T = measureAsr(AsrPart.valueOf(part),block)
                                },sockets,configureSocket).recognize(audio.durationMillis,prepared.format,audio.separateSpeakers,
                                    Hotwords.doubao(hotwords()),key,connectId,headers(),beforeAudio) { emit ->
                                    AsrAudioReader.chunks(prepared,emit)
                                }
                            }
                        }
                    } } catch(e: kotlinx.coroutines.TimeoutCancellationException) {
                        kotlinx.coroutines.currentCoroutineContext().ensureActive()
                        throw AsrException(AsrError.Timeout)
                    }
                }
            } catch(e: AsrException) { if(e.concurrencyLimited) DoubaoConcurrency.global.reduce(); throw e }
        }
    }
    private fun gunzip(bytes: ByteArray,limit: Int): ByteArray = java.util.zip.GZIPInputStream(bytes.inputStream()).use { input ->
        val out = java.io.ByteArrayOutputStream()
        val block = ByteArray(8192)
        while(true) { val n = input.read(block); if(n < 0) break; require(out.size() + n <= limit); out.write(block,0,n) }
        out.toByteArray()
    }
}
