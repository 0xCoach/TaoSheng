package app.resound.settings

import app.resound.digest.HeaderEntry
import app.resound.digest.LlmPreset
import app.resound.digest.LlmProviderSettings
import app.resound.digest.ProviderAccess
import app.resound.digest.SecretHeaders
import app.resound.platform.GroupedKeyValueStore
import app.resound.platform.KeyValueEdit
import app.resound.platform.KeyValueStores
import app.resound.platform.SecretVault
import app.resound.transcribe.CustomAsrApi
import app.resound.transcribe.CustomAsrSettings
import app.resound.transcribe.CustomEndpointMode
import app.resound.transcribe.TranscribeConfiguration
import app.resound.transcribe.TranscribeSource
import app.resound.transcribe.local.LocalAsrLanguage
import org.junit.Assert.*
import org.junit.Test

class SharedSettings233Test {
    private val groups = mutableMapOf<String, MemoryPreferences>()
    private val stores = KeyValueStores { groups.getOrPut(it, ::MemoryPreferences) }
    private val vault = MemoryVault()
    private val headerGroup = "transcribe-header-secrets"
    private var nextId = 0

    private fun providers() = LlmProviderSettings(
        stores, vault, SecretHeaders(vault, "digest-secrets"),
        """[{"id":"deepseek","name":"DeepSeek","baseUrl":"https://api.deepseek.com","models":["deepseek-chat"]}]""",
        { "provider-${++nextId}" }, {}, { it.startsWith("http://127.0.0.1") },
        object : ProviderAccess { override fun <T> run(action: () -> T): T = action() },
    )

    private fun transcribe() = TranscribeConfiguration(
        stores, vault, SecretHeaders(vault, headerGroup),
        { _, default -> default }, { _, _ -> }, {}, { _, _ -> }, {}, {},
    )

    @Test fun providerEditsAndSelectionReloadWithOneNotificationEach() {
        val settings = providers()
        val events = mutableListOf<String?>()
        settings.listen { events.add(it) }
        val id = settings.save(null, null, " 添加 ", "https://example.invalid/v1/", " model ", " test-key ", "", "")
        assertEquals(id, settings.activeId.value)
        assertEquals(listOf(id), events)

        settings.save(id, null, "重命名", "https://example.invalid/v1", "next", null, "", "")
        assertEquals(listOf(id, id), events)
        assertEquals("test-key", settings.readKey(id))

        settings.move(id, 0)
        settings.activate("deepseek")
        settings.activate("deepseek")
        assertEquals(listOf(id, id, null, null), events)
        val reloaded = providers()
        assertEquals("deepseek", reloaded.activeId.value)
        assertEquals(listOf(id, "deepseek"), reloaded.saved.value.map { it.id })
        assertEquals("next", reloaded.credentials(id).first.model)

        settings.saveHeaders(id, listOf(HeaderEntry("x-token", "test-header")))
        assertEquals(listOf(id, id, null, null, id), events)
        settings.delete(id)
        assertEquals(listOf(id, id, null, null, id, id), events)
        assertEquals(listOf("deepseek"), providers().saved.value.map { it.id })
        assertEquals("", settings.readKey(id))
        assertTrue(settings.headers(id).isEmpty())
    }

    @Test fun legacyLocalAndCustomHeaderAreTheEmptyKeySources() {
        val settings = providers()
        val id = settings.save(null, null, "远程", "https://example.invalid", "model", null, "", "")
        val remote = LlmPreset(id, "远程", "https://example.invalid", "model")
        assertFalse(settings.canUseWithoutKey(remote))
        assertTrue(settings.canUseWithoutKey(remote.copy(id = "custom")))
        assertTrue(settings.canUseWithoutKey(remote.copy(baseUrl = "http://127.0.0.1:8080")))
        settings.saveHeaders(id, listOf(HeaderEntry("x-api-key", " ")))
        assertFalse(settings.canUseWithoutKey(remote))
        settings.saveHeaders(id, listOf(HeaderEntry("x-api-key", "test-header")))
        assertTrue(settings.canUseWithoutKey(remote))
    }

    @Test fun transcribeDefaultsKeepLocalAndSeparateCustomProfiles() {
        val settings = transcribe()
        assertEquals(TranscribeSource.Local, settings.preferences.source.value)
        assertEquals(30, settings.preferences.intervalMinutes.value)
        assertTrue(settings.preferences.realtime.value)
        assertTrue(settings.preferences.wifiOnly.value)
        assertEquals(LocalAsrLanguage.Auto, settings.preferences.language.value)
        assertFalse(settings.doubaoPlan.value)
        assertEquals(CustomAsrApi.OpenAi, settings.custom.api.value)
        assertEquals(CustomEndpointMode.Auto, settings.custom.endpointMode.value)
        assertEquals("", settings.custom.endpoint)
        assertEquals("", settings.custom.model)
        settings.custom.setApi(CustomAsrApi.Qwen)
        assertEquals(CustomAsrSettings.QWEN_ENDPOINT, settings.custom.endpoint)
        assertEquals(CustomAsrSettings.QWEN_MODEL, settings.custom.model)
        assertEquals(CustomEndpointMode.Full, settings.custom.endpointMode.value)
    }

    @Test fun transcribeKeyRoutesSurviveReopen() {
        val settings = transcribe()
        settings.preferences.setSource(TranscribeSource.Doubao)
        settings.saveKey(TranscribeSource.Doubao, "standard")
        settings.setDoubaoPlan(true)
        settings.saveKey(TranscribeSource.Doubao, "plan")
        settings.saveKey(TranscribeSource.Custom, "openai")
        settings.custom.setApi(CustomAsrApi.Qwen)
        settings.saveKey(TranscribeSource.Custom, "qwen")

        val reloaded = transcribe()
        assertEquals("plan", reloaded.readKey())
        assertEquals("qwen", reloaded.readKey(TranscribeSource.Custom))
        reloaded.setDoubaoPlan(false)
        reloaded.custom.setApi(CustomAsrApi.OpenAi)
        assertEquals("standard", reloaded.readKey())
        assertEquals("openai", reloaded.readKey(TranscribeSource.Custom))
        reloaded.clearKey()
        assertEquals("", reloaded.readKey())
        assertEquals("openai", reloaded.readKey(TranscribeSource.Custom))
    }

    private class MemoryVault : SecretVault {
        private val values = mutableMapOf<Pair<String, String>, String>()
        override fun read(group: String, key: String) = values[group to key].orEmpty()
        override fun write(group: String, key: String, value: String) { values[group to key] = value }
        override fun remove(group: String, key: String) { values.remove(group to key) }
        override fun has(group: String, key: String) = group to key in values
    }

    private class MemoryPreferences : GroupedKeyValueStore {
        private var values = mutableMapOf<String, Any>()
        override fun contains(key: String) = key in values
        override fun getString(key: String, default: String?) = values[key] as String? ?: default
        @Suppress("UNCHECKED_CAST")
        override fun getStrings(key: String) = (values[key] as List<String>?).orEmpty()
        override fun getLong(key: String, default: Long) = values[key] as Long? ?: default
        override fun getInt(key: String, default: Int) = values[key] as Int? ?: default
        override fun getBoolean(key: String, default: Boolean) = values[key] as Boolean? ?: default
        override fun commit(edit: KeyValueEdit.() -> Unit): Boolean {
            val next = values.toMutableMap()
            object : KeyValueEdit {
                override fun putString(key: String, value: String?) {
                    if (value == null) next.remove(key) else next[key] = value
                }
                override fun putStrings(key: String, value: List<String>) { next[key] = value.toList() }
                override fun putLong(key: String, value: Long) { next[key] = value }
                override fun putInt(key: String, value: Int) { next[key] = value }
                override fun putBoolean(key: String, value: Boolean) { next[key] = value }
            }.edit()
            values = next
            return true
        }
    }
}
