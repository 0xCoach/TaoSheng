package app.resound.transcribe

import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object OpenAiEndpoint {
    /** 保留代理前缀和已有版本号，仅在没有版本号时依次尝试 /v1 与无版本路径。 */
    fun bases(input:String):List<String> {
        val value=input.trim().trimEnd('/');val uri=URI(value)
        require(uri.scheme=="https" && !uri.host.isNullOrBlank() && uri.userInfo==null && uri.rawQuery==null && uri.rawFragment==null){"请输入 HTTPS 服务地址，不要包含密钥或查询参数"}
        val suffixes=listOf("/audio/transcriptions","/chat/completions","/models")
        val suffix=suffixes.firstOrNull{value.endsWith(it)}
        val base=if(suffix!=null)value.removeSuffix(suffix) else value
        val path=URI(base).path.orEmpty()
        return if(Regex("/v[0-9]+(?:beta[0-9]*)?$").containsMatchIn(path))listOf(base)
        else if(suffix!=null)listOf(base,"$base/v1") else listOf("$base/v1",base)
    }
    fun transcriptions(input:String)=bases(input).map{"$it/audio/transcriptions"}
}
data class RemoteModels(val base:String,val ids:List<String>)
suspend fun fetchRemoteModels(endpoint:String,key:String, client:OkHttpClient=OkHttpClient.Builder().connectTimeout(15,TimeUnit.SECONDS).callTimeout(25,TimeUnit.SECONDS).followRedirects(false).build()):RemoteModels=withContext(Dispatchers.IO){
    require(key.isNotBlank()){"请先保存自定义服务密钥"}
    try {
        for(base in OpenAiEndpoint.bases(endpoint)) {
            client.newCall(Request.Builder().url("$base/models").header("Authorization","Bearer $key").build()).awaitResponse().use{response->
                if(response.code in setOf(404,405))return@use
                if(response.code in setOf(401,403))throw IllegalStateException("鉴权失败，请检查密钥")
                check(response.isSuccessful){"拉取失败；可手动填写模型名称"}
                val bytes=response.body!!.byteStream().use{input->val out=java.io.ByteArrayOutputStream();val b=ByteArray(8192);while(out.size()<=1_000_000){val n=input.read(b);if(n<0)break;out.write(b,0,n)};out.toByteArray()}
                check(bytes.size<=1_000_000){"模型列表过大，请手动填写"}
                val data=JSONObject(bytes.toString(Charsets.UTF_8)).optJSONArray("data")?:throw IllegalStateException("服务未返回兼容的模型列表，可手动填写")
                val ids=(0 until data.length()).mapNotNull{data.optJSONObject(it)?.optString("id")?.takeIf{v->v.isNotBlank() && v.length<=200}}.distinct().sorted()
                check(ids.isNotEmpty()){"没有可选模型，请手动填写"}
                return@withContext RemoteModels(base,ids)
            }
        }
        throw IllegalStateException("服务没有提供模型列表，可手动填写模型名称")
    } catch(e:kotlinx.coroutines.CancellationException){throw e}
    catch(e:IllegalStateException){throw e}
    catch(_:Exception){throw IllegalStateException("拉取失败，请检查地址和网络；也可手动填写模型")}
    finally{client.dispatcher.executorService.shutdown();client.connectionPool.evictAll()}
}
