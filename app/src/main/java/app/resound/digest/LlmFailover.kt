package app.resound.digest

import android.content.Context
import app.resound.data.MemoryDao
import app.resound.usage.UsagePurpose
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.time.LocalDate

/** 仅保存安全的展示信息，不含密钥、原话、提示词或服务端错误正文。 */
data class LlmSwitch(val at: Long, val feature: String, val originalModel: String, val actualModel: String, val reason: String)

class LlmFailover(
    context: Context, private val providers: AndroidLlmProviders,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val diagnostics = app.resound.diagnostics.LlmDiagnostics(context)
    private val prefs = context.getSharedPreferences("digest", Context.MODE_PRIVATE)
    private val mutableEnabled = MutableStateFlow(prefs.getBoolean("failoverEnabled", true))
    val enabled = mutableEnabled.asStateFlow()
    private val recent = MutableStateFlow<LlmSwitch?>(null)
    val latestSwitch = recent.asStateFlow()
    private val cooling = mutableMapOf<String, FailoverCooling>()
    // 配置修改后，旧的在途请求不能再次把新配置放进冷却。
    private val generations = mutableMapOf<String, Long>()
    init {
        providers.listen { id -> if (id != null) clear(id) }
        providers.onTestSuccess = ::clear
    }
    /** 只投影模型名及固定原因，不导出配置 id、异常 detail 或凭证。 */
    internal fun coolingDiagnostics(): String {
        val names = providers.saved.value.associate { it.id to "${it.name} / ${it.model}" }
        return synchronized(this) {
            cooling.entries.joinToString("\n") { (id, state) ->
                "${names[id] ?: "已删除模型"} 冷却=${state.until > now()} 截止=${app.resound.diagnostics.LlmDiagnostics.stamp(state.until)} 原因=${state.reason}"
            }.ifBlank { "暂无" }
        }
    }

    fun setEnabled(value: Boolean) {
        check(prefs.edit().putBoolean("failoverEnabled", value).commit())
        mutableEnabled.value = value
    }
    @Synchronized fun clear(id: String) { cooling.remove(id); generations[id] = (generations[id] ?: 0) + 1 }
    fun hasBackup(primary: LlmPreset): Boolean = enabled.value && synchronized(providers.guard) {
        providers.saved.value.any { it.id != primary.id && (it.hasKey || it.preset().allowsEmptyKey() || providers.hasHeaderAuth(it.id)) }
    }
    private fun candidates(primary: LlmPreset, key: String, enabled: Boolean): List<FailoverCandidate> {
        val credentials = synchronized(providers.guard) {
            listOf(primary to key) + if (!enabled) emptyList() else providers.saved.value
                .filter { it.id != primary.id && (it.hasKey || it.preset().allowsEmptyKey() || providers.hasHeaderAuth(it.id)) }
                .map { it.preset() to providers.readKey(it.id) }
        }
        return synchronized(this) {
            credentials.map { FailoverCandidate(it.first, it.second, generations[it.first.id] ?: 0) }
        }
    }
    private fun reason(error: Exception): String = when (error) {
        is LlmInputLimitException -> "模型容量不足"
        is LlmException -> when (error.type) {
            LlmError.Auth -> "密钥无效"
            LlmError.Quota -> "余额不足或额度用完"
            LlmError.RateLimit -> "请求太频繁"
            LlmError.Network -> "网络连接失败"
            else -> "模型服务出错"
        }
        else -> "模型服务出错"
    }
    private val rules = FailoverRules(object : FailoverStore {
        override fun cooling(id: String) = synchronized(this@LlmFailover) { cooling[id] }
        override fun coolingSnapshot(ids: List<String>) = synchronized(this@LlmFailover) {
            ids.mapNotNull { id -> cooling[id]?.let { id to it } }.toMap()
        }
        override fun coolIfCurrent(id: String, generation: Long, value: FailoverCooling) = synchronized(this@LlmFailover) {
            if ((generations[id] ?: 0) == generation) cooling[id] = value
        }
    }, app.resound.platform.NowMillis(now), LlmInputBudget::inputChars,
        classify = { e -> when (e) {
            is LlmInputLimitException -> FailoverFailure(reason(e), capacity = true)
            is LlmException -> FailoverFailure(reason(e), format = e.type == LlmError.Format,
                rejected = e.rejected, longCooling = e.type in setOf(LlmError.Auth, LlmError.Quota))
            else -> null
        } },
        safeCoolingError = { e -> (e as LlmException).let { LlmException(it.type, it.status, it.detail) } },
        exhausted = { e, suffix -> when (e) {
            is LlmException -> LlmException(e.type, e.status, e.detail + suffix)
            is LlmInputLimitException -> LlmInputLimitException(e.message + suffix)
            else -> e
        } })

    private suspend fun <T> execute(feature: String, primary: LlmPreset, key: String,
        canSwitch: () -> Boolean = { true }, call: suspend (LlmPreset, String) -> T): Pair<T, LlmPreset> {
        val on = enabled.value && canSwitch()
        return rules.execute(primary, candidates(primary, key, on), on, canSwitch, switched = { p, reason ->
            recent.value = LlmSwitch(now(), feature, primary.name + " / " + primary.model, p.name + " / " + p.model, reason)
            recent.value?.let { diagnostics.switched(it.at, it.originalModel, it.actualModel, it.reason) }
        }) { candidate ->
            val p = candidate.preset
            val trace = runCatching { diagnostics.start(feature, p) }.getOrNull()
            if (trace == null) call(p, candidate.key) else {
                // 保持取消异常原对象，不让 withContext 复制异常。
                var thrown: Throwable? = null
                val value = withContext(trace) { try { call(p, candidate.key) } catch (e: Throwable) { thrown = e; null } }
                thrown?.let { trace.complete(it); throw it }
                @Suppress("UNCHECKED_CAST") (value as T)
            }
        }
    }

    /** 成功用量仍由调用方原事务记录；这里仅记录失败响应明确返回的用量。 */
    suspend fun chat(feature: String, primary: LlmPreset, key: String, system: String, user: String,
        request: suspend (LlmPreset, String, String, String) -> LlmReply,
        failedUsage: suspend (LlmReply) -> Unit = {},
    ): LlmReply {
        val (reply, actual) = execute(feature, primary, key) { p, secret ->
            try {
                if (secret.isBlank() && !p.allowsEmptyKey() && !providers.hasHeaderAuth(p.id)) throw LlmException(LlmError.Auth)
                if (system.length.toLong() + user.length > LlmInputBudget.inputChars(p)) throw LlmInputLimitException()
                request(p, secret, system, user)
            } catch (e: LlmException) {
                e.usage?.let { withContext(NonCancellable) { failedUsage(it.copy(actualModel = p.model)) } }
                throw e
            }
        }
        return reply.copy(actualModel = actual.model)
    }

    fun request(feature: String, dao: MemoryDao, purpose: UsagePurpose,
        call: suspend (LlmPreset, String, String, String) -> LlmReply,
    ): suspend (LlmPreset, String, String, String) -> LlmReply = { p, key, system, user ->
        chat(feature, p, key, system, user, call) { dao.addUsage(LocalDate.now().toString(), it.promptTokens, it.completionTokens, purpose) }
    }

    /** 一轮问答共用 Session；跨工具轮次或出处修复也不能在已输出后切换。 */
    class StreamSession(var primary: LlmPreset, var key: String) {
        var emitted = false
        /** 仅问问启用：工具兼容错误交给本轮 Prefetch，不能先切换服务掩盖原因。 */
        var askFallback = false
    }
    suspend fun stream(feature: String, session: StreamSession, messages: JSONArray, tools: JSONArray,
        client: LlmClient, onText: (String) -> Unit, usage: suspend (Long, Long) -> Unit, allowTools: Boolean,
    ): LlmStreamReply {
        val (reply, actual) = execute(feature, session.primary, session.key, { !session.emitted }) { p, secret ->
            if (session.askFallback && tools.length() > 0) providers.askCompatibility.state(p).fallback?.let {
                session.primary = p; session.key = secret
                throw app.resound.ask.AskPrefetchRequired(it.reason, actualFailure = false)
            }
            var prompt = 0L; var completion = 0L
            try {
                if (secret.isBlank() && !p.allowsEmptyKey() && !providers.hasHeaderAuth(p.id)) throw LlmException(LlmError.Auth)
                val result = client.streamChat(p, secret, messages, tools, { delta ->
                    if (delta.isNotEmpty()) session.emitted = true
                    onText(delta)
                }, { a, b -> prompt = a; completion = b }, allowTools)
                session.key = secret
                result
            } catch (e: LlmException) {
                if (session.askFallback && (e.status == 400 || e.type == LlmError.Format)) {
                    session.primary = p; session.key = secret; session.emitted = true
                }
                throw e
            } finally { withContext(NonCancellable) { usage(prompt, completion) } }
        }
        session.primary = actual
        return reply
    }
}
