package app.resound.night

import org.junit.Assert.*
import org.junit.Test

/** 11-18 起床判断：用户 7:07 起床、7:42 首页还在“记录中”的那一晚 */
class NightWakeTest {
    private val m = 60_000L
    private fun t(h: Int, min: Int) = (h * 60L + min) * m
    private val config = NightSettings(enabled = true)
    private fun input(at: Long) = NightInput(at, ((at / m) % 1440).toInt(), at, null, at, 0, false, true, 80, false, false)
    private fun night(vararg s: NightSignal) = NightSession(t(-1, 0), t(-1, 0), confirmedAt = 0, lastAt = 0, signals = s.toList())
    private fun sig(kind: String, a: Long, b: Long = a) = NightSignal(a, b, kind)

    @Test fun morningUnlockAndUseEndsTheNightAtOnceEvenIfPhoneIsPutDown() {
        // 7:06 拿起、7:07 解锁看了 3 分钟，然后放桌上去洗漱（手机静止、安静）
        val s = night(sig("pickup", t(7, 6)), sig("unlock", t(7, 7)), sig("screen", t(7, 7), t(7, 10)), sig("quiet", t(7, 10), t(7, 11)))
        assertEquals(NightTransition.Finish, NightRules.transition(config, s, input(t(7, 11))))
        assertEquals(t(7, 6), WakeRules.decide(s, t(7, 11), 7 * 60 + 11))
        assertEquals(t(7, 6), SleepEstimate.calculate(s.copy(signals = s.signals + NightSignal(t(7, 11), t(7, 11), "wake", t(7, 6).toDouble())), emptyList(), t(7, 42)).times.wake)
    }

    @Test fun nightGlanceThenBackToSleepKeepsRecording() {
        // 3:00 解锁看时间 3 分钟，3:05 起又安静了 20 分钟：不算起床
        val s = night(sig("unlock", t(3, 0)), sig("screen", t(3, 0), t(3, 3)), sig("quiet", t(3, 5), t(3, 25)))
        assertEquals(NightTransition.Continue, NightRules.transition(config, s, input(t(3, 40))))
        assertNull(WakeRules.pending(s))
        // 只看一眼还没睡回去：柱子先停在 3:00，等满 30 分钟才算起床
        val awake = night(sig("unlock", t(3, 0)), sig("screen", t(3, 0), t(3, 3)))
        assertEquals(t(3, 0), WakeRules.pending(awake))
        assertEquals(NightTransition.Continue, NightRules.transition(config, awake, input(t(3, 20))))
        assertEquals(NightTransition.Finish, NightRules.transition(config, awake, input(t(3, 31))))
    }

    @Test fun laterMorningUseCountsTheEarlierGetUpAndFinishedNightIsNotReopened() {
        // 7:07 只解锁一下就去洗漱（安静了 30 分钟），7:45 回来解锁刷手机：起床算 7:07
        val s = night(sig("unlock", t(7, 7)), sig("quiet", t(7, 8), t(7, 40)), sig("unlock", t(7, 45)), sig("screen", t(7, 45), t(7, 49)))
        assertEquals(t(7, 7), WakeRules.decide(s, t(7, 49), 7 * 60 + 49))
        // 到中午都没再动手机：收尾时也按早上那一下算
        assertEquals(t(7, 7), WakeRules.atWindowEnd(night(sig("unlock", t(7, 7)), sig("quiet", t(7, 8), t(12, 0))), t(12, 0), 720))
        assertFalse(NightRules.canReopen(NightState.Ready)); assertFalse(NightRules.canReopen(NightState.Processing))
        assertTrue(NightRules.canReopen(NightState.Interrupted)); assertTrue(NightRules.canReopen(null))
    }

    @Test fun interruptedNightStaysOpenUntilItsWindowEnds() {
        // #59：01:19 重启被标成中断，整理任务不能在时段内把它定成 Ready，否则当晚再也续不上
        val deadline = t(12, 0)
        assertFalse(NightRules.readyToProcess(NightState.Interrupted, deadline, t(1, 30)))
        assertTrue(NightRules.canReopen(NightState.Interrupted))
        // 时段结束、进程也没再续上：照常整理
        assertTrue(NightRules.readyToProcess(NightState.Interrupted, deadline, t(12, 0)))
        // 没有 deadline 的旧记录、正常结束的夜晚：照旧立即整理
        assertTrue(NightRules.readyToProcess(NightState.Interrupted, null, t(1, 30)))
        assertTrue(NightRules.readyToProcess(NightState.Processing, deadline, t(1, 30)))
    }
}
