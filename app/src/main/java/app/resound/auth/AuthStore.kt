package app.resound.auth

import android.content.Context
import app.resound.digest.SecretStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json

/**
 * 登录态持久化：token 走 Keystore AES-GCM（SecretStore），凭证/账号/绑定走普通 prefs。
 * 启动即恢复；状态变化即写盘，进程重启后门禁判定仍成立。
 */
class AuthStore(context: Context) {
    private val prefs = context.getSharedPreferences("auth", Context.MODE_PRIVATE)
    private val secrets = SecretStore(context, "auth-secrets", "auth-v1")
    private val json = Json { ignoreUnknownKeys = true }

    val deviceId: String = prefs.getString("device_id", null)
        ?: java.util.UUID.randomUUID().toString().also { prefs.edit().putString("device_id", it).apply() }

    var serverUrl: String
        get() = prefs.getString("server_url", "") ?: ""
        set(v) { prefs.edit().putString("server_url", v.trim().trimEnd('/')).apply() }

    var sessionAccountId: String?
        get() = prefs.getString("account_id", null)?.takeIf { it.isNotEmpty() }
        private set(v) { prefs.edit().putString("account_id", v ?: "").apply() }

    var username: String?
        get() = prefs.getString("username", null)?.takeIf { it.isNotEmpty() }
        private set(v) { prefs.edit().putString("username", v ?: "").apply() }

    /** 本机数据归属账号：首个登录账号确认迁移后写入；写死后不可被其他账号接管。 */
    var boundAccountId: String?
        get() = prefs.getString("bound_account_id", null)?.takeIf { it.isNotEmpty() }
        private set(v) { prefs.edit().putString("bound_account_id", v ?: "").apply() }

    var accessToken: String
        get() = if (secrets.has("access")) secrets.read("access") else ""
        private set(v) { if (v.isEmpty()) secrets.remove("access") else secrets.write("access", v) }

    var refreshToken: String
        get() = if (secrets.has("refresh")) secrets.read("refresh") else ""
        private set(v) { if (v.isEmpty()) secrets.remove("refresh") else secrets.write("refresh", v) }

    /** 验签通过才入库的凭证；签名串本身也存（供排查，不含密钥）。 */
    var licensePayload: LicensePayload?
        get() = prefs.getString("license_payload", null)?.let { runCatching { json.decodeFromString<LicensePayload>(it) }.getOrNull() }
        private set(v) { prefs.edit().putString("license_payload", v?.let { json.encodeToString(LicensePayload.serializer(), it) }).apply() }

    /** 服务端状态快照：active/disabled（联网刷新成功时更新）。 */
    var remoteStatus: String
        get() = prefs.getString("remote_status", "active") ?: "active"
        set(v) { prefs.edit().putString("remote_status", v).apply() }

    /** 隐私锁。 */
    var privacyLockEnabled: Boolean
        get() = prefs.getBoolean("privacy_lock", false)
        set(v) { prefs.edit().putBoolean("privacy_lock", v).apply() }
    var lockAfterMinutes: Int
        get() = prefs.getInt("lock_after_min", 5)
        set(v) { prefs.edit().putInt("lock_after_min", v).apply() }
    var lockedUntilNextUnlock: Boolean
        get() = prefs.getBoolean("locked", false)
        set(v) { prefs.edit().putBoolean("locked", v).apply() }
    var lastBackgroundAt: Long
        get() = prefs.getLong("bg_at", 0L)
        set(v) { prefs.edit().putLong("bg_at", v).apply() }

    private val _status = MutableStateFlow(AuthStatus.Guest)
    val status = _status.asStateFlow()

    /** 重算状态（登录/登出/刷新后调用）。 */
    fun refreshStatus(nowS: Long = System.currentTimeMillis() / 1000) {
        _status.value = when {
            remoteStatus == "disabled" && sessionAccountId != null -> AuthStatus.Disabled
            lockedUntilNextUnlock && privacyLockEnabled -> AuthStatus.Locked
            else -> AuthGateRules.displayStatus(licensePayload, sessionAccountId, nowS)
        }
    }

    fun saveLogin(accountId: String, name: String, access: String, refresh: String, license: LicensePayload) {
        sessionAccountId = accountId; username = name
        accessToken = access; refreshToken = refresh; licensePayload = license
        remoteStatus = "active"; refreshStatus()
    }

    fun updateLicense(license: LicensePayload, access: String, refresh: String) {
        accessToken = access; refreshToken = refresh; licensePayload = license; refreshStatus()
    }

    /** 绑定本机数据到当前账号（首个登录账号经确认后调用）。 */
    fun bindCurrent(): Boolean {
        val id = sessionAccountId ?: return false
        if (boundAccountId == null) { boundAccountId = id; return true }
        return boundAccountId == id
    }

    /** 登出：清会话与凭证；不删本地数据、不动绑定。 */
    fun logout() {
        sessionAccountId = null; username = null
        accessToken = ""; refreshToken = ""; licensePayload = null
        remoteStatus = "active"; lockedUntilNextUnlock = false
        refreshStatus()
    }

    /** 账号被停用/凭证失效时清理会话（保留绑定信息供说明）。 */
    fun invalidate() {
        sessionAccountId = null; accessToken = ""; refreshToken = ""; licensePayload = null
        refreshStatus()
    }
}
