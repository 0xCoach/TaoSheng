package app.resound.transcribe

import android.content.Context
import android.os.BatteryManager
import android.os.PowerManager

/**
 * 录完就转：本机转写每存好一段就转（模型常驻，几秒一段，不多耗电，负载比攒半小时一次算更平缓）；
 * 云端转写攒满 [CLOUD_BATCH_MILLIS] 再转，免得频繁联网。只转写和认人，整理仍按原来 30 分钟一轮。
 * 电量低且没充电、或手机已偏热时不提前，交给原来的定时任务。
 */
object EarlyTranscribe {
    const val CLOUD_BATCH_MILLIS = 10 * 60_000L
    const val LOW_BATTERY = 20

    fun shouldRun(local: Boolean, pendingMillis: Long, battery: Int, charging: Boolean, hot: Boolean): Boolean = when {
        pendingMillis <= 0 -> false
        hot -> false
        !charging && battery in 0 until LOW_BATTERY -> false
        local -> true
        else -> pendingMillis >= CLOUD_BATCH_MILLIS
    }

    fun deviceAllows(context: Context, local: Boolean, pendingMillis: Long): Boolean {
        val battery = context.getSystemService(BatteryManager::class.java)
        val power = context.getSystemService(PowerManager::class.java)
        return shouldRun(local, pendingMillis, battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY), battery.isCharging,
            power.currentThermalStatus >= PowerManager.THERMAL_STATUS_MODERATE)
    }
}

/** 每轮流水线各步用时，最近 30 轮，写进诊断记录，看慢在哪一步 */
object PipelineTimes {
    private const val KEY = "runs"
    fun record(context: Context, line: String) {
        val prefs = context.getSharedPreferences("pipeline-times", Context.MODE_PRIVATE)
        val runs = (prefs.getString(KEY, "")!!.lines().filter { it.isNotBlank() } + line).takeLast(30)
        prefs.edit().putString(KEY, runs.joinToString("\n")).apply()
    }
    fun dump(context: Context): String =
        context.getSharedPreferences("pipeline-times", Context.MODE_PRIVATE).getString(KEY, "")!!
            .lines().filter { it.isNotBlank() }.joinToString("\n") { "流水线 $it" }
}
