package app.resound.transcribe

/** 按旧偏好中的字符串恢复地址模式，显示文案独立于持久化值。 */
enum class CustomEndpointMode(val storedValue: String) {
    Auto("Auto"),
    Full("Full");

    val title: String
        get() = when (this) {
            Auto -> "自动匹配路径"
            Full -> "完整请求地址"
        }

    val hint: String
        get() = when (this) {
            Auto -> "填根地址或带 /v1 的地址，自动补 /audio/transcriptions；OpenAI 兼容的服务用它。"
            Full -> "按填写的地址原样请求，一个路径都不补；非 OpenAI 兼容的服务用它。"
        }

    companion object {
        internal fun fromStoredValue(value: String?): CustomEndpointMode =
            entries.firstOrNull { it.storedValue == value } ?: Auto
    }
}
