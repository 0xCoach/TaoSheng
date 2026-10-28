package app.resound.digest

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class AndroidDigestSettings(
    context: Context, private val client: LlmClient,
    private val recordUsage: suspend (LlmReply) -> Unit = {},
) : DigestSettings {
    val timing = AndroidDigestTiming(context)
    private val prefs = context.getSharedPreferences("digest", Context.MODE_PRIVATE)
    private val secrets = SecretStore(context, "digest-secrets", "resound-llm-aes-v1")

    init {
        // 旧豆包选择迁移为自定义，沿用端点、模型和密钥，避免升级后换服务。
        if(prefs.getString("preset",null)=="doubao") {
            if(!secrets.has("key-custom")) {
                val old=runCatching{secrets.read("key-doubao")}.getOrDefault("")
                if(old.isNotBlank())secrets.write("key-custom",old)
            }
            check(prefs.edit().putString("preset","custom")
                .putString("customEndpoint","https://ark.cn-beijing.volces.com/api/v3")
                .putString("customModel","doubao-seed-2-1-lite-260915").commit())
        }
    }
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
    val customEndpoint get() = prefs.getString("customEndpoint","")!!
    val customModel get() = prefs.getString("customModel","")!!
    override val presets get() = listOf(
        LlmPreset("deepseek", "DeepSeek", "https://api.deepseek.com", "deepseek-flash"),
        LlmPreset("custom", "自定义", customEndpoint, customModel),
    )
    suspend fun fetchModels(endpoint:String,key:String)=app.resound.transcribe.fetchRemoteModels(endpoint,key.trim().ifBlank{readKey()})
    fun saveCustomAll(endpoint:String,model:String,key:String) {
        app.resound.transcribe.OpenAiEndpoint.bases(endpoint)
        require(model.trim().isNotBlank()){"请填写或选择模型"}
        require(key.isNotBlank() || readKey().isNotBlank()){"请填写 API Key"}
        if(key.isNotBlank())saveKey(key)
        check(prefs.edit().putString("customEndpoint",endpoint.trim()).putString("customModel",model.trim()).commit())
    }

    private val _presetId = MutableStateFlow(prefs.getString("preset", null)?.takeIf { id -> presets.any { it.id == id } } ?: presets.first().id)
    override val presetId: StateFlow<String> = _presetId.asStateFlow()
    private val _hasKey = MutableStateFlow(secrets.has(keyName(_presetId.value)))
    override val hasKey: StateFlow<Boolean> = _hasKey.asStateFlow()

    val preset: LlmPreset get() = presets.first { it.id == _presetId.value }

    private fun keyName(id: String) = "key-$id"

    /** 读当前服务的密钥；没填返回空串 */
    fun readKey(): String = runCatching { secrets.read(keyName(_presetId.value)) }.getOrDefault("")

    /** 技能可指定服务，读取时不切换全局整理预设。 */
    fun skillCredentials(id: String?): Pair<LlmPreset, String> {
        val selected = if (id == null) preset else presets.first { it.id == id }
        return selected to runCatching { secrets.read(keyName(selected.id)) }.getOrDefault("")
    }

    override fun setPreset(id: String) {
        require(presets.any { it.id == id })
        check(prefs.edit().putString("preset", id).commit())
        _presetId.value = id
        _hasKey.value = secrets.has(keyName(id))
    }

    override fun saveKey(key: String) {
        val value = key.trim()
        if (value.isEmpty()) { clearKey(); return }
        require(value.all { it.code in 33..126 }) { "密钥格式不正确" }
        secrets.write(keyName(_presetId.value), value)
        _hasKey.value = true
    }

    override fun clearKey() {
        secrets.remove(keyName(_presetId.value))
        _hasKey.value = false
    }

    override suspend fun test(): Result<String> {
        val selected=preset
        if(selected.id=="custom" && (selected.baseUrl.isBlank() || selected.model.isBlank()))return Result.failure(IllegalStateException("请先保存服务地址和模型"))
        val key = readKey()
        if (key.isBlank()) return Result.failure(IllegalStateException("还没填写大模型密钥"))
        return try {
            val reply = client.chatJson(selected, key, "只输出 JSON。", "输出 {\"ok\":true,\"reply\":\"用一句不超过十个字的中文打个招呼\"}")
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { recordUsage(reply) }
            val text = runCatching { org.json.JSONObject(reply.content).optString("reply") }.getOrNull().orEmpty()
            Result.success("可用（${preset.name}）${if (text.isNotBlank()) "：$text" else ""}")
        } catch (e: CancellationException) {
            throw e
        } catch (e: LlmException) {
            Result.failure(IllegalStateException(e.type.human))
        } catch (_: Exception) {
            Result.failure(IllegalStateException("测试失败，请重试"))
        }
    }

    companion object {
        const val DEFAULT_PAUSE = 10
        const val DEFAULT_MAX_WAIT = 30
        val PAUSE_RANGE = 3..60
        val MAX_WAIT_RANGE = 15..240
    }
}
