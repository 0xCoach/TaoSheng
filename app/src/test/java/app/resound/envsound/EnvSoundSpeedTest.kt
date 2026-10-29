package app.resound.envsound

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class EnvSoundSpeedTest {
    private fun scores(vararg values: Pair<String, Float>) =
        (EnvSoundRules.speech + EnvSoundRules.human + EnvSoundRules.tags.values.flatMap { it.second } +
            EnvSoundRules.sounds.values.flatMap { it.second }).associateWith { 0f } + values

    @Test fun windowPreservesOffsetNormalizationAndPadding() {
        val pcm = shortArrayOf(7, Short.MIN_VALUE, 0, Short.MAX_VALUE, 9)
        val actual = EnvSoundSamples.window(pcm, 1..3)
        assertEquals(16000, actual.size)
        assertArrayEquals(floatArrayOf(-1f, 0f, 32767 / 32768f), actual.copyOfRange(0, 3), 0f)
        assertTrue(actual.drop(3).all { it == 0f })
    }

    /** 30 秒全窗与长音频三窗必须在线性时间内完成；原先逐点 count() 会超时。 */
    @Test(timeout = 2000) fun fullAndLongWindowsPrepareWithoutQuadraticWork() {
        for (size in listOf(30 * 16000, 90 * 16000)) {
            val pcm = ShortArray(size) { ((it % 65536) - 32768).toShort() }
            for (window in EnvSoundRules.windows(size)) {
                val actual = EnvSoundSamples.window(pcm, window)
                assertEquals(window.last - window.first + 1, actual.size)
                for (i in actual.indices) assertEquals(pcm[window.first + i] / 32768f, actual[i], 0f)
            }
        }
    }

    @Test fun ordinarySpeechAndInvalidOutputAvoidIdentityWithoutChangingDecisions() = runBlocking {
        for (score in listOf(scores("Speech" to .9f, "Rain" to .4f), scores("Cough" to .1f), mapOf("Music" to 1f))) {
            val result = EnvSoundAnalysis({ shortArrayOf(1) }, { score }, { error("unnecessary identity check") }).analyze(File("unused"))
            assertFalse(result.second)
            for (skip in SkipMode.entries) for (media in MediaMode.entries) {
                val settings = EnvSoundSettings(enabled = true, skip = skip, media = media)
                assertEquals(EnvSoundRules.decide(score, settings, true), EnvSoundRules.decide(score, settings, result.second))
            }
        }
    }

    @Test fun environmentAndMediaRetainIdentityProtectionInEveryMode() = runBlocking {
        for (score in listOf(scores("Music" to .95f), scores("Music" to .95f, "Speech" to .8f))) {
            for (me in listOf(false, true)) {
                var calls = 0
                val result = EnvSoundAnalysis({ shortArrayOf(1) }, { score }, { calls++; me }).analyze(File("unused"))
                assertEquals(1, calls)
                for (skip in SkipMode.entries) for (media in MediaMode.entries) {
                    val settings = EnvSoundSettings(enabled = true, skip = skip, media = media)
                    assertEquals(EnvSoundRules.decide(score, settings, me), EnvSoundRules.decide(result.first, settings, result.second))
                    if (me) assertFalse(EnvSoundRules.decide(result.first, settings, result.second).skip)
                }
            }
        }
    }

    @Test fun identityFailurePropagatesForPassThroughAndClearsStaleProgress() = runBlocking {
        val phases = mutableListOf<String?>()
        val logs = mutableListOf<String>()
        val analysis = EnvSoundAnalysis({ shortArrayOf(1) }, { scores("Music" to .95f) },
            { error("identity failed") }, phases::add, logs::add)
        try { analysis.analyze(File("unused")); fail("failure must reach repository pass-through") }
        catch (e: IllegalStateException) { assertEquals("identity failed", e.message) }
        assertEquals("环境音判断 · 核对本人声音", phases[phases.lastIndex - 1])
        assertNull(phases.last())
        assertTrue(logs.single().contains("成功=false"))
    }
}
