package app.resound.platform

import android.content.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.ZoneId

/** 偏好随卡 190 的设置备份；进程启动和系统换区时各检查一次。 */
internal class AndroidZoneHistory(private val prefs: SharedPreferences) {
    fun read(): ZoneHistory {
        val rows = runCatching { Json.decodeFromString<List<ZoneChange>>(prefs.getString("changes", "[]")!!) }.getOrDefault(emptyList())
        return ZoneHistory(rows, ZoneId.systemDefault().id)
    }
    fun check() {
        val before = read()
        val after = before.record(System.currentTimeMillis(), ZoneId.systemDefault().id)
        if (before.changes != after) check(prefs.edit().putString("changes", Json.encodeToString(after)).commit())
    }
    fun attach(context: Context) {
        check()
        HistoricalZones.history = ::read
        context.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                check()
                app.resound.push.DayThreadPushScheduler.plan(context)
            }
        }, IntentFilter(Intent.ACTION_TIMEZONE_CHANGED))
    }
}
