package app.resound.transcribe

import android.content.Context
import app.resound.transcribe.local.LocalAsrProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

internal fun customAsrBody(audio: AsrAudio, model: String, words: List<String>): MultipartBody {
    val mime = when (audio.format) { "ogg" -> "audio/ogg"; "wav" -> "audio/wav"; else -> "audio/mp4" }
    return MultipartBody.Builder().setType(MultipartBody.FORM).addFormDataPart("model", model)
        .addFormDataPart("file", audio.file.name, audio.file.asRequestBody(mime.toMediaType()))
        .addFormDataPart("response_format", "json").apply {
            Hotwords.customPrompt(words).takeIf { it.isNotEmpty() }?.let { addFormDataPart("prompt", it) }
        }.build()
}

/** 自定义服务使用 OpenAI 兼容的 audio/transcriptions multipart 协议，端点由用户完整指定。 */
class CustomAsrProvider(private val settings:AndroidTranscribeSettings, private val hotwords: () -> List<String> = { emptyList() }):AsrProvider {
    override suspend fun recognize(audio:AsrAudio,key:String,beforeAudio:suspend()->Unit):AsrResult=withContext(Dispatchers.IO){
        val endpoint=settings.customEndpoint;val model=settings.customModel
        if(OpenAiEndpoint.kind(endpoint)==EndpointKind.Invalid || model.isBlank())throw AsrException(AsrError.Protocol)
        val client=OkHttpClient.Builder().connectTimeout(10,TimeUnit.SECONDS).readTimeout(8,TimeUnit.MINUTES).callTimeout(8,TimeUnit.MINUTES).build()
        val body = customAsrBody(audio, model, hotwords())
        var call: Call? = null
        try {
        for(url in OpenAiEndpoint.transcriptions(endpoint)) {
        call=client.newCall(Request.Builder().url(url).apply { if(key.isNotBlank()) header("Authorization","Bearer $key") }.post(body).build())
            beforeAudio()
            call.awaitResponse().use { response->
                if(response.code in setOf(404,405))return@use
                if(response.code in setOf(401,403))throw AsrException(AsrError.Auth, detail = "鉴权失败，请检查密钥")
                if(!response.isSuccessful)throw AsrException(if(response.code>=500 || response.code==429)AsrError.Server else AsrError.Protocol)
                val bytes=response.body!!.byteStream().use{ input -> val out=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192);while(out.size()<=1_000_000){val n=input.read(buffer);if(n<0)break;out.write(buffer,0,n)};out.toByteArray() }
                if(bytes.size>1_000_000)throw AsrException(AsrError.Protocol)
                val json=JSONObject(bytes.toString(Charsets.UTF_8));if(!json.has("text"))throw AsrException(AsrError.Protocol)
                val text=json.getString("text").trim()
                return@withContext AsrResult(if(text.isBlank())emptyList() else listOf(AsrSentence(0,audio.durationMillis,text,null)),audio.durationMillis,null)
            }
        }
        throw AsrException(AsrError.Protocol)
        }catch(e:kotlinx.coroutines.CancellationException){throw e}catch(e:AsrException){throw e}catch(e:java.io.IOException){throw AsrException(AsrError.Network, detail = modelServiceError(endpoint,e,AsrError.Network.human))}
        finally{call?.cancel();client.dispatcher.executorService.shutdown();client.connectionPool.evictAll()}
    }
}
class ConfiguredAsrProvider(context:Context,private val settings:AndroidTranscribeSettings, private val hotwords: () -> List<String> = { emptyList() }):AsrProvider {
    private val doubao=DoubaoAsrProvider(AsrAudioReader(context.cacheDir), hotwords = hotwords)
    private val local=LocalAsrProvider(context,settings,hotwords)
    private val custom=CustomAsrProvider(settings, hotwords)
    private val trimmer=AsrSilenceTrimmer(context.cacheDir, AsrAudioReader(context.cacheDir)::decodeForExport)
    override suspend fun recognize(audio:AsrAudio,key:String,beforeAudio:suspend()->Unit):AsrResult = recognize(settings.source.value, audio, key, beforeAudio)
    internal suspend fun recognize(source: TranscribeSource, audio: AsrAudio, key: String, beforeAudio: suspend () -> Unit): AsrResult = when(source){
        TranscribeSource.Doubao->trimmer.recognize(audio) { doubao.recognize(it,key,beforeAudio) }
        TranscribeSource.Local->trimmer.recognize(audio) { local.recognize(it,key,beforeAudio) }
        TranscribeSource.Custom->custom.recognize(audio,key,beforeAudio)
    }
}
