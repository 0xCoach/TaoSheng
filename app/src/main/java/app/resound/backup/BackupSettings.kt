package app.resound.backup

import android.content.Context
import app.resound.digest.SecretStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** 密钥容器必须解密后导出，绝不能复制绑定旧手机 Keystore 的密文 XML。 */
internal object BackupSettings {
    val prefs = setOf("transcribe", "local-asr", "capture", "ui", "review", "push", "diary", "digest", "setup",
        "backup", "ask-selection", "usage")
    val stores = mapOf("digest-secrets" to "resound-llm-aes-v1", "push-secrets" to "resound-push-aes-v1",
        "backup-secrets" to "resound-backup-aes-v1", "transcribe" to "resound-asr-aes-v1")
    private val asrKeys = setOf("encryptedKey", "customEncryptedKey")
    private fun setting(store: String, key: String): Boolean = !(store == "transcribe" && key in asrKeys) &&
        !(store == "backup" && (key.startsWith("base-") || key in setOf("lastAt", "lastBytes", "count", "error", "localLastAt", "localLastBytes", "localCount", "localError"))) &&
        !(store == "local-asr" && (key == "actual" || key.startsWith("download-")))
    fun capture(context: Context, target: File, read: (String, String) -> String = { store, key ->
        SecretStore(context, store, stores.getValue(store)).read(key)
    }) {
        val settings = JSONObject()
        prefs.sorted().forEach { name ->
            val entries = JSONObject()
            context.getSharedPreferences(name, 0).all.toSortedMap().filterKeys { setting(name, it) }.forEach { (key, value) ->
                val type = when (value) { is Boolean -> "boolean"; is Int -> "int"; is Long -> "long"; is Float -> "float"; is Set<*> -> "set"; else -> "string" }
                entries.put(key, JSONObject().put("type", type).put("value", if (value is Set<*>) JSONArray(value.sortedBy { it.toString() }) else value))
            }
            settings.put(name, entries)
        }
        File(target, "settings.json").writeText(DayVolumeRows.canonical(settings))
        val secrets = JSONObject()
        stores.forEach { (name, _) ->
            val entries = JSONObject()
            context.getSharedPreferences(name, 0).all.keys.filter { name != "transcribe" || it in asrKeys }.sorted().forEach { key ->
                // 读取失败中止本次备份，不把不完整的密钥集合冒充完整备份。
                entries.put(key, read(name, key))
            }
            secrets.put(name, entries)
        }
        File(target, "secrets.json").writeText(DayVolumeRows.canonical(secrets))
    }
    fun validate(settings: JSONObject, secrets: JSONObject) {
        settings.keys().forEach { name ->
            require(name in prefs)
            settings.getJSONObject(name).keys().forEach { key ->
                require(setting(name, key))
                val item = settings.getJSONObject(name).getJSONObject(key)
                require(item.getString("type") in setOf("boolean", "int", "long", "float", "set", "string"))
                item.get("value")
            }
        }
        secrets.keys().forEach { name ->
            require(name in stores)
            secrets.getJSONObject(name).keys().forEach { key ->
                require(name != "transcribe" || key in asrKeys)
                secrets.getJSONObject(name).getString(key)
            }
        }
    }
    fun apply(context: Context, source: File, write: (String, String, String) -> Unit = { store, key, value ->
        SecretStore(context, store, stores.getValue(store)).write(key, value)
    }) {
        val settings = JSONObject(File(source, "settings.json").readText())
        val secrets = JSONObject(File(source, "secrets.json").readText())
        validate(settings, secrets)
        settings.keys().forEach { name ->
            val edit = context.getSharedPreferences(name, 0).edit()
            val entries = settings.getJSONObject(name)
            entries.keys().forEach { key ->
                val item = entries.getJSONObject(key)
                when (item.getString("type")) {
                    "boolean" -> edit.putBoolean(key, item.getBoolean("value"))
                    "int" -> edit.putInt(key, item.getInt("value"))
                    "long" -> edit.putLong(key, item.getLong("value"))
                    "float" -> edit.putFloat(key, item.getDouble("value").toFloat())
                    "set" -> { val a = item.getJSONArray("value"); edit.putStringSet(key, (0 until a.length()).map { a.getString(it) }.toSet()) }
                    "string" -> edit.putString(key, item.getString("value"))
                }
            }
            check(edit.commit())
        }
        secrets.keys().forEach { name ->
            val entries = secrets.getJSONObject(name)
            entries.keys().forEach { key -> write(name, key, entries.getString(key)) }
        }
    }
}
