package app.resound.transcribe

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
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

enum class TranscribeSource(val title: String) { Doubao("豆包"), Local("本地"), Custom("自定义") }

class AndroidTranscribeSettings(private val context: Context) : TranscribeSettings {
    private val localPrefs = context.getSharedPreferences("local-asr", Context.MODE_PRIVATE)
    private val prefs = context.getSharedPreferences("transcribe", Context.MODE_PRIVATE)
    private val _source = MutableStateFlow(runCatching { TranscribeSource.valueOf(prefs.getString("source", "Doubao")!!) }.getOrDefault(TranscribeSource.Doubao))
    val source = _source.asStateFlow()
    private val _backend = MutableStateFlow(runCatching { app.resound.transcribe.local.LocalBackend.valueOf(prefs.getString("backend", "Npu")!!) }.getOrDefault(app.resound.transcribe.local.LocalBackend.Npu))
    val backend = _backend.asStateFlow()
    private val _localStatus = MutableStateFlow(localPrefs.getString("actual", "尚未运行；首次转写会检查加速能力")!!)
    val localStatus = _localStatus.asStateFlow()
    private val _modelRevision = MutableStateFlow(0)
    val modelRevision = _modelRevision.asStateFlow()
    /** 记住的 NPU 检查结果：true 通过、false 没通过、null 还没查过（或版本、模型变了） */
    fun npuCheck(key: String): Boolean? = when (localPrefs.getString("npuCheck", null)) {
        "pass:$key" -> true
        "fail:$key" -> false
        else -> null
    }
    fun recordNpuCheck(key: String, ok: Boolean) { localPrefs.edit().putString("npuCheck", "${if (ok) "pass" else "fail"}:$key").apply() }
    val customEndpoint get() = prefs.getString("endpoint", "")!!
    val customModel get() = prefs.getString("model", "")!!
    private val _ready = MutableStateFlow(false)
    override val ready = _ready.asStateFlow()
    private val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        _modelRevision.value++; refreshReady()
    }
    init { localPrefs.registerOnSharedPreferenceChangeListener(listener) }
    fun downloadStatus(backend: app.resound.transcribe.local.LocalBackend) = localPrefs.getString("download-${backend.name}", "未下载")!!
    fun modelReady(backend: app.resound.transcribe.local.LocalBackend) = app.resound.transcribe.local.LocalModels.ready(context, app.resound.transcribe.local.LocalModels.spec(backend))
    fun download(backend: app.resound.transcribe.local.LocalBackend) = app.resound.transcribe.local.LocalModels.enqueue(context, backend)
    fun setLocalStatus(value: String) { _localStatus.value=value;localPrefs.edit().putString("actual",value).apply() }
    fun setSource(value: TranscribeSource) { prefs.edit().putString("source",value.name).commit();_source.value=value;_hasKey.value=runCatching{readKey().isNotBlank()}.getOrDefault(false);refreshReady();onChanged() }
    fun setBackend(value: app.resound.transcribe.local.LocalBackend) { prefs.edit().putString("backend",value.name).commit();_backend.value=value;localPrefs.edit().remove("npuCheck").apply();setLocalStatus(if(value==app.resound.transcribe.local.LocalBackend.Npu) "已选择 NPU 加速，下次转写时检查一次" else "已选择 CPU 兼容");refreshReady();onChanged() }
    fun saveCustom(endpoint: String, model: String) {
        OpenAiEndpoint.bases(endpoint)
        require(model.trim().isNotEmpty()) { "请填写模型名" }
        prefs.edit().putString("endpoint",endpoint.trim()).putString("model",model.trim()).commit();refreshReady(); onChanged()
    }
    suspend fun fetchModels(endpoint: String, key: String) = fetchRemoteModels(endpoint, key.trim().ifBlank { readKey() })
    fun saveCustomAll(endpoint: String, model: String, key: String) {
        OpenAiEndpoint.bases(endpoint)
        require(model.trim().isNotEmpty()) { "请填写或选择模型" }
        require(key.isNotBlank() || readKey().isNotBlank()) { "请填写 API Key" }
        if(key.isNotBlank()) saveKey(key)
        saveCustom(endpoint,model)
    }
    fun identity() = "${source.value}:${backend.value}:$customEndpoint:$customModel:${keyFingerprint(if(source.value==TranscribeSource.Local) "" else readKey())}"
    fun credential() = if(source.value==TranscribeSource.Local) "local-model" else readKey()
    private fun keyField() = if(source.value==TranscribeSource.Custom) "customEncryptedKey" else "encryptedKey"
    fun refreshReady() { _ready.value=if(source.value==TranscribeSource.Local) {
        if(backend.value==app.resound.transcribe.local.LocalBackend.Npu && app.resound.transcribe.local.LocalModels.npuSupported()) modelReady(app.resound.transcribe.local.LocalBackend.Npu)
        else modelReady(app.resound.transcribe.local.LocalBackend.Cpu)
    } else runCatching{readKey().isNotBlank() && (source.value!=TranscribeSource.Custom || customEndpoint.isNotBlank() && customModel.isNotBlank())}.getOrDefault(false) }
    private val _hasKey = MutableStateFlow(runCatching { readKey().isNotBlank() }.getOrDefault(false))
    override val hasKey = _hasKey.asStateFlow()
    private val _interval = MutableStateFlow(prefs.getInt("interval", 30).takeIf { it in MIN_INTERVAL..MAX_INTERVAL } ?: 30)
    override val intervalMinutes = _interval.asStateFlow()
    private val _wifi = MutableStateFlow(prefs.getBoolean("wifi", true))
    override val wifiOnly = _wifi.asStateFlow()
    var onChanged: () -> Unit = {}
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
    internal fun readKey(): String {
        val encoded = prefs.getString(keyField(), null) ?: return ""
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
    override fun setIntervalMinutes(minutes: Int) {
        require(minutes in MIN_INTERVAL..MAX_INTERVAL) { "间隔需在 $MIN_INTERVAL～$MAX_INTERVAL 分钟之间" }
        check(prefs.edit().putInt("interval", minutes).commit())
        _interval.value = minutes; refreshReady(); onChanged()
    }
    override fun setWifiOnly(value: Boolean) {
        check(prefs.edit().putBoolean("wifi", value).commit())
        _wifi.value = value; refreshReady(); onChanged()
    }
    override suspend fun test(): Result<String> = withContext(Dispatchers.IO) {
        val key = try { credential() } catch (_: Exception) { return@withContext Result.failure(IllegalStateException("密钥安全读取失败，请重新填写")) }
        if (key.isBlank()) Result.failure(IllegalStateException("还没填写转写密钥"))
        else try { testKey(key) } catch (e: CancellationException) { throw e }
        catch (_: Exception) { Result.failure(IllegalStateException("转写测试失败，请重试")) }
    }
    companion object { private const val ALIAS = "resound-asr-aes-v1" }
}
