package app.resound.audio

import org.junit.Assert.*
import org.junit.Test

class CaptureRead173Test {
    @Test fun observersSelectTwentyOtherwiseTwoHundredMilliseconds() {
        assertEquals(20, CaptureReadPolicy.durationMillis(true))
        assertEquals(200, CaptureReadPolicy.durationMillis(false))
        for (rate in listOf(8000, 16000, 44100, 48000)) for (channels in 1..2) {
            assertEquals(maxOf(960, rate * channels / 50), CaptureReadPolicy.samples(rate, channels, 20)) // 与改前读取长度相同
            assertEquals(maxOf(960, rate * channels / 5), CaptureReadPolicy.samples(rate, channels, 200))
        }
    }

    @Test fun firstFrameIncludesConverterRemainderAndIsIndependentOfReadSize() {
        fun times(ms: Int): List<Long> {
            var now = 1_000_000L
            val clock = PcmBatchClock(16000, 1) { now }
            var pending = 0
            return buildList {
                repeat(10_000 / ms) {
                    now += ms
                    val n = ms * 16
                    clock.readCompleted(n)
                    pending += n
                    while (pending >= 480) { add(clock.nextFrame(480)); pending -= 480 }
                }
            }
        }
        val interactive = times(20)
        assertEquals(times(200), interactive)
        assertEquals(1_000_000L, interactive.first())
        assertEquals(1_000_000L + (interactive.size - 1) * 30, interactive.last())
    }

    @Test fun clockResetsForRouteAndIgnoresLaterWallClockJumps() {
        var now = 200L
        val clock = PcmBatchClock(48000, 2) { now }
        clock.readCompleted(19200)
        assertEquals(0L, clock.nextFrame(480))
        now = 9_000L
        clock.readCompleted(1920)
        assertEquals(30L, clock.nextFrame(480))
        clock.reset()
        clock.readCompleted(1920)
        assertEquals(8980L, clock.nextFrame(480))
    }
}
