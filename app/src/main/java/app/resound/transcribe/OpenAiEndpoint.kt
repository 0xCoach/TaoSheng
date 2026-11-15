package app.resound.transcribe

import app.resound.net.EndpointRules
import app.resound.net.androidNetwork
import app.resound.net.defaultAndroidHttp
import app.resound.net.queryModels

import app.resound.digest.HeaderEntry
import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
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
    private fun isLocalHost(host:String) = EndpointRules.isLocalHost(host)
    /** 保留代理前缀和已有版本号，仅在没有版本号时依次尝试 /v1 与无版本路径。 */
    fun bases(input:String):List<String> {
        val value=input.trim().trimEnd('/');val uri=runCatching { URI(value) }.getOrNull()
        require(kind(value)!=EndpointKind.Invalid && uri!=null && uri.userInfo==null && uri.rawQuery==null && uri.rawFragment==null){"请输入 http:// 或 https:// 开头的服务地址，不要包含密钥或查询参数"}
        return EndpointRules.bases(value,uri.path.orEmpty())
    }
    fun transcriptions(input:String)=bases(input).map{"$it/audio/transcriptions"}
}
data class RemoteModels(val base:String,val ids:List<String>)
suspend fun fetchRemoteModels(endpoint:String,key:String, client:OkHttpClient=defaultAndroidHttp.newBuilder().connectTimeout(10,TimeUnit.SECONDS).callTimeout(if(OpenAiEndpoint.kind(
    endpoint)==EndpointKind.LocalHttp) 60 else 25,TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).build(), headers: List<HeaderEntry> = emptyList(
    )):RemoteModels=withContext(Dispatchers.IO){
    val network = androidNetwork(client)
    try {
        val r = queryModels(network, OpenAiEndpoint.bases(endpoint), key, headers)
        RemoteModels(r.base, r.ids)
    } catch(e:kotlinx.coroutines.CancellationException){throw e}
    catch(e:IllegalStateException){throw e}
    catch(e:Exception){throw IllegalStateException(modelServiceError(endpoint,e,"拉取失败，请检查地址和网络；也可手动填写模型"))}
}
