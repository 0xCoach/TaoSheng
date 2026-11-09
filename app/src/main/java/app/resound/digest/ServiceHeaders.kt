package app.resound.digest

import android.content.Context
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.util.UUID

/** 统一校验；错误绝不包含输入，避免值借异常进入问题记录。 */
internal fun validateHeaders(headers: List<HeaderEntry>) {
    val forbidden = setOf("host", "content-length", "content-type", "transfer-encoding", "connection")
    headers.forEach { entry ->
        require(entry.name.isNotEmpty() && entry.name.all {
            it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it in "!#$%&'*+-.^_`|~"
        }) { "请求头名称只能使用英文字母、数字和 HTTP 允许的符号，不能有空格或中文" }
        require(entry.name.lowercase(java.util.Locale.ROOT) !in forbidden) { "不能修改 Host、Content-Length、Content-Type、Transfer-Encoding 或 Connection 请求头" }
        // OkHttp 的请求头使用可打印 ASCII；提前拒绝，防止其异常回显整个值。
        require(entry.value.all { it == '\t' || it.code in 32..126 }) { "请求头的值不能有回车、换行或其他控制字符；中文请先编码" }
    }
}

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
            val array = JSONArray(text)
            (0 until array.length()).map { i -> array.getJSONObject(i).let { HeaderEntry(it.getString("name"), it.getString("value")) } }
                .also(::validateHeaders)
        }.orEmpty()
    } catch (_: Exception) { throw IllegalStateException("请求头安全读取失败，请重新保存或恢复备份") }

    fun save(id: String, headers: List<HeaderEntry>) {
        validateHeaders(headers)
        if (headers.isEmpty()) remove("headers-$id") else write("headers-$id", JSONArray(headers.map {
            JSONObject().put("name", it.name).put("value", it.value)
        }).toString())
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
