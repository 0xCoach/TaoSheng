package app.resound.screentime

/**
 * 系统使用事件的类型。数值即 Android `UsageEvents.Event` 的对应常量
 * （ACTIVITY_RESUMED/ACTIVITY_PAUSED/ACTIVITY_STOPPED/SCREEN_INTERACTIVE/
 * SCREEN_NON_INTERACTIVE/KEYGUARD_SHOWN/KEYGUARD_HIDDEN/DEVICE_SHUTDOWN/DEVICE_STARTUP），
 * 由采集侧原样存整数，本枚举只认白名单内的码。
 */
enum class ScreenEventType(val code: Int) {
    RESUMED(1),
    PAUSED(2),
    STOPPED(23),
    SCREEN_ON(15),
    SCREEN_OFF(16),
    KEYGUARD_SHOWN(17),
    KEYGUARD_HIDDEN(18),
    SHUTDOWN(26),
    STARTUP(27);

    companion object {
        /** 未知事件码返回 null，由调用方丢弃。 */
        fun of(code: Int): ScreenEventType? = entries.firstOrNull { it.code == code }
    }
}

/** 一条系统使用事件；系统级事件（熄屏、锁屏、开关机）无归属时 [pkg]、[cls] 为空串。 */
data class ScreenEvent(val id: Long, val at: Long, val pkg: String, val cls: String, val type: ScreenEventType)

/** 一个 App 的连续使用段：左闭右开，纪元毫秒。 */
data class AppSession(val pkg: String, val from: Long, val until: Long)

/** 按 App 聚合的使用总时长。 */
data class AppTotal(val pkg: String, val millis: Long)

/**
 * 屏幕时间计算参数。[mergeGapMillis] 合并短暂切换的抖动；[lookbackMillis] 重放回看窗口；
 * [titleMaxCodePoints]、[heartbeatMillis]、[pullThrottleMillis]、[topApps] 供采集与输出侧共用。
 */
data class ScreenTimeConfig(
    val mergeGapMillis: Long = 5_000,
    val lookbackMillis: Long = 86_400_000,
    val titleMaxCodePoints: Int = 40,
    val heartbeatMillis: Long = 60_000,
    val pullThrottleMillis: Long = 30_000,
    val topApps: Int = 5,
)
