package app.resound.auth

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** 统一身份状态：游客 / 已登录 / 已授权 / 授权失效 / 账号停用 / 本地隐私锁。 */
enum class AuthStatus { Guest, Authenticated, Authorized, Expired, Disabled, Locked }

/** 服务端签发的功能能力；与 worker.js 中 CAPS 字符串一一对应。 */
enum class Capability(val wire: String) {
    RecordAudio("RECORD_AUDIO"),
    ReadPersonalData("READ_PERSONAL_DATA"),
    AiAnalysis("AI_ANALYSIS"),
    ExportBackup("EXPORT_BACKUP"),
    AgentAccess("AGENT_ACCESS"),
    McpAccess("MCP_ACCESS"),
    ;
    companion object { fun of(wire: String): Capability? = entries.firstOrNull { it.wire == wire } }
}

/** 服务端 ECDSA 签名的离线授权凭证载荷（与 worker.js signLicense 的 payload 字段一致）。 */
@Serializable
data class LicensePayload(
    val v: Int,
    val sub: String,          // 账号 id
    val name: String,         // 用户名
    val dev: String,          // 设备 id
    val caps: List<String>,
    /** 真实 Pro 到期（秒）：会员权益不被凭证有效期延长；null=无 Pro/旧凭证；-1=不限期 */
    @SerialName("pro_until") val proUntil: Long? = null,
    val iat: Long,            // 签发（秒）
    val exp: Long,            // 到期（秒）
)

data class AuthDecision(val allowed: Boolean, val status: AuthStatus, val reason: String = "")

/** 门禁判定纯逻辑：输入本地凭证快照与时刻，输出可否执行某能力。与网络无关，可单测。 */
object AuthGateRules {

    /**
     * @param license 已验签通过的授权凭证（验签失败时调用方应传 null）
     * @param boundAccountId 本机数据已绑定的账号（null=未绑定）
     * @param sessionAccountId 当前登录会话账号（null=游客/未登录）
     * @param nowS 当前秒
     */
    fun decide(
        cap: Capability,
        license: LicensePayload?,
        boundAccountId: String?,
        sessionAccountId: String?,
        deviceId: String,
        nowS: Long,
    ): AuthDecision {
        // 数据归属隔离：已绑定的本地数据只允许绑定账号访问。
        if (boundAccountId != null && sessionAccountId != null && boundAccountId != sessionAccountId) {
            return AuthDecision(false, AuthStatus.Disabled, "本机数据已绑定其他账号，请切回或先处理数据归属")
        }
        if (license == null) {
            return AuthDecision(false, if (sessionAccountId == null) AuthStatus.Guest else AuthStatus.Expired,
                if (sessionAccountId == null) "需要先登录" else "没有有效授权凭证，请联网刷新")
        }
        if (license.dev != deviceId) return AuthDecision(false, AuthStatus.Expired, "授权凭证不属于本设备")
        if (sessionAccountId != null && license.sub != sessionAccountId)
            return AuthDecision(false, AuthStatus.Disabled, "授权凭证与登录账号不一致")
        if (license.exp < nowS) return AuthDecision(false, AuthStatus.Expired, "离线授权已过期，请联网续期")
        if (cap.wire !in license.caps) return AuthDecision(false, AuthStatus.Authenticated, "账号未被授予此项能力")
        return AuthDecision(true, if (license.exp >= nowS) AuthStatus.Authorized else AuthStatus.Authenticated)
    }

    /** 登录后凭证到期前的有效状态（供界面显示）。 */
    fun displayStatus(license: LicensePayload?, sessionAccountId: String?, nowS: Long): AuthStatus = when {
        sessionAccountId == null -> AuthStatus.Guest
        license == null -> AuthStatus.Authenticated
        license.exp < nowS -> AuthStatus.Expired
        else -> AuthStatus.Authorized
    }
}
