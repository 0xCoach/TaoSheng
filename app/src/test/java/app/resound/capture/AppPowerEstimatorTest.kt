package app.resound.capture

import app.resound.data.AppPowerEntity
import org.junit.Assert.*
import org.junit.Test

class AppPowerEstimatorTest {
    private val step = 600_000L
    private fun row(i: Int) = AppPowerEntity(at = i * step, elapsedMs = i * step,
        session = "one", cpuMs = i * 1000L, txBytes = i * 1_000_000L, rxBytes = i * 1_000_000L,
        micMs = i * step, charging = false, chargeEpoch = 0, capacityMah = 4880.0)
    private fun estimate(rows: List<AppPowerEntity>) = AppPowerModel.perHour(rows, 0, 10 * step)

    @Test fun formulaAndThirtyMinuteThreshold() {
        val oneEach = AppPowerModel.CPU_MAH_PER_SECOND + AppPowerModel.MIC_MAH_PER_HOUR + 2 * AppPowerModel.NETWORK_MAH_PER_MB
        assertEquals(oneEach, AppPowerModel.energyMah(1000, 3_600_000, 2_000_000), 1e-9)
        assertNull(estimate((0..2).map(::row)))
        // 3 段 × 10 分钟：CPU 3 秒、麦克风 0.5 小时、流量 6 MB
        val mah = 3 * AppPowerModel.CPU_MAH_PER_SECOND + 0.5 * AppPowerModel.MIC_MAH_PER_HOUR + 6 * AppPowerModel.NETWORK_MAH_PER_MB
        assertEquals((mah / 4880 * 100 / 0.5).toFloat(), estimate((0..3).map(::row))!!, 1e-6f)
    }
    @Test fun resetOfAnyCounterDiscardsInterval() {
        val base = (0..3).map(::row)
        for (bad in listOf(row(2).copy(cpuMs = 0), row(2).copy(txBytes = 0),
            row(2).copy(rxBytes = 0), row(2).copy(micMs = 0))) {
            assertNull(estimate(base.toMutableList().apply { set(2, bad) }))
        }
        // 新进程即使计数已超过旧值，也不能跨会话计算。
        assertNull(estimate(base.map { if (it.at >= 2 * step) it.copy(session = "two") else it }))
    }
    @Test fun chargingUnknownAndBriefPlugInAreExcluded() {
        val base = (0..3).map(::row)
        assertNull(estimate(base.map { if (it.at == step) it.copy(charging = true) else it }))
        assertNull(estimate(base.map { if (it.at == step) it.copy(charging = null) else it }))
        assertNull(estimate(base.map { if (it.at >= step) it.copy(chargeEpoch = 2) else it }))
        val longer = (0..5).map { row(it).copy(charging = if (it < 2) true else false) }
        assertEquals(estimate(base)!!, estimate(longer)!!, 1e-6f)
    }
    @Test fun unsupportedTrafficLongGapsAndClockChangesAreExcluded() {
        assertNull(estimate((0..3).map { row(it).copy(txBytes = -1) }))
        assertNull(estimate(listOf(row(0), row(3))))
        assertNull(estimate((0..3).map { row(it).copy(elapsedMs = it * step / 2) }))
    }
    @Test fun midnightClipsEnergyAndDurationTogether() {
        val rows = (0..4).map(::row)
        assertEquals(estimate(rows)!!, AppPowerModel.perHour(rows, step / 2, step * 3 + step / 2)!!, 1e-6f)
        assertNull(AppPowerModel.perHour(rows, step, step * 3))
    }
    @Test fun capacityUsesRemainingChargeAndFallsBackForInvalidValues() {
        assertEquals(4880.0, AppPowerModel.capacityMah(2_440_000, 50), 1e-9)
        for (value in listOf(-1L, 0L, Long.MIN_VALUE, Long.MAX_VALUE))
            assertEquals(4880.0, AppPowerModel.capacityMah(value, 50), 1e-9)
        assertEquals(4880.0, AppPowerModel.capacityMah(2_440_000, 0), 1e-9)
    }
    @Test fun micCountsOnlyActualOpenAndIncludesSilencedButOpenTime() {
        var clock = 0L
        val mic = MicOpenClock { clock }
        clock = 100; assertEquals(0L, mic.totalMillis()) // 等待别人释放，不算开启
        mic.setOpen(true); clock = 200; mic.setOpen(true) // 状态切换不重新计时
        clock = 400; assertEquals(300L, mic.totalMillis())
        mic.setOpen(false); clock = 600; mic.setOpen(false)
        assertEquals(300L, mic.totalMillis())
        mic.setOpen(true); clock = 700; mic.setOpen(false)
        assertEquals(400L, mic.totalMillis())
    }
}
