package app.resound.night

import org.junit.Assert.*
import org.junit.Test

/** 卡 225：半夜或清早看了手机又睡回去，算“醒了一次”，不把这一夜提前结束 */
class NightResleep225Test {
    private val m = 60_000L
    private fun t(h: Int, min: Int) = (h * 60L + min) * m
    private fun input(at: Long, usual: Int? = null) =
        NightInput(at, ((at / m) % 1440).toInt(), at, null, at, 0, false, true, 80, false, false, usual)
    private fun night(vararg s: NightSignal, confirmed: Long = 0) = NightSession(confirmed - t(1, 0), confirmed - t(1, 0), confirmedAt = confirmed, lastAt = 0, signals = s.toList())
    private fun sig(kind: String, a: Long, b: Long = a, value: Double = 0.0) = NightSignal(a, b, kind, value)
    private fun step(s: NightSession, at: Long, usual: Int? = null) = NightRules.transition(true, false, s, input(at, usual))
    private val seven = 7 * 60

    @Test fun insomniaInTheNightNeverEndsTheNight() {
        // 3:00 起一直刷手机到 4:59，没有睡回去：仍是同一夜
        val s = night(sig("unlock", t(3, 0)), sig("screen", t(3, 0), t(4, 59)))
        assertEquals(NightTransition.Continue, step(s, t(3, 31)))
        assertEquals(NightTransition.Continue, step(s, t(4, 59)))
        // 十点前睡、23:30 醒了 40 分钟：也不结束
        val evening = night(sig("unlock", t(23, 30)), sig("screen", t(23, 30), t(0, 10) + t(24, 0)), confirmed = t(20, 0))
        assertEquals(NightTransition.Continue, NightRules.transition(true, false, evening, input(t(24, 10) - 1)))
    }

    @Test fun reallyUpAtThreeIsSettledAfterFiveAndKeepsThreeAsWake() {
        // 3:30 起床赶飞机，手机一直在用；早于平时 7:00，5 点后再醒满 45 分钟才收尾，起床仍算 3:30
        val s = night(sig("unlock", t(3, 30)), sig("screen", t(3, 30), t(5, 50)))
        assertEquals(NightTransition.Continue, step(s, t(5, 0), seven))
        assertEquals(NightTransition.Continue, step(s, t(5, 40), seven))
        assertEquals(NightTransition.Finish, step(s, t(5, 46), seven))
        assertEquals(t(3, 30), WakeRules.decide(s, t(5, 46), 5 * 60 + 46))
    }

    @Test fun insomniaUntilAfterFiveThenSleepIsNotCutAtFive() {
        val s = night(sig("unlock", t(3, 30)), sig("screen", t(3, 30), t(5, 10)), sig("quiet", t(5, 10), t(5, 30)))
        assertEquals(NightTransition.Continue, step(s, t(5, 5), seven))
        assertNull(WakeRules.pending(s))
        assertEquals(NightTransition.Continue, step(s, t(5, 30), seven))
    }

    @Test fun earlyGlanceThenBackToSleepCountsAsOneWaking() {
        // 平时 7:00 起。5:30 解锁看消息 3 分钟：以前当场算起床，现在先等
        val glance = listOf(sig("pickup", t(5, 30)), sig("unlock", t(5, 30)), sig("screen", t(5, 30), t(5, 33)))
        assertEquals(NightTransition.Finish, step(night(*glance.toTypedArray()), t(5, 34)))
        assertEquals(NightTransition.Continue, step(night(*glance.toTypedArray()), t(5, 34), seven))
        // 放下又睡到 7:20，7:25 起来解锁刷手机：这时才起床，5:30 那次记成醒了一次
        val s = night(*(glance + listOf(sig("quiet", t(5, 34), t(7, 20)), sig("pickup", t(7, 25)), sig("unlock", t(7, 25)),
            sig("screen", t(7, 25), t(7, 28)))).toTypedArray())
        assertEquals(NightTransition.Continue, step(night(*(glance + sig("quiet", t(5, 34), t(6, 30))).toTypedArray()), t(6, 30), seven))
        assertEquals(NightTransition.Finish, step(s, t(7, 28), seven))
        val wake = WakeRules.decide(s, t(7, 28), seven + 28)!!
        assertEquals(t(7, 25), wake)
        val result = SleepEstimate.calculate(s.copy(signals = s.signals + NightSignal(t(7, 28), t(7, 28), "wake", wake.toDouble())), emptyList(), t(7, 30))
        assertEquals(t(7, 25), result.times.wake)
        val middle = result.awake.single()
        assertEquals(t(5, 30), middle.start); assertEquals(1, result.count)
        assertEquals("看手机 3 分", SleepEstimate.awakeText(middle, s.signals))
    }

    @Test fun earlyGlanceButReallyUpIsSettledAfterFortyFiveMinutes() {
        // 5:30 起来就没再睡：45 分钟后收尾，起床算 5:30（分不清时按起床）
        val s = night(sig("unlock", t(5, 30)), sig("screen", t(5, 30), t(6, 20)))
        assertEquals(NightTransition.Continue, step(s, t(6, 10), seven))
        assertEquals(NightTransition.Finish, step(s, t(6, 16), seven))
        assertEquals(t(5, 30), WakeRules.decide(s, t(6, 16), 6 * 60 + 16))
    }

    @Test fun alarmStillAheadMeansEarlyEvenWithoutHistory() {
        val s = night(sig("alarm", t(4, 0), value = t(7, 0).toDouble()), sig("unlock", t(5, 50)), sig("screen", t(5, 50), t(5, 54)))
        assertTrue(WakeRules.early(s, t(5, 50), t(5, 55), 5 * 60 + 55, null))
        assertEquals(NightTransition.Continue, step(s, t(5, 55)))
        // 闹钟只差 20 分钟（多半是起床前看一眼）：照旧当场算起床
        val near = night(sig("alarm", t(4, 0), value = t(7, 0).toDouble()), sig("unlock", t(6, 40)), sig("screen", t(6, 40), t(6, 44)))
        assertFalse(WakeRules.early(near, t(6, 40), t(6, 45), 6 * 60 + 45, null))
        assertEquals(NightTransition.Finish, step(near, t(6, 45)))
    }

    @Test fun usualTimeGetUpStillEndsAtOnce() {
        // 11-18 那晚：平时就是这个点起床，解锁用了 3 分钟后放下去洗漱，当场收尾
        val s = night(sig("pickup", t(7, 6)), sig("unlock", t(7, 7)), sig("screen", t(7, 7), t(7, 10)), sig("quiet", t(7, 10), t(7, 11)))
        assertEquals(NightTransition.Finish, step(s, t(7, 11), seven))
        assertEquals(NightTransition.Finish, step(s, t(7, 11), 7 * 60 + 30))
    }

    @Test fun usualWakeNeedsThreeNights() {
        assertNull(WakeRules.usualWake(listOf(420, 430)))
        assertEquals(425, WakeRules.usualWake(listOf(420, 430, 425)))
    }

    @Test fun awakeTextSaysHowLongThePhoneWasUsed() {
        val span = NightSpan(t(3, 0), t(3, 20))
        assertEquals("看手机 13 分", SleepEstimate.awakeText(span, listOf(sig("screen", t(3, 0), t(3, 8)), sig("screen", t(3, 10), t(3, 14)), sig("screen", t(3, 6), t(3, 9)))))
        assertEquals("看了一眼手机", SleepEstimate.awakeText(span, listOf(sig("screen", t(3, 1), t(3, 1) + 40_000))))
        assertEquals("拿起手机", SleepEstimate.awakeText(span, listOf(sig("pickup", t(3, 2)))))
        assertEquals("醒着说话", SleepEstimate.awakeText(span, emptyList()))
    }
}
