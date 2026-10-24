package app.resound.capture

import app.resound.data.AppPowerEntity

/** 暂定系数；当前系统快照缺少本 UID 分项，不能称为已校准。单位见交付说明。 */
object AppPowerModel {
    // 暂定（10-23 Claude）：约 72 mA/核·秒；后台常驻估算约 14 mAh/时，接近 10-23 上午 batterystats 实测的约 19 mAh/时（0.4%/时）。待整晚后台数据复核
    const val CPU_MAH_PER_SECOND = 0.02
    const val MIC_MAH_PER_HOUR = 12.0
    const val NETWORK_MAH_PER_MB = 0.05
    const val FALLBACK_CAPACITY_MAH = 4880.0

    fun capacityMah(chargeUah: Long, percent: Int?): Double =
        if (chargeUah > 0 && percent != null && percent in 1..100)
            (chargeUah / 1000.0 * 100 / percent).takeIf { it in 1000.0..20000.0 } ?: FALLBACK_CAPACITY_MAH
        else FALLBACK_CAPACITY_MAH

    fun energyMah(cpuMs: Long, micMs: Long, bytes: Long) =
        cpuMs / 1000.0 * CPU_MAH_PER_SECOND + micMs / 3_600_000.0 * MIC_MAH_PER_HOUR +
            bytes / 1_000_000.0 * NETWORK_MAH_PER_MB

    fun perHour(rows: List<AppPowerEntity>, start: Long, end: Long): Float? {
        var duration = 0L
        var percent = 0.0
        rows.zipWithNext().forEach { (a, b) ->
            val elapsed = b.elapsedMs - a.elapsedMs
            val wall = b.at - a.at
            if (a.session != b.session || a.chargeEpoch != b.chargeEpoch ||
                a.charging != false || b.charging != false || elapsed <= 0 || wall <= 0 ||
                elapsed > CaptureTuning.BATTERY_MS * 2 || kotlin.math.abs(wall - elapsed) > 5000) return@forEach
            val cpu = b.cpuMs - a.cpuMs
            val mic = b.micMs - a.micMs
            val tx = b.txBytes - a.txBytes
            val rx = b.rxBytes - a.rxBytes
            if (cpu < 0 || mic < 0 || mic > elapsed || tx < 0 || rx < 0 ||
                a.txBytes < 0 || a.rxBytes < 0 || b.capacityMah <= 0 || !b.capacityMah.isFinite()) return@forEach
            val overlap = (minOf(end, b.at) - maxOf(start, a.at)).coerceAtLeast(0)
            duration += overlap
            percent += energyMah(cpu, mic, tx + rx) * overlap / wall / b.capacityMah * 100
        }
        return if (duration < 30 * 60_000L) null else (percent / (duration / 3_600_000.0)).toFloat()
    }
}

/** 单调时钟计量真实打开的 AudioRecord，跨读取线程和采样线程共享。 */
class MicOpenClock(private val now: () -> Long) {
    private var openedAt: Long? = null
    private var total = 0L
    @Synchronized fun setOpen(open: Boolean) {
        if (open && openedAt == null) openedAt = now()
        if (!open) { openedAt?.let { total += (now() - it).coerceAtLeast(0) }; openedAt = null }
    }
    @Synchronized fun totalMillis(): Long = total + (openedAt?.let { (now() - it).coerceAtLeast(0) } ?: 0)
}
