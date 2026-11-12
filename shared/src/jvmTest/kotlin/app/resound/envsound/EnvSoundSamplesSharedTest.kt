package app.resound.envsound

import org.junit.Assert.*
import org.junit.Test

class EnvSoundSamplesSharedTest {
    @Test fun windowPreservesOffsetNormalizationAndPadding() {
        val pcm = shortArrayOf(7, Short.MIN_VALUE, 0, Short.MAX_VALUE, 9)
        val actual = EnvSoundSamples.window(pcm, 1..3)
        assertEquals(16000, actual.size)
        assertArrayEquals(floatArrayOf(-1f, 0f, 32767 / 32768f), actual.copyOfRange(0, 3), 0f)
        assertTrue(actual.drop(3).all { it == 0f })
    }

}
