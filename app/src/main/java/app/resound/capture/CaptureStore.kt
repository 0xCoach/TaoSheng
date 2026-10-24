package app.resound.capture

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import app.resound.data.*

class CaptureStore(val context: Context, val dao: CaptureDao) {
    val prefs = context.getSharedPreferences("capture", Context.MODE_PRIVATE)
    fun battery(): Pair<Int?, Boolean?> {
        val i = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null to null
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        return (if (scale > 0 && level >= 0) level * 100 / scale else null) to
            (if (status < 0) null else status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL)
    }
    suspend fun event(type: WatchdogEventType, detail: String? = null, gapStart: Long? = null, gapEnd: Long? = null) {
        val (p, c) = battery()
        dao.event(EventEntity(at = System.currentTimeMillis(), type = type.name, detail = detail,
            batteryPercent = p, charging = c, gapStart = gapStart, gapEnd = gapEnd))
    }
    suspend fun sampleBattery() {
        val (p, c) = battery()
        dao.battery(BatteryEntity(at = System.currentTimeMillis(), percent = p, charging = c))
    }
}
