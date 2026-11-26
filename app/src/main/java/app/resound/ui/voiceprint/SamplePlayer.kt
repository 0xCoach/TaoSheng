package app.resound.ui.voiceprint

import android.media.AudioAttributes
import android.media.MediaPlayer
import app.resound.AppGraph
import app.resound.capture.CaptureService
import app.resound.ui.transcript.LinePlayer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.*
import java.io.File

/**
 * 人物页回放声纹样本。全 App 同一时间只放一段：开始前停掉原话播放；
 * 播放期间暂停日常收音（与原话播放一致），免得把回放声又录进去，停下立即恢复。
 */
internal object SamplePlayer {
    /** 正在播的样本 key；没在播为 null */
    var playingKey by mutableStateOf<String?>(null); private set
    /** 0..1 播放进度，给按钮外圈的进度环 */
    var progress by mutableFloatStateOf(0f); private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var player: MediaPlayer? = null
    private var ticker: Job? = null
    private var held = false
    private var request = 0L

    suspend fun play(key: String, file: File) {
        stop()
        val playbackId = ++request
        LinePlayer.stop()
        if (AppGraph.isOriginalAudioReady) {
            AppGraph.originalAudio.beginPlayback()
            held = true
        }
        var prepared: MediaPlayer? = null
        try {
            withTimeoutOrNull(4_000) { CaptureService.awaitPlaybackRelease() }
            withContext(Dispatchers.IO) {
                val audio = MediaPlayer()
                prepared = audio
                val speech = AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .build()
                audio.setAudioAttributes(speech)
                audio.setDataSource(file.path)
                audio.prepare()
            }
            if (playbackId != request) return
            val p = checkNotNull(prepared)
            p.setOnCompletionListener { stop() }
            player = p
            prepared = null
            progress = 0f
            playingKey = key
            p.start()
            ticker = scope.launch {
                while (isActive && player === p) {
                    val d = p.runCatching { duration }.getOrDefault(0)
                    if (d > 0) progress = (p.runCatching { currentPosition }.getOrDefault(0) / d.toFloat()).coerceIn(0f, 1f)
                    delay(50)
                }
            }
        } catch (failure: Exception) {
            if (playbackId == request) stop()
            throw failure
        } finally {
            prepared?.let { audio -> runCatching { audio.release() } }
        }
    }

    fun stop() {
        request++
        ticker?.cancel(); ticker = null
        player?.runCatching { stop(); release() }; player = null
        playingKey = null; progress = 0f
        if (held) { if (app.resound.AppGraph.isOriginalAudioReady) app.resound.AppGraph.originalAudio.endPlayback(); held = false }
    }
}
