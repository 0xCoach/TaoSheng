package app.resound.digest

import android.content.Context
import okhttp3.Request
import java.net.URI
import java.util.UUID

/** 统一校验；错误绝不包含输入，避免值借异常进入问题记录。 */
internal fun validateHeaders(headers: List<HeaderEntry>) = app.resound.net.validateNetworkHeaders(headers)

internal fun Request.Builder.serviceHeaders(key: String, headers: List<HeaderEntry>): Request.Builder = apply {
    validateHeaders(headers)
    require(key.all { it.code in 33..126 }) { "密钥格式不正确" }
    if (key.isNotBlank() && headers.none { it.name.equals("Authorization", true) }) header("Authorization", "Bearer $key")
    headers.forEach { header(it.name, it.value) }
}

/** 与普通 API 密钥使用同一个 Keystore 容器；备份按 secrets.json 解密导出、恢复时重新加密。 */
class ServiceHeaders internal constructor(
    private val read: (String) -> String,
    private val write: (String, String) -> Unit,
    private val remove: (String) -> Unit,
) {
    private constructor(store: SecretStore) : this(store::read, store::write, store::remove)
    constructor(context: Context, transcription: Boolean = false) : this(SecretStore(context,
        if (transcription) "transcribe-header-secrets" else "digest-secrets",
        if (transcription) "resound-asr-headers-aes-v1" else "resound-llm-aes-v1"))

    fun headers(id: String): List<HeaderEntry> = try {
        read("headers-$id").takeIf { it.isNotEmpty() }?.let { text ->
            app.resound.net.decodeNetworkHeaders(text)
        }.orEmpty()
    } catch (_: Exception) { throw IllegalStateException("请求头安全读取失败，请重新保存或恢复备份") }

    fun save(id: String, headers: List<HeaderEntry>) {
        validateHeaders(headers)
        if (headers.isEmpty()) remove("headers-$id") else write("headers-$id", app.resound.net.encodeNetworkHeaders(headers))
    }

    fun forPreset(preset: LlmPreset): List<HeaderEntry> = forService(preset.id, preset.baseUrl)
    fun forService(id: String, baseUrl: String): List<HeaderEntry> {
        val custom = headers(id)
        return if (isOpencodeGo(baseUrl) && custom.none { it.name.equals("x-opencode-session", true) })
            listOf(HeaderEntry("x-opencode-session", session())) + custom else custom
    }

    private fun session(): String = synchronized(SESSION_LOCK) {
        read("opencode-session").ifEmpty { UUID.randomUUID().toString().also { write("opencode-session", it) } }
    }

    companion object {
        private val SESSION_LOCK = Any()
        internal fun isOpencodeGo(baseUrl: String): Boolean = runCatching {
            val uri = URI(baseUrl)
            uri.scheme == "https" && uri.host.equals("opencode.ai", true) && uri.path.startsWith("/inference/go/")
        }.getOrDefault(false)
        internal fun modelsEndpoint(baseUrl: String) = if (isOpencodeGo(baseUrl)) "https://opencode.ai/inference/go/v1" else baseUrl
    }
}
