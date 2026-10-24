package app.resound.privacy

import app.resound.capture.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test

class PrivacyCaptureTest {
    @Test fun discardDropsConfirmedSpeechAndPrerollAndAllowsNewSegment() {
        val endings = mutableListOf<Boolean>()
        var writes = 0
        val cutter = SpeechSegmenter(object : SpeechSegmenter.Sink {
            override fun begin(startSample: Long) {}
            override fun write(samples: ShortArray) { writes += samples.size }
            override fun end(endSample: Long, keep: Boolean) { endings += keep }
        })
        cutter.accept(ShortArray(16000), true)
        cutter.discard(); cutter.finish()
        assertEquals(listOf(false), endings); assertFalse(cutter.active)
        writes = 0
        cutter.accept(ShortArray(16000), true); cutter.finish()
        assertEquals(16000, writes); assertEquals(listOf(false, true), endings)
    }
    @Test fun tileClicksPauseThirtyMinutesResumeOrStartAndShowsStatus() {
        val calls = mutableListOf<String>()
        val controller = object : CaptureController {
            override val status = MutableStateFlow(CaptureStatus(CaptureState.Listening, 0))
            override fun start() { calls += "start" }
            override fun stop() { error("不应停止") }
            override fun pause(minutes: Int) { calls += "pause:$minutes" }
            override fun resume() { calls += "resume" }
        }
        CaptureTilePolicy.click(controller)
        assertEquals("聆听中", CaptureTilePolicy.subtitle(controller.status.value))
        controller.status.value = CaptureStatus(CaptureState.Paused, 0, 0)
        CaptureTilePolicy.click(controller)
        assertTrue(CaptureTilePolicy.subtitle(controller.status.value).startsWith("已暂停到 "))
        controller.status.value = CaptureStatus(CaptureState.Stopped, 0)
        CaptureTilePolicy.click(controller)
        assertEquals("已停止", CaptureTilePolicy.subtitle(controller.status.value))
        assertEquals(listOf("pause:30", "resume", "start"), calls)
    }
}
