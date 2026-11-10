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
    private data class Cooling(val until: Long, val reason: String, val error: LlmException)
    private val cooling = mutableMapOf<String, Cooling>()
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
    private data class Candidate(val preset: LlmPreset, val key: String, val generation: Long)
    private fun candidates(primary: LlmPreset, key: String, enabled: Boolean): List<Candidate> {
        val credentials = synchronized(providers.guard) {
            listOf(primary to key) + if (!enabled) emptyList() else providers.saved.value
                .filter { it.id != primary.id && (it.hasKey || it.preset().allowsEmptyKey() || providers.hasHeaderAuth(it.id)) }
                .map { it.preset() to providers.readKey(it.id) }
        }
        return synchronized(this) {
            credentials.map { Candidate(it.first, it.second, generations[it.first.id] ?: 0) }
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
    private suspend fun <T> execute(feature: String, primary: LlmPreset, key: String,
        canSwitch: () -> Boolean = { true }, call: suspend (LlmPreset, String) -> T): Pair<T, LlmPreset> {
        val on = enabled.value && canSwitch()
        val candidates = candidates(primary, key, on)
        fun ordered(list: List<Candidate>) = synchronized(this) {
            if (!on) list else list.sortedBy { (cooling[it.preset.id]?.until ?: 0) > now() }
        }
        var remaining = ordered(candidates.take(3)).toMutableList()
        var attempts = 0
        val errors = linkedMapOf<String, Exception>()
        var minimumCapacity = -1
        val initialReason = synchronized(this) { cooling[primary.id]?.reason }
        while (remaining.isNotEmpty() && attempts < 3) {
            val candidate = remaining.removeAt(0)
            val p = candidate.preset
            if (LlmInputBudget.inputChars(p) <= minimumCapacity) continue
            attempts++
            try {
                val trace = runCatching { diagnostics.start(feature, p) }.getOrNull()
                // 异常在 withContext 里先接住、出来后原样抛出：协程会复制穿过 withContext 的异常，
                // 取消信号必须保持原对象往上传（LlmFailover103Test）。
                val result = if (trace == null) call(p, candidate.key) else {
                    var thrown: Throwable? = null
                    val value = withContext(trace) { try { call(p, candidate.key) } catch (e: Throwable) { thrown = e; null } }
                    thrown?.let { trace.complete(it); throw it }
                    @Suppress("UNCHECKED_CAST") (value as T)
                }
                if (p.id != primary.id) recent.value = LlmSwitch(now(), feature, primary.name + " / " + primary.model,
                    p.name + " / " + p.model, errors.values.firstOrNull()?.let(::reason) ?: initialReason ?: "主模型暂不可用")
                if (p.id != primary.id) recent.value?.let {
                    diagnostics.switched(it.at, it.originalModel, it.actualModel, it.reason)
                }
                return result to p
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (e !is LlmInputLimitException && e !is LlmException) throw e
                if (!on || !canSwitch() || (e is LlmException && e.type == LlmError.Format)) throw e
                errors[p.id] = e
                if (e is LlmInputLimitException) {
                    minimumCapacity = maxOf(minimumCapacity, LlmInputBudget.inputChars(p))
                    // 不够大的候选不消耗一次请求名额；在完整 saved 顺序中找更大容量。
                    remaining = ordered(candidates.filter { it.preset.id !in errors &&
                        LlmInputBudget.inputChars(it.preset) > minimumCapacity }).toMutableList()
                } else if (!(e as LlmException).rejected) synchronized(this) {
                    // 拒收是这次内容的问题，不让主模型进冷却、连累问问等其他功能。
                    if ((generations[p.id] ?: 0) == candidate.generation) {
                        val long = e.type in setOf(LlmError.Auth, LlmError.Quota)
                        cooling[p.id] = Cooling(now() + (if (long) 30 else 5) * 60_000L, reason(e), LlmException(e.type, e.status, e.detail))
                    }
                }
            }
        }
        val error = errors[primary.id] ?: synchronized(this) { cooling[primary.id]?.error } ?: errors.values.first()
        val suffix = if (candidates.size > 1) "；备用模型也没成功" else ""
        throw when (error) {
            is LlmException -> LlmException(error.type, error.status, error.detail + suffix)
            is LlmInputLimitException -> LlmInputLimitException(error.message + suffix)
            else -> error
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
