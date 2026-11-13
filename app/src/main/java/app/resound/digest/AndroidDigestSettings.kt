package app.resound.digest

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class AndroidDigestSettings internal constructor(
    context: Context, private val client: LlmClient, val providers: AndroidLlmProviders,
    private val recordUsage: suspend (LlmReply) -> Unit = {},
) : DigestSettings {
    constructor(context: Context, client: LlmClient, recordUsage: suspend (LlmReply) -> Unit = {}) :
        this(context, client, AndroidLlmProviders(context, client, recordUsage), recordUsage)
    val failover = LlmFailover(context, providers)
    val failoverEnabled get() = failover.enabled
    fun setFailoverEnabled(enabled: Boolean) = failover.setEnabled(enabled)
    val timing = AndroidDigestTiming(context)
    private val prefs = context.getSharedPreferences("digest", Context.MODE_PRIVATE)
    private val preferences = DigestPreferenceStore(prefs::getBoolean, prefs::getInt,
        { context.getSharedPreferences("transcribe", Context.MODE_PRIVATE).getInt("interval", 30) },
        { key, value -> prefs.edit().putBoolean(key, value).commit() },
        { key, value -> prefs.edit().putInt(key, value).commit() })
    override val includeSituation get() = preferences.includeSituation
    override fun setIncludeSituation(on: Boolean) = preferences.setIncludeSituation(on)
    val pauseMinutes get() = preferences.pauseMinutes
    val maxWaitMinutes get() = preferences.maxWaitMinutes
    val intervalMinutes get() = preferences.intervalMinutes
    val deepThinking get() = preferences.deepThinking
    var onIntervalChanged: () -> Unit
        get() = preferences.onIntervalChanged
        set(value) { preferences.onIntervalChanged = value }
    fun setIntervalMinutes(value: Int) = preferences.setIntervalMinutes(value)
    fun setDeepThinking(on: Boolean) = preferences.setDeepThinking(on)
    fun setPauseMinutes(value: Int) = preferences.setPauseMinutes(value)
    fun setMaxWaitMinutes(value: Int) = preferences.setMaxWaitMinutes(value)
    /** 整理和重新整理用的请求：按开关决定模型要不要先思考 */
    fun digestRequest(client: LlmClient): suspend (LlmPreset, String, String, String) -> LlmReply =
        { p, key, system, user -> client.chatJson(p, key, system, user, thinking = preferences.deepThinking.value) }
    val customEndpoint get() = providers.saved.value.firstOrNull { it.id == "custom" }?.baseUrl.orEmpty()
    val customModel get() = providers.saved.value.firstOrNull { it.id == "custom" }?.model.orEmpty()
    override val presets get() = providers.saved.value.map { it.preset() }
    suspend fun fetchModels(endpoint:String,key:String)=app.resound.transcribe.fetchRemoteModels(ServiceHeaders.modelsEndpoint(endpoint),key.trim().ifBlank{readKey()}, headers = providers.headerStore.forService(presetId.value, endpoint))
    fun saveCustomAll(endpoint:String,model:String,key:String) {
        providers.ensureLegacy("custom")
        val p = providers.saved.value.first { it.id == "custom" }
        providers.saveNow(p.id, p.templateId, p.name, endpoint, model, key.takeIf { it.isNotBlank() }, p.balanceUrl, p.balancePath)
    }

    override val presetId: StateFlow<String> = providers.activeId
    private fun usable() = providers.selected().let { it.hasKey || (canUseWithoutKey(it.preset()) && it.baseUrl.isNotBlank() && it.model.isNotBlank()) }
    private val _hasKey = MutableStateFlow(usable())
    override val hasKey: StateFlow<Boolean> = _hasKey.asStateFlow()

    init { providers.listen { _hasKey.value = usable() } }
    val preset: LlmPreset get() = providers.selected().preset()

    /** 读当前服务的密钥；没填返回空串 */
    fun readKey(): String = providers.credentials().second

    /** 技能可指定服务，读取时不切换全局整理预设。 */
    fun skillCredentials(id: String?): Pair<LlmPreset, String> = providers.credentials(id)

    override fun headers(presetId: String) = providers.headers(presetId)
    override fun saveHeaders(presetId: String, headers: List<HeaderEntry>) = providers.saveHeaders(presetId, headers)
    fun canUseWithoutKey(preset: LlmPreset) = preset.allowsEmptyKey() || providers.hasHeaderAuth(preset.id)

    override fun setPreset(id: String) {
        providers.ensureLegacy(id)
        providers.activate(id)
    }

    override fun saveKey(key: String) = providers.setKey(key)
    override fun clearKey() = providers.setKey("")

    override suspend fun test(): Result<String> {
        val (selected, key) = skillCredentials(null)
        if(selected.baseUrl.isBlank() || selected.model.isBlank())return Result.failure(IllegalStateException("请先保存服务地址和模型"))
        if (key.isBlank() && !canUseWithoutKey(selected)) return Result.failure(IllegalStateException("还没填写大模型密钥"))
        return try {
            val reply = client.chatJson(selected, key, "只输出 JSON。", "输出 {\"ok\":true,\"reply\":\"用一句不超过十个字的中文打个招呼\"}")
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { recordUsage(reply) }
            failover.clear(selected.id)
            val text = runCatching { org.json.JSONObject(reply.content).optString("reply") }.getOrNull().orEmpty()
            Result.success("可用（${selected.name}）${if (text.isNotBlank()) "：$text" else ""}")
        } catch (e: CancellationException) {
            throw e
        } catch (e: LlmException) {
            Result.failure(IllegalStateException(e.detail))
        } catch (_: Exception) {
            Result.failure(IllegalStateException("测试失败，请重试"))
        }
    }

    override suspend fun check(): Result<List<ModelCheckItem>> {
        val compatibility = providers.askCompatibility
        val (selected, key, generation) = synchronized(providers.guard) {
            val (preset, secret) = skillCredentials(null)
            Triple(preset, secret, compatibility.generation(preset.id))
        }
        if(selected.baseUrl.isBlank() || selected.model.isBlank())return Result.failure(IllegalStateException("请先保存服务地址和模型"))
        if (key.isBlank() && !canUseWithoutKey(selected)) return Result.failure(IllegalStateException("还没填写大模型密钥"))
        return Result.success(ModelCheck(
            json = { system, user ->
                client.chatJson(selected, key, system, user).also { kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { recordUsage(it) } }
            },
            toolResult = { reason ->
                if (reason == null) compatibility.passed(selected, generation)
                else compatibility.mark(selected, reason, generation, actualFailure = false)
            },
            stream = { messages, tools ->
                var prompt = 0L; var completion = 0L
                try { client.streamChat(selected, key, messages, tools, {}, { p, c -> prompt = p; completion = c }) }
                finally { kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { recordUsage(LlmReply("", prompt, completion)) } }
            },
        ).run().map { it.copy(lastAskFailure = compatibility.state(selected).lastFailure) })
    }

    companion object {
        const val DEFAULT_PAUSE = DigestPreferenceStore.DEFAULT_PAUSE
        const val DEFAULT_MAX_WAIT = DigestPreferenceStore.DEFAULT_MAX_WAIT
        val PAUSE_RANGE = DigestPreferenceStore.PAUSE_RANGE
        val MAX_WAIT_RANGE = DigestPreferenceStore.MAX_WAIT_RANGE
    }
}
