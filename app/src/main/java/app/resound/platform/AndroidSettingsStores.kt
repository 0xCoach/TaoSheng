package app.resound.platform

import android.content.Context
import android.content.SharedPreferences
import app.resound.digest.SecretStore
import org.json.JSONArray
import javax.crypto.SecretKey

class AndroidKeyValueStores(private val context: Context) : KeyValueStores {
    override fun open(group: String): GroupedKeyValueStore =
        GroupedPreferences(context.getSharedPreferences(group, Context.MODE_PRIVATE))
}

internal class GroupedPreferences(private val prefs: SharedPreferences) : GroupedKeyValueStore {
    override fun contains(key: String) = prefs.contains(key)
    override fun getString(key: String, default: String?) = prefs.getString(key, default)
    override fun getStrings(key: String): List<String> {
        val array = JSONArray(prefs.getString(key, "[]"))
        return List(array.length(), array::getString)
    }
    override fun getLong(key: String, default: Long) = prefs.getLong(key, default)
    override fun getInt(key: String, default: Int) = prefs.getInt(key, default)
    override fun getBoolean(key: String, default: Boolean) = prefs.getBoolean(key, default)

    override fun commit(edit: KeyValueEdit.() -> Unit): Boolean = synchronized(prefs) {
        val pending = Edit(prefs.edit()).apply(edit)
        val previous = prefs.all
        if (pending.editor.commit()) return@synchronized true
        // SharedPreferences 先更新内存再写盘，失败时还原本次涉及的键。
        val current = prefs.all
        val changed = pending.keys.filter { current[it] != previous[it] }
        if (changed.isNotEmpty()) {
            val restore = prefs.edit()
            for (key in changed) when (val value = previous[key]) {
                null -> restore.remove(key)
                is String -> restore.putString(key, value)
                is Long -> restore.putLong(key, value)
                is Int -> restore.putInt(key, value)
                is Boolean -> restore.putBoolean(key, value)
                is Float -> restore.putFloat(key, value)
                is Set<*> -> restore.putStringSet(key, value.filterIsInstance<String>().toSet())
            }
            restore.commit()
        }
        false
    }

    private class Edit(val editor: SharedPreferences.Editor) : KeyValueEdit {
        val keys = mutableSetOf<String>()
        override fun putString(key: String, value: String?) { keys += key; editor.putString(key, value) }
        override fun putStrings(key: String, value: List<String>) = putString(key, JSONArray(value).toString())
        override fun putLong(key: String, value: Long) { keys += key; editor.putLong(key, value) }
        override fun putInt(key: String, value: Int) { keys += key; editor.putInt(key, value) }
        override fun putBoolean(key: String, value: Boolean) { keys += key; editor.putBoolean(key, value) }
    }
}

class AndroidSecretVault internal constructor(
    context: Context,
    keyForAlias: ((String) -> SecretKey)?,
) : SecretVault {
    constructor(context: Context) : this(context, null)

    // 延迟打开，等启动阶段的备份恢复处理完成。
    private val digest by lazy { SecretStore(context, "digest-secrets", "resound-llm-aes-v1", keyForAlias) }
    private val transcribe by lazy { SecretStore(context, "transcribe", "resound-asr-aes-v1", keyForAlias) }
    private val headers by lazy { SecretStore(context, "transcribe-header-secrets", "resound-asr-headers-aes-v1", keyForAlias) }

    private fun store(group: String) = when (group) {
        "digest-secrets" -> digest
        "transcribe" -> transcribe
        "transcribe-header-secrets" -> headers
        else -> error("密钥存储分组不可用")
    }

    override fun read(group: String, key: String): String = secretOperation("密钥安全读取失败") { store(group).read(key) }
    override fun write(group: String, key: String, value: String) =
        secretOperation("密钥保存失败，请重试") { store(group).write(key, value) }
    override fun remove(group: String, key: String) =
        secretOperation("密钥清除失败，请重试") { store(group).remove(key) }
    override fun has(group: String, key: String): Boolean = secretOperation("密钥状态读取失败") { store(group).has(key) }

    private inline fun <T> secretOperation(message: String, action: () -> T): T = try {
        action()
    } catch (_: Exception) {
        throw IllegalStateException(message)
    }
}
