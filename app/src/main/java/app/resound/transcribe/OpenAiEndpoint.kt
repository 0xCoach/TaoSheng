package app.resound.transcribe

import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** 卡 055：服务地址的类型，界面据此给提示（约定，签名不要改） */
enum class EndpointKind { Https, LocalHttp, PublicHttp, Invalid }

object OpenAiEndpoint {
    /** http 的局域网/本机地址算 LocalHttp：localhost、127.x、10.x、172.16–31.x、192.168.x、169.254.x、100.64–127.x（Tailscale）、*.local、IPv6 的 ::1 / fc00::/7 / fe80::/10 */
    fun kind(input:String):EndpointKind {
        val uri=runCatching{URI(input.trim())}.getOrNull() ?: return EndpointKind.Invalid
        val host=uri.host?.trim('[',']')?.lowercase()
        if(host.isNullOrBlank())return EndpointKind.Invalid
        return when(uri.scheme?.lowercase()) {
            "https"->EndpointKind.Https
            "http"->if(isLocalHost(host))EndpointKind.LocalHttp else EndpointKind.PublicHttp
            else->EndpointKind.Invalid
        }
    }
    private fun isLocalHost(host:String):Boolean {
        if(host=="localhost" || host.endsWith(".local") || host.endsWith(".lan") || host=="::1")return true
        if(':' in host)return host.startsWith("fc") || host.startsWith("fd") || host.startsWith("fe8") || host.startsWith("fe9") || host.startsWith("fea") || host.startsWith("feb")
        val p=host.split('.').mapNotNull{it.toIntOrNull()}.takeIf{it.size==4} ?: return false
        return p[0]==127 || p[0]==10 || (p[0]==172 && p[1] in 16..31) || (p[0]==192 && p[1]==168) || (p[0]==169 && p[1]==254) || (p[0]==100 && p[1] in 64..127)
    }
    /** 保留代理前缀和已有版本号，仅在没有版本号时依次尝试 /v1 与无版本路径。 */
    fun bases(input:String):List<String> {
        val value=input.trim().trimEnd('/');val uri=runCatching { URI(value) }.getOrNull()
        require(kind(value)!=EndpointKind.Invalid && uri!=null && uri.userInfo==null && uri.rawQuery==null && uri.rawFragment==null){"请输入 http:// 或 https:// 开头的服务地址，不要包含密钥或查询参数"}
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
suspend fun fetchRemoteModels(endpoint:String,key:String, client:OkHttpClient=OkHttpClient.Builder().connectTimeout(10,TimeUnit.SECONDS).callTimeout(if(OpenAiEndpoint.kind(endpoint)==EndpointKind.LocalHttp) 60 else 25,TimeUnit.SECONDS).followRedirects(false).build()):RemoteModels=withContext(Dispatchers.IO){
    try {
        for(base in OpenAiEndpoint.bases(endpoint)) {
            client.newCall(Request.Builder().url("$base/models").apply { if(key.isNotBlank()) header("Authorization","Bearer $key") }.build()).awaitResponse().use{response->
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
    catch(e:Exception){throw IllegalStateException(modelServiceError(endpoint,e,"拉取失败，请检查地址和网络；也可手动填写模型"))}
    finally{client.dispatcher.executorService.shutdown();client.connectionPool.evictAll()}
}
