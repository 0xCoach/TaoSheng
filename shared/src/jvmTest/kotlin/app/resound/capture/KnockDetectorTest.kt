package app.resound.capture

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 敲敲检测：尖峰次数/间隔/冷却/灵敏度四条规则都要有证据。fire 发生在第 N 个尖峰采样点。 */
class KnockDetectorTest {
    private fun calm(d: KnockDetector, from: Long, until: Long) {
        var t = from; while (t < until) { d.onSample(t, 0f, 0f, 9.81f); t += 20 }
    }
    /** 一次敲击：一个高尖峰采样 + 一个回落采样；返回尖峰那次是否触发。 */
    private fun knock(d: KnockDetector, at: Long, strength: Float = 25f): Boolean {
        val fired = d.onSample(at, 0f, 0f, 9.81f + strength)
        d.onSample(at + 20, 0f, 0f, 9.81f)
        return fired
    }

    @Test fun doubleTapFires() {
        val d = KnockDetector(tapsRequired = 2)
        calm(d, 0, 2000)
        assertFalse(knock(d, 2000))
        assertTrue(knock(d, 2200))
    }

    @Test fun singleTapDoesNotFire() {
        val d = KnockDetector(tapsRequired = 2)
        calm(d, 0, 2000)
        assertFalse(knock(d, 2000))
        assertFalse(d.onSample(2100, 0f, 0f, 9.81f))
    }

    @Test fun tapsTooFarApartDoNotFire() {
        val d = KnockDetector(tapsRequired = 2)
        calm(d, 0, 2000)
        knock(d, 2000)
        calm(d, 2020, 3100)
        // 距上一敲 1100ms > 800ms 窗口 → 重新计数，这一敲只是第 1 下
        assertFalse(knock(d, 3100))
        assertTrue(knock(d, 3300))
    }

    @Test fun refractoryMergesAftershock() {
        val d = KnockDetector(tapsRequired = 2)
        calm(d, 0, 2000)
        // 同一敲 30ms 内的余震不另计——2000/2030 仍是第 1 敲
        assertFalse(d.onSample(2000, 0f, 0f, 30f))
        assertFalse(d.onSample(2030, 0f, 0f, 35f))
        assertTrue(knock(d, 2300))
    }

    @Test fun tripleTapRequired() {
        val d = KnockDetector(tapsRequired = 3)
        calm(d, 0, 2000)
        assertFalse(knock(d, 2000)); assertFalse(knock(d, 2200))
        assertTrue(knock(d, 2400))
    }

    @Test fun cooldownBlocksImmediateRefire() {
        val d = KnockDetector(tapsRequired = 2)
        calm(d, 0, 2000)
        assertFalse(knock(d, 2000)); assertTrue(knock(d, 2200))
        // 冷却期内的双击不重复触发（链被当余震/未达冷却）
        assertFalse(knock(d, 2400)); assertFalse(knock(d, 2600))
        // 冷却结束后恢复
        assertFalse(knock(d, 4200)); assertTrue(knock(d, 4400))
    }

    @Test fun dullSensitivityNeedsHarderKnock() {
        val d = KnockDetector(tapsRequired = 2, sensitivity = 0) // 阈值 14
        calm(d, 0, 2000)
        assertFalse(knock(d, 2000, strength = 12f)) // 12 < 14 不算敲
        assertFalse(knock(d, 2200, strength = 12f))
        assertFalse(knock(d, 2400, strength = 20f))
        assertTrue(knock(d, 2600, strength = 20f))
    }

    @Test fun baselineAdaptsToHoldPosition() {
        val d = KnockDetector(tapsRequired = 2)
        // 握持姿态模长≠9.81：喂常值让基线收敛，再敲
        var t = 0L; while (t < 4000) { d.onSample(t, 0f, 7f, 7f); t += 50 }
        assertFalse(knock(d, t, 25f))
        assertTrue(knock(d, t + 300, 25f))
    }
}
