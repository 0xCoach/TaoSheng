package app.resound.platform

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.util.Base64
import app.resound.digest.AndroidLlmProviders
import app.resound.digest.HeaderEntry
import app.resound.digest.LlmClient
import app.resound.transcribe.AndroidTranscribeSettings
import app.resound.transcribe.CustomAsrApi
import app.resound.transcribe.CustomEndpointMode
import app.resound.transcribe.HotwordPreferenceStore
import app.resound.transcribe.TranscribeSource
import app.resound.transcribe.local.LocalAsrLanguage
import java.lang.reflect.Proxy
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class)
class SettingsUpgrade233Test {
    private val context get() = RuntimeEnvironment.getApplication()
    private fun prefs(group: String) = context.getSharedPreferences(group, Context.MODE_PRIVATE)
    private val llmKey = fixtureKey(1)
    private val asrKey = fixtureKey(2)
    private val asrHeaderKey = fixtureKey(3)

    @Test fun oldPreferencesAndCiphertextOpenAndKeepUneditedValues() = runBlocking {
        val providersJson = JSONArray(listOf(
            JSONObject().put("id", "saved-id").put("templateId", JSONObject.NULL).put("name", "我的 / 服务")
                .put("baseUrl", "https://example.invalid/v1").put("model", "old-model")
                .put("balanceUrl", "https://example.invalid/balance").put("balancePath", "data.available"),
            JSONObject().put("id", "deepseek").put("templateId", "deepseek").put("name", "DeepSeek")
                .put("baseUrl", "https://api.deepseek.com").put("model", "deepseek-chat"),
        ))
        prefs("llm-providers").edit().putString("saved", providersJson.toString()).putString("activeId", "saved-id")
            .putBoolean("placeholder", false).commit()
        val llmHeaders = listOf(HeaderEntry("X-Workspace", "test-project"), HeaderEntry("Authorization", "Bearer test-other"))
        val asrHeaders = listOf(HeaderEntry("X-Api-Key", "test-asr-header"))
        prefs("digest-secrets").edit().putString("key-saved-id", legacyCiphertext(llmKey, 1, "test-llm-key"))
            .putString("headers-saved-id", legacyCiphertext(llmKey, 2, legacyHeaderJson(llmHeaders))).commit()
        prefs("transcribe-header-secrets").edit()
            .putString("headers-Custom", legacyCiphertext(asrHeaderKey, 1, legacyHeaderJson(asrHeaders))).commit()
        val hotwords = listOf("人名甲", "术语 / 乙", "📻".repeat(20))
        prefs("transcribe").edit().putString("source", "Custom").putString("localLanguage", "yue")
            .putString("customApi", "Qwen").putString("qwenEndpoint", "https://example.invalid/asr?group=1")
            .putString("qwenModel", "old-asr").putString("qwenEndpointMode", "Full")
            .putString("hotwords", JSONArray(hotwords).toString()).putInt("interval", 47)
            .putBoolean("doubaoPlan", true)
            .putString("encryptedKey", legacyCiphertext(asrKey, 1, "test-standard"))
            .putString("planEncryptedKey", legacyCiphertext(asrKey, 2, "test-plan"))
            .putString("customEncryptedKey", legacyCiphertext(asrKey, 3, "test-openai"))
            .putString("qwenEncryptedKey", legacyCiphertext(asrKey, 4, "test-qwen")).commit()

        val stores = AndroidKeyValueStores(context)
        val vault = vault(context)
        fun providers() = AndroidLlmProviders(context, LlmClient(), stores, vault)
        fun transcribe() = AndroidTranscribeSettings(context, stores, vault)
        val oldProviders = prefs("llm-providers").all.toMap()
        val oldLlmSecrets = prefs("digest-secrets").all.toMap()
        val oldAsrSecrets = prefs("transcribe-header-secrets").all.toMap()
        val oldTranscribe = prefs("transcribe").all.toMap()

        val llm = providers()
        assertEquals(listOf("saved-id", "deepseek"), llm.saved.value.map { it.id })
        val p = llm.selected()
        assertEquals("saved-id", llm.activeId.value)
        assertEquals("我的 / 服务", p.name)
        assertNull(p.templateId)
        assertEquals("old-model", p.model)
        assertEquals("data.available", p.balancePath)
        assertTrue(p.hasKey)
        assertEquals("test-llm-key", llm.readKey(p.id))
        assertEquals(llmHeaders, llm.requestHeaders(p.presetForTest()))

        val asr = transcribe()
        assertEquals(TranscribeSource.Custom, asr.source.value)
        assertEquals(CustomAsrApi.Qwen, asr.customApi.value)
        assertEquals(CustomEndpointMode.Full, asr.customEndpointMode)
        assertEquals("https://example.invalid/asr?group=1", asr.customEndpoint)
        assertEquals("old-asr", asr.customModel)
        assertEquals(47, asr.intervalMinutes.value)
        assertEquals(LocalAsrLanguage.Cantonese, asr.language.value)
        assertEquals(hotwords, HotwordPreferenceStore(stores).words.value)
        assertEquals("test-qwen", asr.readKey())
        assertEquals("test-plan", asr.readKey(TranscribeSource.Doubao))
        assertEquals(asrHeaders, asr.headers("Custom"))
        assertEquals("test-standard", vault.read("transcribe", "encryptedKey"))
        assertEquals("test-openai", vault.read("transcribe", "customEncryptedKey"))
        assertEquals(oldProviders, prefs("llm-providers").all)
        assertEquals(oldTranscribe, prefs("transcribe").all)

        llm.save(p.id, p.templateId, p.name, p.baseUrl, "new-model", null, p.balanceUrl, p.balancePath)
        val reopenedLlm = providers()
        assertEquals(llm.saved.value, reopenedLlm.saved.value)
        assertEquals(p.copy(model = "new-model"), reopenedLlm.selected())
        assertEquals("test-llm-key", reopenedLlm.readKey(p.id))
        assertEquals(llmHeaders, reopenedLlm.headers(p.id))
        val updated = JSONArray(prefs("llm-providers").getString("saved", null))
        assertEquals(JSONObject.NULL, updated.getJSONObject(0).get("templateId"))
        assertEquals(oldProviders - "saved", prefs("llm-providers").all - "saved")

        asr.setIntervalMinutes(52)
        val reopenedAsr = transcribe()
        assertEquals(52, reopenedAsr.intervalMinutes.value)
        assertEquals(oldTranscribe - "interval", prefs("transcribe").all - "interval")
        assertEquals(oldLlmSecrets, prefs("digest-secrets").all)
        assertEquals(oldAsrSecrets, prefs("transcribe-header-secrets").all)
    }

    @Test fun failedBatchRestoresAndroidMemoryAndKeepsPersistedFieldsTogether() {
        val real = prefs("transcribe")
        real.edit().putString("source", "Custom").putString("endpoint", "https://example.invalid/old")
            .putString("model", "old").putString("endpointMode", "Full").putInt("interval", 47).commit()
        val before = real.all.toMap()
        var failNext = false
        val failingPrefs = object : SharedPreferences by real {
            override fun edit(): SharedPreferences.Editor {
                val editor = real.edit()
                return Proxy.newProxyInstance(SharedPreferences.Editor::class.java.classLoader,
                    arrayOf(SharedPreferences.Editor::class.java)) { proxy, method, args ->
                    if (method.name == "commit" && failNext) {
                        failNext = false
                        // 模拟平台先提交到进程内存，随后返回磁盘写入失败。
                        editor.apply()
                        false
                    } else {
                        method.invoke(editor, *(args ?: emptyArray())).let { if (it is SharedPreferences.Editor) proxy else it }
                    }
                } as SharedPreferences.Editor
            }
        }
        val wrapped = object : ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int) =
                if (name == "transcribe") failingPrefs else super.getSharedPreferences(name, mode)
        }
        val settings = AndroidTranscribeSettings(wrapped)
        var notices = 0
        settings.onChanged = { notices++ }
        failNext = true
        assertThrows(IllegalStateException::class.java) {
            settings.saveCustom("https://example.invalid/new", "new", CustomEndpointMode.Auto)
        }
        assertEquals(before, real.all)
        assertEquals(0, notices)
        assertEquals("old", settings.customModel)
        assertEquals("https://example.invalid/old", settings.customEndpoint)
        assertEquals(CustomEndpointMode.Full, AndroidTranscribeSettings(context).customEndpointMode)
    }

    private fun vault(context: Context) = AndroidSecretVault(context) { alias ->
        when (alias) {
            "resound-llm-aes-v1" -> llmKey
            "resound-asr-aes-v1" -> asrKey
            "resound-asr-headers-aes-v1" -> asrHeaderKey
            else -> error("测试收到未知别名")
        }
    }

    private fun fixtureKey(value: Byte) = SecretKeySpec(ByteArray(16) { value }, "AES")

    // 旧版写入格式：Base64.NO_WRAP(12 字节 IV + AES-GCM 密文和标签)。
    private fun legacyCiphertext(key: SecretKeySpec, nonce: Byte, value: String): String {
        val iv = ByteArray(12).also { it[0] = nonce }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        return Base64.encodeToString(iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
    }

    private fun legacyHeaderJson(headers: List<HeaderEntry>) =
        JSONArray(headers.map { JSONObject().put("name", it.name).put("value", it.value) }).toString()

    private fun app.resound.digest.SavedProvider.presetForTest() =
        app.resound.digest.LlmPreset(id, name, baseUrl, model)
}
