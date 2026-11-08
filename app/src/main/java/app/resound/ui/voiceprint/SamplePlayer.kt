package app.resound.ui.voiceprint

import android.media.AudioAttributes
import android.media.MediaPlayer
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
        val mine = ++request
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
            if (mine != request) { p.release(); return }
            p.setOnCompletionListener { stop() }
            player = p; progress = 0f; playingKey = key; p.start()
            ticker = scope.launch {
                while (isActive && player === p) {
                    val d = p.runCatching { duration }.getOrDefault(0)
                    if (d > 0) progress = (p.runCatching { currentPosition }.getOrDefault(0) / d.toFloat()).coerceIn(0f, 1f)
                    delay(50)
                }
            }
        } catch (e: CancellationException) { stop(); throw e }
        catch (e: Exception) { stop(); throw e }
    }

    fun stop() {
        request++
        ticker?.cancel(); ticker = null
        player?.runCatching { stop(); release() }; player = null
        playingKey = null; progress = 0f
        if (held) { if (app.resound.AppGraph.isOriginalAudioReady) app.resound.AppGraph.originalAudio.endPlayback(); held = false }
    }
}
