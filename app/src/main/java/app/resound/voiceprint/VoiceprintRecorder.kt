package app.resound.voiceprint

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import app.resound.capture.CaptureController
import app.resound.capture.CaptureState
import kotlinx.coroutines.*

/** 录入只在用户点开始后进行；临时让出日常录音麦克风，取消/失败均恢复原状态。 */
object VoiceprintRecorder {
    @android.annotation.SuppressLint("MissingPermission")
    suspend fun record(controller: CaptureController, progress: (Int) -> Unit): ShortArray = withContext(Dispatchers.IO) {
        val original = controller.status.value.state
        val resume = original in setOf(CaptureState.Listening, CaptureState.Recording, CaptureState.MicBusy)
        var recorder: AudioRecord? = null
        try {
            if (resume) { controller.pause(2); delay(500) }
            val min = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            require(min > 0)
            // 和日常录音用同一个音源（MIC）：VOICE_RECOGNITION 会压低离嘴远的人声，录出来的声纹和日常收音对不上
            val active = AudioRecord(MediaRecorder.AudioSource.MIC, 16000, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(min * 2, 32000)).also { recorder = it }
            check(active.state == AudioRecord.STATE_INITIALIZED)
            active.startRecording()
            val pcm = ShortArray(15 * 16000)
            var count = 0
            withTimeout(20_000) {
                while (count < pcm.size) {
                    ensureActive()
                    val n = active.read(pcm, count, minOf(3200, pcm.size-count), AudioRecord.READ_NON_BLOCKING)
                    check(n >= 0)
                    count += n
                    progress(count / 16000)
                    if (n == 0) delay(10)
                }
            }
            pcm
        } finally {
            runCatching { recorder?.stop() }; recorder?.release()
            if (resume && controller.status.value.state != CaptureState.Stopped && controller.status.value.state != CaptureState.NoPermission) withContext(NonCancellable) { controller.resume() }
        }
    }
}
