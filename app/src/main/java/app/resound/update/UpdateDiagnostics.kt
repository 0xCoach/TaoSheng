package app.resound.update

import android.content.Context

internal class AndroidUpdateStore(context: Context) : UpdateStore {
    private val prefs = context.getSharedPreferences("app-update", Context.MODE_PRIVATE)
    override fun string(key: String) = prefs.getString(key, null)
    override fun long(key: String) = prefs.getLong(key, 0)
    override fun save(vararg values: Pair<String, Any?>) {
        check(prefs.edit().apply {
            values.forEach { (key, value) -> when (value) {
                null -> remove(key)
                is Long -> putLong(key, value)
                is String -> putString(key, value)
                else -> error("unsupported_update_preference")
            } }
        }.commit())
    }
}

object UpdateDiagnostics {
    @Synchronized fun record(context: Context, line: String) {
        // 诊断写入失败不能阻断安装状态回调或备份；只保存步骤、数字、固定错误类别。
        runCatching {
            val prefs = context.getSharedPreferences("update-times", Context.MODE_PRIVATE)
            val lines = prefs.getString("runs", "").orEmpty().lines().filter { it.isNotBlank() }
            prefs.edit().putString("runs", (lines + "${System.currentTimeMillis()} $line").takeLast(50).joinToString("\n")).commit()
        }
    }
    fun dump(context: Context) = context.getSharedPreferences("update-times", Context.MODE_PRIVATE)
        .getString("runs", "").orEmpty().lineSequence().filter { it.isNotBlank() }.joinToString("\n") { "涛声更新 $it" }
}
