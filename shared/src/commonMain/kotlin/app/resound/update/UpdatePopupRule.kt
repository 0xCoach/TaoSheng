package app.resound.update

/**
 * 新版本提示的"稍后"节流：同一版本静默一个时间窗；出现更新版本立即再提示；
 * 设备时钟回拨视为窗口已过（宁可多弹一次，不漏提醒）。
 */
object UpdatePopupRule {
    private const val WINDOW_MS = 86_400_000L

    fun autoOpen(versionCode: Int, snoozedVersion: Int, snoozedAt: Long, now: Long): Boolean {
        if (versionCode > snoozedVersion) return true
        val elapsed = now - snoozedAt
        return elapsed < 0 || elapsed >= WINDOW_MS
    }
}
