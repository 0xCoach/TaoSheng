package app.resound.marks

import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class AndroidMarkSettings(private val prefs: SharedPreferences) {
    private val state = MutableStateFlow(MarkRange(
        prefs.getLong("beforeMillis", 120_000).coerceIn(30_000, 600_000),
        prefs.getLong("afterMillis", 60_000).coerceIn(30_000, 600_000)))
    val range = state.asStateFlow()
    fun setRange(beforeMillis: Long, afterMillis: Long) {
        val next = MarkRange(beforeMillis, afterMillis)
        check(prefs.edit().putLong("beforeMillis", beforeMillis).putLong("afterMillis", afterMillis).commit())
        state.value = next
    }
}
object MarkFeedback {
    fun text(at: Long): String = "已记下 ${app.resound.quick.QuickText.clock(at, java.time.ZoneId.systemDefault().rules.getOffset(java.time.Instant.ofEpochMilli(at)).totalSeconds)} 的重点"
}
