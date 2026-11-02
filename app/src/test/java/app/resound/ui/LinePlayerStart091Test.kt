package app.resound.ui

import android.app.Application
import android.media.AudioManager
import android.os.Looper
import app.resound.AppGraph
import app.resound.transcribe.*
import app.resound.ui.transcript.LinePlayer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], manifest = Config.NONE, application = Application::class)
class LinePlayerStart091Test {
    private class Audio : OriginalAudio {
        val result = CompletableDeferred<SegmentAudio?>()
        var locating = false
        var held = 0
        var released = 0
        override val retentionDays = MutableStateFlow(7)
        override val keepStarred = MutableStateFlow(true)
        override fun setRetentionDays(days: Int) {}
        override fun setKeepStarred(keep: Boolean) {}
        override fun usage() = flowOf(AudioUsage(0, 0, 0, 0))
        override suspend fun previewTrim(days: Int) = AudioTrim(0, 0, 0, 0)
        override suspend fun previewClear(includeStarred: Boolean) = AudioTrim(0, 0, 0, 0)
        override suspend fun clear(includeStarred: Boolean) = AudioTrim(0, 0, 0, 0)
        override suspend fun previewClearRange(fromMillis: Long, untilMillis: Long, includeStarred: Boolean) = AudioTrim(0, 0, 0, 0)
        override suspend fun clearRange(fromMillis: Long, untilMillis: Long, includeStarred: Boolean) = AudioTrim(0, 0, 0, 0)
        override suspend fun locate(segmentId: Long): SegmentAudio? { locating = true; return result.await() }
        override suspend fun available(segmentIds: Set<Long>) = segmentIds
        override fun beginPlayback() { held++ }
        override fun endPlayback() { released++ }
    }

    @After fun cleanup() { LinePlayer.stop() }

    private fun audio() = Audio().also { LinePlayer.stop(); AppGraph.originalAudio = it }
    private fun segment(): SegmentAudio {
        val file = File(RuntimeEnvironment.getApplication().cacheDir, "test-playback.pcm").apply { writeBytes(byteArrayOf(0, 0)) }
        return SegmentAudio(1, file, 1000, 2000)
    }
    private fun play() = LinePlayer.play(RuntimeEnvironment.getApplication(), listOf(TranscriptLine(1, 1000, 2000, "测试", null, segmentId = 1)), 0)

    @Test fun stopWhileLocatingCannotStartPlaybackWhenLookupCompletes() {
        val audio = audio()
        play(); shadowOf(Looper.getMainLooper()).idle()
        assertTrue(audio.locating)
        LinePlayer.stop()
        audio.result.complete(segment()); shadowOf(Looper.getMainLooper()).idle()
        assertFalse(LinePlayer.playing); assertFalse(LinePlayer.active)
        assertEquals(0, audio.held)
    }

    @Test fun pauseDuringModeSwitchInvalidatesPendingStartAndReturnsCapture() {
        val audio = audio()
        val manager = RuntimeEnvironment.getApplication().getSystemService(AudioManager::class.java)
        manager.mode = AudioManager.MODE_IN_COMMUNICATION
        audio.result.complete(segment())
        play(); shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, audio.held)
        LinePlayer.pause(); manager.mode = AudioManager.MODE_NORMAL
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(4))
        assertFalse(LinePlayer.playing)
        assertEquals(1, audio.released)
    }
}
