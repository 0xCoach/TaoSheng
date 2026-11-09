package app.resound.backup

import android.content.SharedPreferences
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject

/** 不解析任意域名来放行 HTTP：只接受指定私网字面地址和 .local。 */
internal object CloudAddress {
    fun parse(value: String): HttpUrl {
        val url = value.toHttpUrlOrNull() ?: throw IllegalArgumentException("云端地址格式不正确")
        require(url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null) { "地址不能含账号、密码、查询参数或片段" }
        val parts = url.host.split('.')
        val octets = parts.mapNotNull { it.toIntOrNull()?.takeIf { n -> n in 0..255 } }
        val privateIp = parts.size == 4 && octets.size == 4 && (octets[0] == 10 || octets[0] == 172 && octets[1] in 16..31 || octets[0] == 192 && octets[1] == 168)
        require(url.isHttps || privateIp || url.host.endsWith(".local")) { "请使用 HTTPS；HTTP 只允许局域网 IP 或 .local 地址" }
        return url
    }
    fun s3(settings: S3Settings): S3Settings {
        val url = parse(settings.endpoint.trim())
        require(url.encodedPath == "/") { "S3 服务地址不要含桶名或目录，请单独填写桶和前缀" }
        require(settings.region.matches(Regex("[a-z0-9-]+"))) { "请填写正确的 S3 区域" }
        require(settings.bucket.matches(Regex("[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]")) && ".." !in settings.bucket) { "请填写正确的桶名" }
        val prefix = settings.prefix.trim().trim('/')
        require(prefix.isEmpty() || prefix.split('/').all { it.isNotBlank() && it != "." && it != ".." && it.none { c -> c.isISOControl() || c == '\\' } }) { "S3 前缀须为相对目录" }
        require(settings.pathStyle || url.host != settings.bucket && !url.host.startsWith(settings.bucket + ".")) { "服务地址不要重复填写桶名" }
        return settings.copy(endpoint = url.toString().trimEnd('/'), prefix = if (prefix.isEmpty()) "" else "$prefix/")
    }
}

internal interface CloudSecrets {
    fun has(key: String): Boolean
    fun read(key: String): String
    fun write(key: String, value: String)
    fun remove(key: String)
}

/** 每种存储各留一套设置；沿用旧 account/password，升级无需写入或重新登录。 */
internal class CloudSettings(private val prefs: SharedPreferences, private val secrets: CloudSecrets) {
    val kind: CloudKind get() = prefs.getString("cloud", null)?.let { CloudKind.valueOf(it) } ?: CloudKind.Nutstore
    private fun accountKey(kind: CloudKind) = when (kind) { CloudKind.Nutstore -> "account"; CloudKind.WebDav -> "webDavAccount"; CloudKind.S3 -> "s3Account" }
    private fun secretKey(kind: CloudKind) = when (kind) { CloudKind.Nutstore -> "password"; CloudKind.WebDav -> "webDavPassword"; CloudKind.S3 -> "s3Secret" }
    val account: String? get() = prefs.getString(accountKey(kind), null)
    val hasSecret: Boolean get() = secrets.has(secretKey(kind))
    val webDavUrl: String? get() = prefs.getString("webDavUrl", null)
    val s3: S3Settings? get() = prefs.getString("s3", null)?.let(::s3FromJson)
    fun password(): String = try { secrets.read(secretKey(kind)) } catch (_: Exception) { throw BackupFailure("云端密钥读取失败，请重新填写") }
    fun save(kind: CloudKind, account: String, secret: String?, webDavUrl: String?, s3: S3Settings?) {
        val login = account.trim()
        require(login.isNotBlank() && login.none { it.isISOControl() } && ':' !in login) { "请填写正确的账号或访问密钥 ID" }
        if (kind == CloudKind.Nutstore) require('@' in login) { "请填写坚果云邮箱" }
        val endpoint = if (kind == CloudKind.WebDav) CloudAddress.parse(requireNotNull(webDavUrl) { "请填写 WebDAV 地址" }.trim()).toString() else null
        val settings = if (kind == CloudKind.S3) CloudAddress.s3(requireNotNull(s3) { "请填写 S3 设置" }) else null
        require(secret == null || secret.isNotBlank()) { "请填写密码或私有访问密钥" }
        require(secret != null || secrets.has(secretKey(kind))) { "请先填写该存储的密码或私有访问密钥" }
        // 所有校验通过才写安全区；不向普通 preferences 写入明文。
        if (secret != null) secrets.write(secretKey(kind), secret)
        val changed = this.kind != kind || prefs.getString(accountKey(kind), null) != login ||
            kind == CloudKind.WebDav && this.webDavUrl != endpoint || kind == CloudKind.S3 && this.s3 != settings
        check(prefs.edit().apply {
            putString("cloud", kind.name).putString(accountKey(kind), login).remove("error")
            if (endpoint != null) putString("webDavUrl", endpoint)
            if (settings != null) putString("s3", s3Json(settings).toString())
            if (changed) remove("lastAt").remove("lastBytes").remove("count")
        }.commit())
    }
    fun clear() {
        secrets.remove(secretKey(kind))
        check(prefs.edit().remove(accountKey(kind)).putBoolean("enabled", false).remove("error")
            .remove("lastAt").remove("lastBytes").remove("count").commit())
    }
    fun credentials(nutstore: BackupConfig = BackupConfig()): Map<String, String>? {
        val account = account?.takeIf { it.isNotBlank() } ?: return null
        val password = password().takeIf { it.isNotBlank() } ?: return null
        return mapOf("account" to account, "password" to password) + when (kind) {
            CloudKind.Nutstore -> mapOf("url" to nutstore.endpoint)
            CloudKind.WebDav -> mapOf("url" to CloudAddress.parse(webDavUrl ?: throw BackupFailure("请填写 WebDAV 地址")).toString())
            CloudKind.S3 -> mapOf("cloudKind" to "S3", "s3" to s3Json(CloudAddress.s3(s3 ?: throw BackupFailure("请填写 S3 设置"))).toString(), "url" to s3!!.endpoint)
        }
    }
    fun remote(config: BackupConfig): RemoteStore {
        val values = credentials(config) ?: throw BackupFailure("请先填写当前云端存储的账号和密钥")
        return if (kind == CloudKind.S3) S3Client(s3!!, values.getValue("account"), values.getValue("password"))
        else WebDavClient(BackupConfig(values.getValue("url"), if (kind == CloudKind.Nutstore) "坚果云" else "WebDAV"), "", values.getValue("account"), values.getValue("password"))
    }
}

internal fun s3Json(s: S3Settings): JSONObject = JSONObject().put("presetId", s.presetId).put("endpoint", s.endpoint)
    .put("region", s.region).put("bucket", s.bucket).put("prefix", s.prefix).put("pathStyle", s.pathStyle)
internal fun s3FromJson(value: String): S3Settings = JSONObject(value).let {
    S3Settings(it.getString("presetId"), it.getString("endpoint"), it.getString("region"), it.getString("bucket"), it.getString("prefix"), it.getBoolean("pathStyle"))
}
