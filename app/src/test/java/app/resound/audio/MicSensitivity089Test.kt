package app.resound.audio

import app.resound.capture.SpeechSegmenter
import org.junit.Assert.*
import org.junit.Test

class MicSensitivity089Test {
    private class Sink : SpeechSegmenter.Sink {
        var kept = false
        var confirmed = false
        var count = 0
        val samples = mutableListOf<Short>()
        override fun begin(startSample: Long) { count++ }
        override fun write(samples: ShortArray) { this.samples.addAll(samples.toList()) }
        override fun confirmed() { confirmed = true }
        override fun end(endSample: Long, keep: Boolean) { kept = keep }
    }
    @Test fun externalShortSpeechIsKeptWithoutChangingRawSamplesOrPhoneRules() {
        val pcm = ShortArray(9600) { 25 }
        val external = Sink()
        val profile = VadSensitivity.forInput(InputKind.BluetoothCall)
        val cutter = SpeechSegmenter(external, profile.minSpeechSamples)
        cutter.accept(pcm, true); cutter.finish()
        assertTrue(external.kept); assertTrue(external.confirmed)
        assertArrayEquals(pcm, external.samples.toShortArray())
        val phone = Sink()
        SpeechSegmenter(phone, VadSensitivity.forInput(InputKind.Phone).minSpeechSamples).also {
            it.accept(pcm, true); it.finish()
        }
        assertFalse(phone.kept)
    }
    @Test fun silenceAndVeryShortClicksAreNotKept() {
        val sink = Sink()
        val cutter = SpeechSegmenter(sink, VadSensitivity.EXTERNAL.minSpeechSamples)
        cutter.accept(ShortArray(16_000), false); cutter.finish()
        assertEquals(0, sink.count)
        cutter.accept(ShortArray(1000), true); cutter.finish()
        assertFalse(sink.kept); assertFalse(sink.confirmed)
    }
    @Test fun detectorGainIsBoundedAndDoesNotApplyToPhone() {
        assertEquals(8f, VadSensitivity.EXTERNAL.detectionGain(0.001f))
        assertEquals(1f, VadSensitivity.EXTERNAL.detectionGain(0.1f))
        assertEquals(1f, VadSensitivity.EXTERNAL.detectionGain(0f))
        assertEquals(1f, VadSensitivity.DEFAULT.detectionGain(0.001f))
        assertEquals(VadSensitivity.EXTERNAL, VadSensitivity.forInput(InputKind.Usb))
    }
}
