package app.resound.auth

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.security.MessageDigest

/**
 * 官方 AI 入口：同意状态、额度视图、点数制生成。
 * 服务商/模型/密钥只存在服务端，本类不返回也不接触这些字段；
 * 令牌统一走 AuthGate 串行刷新通道，官方任务用账号 access token 而非用户自填密钥。
 */
class OfficialAi(context: Context, private val gate: AuthGate, private val store: AuthStore) {
    private val prefs = context.getSharedPreferences("official-ai", Context.MODE_PRIVATE)
    private val consentMutex = Mutex()

    /** 最近一次服务端回执的状态；网络失败或登出时保持旧值，不假装可用。 */
    private val _status = MutableStateFlow<OfficialStatus?>(null)
    private var statusAccountId: String? = null
    val status: StateFlow<OfficialStatus?> = _status.asStateFlow()

    /** 状态变化回调（AppGraph 用它刷新服务商条目的可用标记）。 */
    var onStatusChanged: (OfficialStatus?) -> Unit = {}

    data class OfficialStatus(
        val enabled: Boolean, val configured: Boolean, val consented: Boolean,
        val plan: String, val remaining: Long)

    /** 本地判定：已登录+服务开启+已同意即可发起；服务端仍是最终裁决（额度/权限以回执为准）。 */
    fun ready(): Boolean = !store.sessionAccountId.isNullOrEmpty() &&
        statusAccountId == store.sessionAccountId &&
        status.value?.let { it.enabled && it.configured && it.consented } == true

    fun clearStatus() {
        statusAccountId = null
        _status.value = null
        onStatusChanged(null)
    }

    suspend fun refreshStatus(): OfficialStatus? {
        val account = store.sessionAccountId ?: run { clearStatus(); return null }
        val result = gate.officialStatus()
        if (store.sessionAccountId != account) return null
        return when (result) {
            is AuthApi.Result.Ok -> parseStatus(result.body).also {
                statusAccountId = account
                _status.value = it
                onStatusChanged(it)
            }
            else -> null
        }
    }

    suspend fun quota(): AuthApi.Result? = gate.officialQuota()
    suspend fun usage(limit: Int = 20): AuthApi.Result? = gate.officialUsage(limit)
    suspend fun estimate(taskType: String, chars: Int): AuthApi.Result? = gate.officialEstimate(taskType, chars)

    /** 用户明确同意后调用（设置页勾选/弹窗确认）；服务端与本地都记录版本。 */
    suspend fun consent(version: Int = CONSENT_VERSION): AuthApi.Result? = consentMutex.withLock {
        val account = store.sessionAccountId ?: return@withLock null
        val r = gate.officialConsent(version)
        if (r is AuthApi.Result.Ok && store.sessionAccountId == account) {
            prefs.edit().putInt("consentVersion", version).putString("consentAccountId", account).apply()
            val cur = status.value
            if (cur == null || statusAccountId != account) {
                // 登录时的状态拉取若失败（弱网/冷启动），同意后必须补拉一次，
                // 否则 ready() 永远 false、官方条目停在「同意后即可用」形成死锁。
                refreshStatus()
            } else {
                _status.value = cur.copy(consented = true)
                onStatusChanged(status.value)
            }
        }
        r
    }

    fun localConsented() = store.sessionAccountId != null &&
        prefs.getString("consentAccountId", null) == store.sessionAccountId &&
        prefs.getInt("consentVersion", 0) >= CONSENT_VERSION

    sealed class GenOutcome {
        data class Ok(val content: String, val points: Long, val remaining: Long?, val duplicated: Boolean) : GenOutcome()
        /** 服务端业务拒绝：code 为 CONSENT_REQUIRED / QUOTA_EXHAUSTED / PROVIDER_UNAVAILABLE / RATE_LIMITED / TASK_FAILED / REQUEST_TOO_LARGE / UNKNOWN_PROVIDER_OUTCOME 等。 */
        data class Denied(val httpCode: Int, val code: String) : GenOutcome()
        data class Network(val msg: String) : GenOutcome()
        object NeedLogin : GenOutcome()
    }

    /**
     * request_id = 账号+任务+内容散列：同内容重试/响应丢失重发命中服务端幂等，不重复扣点；
     * 用户主动重新生成时调用方传 nonce 区分，视为新任务。
     */
    suspend fun generate(taskType: String, system: String, user: String, maxOutput: Int = 0, nonce: String = ""): GenOutcome {
        if (store.accessToken.isEmpty()) return GenOutcome.NeedLogin
        val account = store.sessionAccountId ?: return GenOutcome.NeedLogin
        val r0 = send(taskType, system, user, maxOutput, nonce)
        if (store.sessionAccountId != account) return GenOutcome.NeedLogin
        // 本地同意过但服务端没记上（换设备/重安装）→ 补登记后重试一次。
        if (r0 is GenOutcome.Denied && r0.code == "CONSENT_REQUIRED" && localConsented() &&
            consent() is AuthApi.Result.Ok) return send(taskType, system, user, maxOutput, nonce)
        return r0
    }

    private suspend fun send(taskType: String, system: String, user: String, maxOutput: Int, nonce: String): GenOutcome {
        val account = store.sessionAccountId ?: return GenOutcome.NeedLogin
        val requestId = sha256(listOf(account, taskType, system, user, nonce).joinToString("|"))
        val result = gate.officialGenerate(requestId, taskType, system, user, maxOutput)
        if (store.sessionAccountId != account) return GenOutcome.NeedLogin
        return when (val r = result) {
            is AuthApi.Result.Ok -> {
                val bal = r.body["balance"] as? JsonObject
                GenOutcome.Ok(
                    content = r.body["content"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    points = r.body["points"]?.jsonPrimitive?.longOrNull ?: 0,
                    remaining = bal?.get("remaining")?.jsonPrimitive?.longOrNull,
                    duplicated = r.body["duplicated"]?.jsonPrimitive?.content == "true").also { out ->
                    if (store.sessionAccountId == account && statusAccountId == account && out.remaining != null) {
                        _status.value = status.value?.copy(remaining = out.remaining)
                        onStatusChanged(status.value)
                    }
                }
            }
            is AuthApi.Result.Denied -> if (r.code == 401) GenOutcome.NeedLogin else GenOutcome.Denied(r.code, r.message)
            is AuthApi.Result.Network -> GenOutcome.Network(r.message)
            null -> GenOutcome.NeedLogin
        }
    }

    private fun parseStatus(o: JsonObject) = OfficialStatus(
        enabled = (o["enabled"] as? JsonPrimitive)?.booleanOrNull == true,
        configured = (o["configured"] as? JsonPrimitive)?.booleanOrNull == true,
        consented = (o["consented"] as? JsonPrimitive)?.booleanOrNull == true,
        plan = o["plan"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        remaining = o["remaining"]?.jsonPrimitive?.longOrNull ?: 0)

    companion object {
        const val CONSENT_VERSION = 1
        private fun sha256(text: String) = MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }.take(48)
    }
}
