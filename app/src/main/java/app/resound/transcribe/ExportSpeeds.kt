package app.resound.transcribe

import android.content.SharedPreferences

/** 毫秒 / 录音分钟；仅记录成功导出，最近 10 次落盘，最近 5 次中位数用于预估。 */
internal class ExportSpeeds(private val prefs: SharedPreferences? = null) {
    private var samples = prefs?.getString("speeds", "")?.split(',')
        ?.mapNotNull { it.toDoubleOrNull()?.takeIf { v -> v.isFinite() && v >= 0 } }?.takeLast(10).orEmpty()
    @Synchronized fun record(durationMillis: Long, elapsedMillis: Long) {
        if (durationMillis <= 0) return
        samples = (samples + elapsedMillis.coerceAtLeast(1) * 60000.0 / durationMillis).takeLast(10)
        prefs?.edit()?.putString("speeds", samples.joinToString(","))?.apply()
    }
    @Synchronized fun estimate(durationMillis: Long): Long {
        val sorted = samples.takeLast(5).sorted()
        val median = if (sorted.isEmpty()) 2000.0 else
            (sorted[(sorted.size - 1) / 2] + sorted[sorted.size / 2]) / 2
        return (durationMillis / 60000.0 * median).toLong()
    }
}
