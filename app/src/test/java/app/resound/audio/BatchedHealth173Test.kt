package app.resound.audio

import org.junit.Assert.*
import org.junit.Test

class BatchedHealth173Test {
    @Test fun twoHundredMsReturnsStayBelowBothWatchdogLimits() {
        // 模拟 500 ms 看门狗与 200 ms 阻塞读相位错开；同时覆盖先检查再更新的边界。
        var lastReturn = 0L
        for (now in 0L..20_000L) {
            if (now % 500 == 0L) {
                assertFalse(now - lastReturn > CaptureHealth.STALL_MS)
                assertFalse(now - lastReturn > CaptureHealth.STALL_YIELDING_MS)
            }
            if (now % CaptureReadPolicy.BATCH_MS == 0L) lastReturn = now
        }
        assertTrue(2500 > CaptureHealth.STALL_MS) // 真卡死仍会超时
    }
    @Test fun batchedSilenceUsesElapsedTimeAndYieldResetsIt() {
        val pcm = ShortArray(3200)
        val watch = CaptureHealth.ZeroWatch()
        repeat(100) { assertFalse(watch.accept(pcm, pcm.size, it * 200L)) }
        assertTrue(watch.accept(pcm, pcm.size, 20_000))
        watch.reset()
        assertFalse(watch.accept(pcm, pcm.size, 40_000))
        pcm[3199] = 1
        assertFalse(watch.accept(pcm, pcm.size, 60_000))
        assertEquals(60_000L, CaptureHealth.lastSoundAt)
    }
}
