package app.resound.backup

import android.app.Application
import android.content.Context
import app.resound.digest.AndroidLlmProviders
import app.resound.digest.LlmClient
import app.resound.digest.ProviderKeys
import app.resound.push.PushFields
import app.resound.push.PushKind
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, application = Application::class)
class BackupSecrets078Test {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private lateinit var root: File
    private fun prefs(store: String) = context.getSharedPreferences(store, 0)
    @Before fun setup() {
        (BackupSettings.prefs + BackupSettings.stores.keys + "backup-encryption-secrets").forEach { prefs(it).edit().clear().commit() }
        root = File(context.cacheDir, "backup078").apply { mkdirs() }
    }
    @After fun close() { root.deleteRecursively() }

    @Test fun allCredentialStoresRoundTripAlongsideProviderAndNewSettings() {
        // Robolectric 无手机 Keystore；只用假凭证替代加解密，实际执行序列化、校验和逐项恢复。
        val credentials = mutableMapOf<Pair<String, String>, String>()
        fun seed(store: String, key: String, value: String = "fixture-$store-$key") {
            credentials[store to key] = value
            check(prefs(store).edit().putString(key, "fixture-ciphertext").commit())
        }
        seed("transcribe", "encryptedKey"); seed("transcribe", "customEncryptedKey")
        listOf("deepseek", "custom", "doubao").forEach { seed("digest-secrets", "key-$it") }
        fun providers() = AndroidLlmProviders(context, LlmClient(), object : ProviderKeys {
            override fun has(id: String) = credentials.containsKey("digest-secrets" to "key-$id")
            override fun read(id: String) = credentials.getValue("digest-secrets" to "key-$id")
            override fun write(id: String, key: String) = seed("digest-secrets", "key-$id", key)
            override fun remove(id: String) { error("本测试不删密钥") }
        })
        val providers = providers()
        val ids = providers.templates.map { template ->
            providers.saveNow(null, template.id, template.name, template.baseUrl.replace("{WorkspaceId}", "fixture-workspace"),
                template.models.first(), "fixture-${template.id}", "", "")
        } + providers.saveNow(null, null, "另一个自建服务", "https://example.invalid/v1", "model", "fixture-custom-076", "", "")
        providers.activate(ids.last())
        PushKind.entries.forEach { kind -> PushFields.fields(kind).filter { it.secret }.forEach { field ->
            seed("push-secrets", UUID.randomUUID().toString(), "fixture-${kind.name}-${field.key}")
        } }
        seed("backup-secrets", "password")
        prefs("backup").edit().putString("account", "fixture@example.invalid").putBoolean("enabled", true).commit()
        prefs("transcribe").edit().putString("hotwords", "[\"回响\"]").putString("model", "fixture-asr-model").commit()
        prefs("asr-speed").edit().putString("history", "[{\"at\":0,\"separate\":false,\"classroom\":false,\"until\":null}]").commit()
        prefs("env-sound").edit().putBoolean("enabled", true).putString("skip", "On").commit()
        prefs("redo-choices").edit().putString("asr", "Custom").putString("llm", ids.first()).putFloat("local-ratio", .2f).commit()
        prefs("import-kinds").edit().putString("last", "Training").commit()
        prefs("app-update").edit().putLong("previewChannel", 1).putLong("autoOff", 1).putString("ready", "device-cache").commit()
        val beforeKeys = credentials.toMap()
        val beforeProviders = providers.saved.value
        val configs = listOf("backup", "transcribe", "llm-providers", "asr-speed", "env-sound", "redo-choices", "import-kinds", "app-update")
        val beforeSettings = configs.associateWith { prefs(it).all.toMap().filterKeys { key -> key !in setOf("encryptedKey", "customEncryptedKey", "local-ratio", "ready") } }
        BackupSettings.capture(context, root) { store, key -> credentials.getValue(store to key) }
        val exported = JSONObject(File(root, "secrets.json").readText())
        assertEquals(beforeKeys.size, exported.keys().asSequence().sumOf { exported.getJSONObject(it).length() })
        assertEquals(beforeKeys.size, beforeKeys.values.toSet().size) // 每项不同，避免串密钥也蒙混过关。
        configs.forEach { prefs(it).edit().clear().commit() }
        credentials.clear()
        BackupSettings.stores.keys.forEach { prefs(it).edit().clear().commit() }
        seed("push-secrets", "only-on-target", "fixture-retained")
        BackupSettings.apply(context, root) { store, key, value -> seed(store, key, value) }
        assertEquals(beforeKeys + (("push-secrets" to "only-on-target") to "fixture-retained"), credentials)
        beforeSettings.forEach { (store, values) ->
            assertEquals(values, prefs(store).all.filterKeys { it !in setOf("encryptedKey", "customEncryptedKey") })
        }
        val restored = providers()
        assertEquals(beforeProviders, restored.saved.value); assertEquals(ids.last(), restored.activeId.value)
        ids.forEach { id -> assertEquals(beforeKeys.getValue("digest-secrets" to "key-$id"), restored.credentials(id).second) }
    }

    @Test fun unreadableCredentialAbortsCaptureInsteadOfSilentlyOmittingIt() {
        prefs("digest-secrets").edit().putString("key-test-provider", "fixture-unreadable").commit()
        assertTrue(runCatching { BackupSettings.capture(context, root) { _, _ -> error("模拟安全区暂不可读") } }.isFailure)
        assertFalse(File(root, "secrets.json").exists())
    }
}
