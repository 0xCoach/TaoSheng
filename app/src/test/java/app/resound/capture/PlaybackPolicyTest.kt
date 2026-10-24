package app.resound.capture

import android.media.AudioAttributes as A
import android.media.AudioDeviceInfo as D
import org.junit.Assert.*
import org.junit.Test

class PlaybackPolicyTest {
    @Test fun majorityThresholdAndDisabledSetting() {
        assertTrue(PlaybackPolicy.skip(10_000, 5_000, true))
        assertFalse(PlaybackPolicy.skip(10_000, 4_999, true))
        assertFalse(PlaybackPolicy.skip(10_000, 10_000, false))
        assertFalse(PlaybackPolicy.skip(0, 0, true))
    }
    @Test fun headphonesAndShortSystemSoundsAreNotSpeakerMedia() {
        assertTrue(PlaybackPolicy.speaker(A.USAGE_MEDIA, D.TYPE_BUILTIN_SPEAKER, emptyList()))
        assertFalse(PlaybackPolicy.speaker(A.USAGE_MEDIA, D.TYPE_BLUETOOTH_A2DP, listOf(D.TYPE_BUILTIN_SPEAKER)))
        assertFalse(PlaybackPolicy.speaker(A.USAGE_MEDIA, null, listOf(D.TYPE_BUILTIN_SPEAKER, D.TYPE_WIRED_HEADPHONES)))
        assertFalse(PlaybackPolicy.speaker(A.USAGE_MEDIA, null, listOf(D.TYPE_BUILTIN_SPEAKER, D.TYPE_BLE_HEADSET)))
        assertFalse(PlaybackPolicy.speaker(A.USAGE_ALARM, D.TYPE_BUILTIN_SPEAKER, emptyList()))
        assertTrue(PlaybackPolicy.speaker(A.USAGE_GAME, null, listOf(D.TYPE_BUILTIN_SPEAKER)))
    }
    @Test fun halfHourIsContinuousWithExactOneSecondOverlapAndPlaybackAccounting() {
        data class Part(val start: Long, var size: Long = 0, var end: Long = 0, var playback: Long = 0, var keep: Boolean = false)
        val parts = mutableListOf<Part>()
        val sink = object : SpeechSegmenter.Sink {
            override fun begin(startSample: Long) { parts += Part(startSample) }
            override fun write(samples: ShortArray) {
                val p = parts.last()
                samples.forEachIndexed { i, value -> assertEquals((p.start + p.size + i).toShort(), value) }
                p.size += samples.size
            }
            override fun playback(samples: Long) { parts.last().playback = samples }
            override fun end(endSample: Long, keep: Boolean) { parts.last().end = endSample; parts.last().keep = keep }
        }
        val s = SpeechSegmenter(sink)
        val total = 30 * 60 * CaptureTuning.SAMPLE_RATE
        var cursor = 0
        while (cursor < total) {
            val n = minOf(512, total - cursor)
            s.accept(ShortArray(n) { (cursor + it).toShort() }, true, true); cursor += n
        }
        s.finish()
        assertEquals(7, parts.size) // 六个五分钟段带重叠，最后余下六秒。
        assertEquals(0L, parts.first().start); assertEquals(total.toLong(), parts.last().end)
        parts.forEach { assertTrue(it.keep); assertTrue(it.size <= CaptureTuning.MAX_SEGMENT_SAMPLES); assertEquals(it.size, it.end - it.start); assertEquals(it.size, it.playback) }
        parts.zipWithNext().forEach { (a, b) -> assertEquals(16_000L, a.end - b.start) }
    }
}
