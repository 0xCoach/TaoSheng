package app.resound.update

import app.resound.diagnostics.FailureNotes

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
    /** 卡 210：例行“检查成功”单独存、只留最近几条，免得一天几十次检查把下载/安装步骤挤掉 */
    internal fun routine(line: String) = line.startsWith("check ") && line.endsWith("result=ok")

    @Synchronized fun record(context: Context, line: String) {
        // 诊断写入失败不能阻断安装状态回调或备份；只保存步骤、数字、固定错误类别。
        runCatching {
            val prefs = context.getSharedPreferences("update-times", Context.MODE_PRIVATE)
            val (key, keep) = if (routine(line)) "checks" to 6 else "runs" to 80
            val lines = prefs.getString(key, "").orEmpty().lines().filter { it.isNotBlank() }
            prefs.edit().putString(key, (lines + "${System.currentTimeMillis()} $line").takeLast(keep).joinToString("\n")).commit()
        }.onFailure {
            FailureNotes.record("update/UpdateDiagnostics.kt:24", it)
        }
    }
    fun dump(context: Context): String {
        val prefs = context.getSharedPreferences("update-times", Context.MODE_PRIVATE)
        return (prefs.getString("runs", "").orEmpty().lines() + prefs.getString("checks", "").orEmpty().lines())
            .filter { it.isNotBlank() }.sortedBy { it.substringBefore(' ').toLongOrNull() ?: 0L }
            .joinToString("\n") { "涛声更新 $it" }
    }
}
