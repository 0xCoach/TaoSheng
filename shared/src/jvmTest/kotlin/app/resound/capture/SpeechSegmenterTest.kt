package app.resound.capture

import org.junit.Assert.*
import org.junit.Test

class SpeechSegmenterTest {
    private class Output : SpeechSegmenter.Sink {
        data class Part(val start: Long, val samples: MutableList<Short> = mutableListOf(), var end: Long = -1, var keep: Boolean = false)
        val parts = mutableListOf<Part>()
        override fun begin(startSample: Long) { parts += Part(startSample) }
        override fun write(samples: ShortArray) { parts.last().samples.addAll(samples.toList()) }
        override fun end(endSample: Long, keep: Boolean) { parts.last().end = endSample; parts.last().keep = keep }
    }
    @Test fun retainsExactlyOneSecondBeforeSpeech() {
        val out = Output(); val s = SpeechSegmenter(out)
        s.accept(ShortArray(24_000) { it.toShort() }, false)
        s.accept(ShortArray(16_000) { 123 }, true); s.finish()
        assertEquals(8_000L, out.parts.single().start)
        assertEquals(32_000, out.parts.single().samples.size)
        assertEquals(8_000.toShort(), out.parts.single().samples.first())
        assertEquals(123.toShort(), out.parts.single().samples.last())
        assertTrue(out.parts.single().keep)
    }
    @Test fun endsAtTwoSecondsOfConsecutiveSilence() {
        val out = Output(); val s = SpeechSegmenter(out)
        s.accept(ShortArray(16_000), true)
        s.accept(ShortArray(31_999), false); assertTrue(s.active)
        s.accept(shortArrayOf(0), true) // 人声会重置静音计数
        s.accept(ShortArray(31_999), false); assertTrue(s.active)
        s.accept(shortArrayOf(0), false); assertFalse(s.active)
        assertEquals(80_000L, out.parts.single().end)
    }
    @Test fun discardsCoughDespitePreRollAndTrailingSilence() {
        val out = Output(); val s = SpeechSegmenter(out)
        s.accept(ShortArray(16_000), false)
        s.accept(ShortArray(15_999), true)
        s.accept(ShortArray(32_000), false)
        assertFalse(out.parts.single().keep)
    }
    @Test fun splitsAtFiveMinutesWithOneSecondOverlap() {
        val out = Output(); val s = SpeechSegmenter(out)
        val total = CaptureTuning.MAX_SEGMENT_SAMPLES + 16_000
        repeat(total / 512) { frame -> s.accept(ShortArray(512) { (frame * 512 + it).toShort() }, true) }
        val remainder = total % 512
        if (remainder > 0) s.accept(ShortArray(remainder) { (total - remainder + it).toShort() }, true)
        s.finish()
        assertEquals(2, out.parts.size)
        assertEquals(CaptureTuning.MAX_SEGMENT_SAMPLES, out.parts[0].samples.size)
        assertEquals((CaptureTuning.MAX_SEGMENT_SAMPLES - 16_000).toLong(), out.parts[1].start)
        assertEquals(total.toLong(), out.parts[1].end)
        assertTrue(out.parts.all { it.keep })
        out.parts.forEach { p -> p.samples.forEachIndexed { i, sample -> assertEquals((p.start + i).toShort(), sample) } }
    }
    @Test fun nextUtteranceKeepsPreRollImmediatelyAfterPreviousSilenceEnd() {
        val out = Output(); val s = SpeechSegmenter(out)
        s.accept(ShortArray(16_000) { 1 }, true)
        s.accept(ShortArray(32_000) { 2 }, false)
        assertFalse(s.active)
        s.accept(ShortArray(16_000) { 3 }, true)
        s.finish()
        assertEquals(2, out.parts.size)
        assertEquals(32_000L, out.parts[1].start)
        assertEquals(32_000, out.parts[1].samples.size)
        assertTrue(out.parts[1].samples.take(16_000).all { it == 2.toShort() })
        assertTrue(out.parts[1].keep)
    }
    @Test fun finishDropsShortTailAndSilenceAloneCreatesNoFile() {
        val out = Output(); val s = SpeechSegmenter(out)
        repeat(100) { s.accept(ShortArray(480), false) }; s.finish()
        assertTrue(out.parts.isEmpty())
        s.accept(ShortArray(100), true); s.finish()
        assertFalse(out.parts.single().keep)
    }
}
