package app.resound.platform

import android.content.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.ZoneId

/** 偏好随卡 190 的设置备份；进程启动和系统换区时各检查一次。 */
internal class AndroidZoneHistory(private val prefs: SharedPreferences) {
    /** 整理时每句原话都要查时区：读一次就缓存，系统时区变了或记录更新后再重读 */
    @Volatile private var cache: Pair<String, ZoneHistory>? = null
    private fun load(zone: String): ZoneHistory {
        val rows = runCatching { Json.decodeFromString<List<ZoneChange>>(prefs.getString("changes", "[]")!!) }.getOrDefault(emptyList())
        return ZoneHistory(rows, zone)
    }
    fun read(): ZoneHistory {
        val zone = ZoneId.systemDefault().id
        cache?.let { (z, h) -> if (z == zone) return h }
        return load(zone).also { cache = zone to it }
    }
    fun check() {
        val before = load(ZoneId.systemDefault().id)
        val after = before.record(System.currentTimeMillis(), ZoneId.systemDefault().id)
        if (before.changes != after) check(prefs.edit().putString("changes", Json.encodeToString(after)).commit())
        cache = null
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
