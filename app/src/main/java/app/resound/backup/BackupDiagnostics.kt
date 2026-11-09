package app.resound.backup

import android.content.Context

/** 沿用环形记录；保留脱敏后的提供方说明，不写堆栈。 */
object BackupDiagnostics {
    internal fun safeReason(error: Exception): String {
        return error.message.orEmpty()
            .replace(Regex("(?i)\\b(?:bearer|basic)\\s+[^\\s,;]+"), "[credential]")
            .replace(Regex("(?i)(?:api[-_]?key|access[-_]?key|secret|token|password|authorization|key|credential|密码|密钥)[\"']?\\s*[=:]\\s*(?:\"[^\"]*\"|'[^']*'|[^\\s,;]+)"), "[credential]")
            .replace(Regex("[a-zA-Z][a-zA-Z0-9+.-]*://[^\\s<>\"']+"), "[uri]")
            .replace(Regex("(?<![\\w])/(?:[^\\s<>\"']+)"), "[path]")
            .replace(Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}"), "[account]")
            .replace(Regex("[A-Za-z0-9_+%=-]{24,}"), "[redacted]")
            .replace(Regex("[\\p{Cntrl}\\s]+"), " ")
            .trim().ifEmpty { "no message" }.take(200)
    }
    internal inline fun <T> step(context: Context, name: String, block: () -> T): T = try { block() }
        catch (e: Exception) { record(context, name, e); throw e }
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
