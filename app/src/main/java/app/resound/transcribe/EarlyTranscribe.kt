package app.resound.transcribe

import android.content.Context
import android.os.BatteryManager
import android.os.PowerManager

/**
 * 实时转写：录完一段就转（本机模型常驻，几秒一段，不多耗电，负载比攒半小时一次算更平缓；云端每段都上传）。
 * 只转写和认人，整理按整理设置另外排。电量低且没充电、或手机已偏热时先攒着，交给定时转写兜底。
 */
object EarlyTranscribe {
    const val LOW_BATTERY = 20

    fun shouldRun(realtime: Boolean, pendingMillis: Long, battery: Int, charging: Boolean, hot: Boolean): Boolean = when {
        !realtime || pendingMillis <= 0 -> false
        hot -> false
        !charging && battery in 0 until LOW_BATTERY -> false
        else -> true
    }

    fun deviceAllows(context: Context, realtime: Boolean, pendingMillis: Long): Boolean {
        val battery = context.getSystemService(BatteryManager::class.java)
        val power = context.getSystemService(PowerManager::class.java)
        return shouldRun(realtime, pendingMillis, battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY), battery.isCharging,
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
