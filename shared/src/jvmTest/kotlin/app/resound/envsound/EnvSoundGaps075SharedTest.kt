package app.resound.envsound

import org.junit.Assert.*
import org.junit.Test

class EnvSoundGaps075SharedTest {
    @Test fun noGapAndSubHalfSecondRunsGiveNoAudio() {
        val pcm = ShortArray(160000)
        assertEquals(0, EnvSoundGaps.extract(pcm, emptyList()).pcm.size)
        val result = EnvSoundGaps.extract(pcm, listOf(0 until 7999, 9000 until 16999))
        assertEquals(0, result.availableSamples)
        assertEquals(0, result.pcm.size)
    }

    @Test fun trimBothEdgesBeforeApplyingTwoSecondMinimum() {
        val pcm = ShortArray(40000) { it.toShort() }
        val short = EnvSoundGaps.extract(pcm, listOf(0 until 35199))
        assertEquals(31999, short.availableSamples)
        assertTrue(short.pcm.isEmpty())
        val enough = EnvSoundGaps.extract(pcm, listOf(0 until 35200))
        assertArrayEquals(pcm.copyOfRange(1600, 33600), enough.pcm)
        // 0.5秒是收边前门槛；七段各剩0.3秒，累计2.1秒。
        val fragmented = EnvSoundGaps.extract(ShortArray(70000), (0..6).map { it*10000 until it*10000+8000 })
        assertEquals(33600, fragmented.pcm.size)
    }

    @Test fun collectorKeepsActualTailAndExcludesSpeech() {
        val pcm = ShortArray(60000) { it.toShort() }
        val collector = EnvSoundGaps.Collector()
        collector.accept(10000, true)
        collector.accept(20000, false)
        collector.accept(20000, false)
        collector.accept(9999, false)
        collector.accept(1, false)
        val result = collector.finish(pcm)
        assertEquals(46800, result.availableSamples)
        assertArrayEquals(pcm.copyOfRange(11600, 58400), result.pcm)
    }

    @Test fun longGapsUseThirtySecondsAcrossBeginningMiddleAndEnd() {
        val pcm = ShortArray(70*16000) { 9 }
        pcm.fill(1, 0, 10*16000)
        pcm.fill(2, 20*16000, 40*16000)
        pcm.fill(3, 50*16000, 70*16000)
        val result = EnvSoundGaps.extract(pcm, listOf(0 until 10*16000, 20*16000 until 40*16000, 50*16000 until 70*16000))
        assertEquals(790400, result.availableSamples)
        assertEquals(480000, result.pcm.size)
        assertEquals(1.toShort(), result.pcm.first())
        assertEquals(3.toShort(), result.pcm.last())
        assertEquals(setOf<Short>(1, 2, 3), result.pcm.toSet())
        assertTrue((1 until result.pcm.size).all { result.pcm[it-1] <= result.pcm[it] })
    }

}
