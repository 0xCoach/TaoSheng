package app.resound.importer

import android.content.Context

/** 卡 210：文件夹录音点“转写”失败的原因分类与诊断（进问题记录，最近 30 条） */
internal object FolderImportProblems {
    fun reason(e: Exception): String = when (e) {
        is java.io.FileNotFoundException -> "原文件不在了"
        is SecurityException -> "文件夹需要重新授权"
        is ImportFailure -> e.message ?: "导入失败，可以重试"
        is java.io.IOException -> e.message?.takeIf { it in OWN } ?: "读取失败，可以重试"
        else -> "导入失败，可以重试"
    }

    /** 声声不息自己写的说明；系统异常文字可能带路径和文件名（含姓名、号码），一律不记 */
    private val OWN = setOf("无法读取文件夹", "文件夹尚未加载完成，请稍后重试", "无法读取录音", "文件仍在写入，稍后重试",
        "文件已不存在", "这个文件里没找到声音", "暂不支持这种格式")

    /** 只记扩展名、原因、异常类别和声声不息自己的说明 */
    @Synchronized fun record(context: Context, extension: String, reason: String, e: Exception) {
        runCatching {
            val prefs = context.getSharedPreferences("folder-import-problems", Context.MODE_PRIVATE)
            val lines = prefs.getString("lines", "").orEmpty().lines().filter { it.isNotBlank() }
            val detail = e.message?.takeIf { it in OWN }
            val line = "${System.currentTimeMillis()} ext=${extension.lowercase().take(8)} reason=$reason error=${e.javaClass.simpleName}" + (detail?.let { " detail=$it" } ?: "")
            prefs.edit().putString("lines", (lines + line).takeLast(30).joinToString("\n")).commit()
        }
    }

    fun dump(context: Context): String = context.getSharedPreferences("folder-import-problems", Context.MODE_PRIVATE)
        .getString("lines", "").orEmpty().ifBlank { "无" }
}
