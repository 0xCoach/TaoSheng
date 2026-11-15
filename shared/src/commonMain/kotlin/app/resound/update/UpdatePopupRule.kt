package app.resound.update

/** 首页新版本弹窗：同一版本点了“稍后”24 小时内不再自动弹；换了更新的版本立刻再弹。 */
object UpdatePopupRule {
    const val SNOOZE_MILLIS = 24 * 60 * 60_000L
    fun autoOpen(versionCode: Int, snoozedVersion: Int, snoozedAt: Long, now: Long): Boolean =
        versionCode > snoozedVersion || now - snoozedAt >= SNOOZE_MILLIS || now < snoozedAt
}
