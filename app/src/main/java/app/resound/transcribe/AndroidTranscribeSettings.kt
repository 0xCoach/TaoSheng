package app.resound.transcribe

import android.content.Context
import app.resound.diagnostics.FailureNotes
import app.resound.digest.HeaderEntry
import app.resound.digest.ServiceHeaders
import app.resound.platform.AndroidKeyValueStores
import app.resound.platform.AndroidSecretVault
import app.resound.platform.KeyValueStores
import app.resound.platform.SecretVault
import app.resound.transcribe.local.LocalAsrLanguage
import app.resound.transcribe.local.LocalBackend
import app.resound.transcribe.local.LocalEngine
import app.resound.transcribe.local.LocalFacts
import app.resound.transcribe.local.LocalModels
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext


class AndroidTranscribeSettings internal constructor(
    private val context: Context,
    stores: KeyValueStores,
    vault: SecretVault,
    headerStore: ServiceHeaders = ServiceHeaders(vault, transcription = true),
) : TranscribeSettings {
    constructor(context: Context) : this(context, AndroidKeyValueStores(context), AndroidSecretVault(context))
    internal constructor(context: Context, headerStore: ServiceHeaders) :
        this(context, AndroidKeyValueStores(context), AndroidSecretVault(context), headerStore)

    override fun headers(presetId: String) = configuration.headers(presetId)
    override fun saveHeaders(presetId: String, headers: List<HeaderEntry>) = configuration.saveHeaders(presetId, headers)
    private val formatPrefs = context.getSharedPreferences("custom-asr-formats", Context.MODE_PRIVATE)
    internal fun customFormats(endpoint: String, model: String, mode: CustomEndpointMode) = app.resound.net.CustomAsrFormats(
        ::keyFingerprint,
        { key -> if (formatPrefs.contains(key)) formatPrefs.getLong(key, 0) else null },
        { key, at ->
            // 设置已改变时，旧请求不能重新写入过期结论。
            if (customApi.value == CustomAsrApi.OpenAi && customSpeakerFormat.value == CustomSpeakerFormat.Auto &&
                customEndpoint == endpoint && customModel == model && customEndpointMode == mode)
                formatPrefs.edit().putLong(key, at).apply()
        }, System::currentTimeMillis,
    )
    private val localPrefs = context.getSharedPreferences("local-asr", Context.MODE_PRIVATE)
    private val prefs = context.getSharedPreferences("transcribe", Context.MODE_PRIVATE)
    private val configuration: TranscribeConfiguration = TranscribeConfiguration(
        stores, vault, headerStore.settings,
        localPrefs::getString,
        { key, value -> localPrefs.edit().putString(key, value).apply() },
        { localPrefs.edit().remove("npuCheck").apply() },
        ::validateEndpoint, ::sourceChanged, ::refreshReady,
        openAiFieldsChanged = { formatPrefs.edit().clear().commit() },
        loadVerified = { api ->
            verifiedPrefs.getString(api.name, null)?.split('|', limit = 2)
                ?.takeIf { it.size == 2 && it[1] == verifiedFingerprint(api) }
                ?.let { saved -> CustomCapability.entries.firstOrNull { it.name == saved[0] } }
        },
        saveVerified = { api, capability ->
            verifiedPrefs.edit().apply {
                if (capability == null) remove(api.name)
                else putString(api.name, "${capability.name}|${verifiedFingerprint(api)}")
            }.apply()
        },
        // 旧版把空密文字段视作未填写。验证指纹仍使用原密文。
        hasCustomKey = { field -> !prefs.getString(field, null).isNullOrBlank() },
        onKeyStored = { _hasKey.value = it },
    )
    val doubaoPlan get() = configuration.doubaoPlan
    fun setDoubaoPlan(on: Boolean) = configuration.setDoubaoPlan(on)
    private val customSettings get() = configuration.custom

    /** 卡 212：测试通过的结论按配置指纹存本机，不备份；换配置、换密钥、恢复到新手机都会读回“未验证”。 */
    private val verifiedPrefs get() = context.getSharedPreferences("custom-asr-verified", Context.MODE_PRIVATE)
    private fun verifiedFingerprint(api: CustomAsrApi): String = runCatching {
        val qwen = api == CustomAsrApi.Qwen
        val parts = listOf(prefs.getString(if (qwen) "qwenEndpoint" else "endpoint", CustomAsrSettings.QWEN_ENDPOINT.takeIf { qwen } ?: "").orEmpty(),
            prefs.getString(if (qwen) "qwenModel" else "model", CustomAsrSettings.QWEN_MODEL.takeIf { qwen } ?: "").orEmpty(),
            if (qwen) CustomEndpointMode.Full.name else prefs.getString("endpointMode", CustomEndpointMode.Auto.name).orEmpty(),
            if (qwen) "" else prefs.getString("customSpeakerFormat", CustomSpeakerFormat.Auto.name).orEmpty(),
            prefs.getString(CustomAsrSettings.keyField(api), "").orEmpty(),
            org.json.JSONArray(headers(TranscribeSource.Custom.name).map { listOf(it.name, it.value) }).toString())
        keyFingerprint(parts.joinToString("\u0000"))
    }.getOrDefault("")
    val customApi get() = customSettings.api
    val customSpeakerFormat get() = customSettings.speakerFormat
    val customKeyState get() = customSettings.keyState
    val customCapability get() = customSettings.capability
    fun setCustomApi(api: CustomAsrApi) = customSettings.setApi(api)
    fun setCustomSpeakerFormat(format: CustomSpeakerFormat) = customSettings.setSpeakerFormat(format)
    private val preferences get() = configuration.preferences
    val source get() = preferences.source
    val backend get() = preferences.backend
    val language get() = preferences.language
    val modelRevision get() = preferences.modelRevision
    val customEndpoint get() = customSettings.endpoint
    val customModel get() = customSettings.model
    val endpointMode get() = customSettings.endpointMode
    val customEndpointMode get() = customSettings.endpointMode.value
    val customPrompt get() = customSettings.customPrompt
    fun setLanguage(value: LocalAsrLanguage) = preferences.setLanguage(value)
    fun npuCheck(key: String) = preferences.npuCheck(key)
    fun recordNpuCheck(key: String, ok: Boolean) {
        preferences.recordNpuCheck(key, ok)
        refreshLocal(localFacts())
    }
    fun setCustomPrompt(on: Boolean) = customSettings.setPrompt(on)
    private val _ready = MutableStateFlow(false)
    override val ready = _ready.asStateFlow()
    // 保留各请求自己的进度，结束时只清除本次的提示。
    private data class LocalStatus(val idle: String, val requests: Map<Any, String> = emptyMap()) {
        val text get() = requests.values.lastOrNull() ?: idle
    }
    private val status = MutableStateFlow(LocalStatus(""))
    @OptIn(ExperimentalForInheritanceCoroutinesApi::class)
    val localStatus: StateFlow<String> = object : StateFlow<String> {
        override val value get() = status.value.text
        override val replayCache get() = listOf(value)
        override suspend fun collect(collector: FlowCollector<String>): Nothing {
            status.map { it.text }.distinctUntilChanged().collect(collector)
            awaitCancellation()
        }
    }
    private val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        preferences.localPreferenceChanged(); refreshReady()
    }
    init {
        if (localPrefs.contains("actual")) localPrefs.edit().remove("actual").apply()
        localPrefs.registerOnSharedPreferenceChangeListener(listener)
    }
    fun downloadStatus(backend: LocalBackend) = localPrefs.getString("download-${backend.name}", "未下载")!!
    fun modelReady(backend: LocalBackend) = LocalModels.ready(context, LocalModels.spec(backend))
    fun download(backend: LocalBackend, retry: Boolean = false) = LocalModels.enqueue(context, backend, retry)
    internal fun localFacts(checkKey: String = LocalModels.npuCheckKey(context)): LocalFacts {
        val supported = LocalModels.npuSupported()
        return LocalFacts(
            wish = backend.value,
            npuSupported = supported,
            npuReady = supported && modelReady(LocalBackend.Npu),
            cpuReady = modelReady(LocalBackend.Cpu),
            npuCheck = npuCheck(checkKey),
        )
    }
    internal fun setLocalStatus(request: Any, value: String) {
        status.update { it.copy(requests = it.requests + (request to value)) }
    }
    internal fun clearLocalStatus(request: Any) {
        val idle = LocalEngine.status(localFacts())
        status.update { it.copy(idle = idle, requests = it.requests - request) }
    }
    fun setSource(value: TranscribeSource) = preferences.setSource(value)
    fun setBackend(value: LocalBackend) = preferences.setBackend(value)
    fun saveCustom(endpoint: String, model: String, mode: CustomEndpointMode = endpointMode.value) = customSettings.save(endpoint, model, mode)
    private fun sourceChanged() {
        _hasKey.value = runCatching { readKey().isNotBlank() || headers(source.value.name).any { it.value.isNotBlank() } }.onFailure {
            FailureNotes.record("transcribe/AndroidTranscribeSettings.kt:73", it)
        }.getOrDefault(false)
    }
    suspend fun fetchModels(endpoint: String, key: String): RemoteModels {
        require(customApi.value == CustomAsrApi.OpenAi) { "千问接口请直接填写模型名" }
        return fetchRemoteModels(endpoint, key.trim().ifBlank { readKey(TranscribeSource.Custom) }, headers = headers(TranscribeSource.Custom.name))
    }
    fun saveCustomAll(endpoint: String, model: String, key: String, mode: CustomEndpointMode = endpointMode.value) =
        configuration.saveCustomAll(endpoint, model, key, mode)
    /** 完整地址按填写的原样校验；自动模式沿用 OpenAI 兼容的路径规则。 */
    private fun validateEndpoint(endpoint: String, mode: CustomEndpointMode) {
        if (mode == CustomEndpointMode.Full) fullEndpoint(endpoint) else OpenAiEndpoint.bases(endpoint)
    }
    fun identity() = "${customApi.value}:${customSpeakerFormat.value}:${source.value}:${backend.value}:${endpointMode.value.name}:$customEndpoint:$customModel:${keyFingerprint(if(source.value==TranscribeSource
        .Local) "" else readKey())}:${keyFingerprint(org.json.JSONArray(headers(source.value.name).map { listOf(it.name, it.value) }).toString())}" +
        (if (source.value == TranscribeSource.Local) ":${language.value.code}" else "") +
        if (source.value == TranscribeSource.Doubao && doubaoPlan.value) ":plan" else ""
    internal fun credential(source: TranscribeSource) = if (source == TranscribeSource.Local) "local-model" else readKey(source)
    internal fun ready(source: TranscribeSource): Boolean = when (source) {
        TranscribeSource.Local -> LocalEngine.choose(localFacts()) != null
        TranscribeSource.Doubao -> runCatching { readKey(source).isNotBlank() || headers(source.name).any { it.value.isNotBlank() } }.onFailure {
            FailureNotes.record("transcribe/AndroidTranscribeSettings.kt:88", it)
        }.getOrDefault(false)
        TranscribeSource.Custom -> customModel.isNotBlank() && runCatching { validateEndpoint(customEndpoint, endpointMode.value); true }.onFailure {
            FailureNotes.record("transcribe/AndroidTranscribeSettings.kt:89", it)
        }.getOrDefault(false)
    }
    fun credential() = if(source.value==TranscribeSource.Local) "local-model" else readKey()
    private fun refreshLocal(facts: LocalFacts) {
        if (source.value == TranscribeSource.Local) _ready.value = LocalEngine.choose(facts) != null
        val idle = LocalEngine.status(facts)
        status.update { it.copy(idle = idle) }
    }
    fun refreshReady() {
        refreshLocal(localFacts())
        if (source.value != TranscribeSource.Local) _ready.value = usable()
        _hasKey.value = usable()
        customSettings.refresh()
    }
    private fun usable() = (source.value == TranscribeSource.Custom && customEndpoint.isNotBlank() && customModel.isNotBlank()) || runCatching {
        readKey().isNotBlank() || headers(source.value.name).any { it.value.isNotBlank() }
    }.onFailure {
        FailureNotes.record("transcribe/AndroidTranscribeSettings.kt:99", it)
    }.getOrDefault(false)
    private val _hasKey = MutableStateFlow(usable())
    override val hasKey = _hasKey.asStateFlow()
    override val intervalMinutes get() = preferences.intervalMinutes
    val realtime get() = preferences.realtime
    override val wifiOnly get() = preferences.wifiOnly
    fun setRealtime(on: Boolean) = preferences.setRealtime(on)
    var onChanged: () -> Unit
        get() = preferences.onChanged
        set(value) { preferences.onChanged = value }
    var testKey: suspend (String) -> Result<String> = { Result.failure(AsrException(AsrError.Storage)) }

    init { refreshReady() }

    @Synchronized
    internal fun readKey(): String = configuration.readKey()

    @Synchronized
    internal fun readKey(source: TranscribeSource): String = configuration.readKey(source)

    @Synchronized
    override fun saveKey(key: String) = configuration.saveKey(source.value, key)

    @Synchronized
    override fun clearKey() = configuration.clearKey()

    override fun setIntervalMinutes(minutes: Int) = preferences.setIntervalMinutes(minutes)
    override fun setWifiOnly(value: Boolean) = preferences.setWifiOnly(value)
    internal var testWithCapability: suspend (String, (CustomCapability) -> Unit) -> Result<String> = { key, _ -> testKey(key) }
    override suspend fun test(): Result<String> = withContext(Dispatchers.IO) {
        val ticket = customSettings.testTicket()
        val testedSource = source.value
        val key = try { credential() } catch (caught: Exception) {
            FailureNotes.record("transcribe/AndroidTranscribeSettings.kt:158", caught)
            return@withContext Result.failure(IllegalStateException("密钥安全读取失败，请重新填写")) }
        if (key.isBlank() && headers(source.value.name).isEmpty() && source.value != TranscribeSource.Custom) Result.failure(IllegalStateException("还没填写转写密钥"))
        else try {
            var capability: CustomCapability? = null
            val result = testWithCapability(key) { capability = it }
            if (result.isSuccess && testedSource == TranscribeSource.Custom && source.value == testedSource)
                capability?.let { customSettings.tested(ticket, it) }
            result
        } catch (e: CancellationException) { throw e }
        catch (caught: Exception) {
            FailureNotes.record("transcribe/AndroidTranscribeSettings.kt:161", caught)
            Result.failure(IllegalStateException("转写测试失败，请重试")) }
    }
}
