package app.resound.night

import android.content.Context

/** 夜里记录被删、被取消的去向（最近 30 行），进问题记录；只记日期、入口和原因 */
object NightLog {
    private const val KEY = "lines"
    fun add(context: Context, line: String) {
        val prefs = context.getSharedPreferences("night-log", 0)
        val stamp = java.time.LocalDateTime.now().withNano(0).toString().replace('T', ' ')
        val lines = (prefs.getString(KEY, "").orEmpty().lines().filter { it.isNotBlank() } + "$stamp $line").takeLast(30)
        prefs.edit().putString(KEY, lines.joinToString("\n")).apply()
    }
    fun dump(context: Context): String = context.getSharedPreferences("night-log", 0).getString(KEY, "").orEmpty().ifBlank { "（没有删除或取消记录）" }
}
