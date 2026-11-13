package app.resound.transcribe

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import app.resound.transcribe.local.LocalAsrLanguage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec


class AndroidTranscribeSettings internal constructor(private val context: Context, private val headerStore: app.resound.digest.ServiceHeaders) : TranscribeSettings {
    constructor(context: Context) : this(context, app.resound.digest.ServiceHeaders(context, transcription = true))
    override fun headers(presetId: String) = headerStore.headers(presetId)
    override fun saveHeaders(presetId: String, headers: List<app.resound.digest.HeaderEntry>) {
        require(TranscribeSource.entries.any { it.name == presetId }) { "转写服务不存在" }
        headerStore.save(presetId, headers)
        refreshReady(); onChanged()
    }
    private val localPrefs = context.getSharedPreferences("local-asr", Context.MODE_PRIVATE)
    private val prefs = context.getSharedPreferences("transcribe", Context.MODE_PRIVATE)
    private val preferences = TranscribePreferenceStore(prefs::getString, prefs::getBoolean, prefs::getInt, prefs::contains,
        { key, value -> prefs.edit().putString(key, value).commit() },
        { key, value -> prefs.edit().putBoolean(key, value).commit() },
        { key, value -> prefs.edit().putInt(key, value).commit() }, localPrefs::getString,
        { key, value -> localPrefs.edit().putString(key, value).apply() },
        { localPrefs.edit().remove("npuCheck").apply() },
        { endpoint, model, mode -> prefs.edit().putString("endpoint", endpoint).putString("model", model).putString("endpointMode", mode.name).commit() },
        ::validateEndpoint, ::saveKey, ::sourceChanged, ::refreshReady)
    val source get() = preferences.source
    val backend get() = preferences.backend
    val language get() = preferences.language
    val localStatus get() = preferences.localStatus
    val modelRevision get() = preferences.modelRevision
    val customEndpoint get() = preferences.customEndpoint
    val customModel get() = preferences.customModel
    val endpointMode get() = preferences.endpointMode
    val customEndpointMode get() = preferences.customEndpointMode
    val customPrompt get() = preferences.customPrompt
    fun setLanguage(value: LocalAsrLanguage) = preferences.setLanguage(value)
    fun npuCheck(key: String) = preferences.npuCheck(key)
    fun recordNpuCheck(key: String, ok: Boolean) = preferences.recordNpuCheck(key, ok)
    fun setCustomPrompt(on: Boolean) = preferences.setCustomPrompt(on)
    private val _ready = MutableStateFlow(false)
    override val ready = _ready.asStateFlow()
    private val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        preferences.localPreferenceChanged(); refreshReady()
    }
    init { localPrefs.registerOnSharedPreferenceChangeListener(listener) }
    fun downloadStatus(backend: app.resound.transcribe.local.LocalBackend) = localPrefs.getString("download-${backend.name}", "未下载")!!
    fun modelReady(backend: app.resound.transcribe.local.LocalBackend) = app.resound.transcribe.local.LocalModels.ready(context, app.resound.transcribe.local.LocalModels.spec(backend))
    fun download(backend: app.resound.transcribe.local.LocalBackend, retry: Boolean = false) = app.resound.transcribe.local.LocalModels.enqueue(context, backend, retry)
    fun setLocalStatus(value: String) = preferences.setLocalStatus(value)
    fun setSource(value: TranscribeSource) = preferences.setSource(value)
    fun setBackend(value: app.resound.transcribe.local.LocalBackend) = preferences.setBackend(value)
    fun saveCustom(endpoint: String, model: String, mode: CustomEndpointMode = endpointMode.value) = preferences.saveCustom(endpoint, model, mode)
    private fun sourceChanged() {
        _hasKey.value = runCatching { readKey().isNotBlank() || headers(source.value.name).any { it.value.isNotBlank() } }.getOrDefault(false)
    }
    suspend fun fetchModels(endpoint: String, key: String) = fetchRemoteModels(endpoint, key.trim().ifBlank { readKey() }, headers = headers(TranscribeSource.Custom.name))
    fun saveCustomAll(endpoint: String, model: String, key: String, mode: CustomEndpointMode = endpointMode.value) =
        preferences.saveCustomAll(endpoint, model, key, mode)
    /** 完整地址按填写的原样校验；自动模式沿用 OpenAI 兼容的路径规则。 */
    private fun validateEndpoint(endpoint: String, mode: CustomEndpointMode) {
        if (mode == CustomEndpointMode.Full) fullEndpoint(endpoint) else OpenAiEndpoint.bases(endpoint)
    }
    fun identity() = "${source.value}:${backend.value}:${endpointMode.value.name}:$customEndpoint:$customModel:${keyFingerprint(if(source.value==TranscribeSource.Local) "" else readKey())}:${keyFingerprint(org.json.JSONArray(headers(source.value.name).map { listOf(it.name, it.value) }).toString())}" +
        if (source.value == TranscribeSource.Local) ":${language.value.code}" else ""
    internal fun credential(source: TranscribeSource) = if (source == TranscribeSource.Local) "local-model" else readKey(source)
    internal fun ready(source: TranscribeSource): Boolean = when (source) {
        TranscribeSource.Local -> modelReady(if (backend.value == app.resound.transcribe.local.LocalBackend.Npu && app.resound.transcribe.local.LocalModels.npuSupported()) backend.value else app.resound.transcribe.local.LocalBackend.Cpu)
        TranscribeSource.Doubao -> runCatching { readKey(source).isNotBlank() || headers(source.name).any { it.value.isNotBlank() } }.getOrDefault(false)
        TranscribeSource.Custom -> customModel.isNotBlank() && runCatching { validateEndpoint(customEndpoint, endpointMode.value); true }.getOrDefault(false)
    }
    fun credential() = if(source.value==TranscribeSource.Local) "local-model" else readKey()
    private fun keyField() = if(source.value==TranscribeSource.Custom) "customEncryptedKey" else "encryptedKey"
    fun refreshReady() { _ready.value=if(source.value==TranscribeSource.Local) {
        if(backend.value==app.resound.transcribe.local.LocalBackend.Npu && app.resound.transcribe.local.LocalModels.npuSupported()) modelReady(app.resound.transcribe.local.LocalBackend.Npu)
        else modelReady(app.resound.transcribe.local.LocalBackend.Cpu)
    } else usable()
        _hasKey.value = usable()
    }
    private fun usable() = (source.value == TranscribeSource.Custom && customEndpoint.isNotBlank() && customModel.isNotBlank()) || runCatching {
        readKey().isNotBlank() || headers(source.value.name).any { it.value.isNotBlank() }
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
        val encoded = prefs.getString(if (source == TranscribeSource.Custom) "customEncryptedKey" else "encryptedKey", null) ?: return ""
        val bytes = Base64.decode(encoded, Base64.NO_WRAP)
        require(bytes.size > 12)
        return Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, secret(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            doFinal(bytes.copyOfRange(12, bytes.size)).toString(Charsets.UTF_8)
        }
    }
    @Synchronized
    override fun saveKey(key: String) {
        val value = key.trim()
        if (value.isEmpty()) { clearKey(); return }
        // 请求头不得带换行；任何校验错误均不回显输入。
        require(value.all { it.code in 33..126 }) { "密钥格式不正确" }
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, secret())
            val encrypted = cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
            check(prefs.edit().putString(keyField(), Base64.encodeToString(encrypted, Base64.NO_WRAP)).commit())
            _hasKey.value = true
        } catch (_: Exception) { throw IllegalStateException("密钥安全保存失败，请重试") }
        refreshReady(); onChanged()
    }
    @Synchronized
    override fun clearKey() {
        check(prefs.edit().remove(keyField()).commit()) { "密钥清除失败，请重试" }
        _hasKey.value = false
        refreshReady(); onChanged()
    }
    override fun setIntervalMinutes(minutes: Int) = preferences.setIntervalMinutes(minutes)
    override fun setWifiOnly(value: Boolean) = preferences.setWifiOnly(value)
    override suspend fun test(): Result<String> = withContext(Dispatchers.IO) {
        val key = try { credential() } catch (_: Exception) { return@withContext Result.failure(IllegalStateException("密钥安全读取失败，请重新填写")) }
        if (key.isBlank() && headers(source.value.name).isEmpty() && source.value != TranscribeSource.Custom) Result.failure(IllegalStateException("还没填写转写密钥"))
        else try { testKey(key) } catch (e: CancellationException) { throw e }
        catch (_: Exception) { Result.failure(IllegalStateException("转写测试失败，请重试")) }
    }
    companion object { private const val ALIAS = "resound-asr-aes-v1" }
}
