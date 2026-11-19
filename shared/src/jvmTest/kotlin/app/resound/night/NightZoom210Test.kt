package app.resound.night

import org.junit.Assert.*
import org.junit.Test

/** 卡 210：声音详情图放大缩小、刻度、最响时刻 */
class NightZoom210Test {
    private val start = 0L
    private val end = 8 * 3_600_000L
    @Test fun zoomKeepsFingerPointAndClampsToTenMinutesAndNight() {
        val whole = NightView(start, end)
        val half = NightZoom.zoom(whole, .25f, 2f, start, end)
        assertEquals(4 * 3_600_000L, half.span)
        assertEquals(2 * 3_600_000L, half.from + (half.span * .25f).toLong())  // 手指下仍是 2 点
        val tight = NightZoom.zoom(half, .5f, 1000f, start, end)
        assertEquals(NightZoom.MIN_SPAN, tight.span)
        assertEquals(whole, NightZoom.zoom(half, .5f, .01f, start, end))
        assertEquals(NightView(end - half.span, end), NightZoom.pan(half, 99 * 3_600_000L, start, end))
        assertEquals(NightView(0, NightZoom.MIN_SPAN), NightZoom.around(0, 1, start, end))
    }
    @Test fun doubleTapStepsWholeHourTenMinutesThenBack() {
        val hour = NightZoom.nextLevel(NightView(start, end), 3 * 3_600_000L, start, end)
        assertEquals(3_600_000L, hour.span)
        val ten = NightZoom.nextLevel(hour, 3 * 3_600_000L, start, end)
        assertEquals(NightZoom.MIN_SPAN, ten.span)
        assertEquals(NightView(start, end), NightZoom.nextLevel(ten, 0, start, end))
    }
    @Test fun ticksGetFinerAndAlignToLocalClock() {
        assertEquals(2 * 3_600_000L, NightZoom.tickStep(8 * 3_600_000L))
        assertEquals(60_000L * 2, NightZoom.tickStep(NightZoom.MIN_SPAN))
        val offset = 8 * 3_600_000L  // UTC+8
        val v = NightView(3_600_000L + 7 * 60_000L, 3_600_000L + 17 * 60_000L)
        val ticks = NightZoom.ticks(v, offset)
        assertTrue(ticks.isNotEmpty() && ticks.all { (it + offset) % 120_000L == 0L && it in v.from..v.to })
    }
    @Test fun loudestAndReadoutUseAboveBackground() {
        val env = NightEnvelope(1_000, 5_000, byteArrayOf(-40, -20, -30), byteArrayOf(-50, -50, -10), byteArrayOf(-50, -50, -10))
        assertEquals(6_000L to 30, NightZoom.loudest(env, 0, 99_000))
        assertEquals(30, NightZoom.aboveBackground(env, 7_000))
        assertNull(NightZoom.aboveBackground(env, 500))
        assertNull(NightZoom.aboveBackground(env, 16_000))
    }
}

class LoudPicker210Test {
    /** 每 2 秒喂一次，返回存下的（起点、分贝）和最后留下的 id */
    private fun run(levels: Map<Long, Int>, until: Long): Pair<List<Pair<Long, Int>>, LoudPicker> {
        val picker = LoudPicker(emptyList()); val saved = mutableListOf<Pair<Long, Int>>(); var id = 0L
        val alive = mutableSetOf<Long>()
        for (at in 0L..until step 2_000) picker.offer(at, levels[at] ?: 0)?.let { (a, d) ->
            saved += a to d; id++; alive += id; alive.removeAll(picker.kept(id, a, d).toSet())
        }
        picker.flush()?.let { saved += it }
        return saved to picker
    }
    @Test fun waitsForTailKeepsLouderNeighbourAndOnlyTopThree() {
        // 10 秒处 20 分贝，12 秒处更响的 25 分贝（相邻只留更响）；之后 4 下更响的，最终只留最响 3 下
        val levels = mapOf(10_000L to 20, 12_000L to 25, 100_000L to 30, 200_000L to 35, 300_000L to 40, 400_000L to 18)
        val (saved, _) = run(levels, 500_000)
        assertEquals(listOf(12_000L to 25, 100_000L to 30, 200_000L to 35, 300_000L to 40), saved)
        assertFalse(saved.any { it.first == 400_000L })  // 18 分贝比留下的三下都轻，不存
    }
    @Test fun quietNightKeepsNothingAndPendingFlushesAtEnd() {
        assertTrue(run(mapOf(10_000L to 14), 60_000).first.isEmpty())
        val picker = LoudPicker(emptyList())
        assertNull(picker.offer(0, 30)); assertNull(picker.offer(2_000, 0))
        assertEquals(0L to 30, picker.flush())
    }
    @Test fun keptReturnsReplacedAndOverflowIds() {
        val picker = LoudPicker(listOf(LoudPicker.Pick(1, 0, 20), LoudPicker.Pick(2, 100_000, 30), LoudPicker.Pick(3, 200_000, 40)))
        assertEquals(listOf(1L), picker.kept(4, 300_000, 25))
        assertEquals(listOf(4L), picker.kept(5, 305_000, 28))   // 相隔 5 秒，替换更轻的那下
    }
}
