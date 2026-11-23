package app.resound.transcribe

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import app.resound.diagnostics.FailureNotes
import app.resound.digest.HeaderEntry
import app.resound.digest.ServiceHeaders
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
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec


class AndroidTranscribeSettings internal constructor(private val context: Context, private val headerStore: ServiceHeaders) : TranscribeSettings {
    constructor(context: Context) : this(context, ServiceHeaders(context, transcription = true))
    override fun headers(presetId: String) = headerStore.headers(presetId)
    override fun saveHeaders(presetId: String, headers: List<HeaderEntry>) {
        require(TranscribeSource.entries.any { it.name == presetId }) { "转写服务不存在" }
        headerStore.save(presetId, headers)
        if (presetId == TranscribeSource.Custom.name) customSettings.invalidateAll()
        refreshReady(); onChanged()
    }
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
    /** #52：豆包接入方式。true = 火山方舟 Agent Plan（套餐专属地址与 Key），Key 与普通 API 分开存 */
    private val _plan = MutableStateFlow(prefs.getBoolean("doubaoPlan", false))
    val doubaoPlan = _plan.asStateFlow()
    fun setDoubaoPlan(on: Boolean) {
        check(prefs.edit().putBoolean("doubaoPlan", on).commit()) { "保存失败，请重试" }
        _plan.value = on
        refreshReady(); onChanged()
    }
    private fun keyFieldFor(source: TranscribeSource) = when {
        source == TranscribeSource.Custom -> CustomAsrSettings.keyField(customApi.value)
        _plan.value -> "planEncryptedKey"
        else -> "encryptedKey"
    }
    private val customSettings = CustomAsrSettings(prefs::getString, prefs::getBoolean,
        { key, value -> check(prefs.edit().putString(key, value).commit()) { "保存失败，请重试" } },
        { key, value -> check(prefs.edit().putBoolean(key, value).commit()) { "保存失败，请重试" } },
        { api, endpoint, model, mode ->
            val qwen = api == CustomAsrApi.Qwen
            val addressField = if (qwen) "qwenEndpoint" else "endpoint"
            val modelField = if (qwen) "qwenModel" else "model"
            val modeField = if (qwen) "qwenEndpointMode" else "endpointMode"
            val different = prefs.getString(addressField, "") != endpoint || prefs.getString(modelField, "") != model ||
                prefs.getString(modeField, CustomEndpointMode.Auto.name) != mode.name
            check(prefs.edit().putString(addressField, endpoint).putString(modelField, model).putString(modeField, mode.name).commit()) {
                "保存失败，请重试"
            }
            if (!qwen && different) formatPrefs.edit().clear().commit()
        }, ::validateEndpoint,
        { api -> !prefs.getString(CustomAsrSettings.keyField(api), null).isNullOrBlank() ||
            runCatching { headers(TranscribeSource.Custom.name).any { it.value.isNotBlank() } }.getOrDefault(false) },
        { refreshReady(); onChanged() },
        { api -> verifiedPrefs.getString(api.name, null)?.split('|', limit = 2)?.takeIf { it.size == 2 && it[1] == verifiedFingerprint(api) }
            ?.let { saved -> CustomCapability.entries.firstOrNull { it.name == saved[0] } } },
        { api, capability -> verifiedPrefs.edit().apply {
            if (capability == null) remove(api.name) else putString(api.name, "${capability.name}|${verifiedFingerprint(api)}") }.apply() })
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
    private val preferences = TranscribePreferenceStore(prefs::getString, prefs::getBoolean, prefs::getInt, prefs::contains,
        { key, value -> prefs.edit().putString(key, value).commit() },
        { key, value -> prefs.edit().putBoolean(key, value).commit() },
        { key, value -> prefs.edit().putInt(key, value).commit() }, localPrefs::getString,
        { key, value -> localPrefs.edit().putString(key, value).apply() },
        { localPrefs.edit().remove("npuCheck").apply() },
        ::sourceChanged, ::refreshReady, { prefs.contains("encryptedKey") || prefs.contains("planEncryptedKey") })
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
    fun saveCustomAll(endpoint: String, model: String, key: String, mode: CustomEndpointMode = endpointMode.value): CustomSaveResult {
        customSettings.validateFields(endpoint, model, mode)
        val keySaved = key.isNotBlank()
        if (keySaved) saveKeyFor(TranscribeSource.Custom, key)
        customSettings.save(endpoint, model, mode)
        return CustomSaveResult(keySaved, customKeyState.value)
    }
    /** 完整地址按填写的原样校验；自动模式沿用 OpenAI 兼容的路径规则。 */
    private fun validateEndpoint(endpoint: String, mode: CustomEndpointMode) {
        if (mode == CustomEndpointMode.Full) fullEndpoint(endpoint) else OpenAiEndpoint.bases(endpoint)
    }
    fun identity() = "${customApi.value}:${customSpeakerFormat.value}:${source.value}:${backend.value}:${endpointMode.value.name}:$customEndpoint:$customModel:${keyFingerprint(if(source.value==TranscribeSource
        .Local) "" else readKey())}:${keyFingerprint(org.json.JSONArray(headers(source.value.name).map { listOf(it.name, it.value) }).toString())}" +
        (if (source.value == TranscribeSource.Local) ":${language.value.code}" else "") +
        if (source.value == TranscribeSource.Doubao && _plan.value) ":plan" else ""
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
    private fun keyField() = keyFieldFor(source.value)
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

    private fun secret(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    @Synchronized
    internal fun readKey(): String = readKey(source.value)
    @Synchronized internal fun readKey(source: TranscribeSource): String {
        val encoded = prefs.getString(keyFieldFor(source), null) ?: return ""
        val bytes = Base64.decode(encoded, Base64.NO_WRAP)
        require(bytes.size > 12)
        return Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, secret(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            doFinal(bytes.copyOfRange(12, bytes.size)).toString(Charsets.UTF_8)
        }
    }
    @Synchronized
    override fun saveKey(key: String): Unit = saveKeyFor(source.value, key)
    private fun saveKeyFor(source: TranscribeSource, key: String) {
        val value = key.trim()
        if (value.isEmpty()) { clearKey(); return }
        // 请求头不得带换行；任何校验错误均不回显输入。
        require(value.all { it.code in 33..126 }) { "密钥格式不正确" }
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, secret())
            val encrypted = cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
            check(prefs.edit().putString(keyFieldFor(source), Base64.encodeToString(encrypted, Base64.NO_WRAP)).commit())
            _hasKey.value = true
        } catch (caught: Exception) {
            FailureNotes.record("transcribe/AndroidTranscribeSettings.kt:146", caught)
            throw IllegalStateException("密钥安全保存失败，请重试") }
        if (source == TranscribeSource.Custom) customSettings.invalidate(customApi.value)
        refreshReady(); onChanged()
    }
    @Synchronized
    override fun clearKey() {
        check(prefs.edit().remove(keyField()).commit()) { "密钥清除失败，请重试" }
        _hasKey.value = false
        if (source.value == TranscribeSource.Custom) customSettings.invalidate(customApi.value)
        refreshReady(); onChanged()
    }
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
    companion object { private const val ALIAS = "resound-asr-aes-v1" }
}
