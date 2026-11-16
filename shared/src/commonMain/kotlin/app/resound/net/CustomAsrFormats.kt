package app.resound.net

/** 本机能力缓存；存储、地址指纹和时钟由平台注入，不接触密钥。 */
class CustomAsrFormats(
    private val fingerprint: (String) -> String,
    private val read: (String) -> Long?,
    private val write: (String, Long) -> Unit,
    private val now: () -> Long,
) {
    fun format(url: String): String {
        val at = read(fingerprint(url)) ?: return "verbose_json"
        val age = now() - at
        return if (age >= 0 && age < REPROBE_MILLIS) "json" else "verbose_json"
    }
    fun remember(url: String) = write(fingerprint(url), now())
    companion object {
        const val REPROBE_MILLIS = 30L * 24 * 60 * 60 * 1000
        fun shouldFallback(code: Int, body: String): Boolean = code in 400..499 &&
            code !in setOf(401, 403, 408, 429) && (code in setOf(400, 415, 422) || body.contains("response_format", ignoreCase = true))
        fun configurationChanged(oldEndpoint: String, oldModel: String, oldMode: String,
            endpoint: String, model: String, mode: String) = oldEndpoint != endpoint || oldModel != model || oldMode != mode
    }
}
