package app.resound.transcribe

import java.net.*
import java.io.InterruptedIOException

/** 只返回固定文案和地址的主机端口，不回显异常原文、密钥、路径。 */
fun modelServiceError(endpoint: String, error: Throwable, fallback: String, status: Int = 0): String {
    if (status == 401 || status == 403) return "鉴权失败，请检查密钥"
    val causes = generateSequence(error) { it.cause }.take(8).toList()
    return when {
        causes.any { it is UnknownHostException } -> "找不到这个地址，请检查拼写"
        causes.any { it is SocketTimeoutException || it is InterruptedIOException } -> "等待超时：本地模型首次加载可能较慢，稍后再试"
        causes.any { it is ConnectException || it is NoRouteToHostException } -> {
            val uri = runCatching { URI(endpoint.trim()) }.getOrNull()
            val host = uri?.host.orEmpty()
            val port = uri?.port?.takeIf { it >= 0 } ?: if (uri?.scheme == "https") 443 else 80
            "连不上 $host:$port：确认服务已启动，手机和它在同一网络" +
                if (OpenAiEndpoint.kind(endpoint.replaceFirst("https://", "http://")) == EndpointKind.LocalHttp)
                    "。Ollama 需在电脑上设置 OLLAMA_HOST=0.0.0.0 才能被局域网访问" else ""
        }
        else -> fallback
    }
}
