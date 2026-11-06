package app.resound.backup

import android.content.Context

/** 沿用现有诊断的 SharedPreferences 环形记录；不写原始异常、堆栈或任意提供方文本。 */
object BackupDiagnostics {
    internal fun safeReason(error: Exception): String {
        val text = error.message.orEmpty().lowercase()
        // 只提取系统原因词，避免 URI、路径、文件内容或账号混入日志。
        return when {
            "permission denied" in text -> "permission denied"
            "persistable" in text -> "persistable permission unavailable"
            "revoked" in text -> "permission revoked"
            "mode" in text && ("unsupported" in text || "not support" in text || "不支持" in text) -> "unsupported mode"
            "mode" in text && "invalid" in text -> "invalid mode"
            "no space" in text -> "no space left"
            "read-only" in text -> "read-only filesystem"
            "not found" in text || "no such file" in text -> "file not found"
            "checksum mismatch" in text -> "checksum mismatch"
            else -> "reason omitted"
        }
    }
    internal fun record(context: Context, step: String, error: Exception) {
        runCatching {
            val prefs = context.getSharedPreferences("backup-diagnostics", Context.MODE_PRIVATE)
            val lines = prefs.getString("runs", "").orEmpty().lines().filter { it.isNotBlank() }
            val line = "${System.currentTimeMillis()} $step ${error.javaClass.simpleName} ${safeReason(error)}"
            prefs.edit().putString("runs", (lines + line).takeLast(50).joinToString("\n")).commit()
        }
    }
    fun dump(context: Context): String = context.getSharedPreferences("backup-diagnostics", Context.MODE_PRIVATE)
        .getString("runs", "").orEmpty()
}
