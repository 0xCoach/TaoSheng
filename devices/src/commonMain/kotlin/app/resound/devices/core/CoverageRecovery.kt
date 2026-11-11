package app.resound.devices.core

import app.resound.devices.driver.MillisRange

/** 找出任何可能的起始时钟下都已落盘的交集；按设备时间单位向内收缩。 */
fun coveredFromSavedAudio(duration: Long, offset: Long, lower: Long, upper: Long, start: Long, end: Long, unitMillis: Long): MillisRange? {
    if (duration < 0 || offset < 0 || lower > upper || start > end || unitMillis <= 0) return null
    val from = (offset + start - lower).coerceAtLeast(0)
    val to = (offset + end - upper).coerceAtMost(duration)
    val roundedFrom = ((from + unitMillis - 1) / unitMillis) * unitMillis
    val roundedTo = (to / unitMillis) * unitMillis
    return if (roundedTo > roundedFrom) MillisRange(roundedFrom, roundedTo) else null
}
