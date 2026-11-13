package app.resound.transcribe

import java.net.URI

/**
 * 完整地址校验：只认 http/https 与主机名；不允许写进账号信息（user:pass@）与 # 片段，
 * 查询参数（?GroupId= 之类）原样保留。
 */
fun fullEndpoint(input: String): String {
    val value = input.trim().let { if (it.length > 1) it.trimEnd('/') else it }
    val uri = runCatching { URI(value) }.getOrNull()
    require(OpenAiEndpoint.kind(value) != EndpointKind.Invalid && uri != null && uri.userInfo == null && uri.rawFragment == null) {
        "请填写 http:// 或 https:// 开头的完整地址，不要包含账号信息或 # 片段"
    }
    return value
}

/** 按写法算出实际要请求的转写地址：完整模式只有一条，不会再退回 /v1 去试。 */
fun transcriptionEndpoints(endpoint: String, mode: CustomEndpointMode): List<String> = when (mode) {
    CustomEndpointMode.Full -> listOf(fullEndpoint(endpoint))
    CustomEndpointMode.Auto -> OpenAiEndpoint.transcriptions(endpoint)
}
