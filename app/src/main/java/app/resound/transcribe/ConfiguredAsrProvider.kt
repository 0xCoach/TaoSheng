package app.resound.transcribe

import app.resound.diagnostics.FailureNotes

import app.resound.net.AudioUpload
import app.resound.net.androidNetwork
import app.resound.net.customRecognize
import app.resound.net.defaultAndroidHttp

import android.content.Context
import app.resound.transcribe.local.LocalAsrProvider
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/** 自定义服务通过共用网络层发送 OpenAI 兼容的 multipart 请求。 */
class CustomAsrProvider(private val settings: AndroidTranscribeSettings, private val hotwords: () -> List<String> = { emptyList() }) : AsrProvider {
    override suspend fun recognize(audio: AsrAudio, key: String, beforeAudio: suspend () -> Unit): AsrResult = withContext(Dispatchers.IO) {
        val endpoint = settings.customEndpoint
        val urls = runCatching { transcriptionEndpoints(endpoint, settings.customEndpointMode) }.onFailure {
            FailureNotes.record("transcribe/ConfiguredAsrProvider.kt:19", it)
        }.getOrElse { throw AsrException(AsrError.Protocol) }
        // 议题 #46：慢速链路（如约 7 KB/s 的中继）上传一段录音要几十秒，中途偶尔卡住；
        // 上传不能沿用默认的 10 秒写入限制，总时限也要容得下“上传 + 服务端转写”。固定值，避免每次新建一套网络客户端。
        val http = defaultAndroidHttp.newBuilder().connectTimeout(30,TimeUnit.SECONDS).writeTimeout(2,TimeUnit.MINUTES)
            .readTimeout(8,TimeUnit.MINUTES).callTimeout(15,TimeUnit.MINUTES).followRedirects(false).followSslRedirects(false).build()
        val network = androidNetwork(http)
        try {
            val upload = AudioUpload(audio.file.name, audio.format, audio.file.length()) { consume ->
                audio.file.inputStream().use { input ->
                    val bytes = ByteArray(8192)
                    while(true) {
                        kotlinx.coroutines.currentCoroutineContext().ensureActive()
                        val n = input.read(bytes); if(n < 0) break
                        consume(bytes.copyOf(n))
                    }
                }
            }
            val prompt = if(settings.customEndpointMode == CustomEndpointMode.Auto || settings.customPrompt.value) Hotwords.customPrompt(hotwords()) else null
            customRecognize(network,urls,key,settings.headers(TranscribeSource.Custom.name),upload,audio.durationMillis,settings.customModel,prompt,java.util.UUID.randomUUID().toString(),beforeAudio)
        } catch(e:kotlinx.coroutines.CancellationException){throw e}
        catch(e:AsrException){throw e}
        catch(e:java.io.IOException){throw AsrException(AsrError.Network,detail=modelServiceError(endpoint,e,AsrError.Network.human))}
    }
}
class ConfiguredAsrProvider(context:Context,private val settings:AndroidTranscribeSettings, private val hotwords: () -> List<String> = { emptyList() }):AsrProvider {
    private val doubao=DoubaoAsrProvider(AsrAudioReader(context.cacheDir), hotwords = hotwords, headers = { settings.headers(TranscribeSource.Doubao.name) })
    private val local=LocalAsrProvider(context,settings,hotwords)
    private val custom=CustomAsrProvider(settings, hotwords)
    override suspend fun recognize(audio:AsrAudio,key:String,beforeAudio:suspend()->Unit):AsrResult = recognize(settings.source.value, audio, key, beforeAudio)
    internal suspend fun recognize(source: TranscribeSource, audio: AsrAudio, key: String, beforeAudio: suspend () -> Unit): AsrResult = when(source){
        // 073 审核：同批没有节省时长，默认不剪音，也不为剪音额外解码。
        TranscribeSource.Doubao->doubao.recognize(audio,key,beforeAudio)
        TranscribeSource.Local->local.recognize(audio,key,beforeAudio)
        TranscribeSource.Custom->custom.recognize(audio,key,beforeAudio)
    }
}
