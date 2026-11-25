package app.resound.digest

import app.resound.net.validateNetworkHeaders
import app.resound.platform.AndroidSecretVault
import app.resound.platform.SecretVault

import android.content.Context
import okhttp3.Request
import java.net.URI
import java.util.UUID

/** 统一校验；错误绝不包含输入，避免值借异常进入问题记录。 */
internal fun validateHeaders(headers: List<HeaderEntry>) = validateNetworkHeaders(headers)

internal fun Request.Builder.serviceHeaders(key: String, headers: List<HeaderEntry>): Request.Builder = apply {
    validateHeaders(headers)
    require(key.all { it.code in 33..126 }) { "密钥格式不正确" }
    if (key.isNotBlank() && headers.none { it.name.equals("Authorization", true) }) header("Authorization", "Bearer $key")
    headers.forEach { header(it.name, it.value) }
}

/** 与普通 API 密钥使用同一个 Keystore 容器；备份按 secrets.json 解密导出、恢复时重新加密。 */
class ServiceHeaders internal constructor(
    private val vault: SecretVault,
    transcription: Boolean = false,
) {
    constructor(context: Context, transcription: Boolean = false) : this(AndroidSecretVault(context), transcription)
    internal constructor(read: (String) -> String, write: (String, String) -> Unit, remove: (String) -> Unit) :
        this(object : SecretVault {
            override fun read(group: String, key: String) = read(key)
            override fun write(group: String, key: String, value: String) = write(key, value)
            override fun remove(group: String, key: String) = remove(key)
            override fun has(group: String, key: String) = read(key).isNotEmpty()
        })

    private val group = if (transcription) "transcribe-header-secrets" else "digest-secrets"
    internal val settings = SecretHeaders(vault, group)
    fun headers(id: String) = settings.headers(id)
    fun save(id: String, headers: List<HeaderEntry>) = settings.save(id, headers)

    fun forPreset(preset: LlmPreset): List<HeaderEntry> = forService(preset.id, preset.baseUrl)
    fun forService(id: String, baseUrl: String): List<HeaderEntry> {
        val custom = headers(id)
        return if (isOpencodeGo(baseUrl) && custom.none { it.name.equals("x-opencode-session", true) })
            listOf(HeaderEntry("x-opencode-session", session())) + custom else custom
    }

    private fun session(): String = synchronized(SESSION_LOCK) {
        vault.read(group, "opencode-session").ifEmpty {
            UUID.randomUUID().toString().also { vault.write(group, "opencode-session", it) }
        }
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
