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
    val timing = AndroidDigestTiming(context)
    private val prefs = context.getSharedPreferences("digest", Context.MODE_PRIVATE)
    /** 两句话之间停多久算这段对话结束（分钟）；结束后才整理，避免一件事被切成两半 */
    private val _pauseMinutes = MutableStateFlow(prefs.getInt("pauseMinutes", DEFAULT_PAUSE).coerceIn(PAUSE_RANGE))
    val pauseMinutes = _pauseMinutes.asStateFlow()
    /** 一直在说、对话迟迟不结束时，最多等多久先整理前面已稳定的部分（分钟） */
    private val _maxWaitMinutes = MutableStateFlow(prefs.getInt("maxWaitMinutes", DEFAULT_MAX_WAIT).coerceIn(MAX_WAIT_RANGE))
    val maxWaitMinutes = _maxWaitMinutes.asStateFlow()
    /** 多久整理一次（分钟）；第一次沿用原来“多久转写一次”的设置（以前两者是同一个定时任务） */
    private val _intervalMinutes = MutableStateFlow(prefs.getInt("interval",
        context.getSharedPreferences("transcribe", Context.MODE_PRIVATE).getInt("interval", 30))
        .coerceIn(app.resound.transcribe.MIN_INTERVAL, app.resound.transcribe.MAX_INTERVAL))
    val intervalMinutes = _intervalMinutes.asStateFlow()
    /** 间隔改了要重排整理任务；由 ResoundApp 装配 */
    var onIntervalChanged: () -> Unit = {}
    fun setIntervalMinutes(value: Int) {
        val v = value.coerceIn(app.resound.transcribe.MIN_INTERVAL, app.resound.transcribe.MAX_INTERVAL)
        check(prefs.edit().putInt("interval", v).commit()); _intervalMinutes.value = v; onIntervalChanged()
    }
    fun setPauseMinutes(value: Int) {
        val v = value.coerceIn(PAUSE_RANGE)
        check(prefs.edit().putInt("pauseMinutes", v).commit()); _pauseMinutes.value = v
    }
    fun setMaxWaitMinutes(value: Int) {
        val v = value.coerceIn(MAX_WAIT_RANGE)
        check(prefs.edit().putInt("maxWaitMinutes", v).commit()); _maxWaitMinutes.value = v
    }
    val customEndpoint get() = providers.saved.value.firstOrNull { it.id == "custom" }?.baseUrl.orEmpty()
    val customModel get() = providers.saved.value.firstOrNull { it.id == "custom" }?.model.orEmpty()
    override val presets get() = providers.saved.value.map { it.preset() }
    suspend fun fetchModels(endpoint:String,key:String)=app.resound.transcribe.fetchRemoteModels(endpoint,key.trim().ifBlank{readKey()})
    fun saveCustomAll(endpoint:String,model:String,key:String) {
        providers.ensureLegacy("custom")
        val p = providers.saved.value.first { it.id == "custom" }
        providers.saveNow(p.id, p.templateId, p.name, endpoint, model, key.takeIf { it.isNotBlank() }, p.balanceUrl, p.balancePath)
    }

    override val presetId: StateFlow<String> = providers.activeId
    private fun usable() = providers.selected().let { it.hasKey || (it.preset().allowsEmptyKey() && it.baseUrl.isNotBlank() && it.model.isNotBlank()) }
    private val _hasKey = MutableStateFlow(usable())
    override val hasKey: StateFlow<Boolean> = _hasKey.asStateFlow()

    init { providers.listen { _hasKey.value = usable() } }
    val preset: LlmPreset get() = providers.selected().preset()

    /** 读当前服务的密钥；没填返回空串 */
    fun readKey(): String = providers.credentials().second

    /** 技能可指定服务，读取时不切换全局整理预设。 */
    fun skillCredentials(id: String?): Pair<LlmPreset, String> = providers.credentials(id)

    override fun setPreset(id: String) {
        providers.ensureLegacy(id)
        providers.activate(id)
    }

    override fun saveKey(key: String) = providers.setKey(key)
    override fun clearKey() = providers.setKey("")

    override suspend fun test(): Result<String> {
        val (selected, key) = skillCredentials(null)
        if(selected.baseUrl.isBlank() || selected.model.isBlank())return Result.failure(IllegalStateException("请先保存服务地址和模型"))
        if (key.isBlank() && !selected.allowsEmptyKey()) return Result.failure(IllegalStateException("还没填写大模型密钥"))
        return try {
            val reply = client.chatJson(selected, key, "只输出 JSON。", "输出 {\"ok\":true,\"reply\":\"用一句不超过十个字的中文打个招呼\"}")
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { recordUsage(reply) }
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
        val (selected, key) = skillCredentials(null)
        if(selected.baseUrl.isBlank() || selected.model.isBlank())return Result.failure(IllegalStateException("请先保存服务地址和模型"))
        if (key.isBlank() && !selected.allowsEmptyKey()) return Result.failure(IllegalStateException("还没填写大模型密钥"))
        return Result.success(ModelCheck(
            json = { system, user ->
                client.chatJson(selected, key, system, user).also { kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { recordUsage(it) } }
            },
            stream = { messages, tools ->
                var prompt = 0L; var completion = 0L
                try { client.streamChat(selected, key, messages, tools, {}, { p, c -> prompt = p; completion = c }) }
                finally { kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { recordUsage(LlmReply("", prompt, completion)) } }
            },
        ).run())
    }

    companion object {
        const val DEFAULT_PAUSE = 10
        const val DEFAULT_MAX_WAIT = 30
        val PAUSE_RANGE = 3..60
        val MAX_WAIT_RANGE = 15..240
    }
}
