package app.resound.auth

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json

/**
 * 统一授权门禁：所有受保护入口（录音/个人数据/AI 分析/导出/Agent/MCP/通知/磁贴/Widget/分享/Worker）
 * 在动作前调用 require()；UI 层用 requireOrPrompt() 拦下后弹登录、授权成功自动回到原动作。
 * 判定依据 = 已验签凭证 + 绑定关系 + 时刻；网络只用于登录/刷新，不影响判定。
 */
class AuthGate(
    val store: AuthStore,
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
        // 未登录 = 免费试用：本机数据未绑定时，累计录音 GUEST_TRIAL_MS 内放行；
        // 试用耗尽后一切受保护能力转拒绝——数据跟账号走，不存在"手机主人"这个永久身份。
        if (store.boundAccountId == null && store.sessionAccountId == null) {
            return if (store.guestTrialUsedMs < GUEST_TRIAL_MS)
                AuthDecision(true, AuthStatus.Authorized, "免费试用中")
            else AuthDecision(false, AuthStatus.Guest, "免费试用已用完（录音 ${GUEST_TRIAL_MS / 60000} 分钟），注册/登录后每月免费 1800 分钟")
        }
        // 有数据未绑定但已登录账号：必须通过设备凭据验证机主身份——登录不等于本机数据所有者。
        if (store.boundAccountId == null && store.sessionAccountId != null && store.hasLocalData && !store.ownerVerified)
            return AuthDecision(false, AuthStatus.Expired, "本机数据待归属确认：验证机主身份后绑定")
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

    /** 归属类硬阻断：待确认(已登录+有数据+未绑定+未验机主) 或 数据已归他号——重弹登录面板也解决不了。 */
    private fun ownershipIssue() = when {
        store.boundAccountId == null && store.sessionAccountId != null &&
            store.hasLocalData && !store.ownerVerified -> true
        store.boundAccountId != null && store.boundAccountId != store.sessionAccountId -> true
        else -> false
    }

    /** UI 门禁：通过返回 true；未通过则登记待恢复动作并请求登录。 */
    fun requireOrPrompt(cap: Capability, action: () -> Unit): Boolean {
        val d = require(cap)
        if (d.allowed) return true
        // 归属问题被"暂不"过：冷却期内不再自动弹（账号页仍保留绑定/隔离说明），动作照常拒绝。
        if (ownershipIssue() && System.currentTimeMillis() - store.bindDeclinedAt < BIND_DECLINE_COOLDOWN_MS) return false
        if (d.status != AuthStatus.Disabled && d.status != AuthStatus.Expired) pendingAction = action
        else pendingAction = null
        _loginRequested.value = LoginRequest(cap, d.reason)
        return false
    }

    /** 登录面板关闭（取消/暂不）：清待恢复动作与请求；归属确认场景记冷却起点。 */
    fun dismissLogin() {
        pendingAction = null; _loginRequested.value = null
        if (ownershipIssue()) store.bindDeclinedAt = System.currentTimeMillis()
    }

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
        // 空设备（无历史数据）：登录即绑定，账号即机主；有数据则必须走确认归属。
        if (store.boundAccountId == null && !store.hasLocalData) store.bindCurrent()
        return true
    }

    sealed class BindOutcome {
        object Bound : BindOutcome()               // 绑定成功，归属生效
        object AlreadyBound : BindOutcome()        // 已归属本账号
        object BoundToOther : BindOutcome()        // 已归属其他账号——拒绝接管
        object NotLoggedIn : BindOutcome()
        /** 没有任何可用备份通道（本机文件夹未选、云端未配置）——先解决备份再绑定 */
        object BackupUnavailable : BindOutcome()
        /** 备份任务未在时限内成功——归属变更被拒绝，数据原样保留 */
        object BackupFailed : BindOutcome()
    }

    /**
     * 确认将本机历史数据归属到当前登录账号（数据层绑定，唯一入口）。
     * 硬门槛：必须先有一份真实成功的备份（本机文件夹或云端任一），且能验证完成；
     * 备份不可用时拒绝绑定——归属一旦绑定，其他账号立刻被隔离，出错没有回头路。
     */
    suspend fun bindLocalData(): BindOutcome {
        if (store.sessionAccountId == null) return BindOutcome.NotLoggedIn
        val bound = store.boundAccountId
        if (bound != null) return if (bound == store.sessionAccountId) BindOutcome.AlreadyBound else BindOutcome.BoundToOther
        if (!app.resound.AppGraph.isBackupReady) return BindOutcome.BackupUnavailable
        val backup = app.resound.AppGraph.backup
        val startedAt = System.currentTimeMillis()
        // 已有成功备份就直接用；否则触发本机+云端两条路，等任一新备份落盘。
        val local = backup.local.value
        val cloud = backup.state.value
        val hasUsableBackup = (local.lastAt != null && local.lastError == null && local.folderAccess != app.resound.backup.LocalFolderAccess.Unsupported) ||
                (cloud.lastBackupAt != null && cloud.lastError == null)
        if (!hasUsableBackup) {
            if (local.folder == null && !(cloud.hasPassword || cloud.account != null)) return BindOutcome.BackupUnavailable
            runCatching { backup.backupLocalNow() }
            runCatching { backup.backupNow() }
            val deadline = startedAt + 90_000L
            var ok = false
            while (System.currentTimeMillis() < deadline) {
                val l = backup.local.value; val c = backup.state.value
                if (((l.lastAt ?: 0L) > startedAt && l.lastError == null) ||
                        ((c.lastBackupAt ?: 0L) > startedAt && c.lastError == null)) { ok = true; break }
                kotlinx.coroutines.delay(1_000)
            }
            if (!ok) return BindOutcome.BackupFailed
        }
        return if (store.bindCurrent()) { store.refreshStatus(); BindOutcome.Bound } else BindOutcome.BoundToOther
    }

    /** 设备凭据验证通过（由 UI 层在完成系统解锁验证后调用）。 */
    fun markOwnerVerified() { store.ownerVerified = true }

    /**
     * 带令牌的调用统一入口：access 过期(15min TTL)时静默用 refresh 续期并重试一次；
     * refresh 也被拒则 invalidate 会话。各业务调用不再各自处理令牌老化。
     */
    /** 轮换式 refresh token 是一次性的：并发 authed 调用必须串行刷新，否则后到者拿已轮换的旧票换票被拒→误登出。 */
    private val refreshMutex = Mutex()

    private suspend fun authed(call: suspend (String) -> AuthApi.Result): AuthApi.Result? {
        val token = store.accessToken
        if (token.isEmpty()) return null
        val r = call(token)
        if (r is AuthApi.Result.Denied && r.code == 401 && refreshIfNeeded(token) == RefreshOutcome.Refreshed) {
            return store.accessToken.takeIf { it.isNotEmpty() && it != token }?.let { call(it) } ?: r
        }
        return r
    }

    /** 401 触发的去重刷新：锁内再看一眼——并发者若已换到新令牌直接复用，只有持票者真正发 /auth/refresh。 */
    private suspend fun refreshIfNeeded(failedToken: String): RefreshOutcome = refreshMutex.withLock {
        if (store.accessToken != failedToken && store.accessToken.isNotEmpty()) RefreshOutcome.Refreshed
        else refreshLocked()
    }

    /** 登录账号可见的设备会话列表（设备管理）。 */
    suspend fun devices(): AuthApi.Result? = authed { api.devices(it) }

    /** 撤销一个设备会话（踢下线）。 */
    suspend fun revokeDevice(sessionId: String): AuthApi.Result? = authed { api.revoke(sessionId, it) }

    /** 会员视图（套餐/能力/本月用量）。 */
    suspend fun membership(): AuthApi.Result? = authed { api.membership(it) }

    /** 用量上报（服务端按 id 去重）。 */
    suspend fun reportUsage(items: List<AuthApi.UsageItem>): AuthApi.Result? = authed { api.reportUsage(items, it) }

    /**
     * 兑换码核销。request_id 取「账号+规范化码」的确定性值：
     * 同一账号对同一码的重试天然幂等，响应丢失后重试不会重复发权益。
     */
    suspend fun redeem(code: String): RedeemOutcome {
        if (store.accessToken.isEmpty()) return RedeemOutcome.NeedLogin
        val norm = code.uppercase().filter { it.isLetterOrDigit() }
        if (norm.length < 8) return RedeemOutcome.Invalid
        val requestId = "r:${store.sessionAccountId}:$norm".let {
            java.security.MessageDigest.getInstance("SHA-256").digest(it.toByteArray())
                .joinToString("") { b -> "%02x".format(b) }.take(32)
        }
        return when (val r = authed { api.redeem(code, requestId, it) }) {
            is AuthApi.Result.Ok -> RedeemOutcome.Ok(
                grantedDays = r.body["granted_days"]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull() } ?: 0,
                proUntil = r.body["pro_until"]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content?.toLongOrNull() },
                duplicated = r.body["duplicated"]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content == "true" } == true,
            )
            is AuthApi.Result.Denied -> when (r.code) {
                401 -> RedeemOutcome.NeedLogin
                404 -> RedeemOutcome.Invalid
                else -> RedeemOutcome.Rejected(r.message)
            }
            is AuthApi.Result.Network -> RedeemOutcome.Offline
            null -> RedeemOutcome.NeedLogin
        }
    }

    sealed class RedeemOutcome {
        data class Ok(val grantedDays: Int, val proUntil: Long?, val duplicated: Boolean) : RedeemOutcome()
        object Invalid : RedeemOutcome()
        data class Rejected(val msg: String) : RedeemOutcome()
        object Offline : RedeemOutcome()
        object NeedLogin : RedeemOutcome()
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

    /** 联网刷新一次：网络失败不降级现有凭证（离线授权照常到期前可用）。入口串行化，防并发双刷。 */
    suspend fun refreshNow(): RefreshOutcome = refreshMutex.withLock { refreshLocked() }

    private suspend fun refreshLocked(): RefreshOutcome {
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

    companion object {
        /** 归属确认"暂不"后的静默期：期内被拦动作只拒绝、不再弹窗（账号页入口仍在）。 */
        const val BIND_DECLINE_COOLDOWN_MS = 24L * 60 * 60 * 1000
        /** 游客免费试用额度：累计录音 10 分钟，用完必须注册授权。 */
        const val GUEST_TRIAL_MS = 10L * 60 * 1000
    }
}
