package app.resound.ui.voiceprint

import android.media.AudioAttributes
import android.media.MediaPlayer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.*
import java.io.File

/** 人物页回放声纹样本：播放期间暂停日常收音（与原话播放一致），避免把回放声录进去。 */
internal object SamplePlayer {
    var playingKey by mutableStateOf<String?>(null); private set
    private var player: MediaPlayer? = null
    private var held = false

    suspend fun play(key: String, file: File) {
        stop()
        app.resound.ui.transcript.LinePlayer.stop()
        if (app.resound.AppGraph.isOriginalAudioReady) { app.resound.AppGraph.originalAudio.beginPlayback(); held = true }
        try {
            withTimeoutOrNull(4_000) { app.resound.capture.CaptureService.awaitPlaybackRelease() }
            val p = withContext(Dispatchers.IO) {
                MediaPlayer().apply {
                    setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                    setDataSource(file.path); prepare()
                }
            }
            p.setOnCompletionListener { stop() }
            player = p; playingKey = key; p.start()
        } catch (e: CancellationException) { stop(); throw e }
        catch (e: Exception) { stop(); throw e }
    }

    fun stop() {
        player?.runCatching { stop(); release() }; player = null; playingKey = null
        if (held) { if (app.resound.AppGraph.isOriginalAudioReady) app.resound.AppGraph.originalAudio.endPlayback(); held = false }
    }
}
