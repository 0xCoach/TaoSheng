package app.resound.auth

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json

/**
 * 统一授权门禁：所有受保护入口（录音/个人数据/AI 分析/导出/Agent/MCP/通知/磁贴/Widget/分享/Worker）
 * 在动作前调用 require()；UI 层用 requireOrPrompt() 拦下后弹登录、授权成功自动回到原动作。
 * 判定依据 = 已验签凭证 + 绑定关系 + 时刻；网络只用于登录/刷新，不影响判定。
 */
class AuthGate(
    private val store: AuthStore,
    private val api: AuthApi,
    private val now: () -> Long = { System.currentTimeMillis() / 1000 },
) {
    private val json = Json { ignoreUnknownKeys = true }

    /** 待恢复的受保护动作（登录成功后执行一次）。 */
    private var pendingAction: (() -> Unit)? = null

    private val _loginRequested = MutableStateFlow<LoginRequest?>(null)
    /** UI 观察：非空即弹登录。 */
    val loginRequested = _loginRequested.asStateFlow()

    data class LoginRequest(val capability: Capability, val reason: String)

    /** 界面展示用状态（游客/已登录/已授权/过期/停用/锁定）。 */
    val status = store.status

    /** 界面主动请求登录（如「我的账号」页的登录按钮）：只弹面板，无待恢复动作。 */
    fun requestLogin(cap: Capability, reason: String) { _loginRequested.value = LoginRequest(cap, reason) }

    /** 登出/失效时立即停止录音与敏感后台任务——由 AppGraph 装配时挂上 CaptureController。 */
    var onSessionCleared: (() -> Unit)? = null

    /** 数据层/服务层门禁：同步、无副作用。 */
    fun require(cap: Capability): AuthDecision {
        val lic = store.licensePayload
        return AuthGateRules.decide(cap, lic, store.boundAccountId, store.sessionAccountId, store.deviceId, now())
            .let { d ->
                // 账号停用由远端状态直接判禁
                if (store.remoteStatus == "disabled" && store.sessionAccountId != null)
                    AuthDecision(false, AuthStatus.Disabled, "账号已被停用")
                else d
            }
    }

    /** 快捷判断（服务/Worker/后台路径用）。 */
    fun allowed(cap: Capability): Boolean = require(cap).allowed

    /** UI 门禁：通过返回 true；未通过则登记待恢复动作并请求登录。 */
    fun requireOrPrompt(cap: Capability, action: () -> Unit): Boolean {
        val d = require(cap)
        if (d.allowed) return true
        if (d.status != AuthStatus.Disabled && d.status != AuthStatus.Expired) pendingAction = action
        else pendingAction = null
        _loginRequested.value = LoginRequest(cap, d.reason)
        return false
    }

    /** 登录面板关闭（取消）：清待恢复动作与请求。 */
    fun dismissLogin() { pendingAction = null; _loginRequested.value = null }

    /** 登录/刷新成功后：恢复被拦动作。 */
    fun afterAuthSuccess() {
        _loginRequested.value = null
        val a = pendingAction; pendingAction = null; a?.invoke()
    }

    /** 验签并保存授权凭证；验签失败返回 false。 */
    fun acceptLicense(payloadRaw: String, sigB64: String): Boolean {
        if (!LicenseVerifier.verify(payloadRaw.toByteArray(Charsets.UTF_8), sigB64)) return false
        val payload = runCatching { json.decodeFromString(LicensePayload.serializer(), payloadRaw) }.getOrNull() ?: return false
        store.updateLicense(payload, store.accessToken, store.refreshToken)
        return true
    }

    /** 凭证验签+登录信息一起存（登录路径）。 */
    fun acceptLogin(session: AuthApi.ParsedSession): Boolean {
        if (!LicenseVerifier.verify(session.licenseRaw.toByteArray(Charsets.UTF_8), session.licenseSig)) return false
        val payload = runCatching { json.decodeFromString(LicensePayload.serializer(), session.licenseRaw) }.getOrNull() ?: return false
        store.saveLogin(session.accountId, session.username, session.access, session.refresh, payload)
        return true
    }

    /** 登录：网络/密码错/需激活/停用分别归类；成功后恢复被拦动作由 UI 层调 afterAuthSuccess。 */
    suspend fun login(username: String, password: String, deviceName: String, activationCode: String = ""): LoginOutcome {
        val r = api.login(username, password, store.deviceId, deviceName, activationCode)
        return when (r) {
            is AuthApi.Result.Ok -> {
                val s = api.parseSession(r.body)
                if (s != null && acceptLogin(s)) LoginOutcome.Ok else LoginOutcome.Error("授权凭证验签失败")
            }
            is AuthApi.Result.Denied -> when {
                r.code == 401 -> LoginOutcome.BadCredentials
                r.code == 403 && r.message.contains("disabled") -> LoginOutcome.Disabled
                r.code == 403 -> LoginOutcome.NeedActivation
                r.code == 429 -> LoginOutcome.RateLimited
                else -> LoginOutcome.Error(r.message)
            }
            is AuthApi.Result.Network -> LoginOutcome.Offline
        }
    }

    sealed class LoginOutcome {
        object Ok : LoginOutcome()
        object BadCredentials : LoginOutcome()
        object NeedActivation : LoginOutcome()
        object Disabled : LoginOutcome()
        object RateLimited : LoginOutcome()
        object Offline : LoginOutcome()
        data class Error(val msg: String) : LoginOutcome()
    }

    /** 联网刷新一次：网络失败不降级现有凭证（离线授权照常到期前可用）。 */
    suspend fun refreshNow(): RefreshOutcome {
        val refresh = store.refreshToken
        if (refresh.isEmpty()) return RefreshOutcome.NoSession
        return when (val r = api.refresh(refresh)) {
            is AuthApi.Result.Ok -> {
                val s = api.parseSession(r.body)
                if (s != null && acceptLicense(s.licenseRaw, s.licenseSig)) {
                    store.updateLicense(
                        json.decodeFromString(LicensePayload.serializer(), s.licenseRaw), s.access, s.refresh)
                    store.remoteStatus = "active"; store.refreshStatus()
                    RefreshOutcome.Refreshed
                } else RefreshOutcome.Failed
            }
            is AuthApi.Result.Denied -> {
                if (r.code in 401..403) { store.invalidate(); onSessionCleared?.invoke(); RefreshOutcome.SessionGone }
                else RefreshOutcome.Failed
            }
            is AuthApi.Result.Network -> RefreshOutcome.Offline
        }
    }

    /** 联网核对账号状态（停用/撤销即时生效）。 */
    suspend fun checkStatus(): RefreshOutcome {
        val access = store.accessToken
        if (access.isEmpty()) return RefreshOutcome.NoSession
        return when (val r = api.status(access)) {
            is AuthApi.Result.Ok -> {
                store.remoteStatus = "active"; store.refreshStatus(); RefreshOutcome.Refreshed
            }
            is AuthApi.Result.Denied -> {
                if (r.code in 401..403) { store.remoteStatus = "disabled"; store.refreshStatus(); onSessionCleared?.invoke(); RefreshOutcome.SessionGone }
                else RefreshOutcome.Failed
            }
            is AuthApi.Result.Network -> RefreshOutcome.Offline
        }
    }

    suspend fun logout() {
        val refresh = store.refreshToken
        if (refresh.isNotEmpty()) runCatching { api.logout(refresh) }
        store.logout()
        onSessionCleared?.invoke()
    }

    enum class RefreshOutcome { Refreshed, NoSession, SessionGone, Offline, Failed }
}
