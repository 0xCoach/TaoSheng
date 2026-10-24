package app.resound.digest

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 放在既有 digest 设置中，随卡 037 分卷设置备份。发送记录另存，不带入设置备份。 */
class AndroidAppointmentSettings(context: Context) {
    private val prefs = context.getSharedPreferences("digest", Context.MODE_PRIVATE)
    private val mutable = MutableStateFlow(AppointmentReminders(
        prefs.getBoolean("appointmentEvening", true), prefs.getInt("appointmentEveningAt", 1260).coerceIn(0, 1439),
        prefs.getInt("appointmentLead", 60).takeIf { it > 0 },
    ))
    val state = mutable.asStateFlow()
    fun set(value: AppointmentReminders) {
        val clean = value.copy(eveningAt = value.eveningAt.coerceIn(0, 1439), leadMinutes = value.leadMinutes?.coerceIn(1, 1440))
        check(prefs.edit().putBoolean("appointmentEvening", clean.eveningBefore)
            .putInt("appointmentEveningAt", clean.eveningAt).putInt("appointmentLead", clean.leadMinutes ?: 0).commit())
        mutable.value = clean
    }
}
