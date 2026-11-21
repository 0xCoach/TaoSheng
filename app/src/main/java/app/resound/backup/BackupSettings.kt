package app.resound.backup

import android.content.Context
import app.resound.digest.SecretStore
import app.resound.diagnostics.FailureNotes
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** 密钥容器必须解密后导出，绝不能复制绑定旧手机 Keystore 的密文 XML。 */
internal object BackupSettings {
    val prefs = PreferenceBackupPolicy.settings.keys
    val stores = mapOf("digest-secrets" to "resound-llm-aes-v1", "push-secrets" to "resound-push-aes-v1",
        "backup-secrets" to "resound-backup-aes-v1", "transcribe" to "resound-asr-aes-v1",
        "transcribe-header-secrets" to "resound-asr-headers-aes-v1", "location-secrets" to "resound-location-aes-v1",
        "backup-encryption-secrets" to "resound-backup-passphrase-v1",
        "agent-secrets" to "agent-bridge-v1", "mcp-secrets" to "resound-mcp-aes-v1")
    private val asrKeys = PreferenceBackupPolicy.asrKeys
    // 默认本机密钥容器有明确键空间；显式注入的读写端口仍保留既有通用键空间约定。
    private class LocalSecretReader(private val context: Context) : (String, String) -> String {
        override fun invoke(store: String, key: String) = SecretStore(context, store, stores.getValue(store)).read(key)
    }
    private class LocalSecretWriter(private val context: Context) : (String, String, String) -> Unit {
        override fun invoke(store: String, key: String, value: String) = SecretStore(context, store, stores.getValue(store)).write(key, value)
    }
    internal fun setting(store: String, key: String): Boolean = PreferenceBackupPolicy.setting(store, key)
    private fun value(store: String, key: String, value: Any?): Any? {
        if (value !is String) return value
        return try { PreferenceBackupPolicy.stringValue(store, key, value) }
        catch (failure: Exception) {
            FailureNotes.record("backup/structured-preference", failure)
            // JSON 解析器异常可能夹带字段内容，不能把它带进既有备份诊断。
            throw BackupFailure("备份设置结构损坏，请修复设备或监听目录设置后重试")
        }
    }

    internal fun values(context: Context, name: String): Map<String, Any?> =
        context.getSharedPreferences(name, 0).all.filterKeys { setting(name, it) }.mapValues { (key, v) -> value(name, key, v) }

    internal fun writeLegacyPreferences(context: Context, name: String, target: File) {
        target.parentFile!!.mkdirs()
        target.outputStream().use { output ->
            val xml = android.util.Xml.newSerializer()
            xml.setOutput(output, "UTF-8"); xml.startDocument("UTF-8", true); xml.startTag(null, "map")
            values(context, name).toSortedMap().forEach { (key, value) ->
                val tag = when (value) {
                    is Boolean -> "boolean"; is Int -> "int"; is Long -> "long"; is Float -> "float"
                    is Set<*> -> "set"; else -> "string"
                }
                xml.startTag(null, tag).attribute(null, "name", key)
                when (value) {
                    is String -> xml.text(value)
                    is Set<*> -> value.map { it as String }.sorted().forEach {
                        xml.startTag(null, "string").text(it).endTag(null, "string")
                    }
                    else -> xml.attribute(null, "value", value.toString())
                }
                xml.endTag(null, tag)
            }
            xml.endTag(null, "map"); xml.endDocument(); xml.flush()
        }
    }

    fun capture(context: Context, target: File, read: (String, String) -> String = LocalSecretReader(context)) {
        val settings = JSONObject()
        prefs.sorted().forEach { name ->
            val entries = JSONObject()
            values(context, name).toSortedMap().forEach { (key, value) ->
                val type = when (value) { is Boolean -> "boolean"; is Int -> "int"; is Long -> "long"; is Float -> "float"; is Set<*> -> "set"; else -> "string" }
                entries.put(key, JSONObject().put("type", type).put("value", if (value is Set<*>) JSONArray(value.sortedBy { it.toString() }) else value))
            }
            settings.put(name, entries)
        }
        File(target, "settings.json").writeText(DayVolumeRows.canonical(settings))
        val secrets = JSONObject()
        stores.forEach { (name, _) ->
            val entries = JSONObject()
            context.getSharedPreferences(name, 0).all.keys.filter {
                (name != "transcribe" || it in asrKeys) && (read !is LocalSecretReader || PreferenceBackupPolicy.secret(name, it))
            }.sorted().forEach { key ->
                // 读取失败中止本次备份，不把不完整的密钥集合冒充完整备份。
                val plain = try { read(name, key) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (caught: Exception) {
                    FailureNotes.record("backup/credential-read", caught)
                    throw BackupFailure("密钥读取失败，备份未完成；请检查密钥或重新保存后重试")
                }
                entries.put(key, plain)
            }
            secrets.put(name, entries)
        }
        File(target, "secrets.json").writeText(DayVolumeRows.canonical(secrets))
    }
    fun validate(settings: JSONObject, secrets: JSONObject) {
        settings.keys().forEach { name ->
            require(name in prefs || name in PreferenceBackupPolicy.excluded)
            settings.getJSONObject(name).keys().forEach { key ->
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
    fun apply(context: Context, source: File, write: (String, String, String) -> Unit = LocalSecretWriter(context)) {
        val settings = JSONObject(File(source, "settings.json").readText())
        val secrets = JSONObject(File(source, "secrets.json").readText())
        validate(settings, secrets)
        settings.keys().forEach { name ->
            if (name !in prefs) return@forEach
            val edit = context.getSharedPreferences(name, 0).edit()
            val entries = settings.getJSONObject(name)
            if (name == "backup" && !entries.has("cloud")) edit.putString("cloud", CloudKind.Nutstore.name)
            // 老备份只记录树 URI，不能误沿用当前手机的下载目录选择。
            if (name == "backup" && entries.has("localTree") && !entries.has("localDownloads")) edit.putBoolean("localDownloads", false)
            entries.keys().forEach keyLoop@ { key ->
                if (!setting(name, key)) return@keyLoop
                val item = entries.getJSONObject(key)
                when (item.getString("type")) {
                    "boolean" -> edit.putBoolean(key, item.getBoolean("value"))
                    "int" -> edit.putInt(key, item.getInt("value"))
                    "long" -> edit.putLong(key, item.getLong("value"))
                    "float" -> edit.putFloat(key, item.getDouble("value").toFloat())
                    "set" -> { val a = item.getJSONArray("value"); edit.putStringSet(key, (0 until a.length()).map { a.getString(it) }.toSet()) }
                    "string" -> edit.putString(key, value(name, key, item.getString("value")) as String)
                }
            }
            check(edit.commit())
        }
        secrets.keys().forEach { name ->
            val entries = secrets.getJSONObject(name)
            entries.keys().forEach { key ->
                if (write !is LocalSecretWriter || PreferenceBackupPolicy.secret(name, key)) write(name, key, entries.getString(key))
            }
        }
    }
}
