package app.resound.transcribe

import android.content.Context
import app.resound.digest.serviceHeaders
import app.resound.transcribe.local.LocalAsrProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

internal fun customAsrBody(audio: AsrAudio, model: String, words: List<String>, withPrompt: Boolean = true): MultipartBody {
    val mime = when (audio.format) { "ogg" -> "audio/ogg"; "wav" -> "audio/wav"; else -> "audio/mp4" }
    return MultipartBody.Builder().setType(MultipartBody.FORM).addFormDataPart("model", model)
        .addFormDataPart("file", audio.file.name, audio.file.asRequestBody(mime.toMediaType()))
        .addFormDataPart("response_format", "json").apply {
            // 热词靠 prompt 字段；MiniMax 等服务不认它，完整地址模式下默认不发。
            if (withPrompt) Hotwords.customPrompt(words).takeIf { it.isNotEmpty() }?.let { addFormDataPart("prompt", it) }
        }.build()
}

/**
 * 服务返回体里的正文。OpenAI 兼容服务返回 {"text":"…"}；
 * MiniMax 等服务出错时返回 {"base_resp":{"status_code":…}}，这里只按固定文案提示，不回显服务端原文。
 */
internal fun transcriptionText(json: JSONObject): String {
    if (json.has("text")) return json.optString("text").trim()
    val code = json.optJSONObject("base_resp")?.optInt("status_code", 0) ?: 0
    if (code == 1004) throw AsrException(AsrError.Auth, detail = "鉴权失败，请检查密钥")
    if (code != 0) throw AsrException(AsrError.Protocol, detail = "服务返回错误，请检查地址、密钥和参数")
    throw AsrException(AsrError.Protocol)
}

/** 自定义服务用 OpenAI 兼容的 audio/transcriptions multipart 协议；完整地址模式按填写的地址原样请求。 */
class CustomAsrProvider(private val settings:AndroidTranscribeSettings, private val hotwords: () -> List<String> = { emptyList() }):AsrProvider {
    override suspend fun recognize(audio:AsrAudio,key:String,beforeAudio:suspend()->Unit):AsrResult=withContext(Dispatchers.IO){
        val endpoint=settings.customEndpoint;val model=settings.customModel
        val mode=settings.customEndpointMode
        val urls=runCatching{ transcriptionEndpoints(endpoint,mode) }.getOrElse{ throw AsrException(AsrError.Protocol) }
        if(urls.isEmpty() || model.isBlank())throw AsrException(AsrError.Protocol)
        val client=OkHttpClient.Builder().connectTimeout(10,TimeUnit.SECONDS).readTimeout(8,TimeUnit.MINUTES).callTimeout(8,TimeUnit.MINUTES).followRedirects(false).followSslRedirects(false).build()
        // 自动模式照旧发热词；完整模式由设置决定，默认不发。
        val body = customAsrBody(audio, model, hotwords(), withPrompt = mode == CustomEndpointMode.Auto || settings.customPrompt.value)
        var call: Call? = null
        try {
            for(url in urls) {
                call=client.newCall(Request.Builder().url(url).serviceHeaders(key, settings.headers(TranscribeSource.Custom.name)).post(body).build())
                beforeAudio()
                call.awaitResponse().use { response->
                    if(response.code in setOf(404,405))return@use
                    if(response.code in setOf(401,403))throw AsrException(AsrError.Auth, detail = "鉴权失败，请检查密钥")
                    if(!response.isSuccessful)throw AsrException(if(response.code>=500 || response.code==429)AsrError.Server else AsrError.Protocol)
                    val bytes=response.body!!.byteStream().use{ input -> val out=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192);while(out.size()<=1_000_000){val n=input.read(buffer);if(n<0)break;out.write(buffer,0,n)};out.toByteArray() }
                    if(bytes.size>1_000_000)throw AsrException(AsrError.Protocol)
                    val text=transcriptionText(JSONObject(bytes.toString(Charsets.UTF_8)))
                    return@withContext AsrResult(if(text.isBlank())emptyList() else listOf(AsrSentence(0,audio.durationMillis,text,null)),audio.durationMillis,null)
                }
            }
            throw AsrException(AsrError.Protocol)
        }catch(e:kotlinx.coroutines.CancellationException){throw e}catch(e:AsrException){throw e}catch(e:java.io.IOException){throw AsrException(AsrError.Network, detail = modelServiceError(endpoint,e,AsrError.Network.human))}
        finally{call?.cancel();client.dispatcher.executorService.shutdown();client.connectionPool.evictAll()}
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
