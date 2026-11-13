package app.resound.transcribe

/**
 * 自定义转写服务的地址写法。
 *
 * Auto：填根地址，自动补 /v1 与 /audio/transcriptions，OpenAI 兼容服务用这个。
 * Full：填完整请求地址，按填写的原样请求，一个字符都不补；MiniMax 等不是 OpenAI 兼容的服务用它。
 */
enum class CustomEndpointMode(val title: String, val hint: String) {
    Auto("自动匹配路径", "填根地址或带 /v1 的地址，自动补 /audio/transcriptions；OpenAI 兼容的服务用它。"),
    Full("完整请求地址", "按填写的地址原样请求，一个路径都不补；非 OpenAI 兼容的服务用它。"),
}
