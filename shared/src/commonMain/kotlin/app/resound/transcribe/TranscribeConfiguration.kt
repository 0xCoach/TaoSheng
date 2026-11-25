package app.resound.transcribe

import app.resound.digest.HeaderEntry
import app.resound.digest.SecretHeaders
import app.resound.platform.KeyValueStores
import app.resound.platform.SecretVault
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 存储格式沿用已发布的转写配置。平台注入本机状态、地址校验与验证缓存。 */
class TranscribeConfiguration(
    stores: KeyValueStores,
    private val vault: SecretVault,
    private val headers: SecretHeaders,
    readLocalString: (String, String?) -> String?,
    saveLocalString: (String, String) -> Unit,
    clearNpuCheck: () -> Unit,
    validateEndpoint: (String, CustomEndpointMode) -> Unit,
    sourceChanged: () -> Unit,
    private val refreshReady: () -> Unit,
    openAiFieldsChanged: () -> Unit = {},
    loadVerified: (CustomAsrApi) -> CustomCapability? = { null },
    saveVerified: (CustomAsrApi, CustomCapability?) -> Unit = { _, _ -> },
    hasCustomKey: (String) -> Boolean = { vault.has(GROUP, it) },
    private val onKeyStored: (Boolean) -> Unit = {},
) {
    private val prefs = stores.open(GROUP)
    private val plan = MutableStateFlow(prefs.getBoolean("doubaoPlan", false))
    val doubaoPlan = plan.asStateFlow()

    val custom = CustomAsrSettings(
        prefs::getString,
        prefs::getBoolean,
        { key, value -> check(prefs.commit { putString(key, value) }) { "保存失败，请重试" } },
        { key, value -> check(prefs.commit { putBoolean(key, value) }) { "保存失败，请重试" } },
        { api, endpoint, model, mode ->
            val qwen = api == CustomAsrApi.Qwen
            val addressField = if (qwen) "qwenEndpoint" else "endpoint"
            val modelField = if (qwen) "qwenModel" else "model"
            val modeField = if (qwen) "qwenEndpointMode" else "endpointMode"
            val different = prefs.getString(addressField, "") != endpoint || prefs.getString(modelField, "") != model ||
                prefs.getString(modeField, CustomEndpointMode.Auto.name) != mode.name
            check(prefs.commit {
                putString(addressField, endpoint)
                putString(modelField, model)
                putString(modeField, mode.name)
            }) { "保存失败，请重试" }
            if (!qwen && different) openAiFieldsChanged()
        },
        validateEndpoint,
        { api -> hasCustomKey(CustomAsrSettings.keyField(api)) ||
            runCatching { headers.headers(TranscribeSource.Custom.name).any { it.value.isNotBlank() } }.getOrDefault(false) },
        ::changed,
        loadVerified,
        saveVerified,
    )

    val preferences = TranscribePreferenceStore(
        prefs::getString, prefs::getBoolean, prefs::getInt, prefs::contains,
        { key, value -> prefs.commit { putString(key, value) } },
        { key, value -> prefs.commit { putBoolean(key, value) } },
        { key, value -> prefs.commit { putInt(key, value) } },
        readLocalString, saveLocalString, clearNpuCheck, sourceChanged, refreshReady,
        { vault.has(GROUP, STANDARD_KEY) || vault.has(GROUP, PLAN_KEY) },
    )

    fun setDoubaoPlan(on: Boolean) {
        check(prefs.commit { putBoolean("doubaoPlan", on) }) { "保存失败，请重试" }
        plan.value = on
        changed()
    }

    fun readKey(source: TranscribeSource = preferences.source.value): String = vault.read(GROUP, keyField(source))

    fun saveKey(source: TranscribeSource, key: String) {
        val value = key.trim()
        require(value.all { it.code in 33..126 }) { "密钥格式不正确" }
        if (value.isEmpty()) {
            clearKey()
            return
        }
        try {
            vault.write(GROUP, keyField(source), value)
        } catch (_: Exception) {
            throw IllegalStateException("密钥安全保存失败，请重试")
        }
        onKeyStored(true)
        if (source == TranscribeSource.Custom) custom.invalidate(custom.api.value)
        changed()
    }

    fun clearKey() {
        val source = preferences.source.value
        vault.remove(GROUP, keyField(source))
        onKeyStored(false)
        if (source == TranscribeSource.Custom) custom.invalidate(custom.api.value)
        changed()
    }

    fun headers(id: String) = headers.headers(id)

    fun saveHeaders(id: String, values: List<HeaderEntry>) {
        require(TranscribeSource.entries.any { it.name == id }) { "转写服务不存在" }
        headers.save(id, values)
        if (id == TranscribeSource.Custom.name) custom.invalidateAll()
        changed()
    }

    fun saveCustomAll(endpoint: String, model: String, key: String, mode: CustomEndpointMode): CustomSaveResult {
        custom.validateFields(endpoint, model, mode)
        val keySaved = key.isNotBlank()
        if (keySaved) saveKey(TranscribeSource.Custom, key)
        custom.save(endpoint, model, mode)
        return CustomSaveResult(keySaved, custom.keyState.value)
    }

    private fun keyField(source: TranscribeSource) = when {
        source == TranscribeSource.Custom -> CustomAsrSettings.keyField(custom.api.value)
        plan.value -> PLAN_KEY
        else -> STANDARD_KEY
    }

    private fun changed() {
        refreshReady()
        preferences.onChanged()
    }

    companion object {
        const val GROUP = "transcribe"
        private const val STANDARD_KEY = "encryptedKey"
        private const val PLAN_KEY = "planEncryptedKey"
    }
}
