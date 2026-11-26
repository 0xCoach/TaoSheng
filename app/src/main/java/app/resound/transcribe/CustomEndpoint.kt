package app.resound.transcribe

import java.net.URI
import java.net.URISyntaxException

private const val InvalidFullAddress = "请填写 http:// 或 https:// 开头的完整地址，不要包含账号信息或 # 片段"

/** 校验完整请求地址后返回原有写法，保留路径与查询参数。 */
fun fullEndpoint(input: String): String {
    val address = input.trim().trimEnd('/')
    val requestUri = try {
        URI(address)
    } catch (_: URISyntaxException) {
        throw IllegalArgumentException(InvalidFullAddress)
    }
    val supportedTransport = when (requestUri.scheme?.lowercase()) {
        "http", "https" -> true
        else -> false
    }
    require(supportedTransport && !requestUri.host.isNullOrBlank()) { InvalidFullAddress }
    require(requestUri.rawUserInfo == null && requestUri.rawFragment == null) { InvalidFullAddress }
    return address
}

/** 自动模式生成候选路径，直连模式使用完整地址。 */
fun transcriptionEndpoints(endpoint: String, mode: CustomEndpointMode): List<String> {
    if (mode == CustomEndpointMode.Auto) return OpenAiEndpoint.transcriptions(endpoint)
    return listOf(fullEndpoint(endpoint))
}
