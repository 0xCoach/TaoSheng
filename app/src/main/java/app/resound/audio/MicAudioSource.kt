package app.resound.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioRecordingConfiguration
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive

/**
 * 手机麦克风。回响是后台记录者，必须让着别人：
 * 打开前有别的 App 在录音或正在通话就等；已打开后被系统静音也不关、不重开（重开会变成“最近开始录音”的一方，把别人挤成静音），
 * 静静等系统取消静音即可。
 */
class MicAudioSource(private val context: Context, private val onOpenChanged: (Boolean) -> Unit = {}) : AudioSource {
    override val id = "mic"
    override val displayName = "手机麦克风"
    override fun events(): Flow<AudioEvent> = flow {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            throw AudioSourceException(AudioSourceException.Reason.NoPermission)
        val manager = context.getSystemService(AudioManager::class.java)
        val mySession = AtomicInteger(0)
        val silenced = AtomicBoolean(false)
        val changed = Channel<Unit>(Channel.CONFLATED)
        val callback = object : AudioManager.AudioRecordingCallback() {
            override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>) {
                silenced.set(configs.any { it.clientAudioSessionId == mySession.get() && it.isClientSilenced })
                changed.trySend(Unit)
            }
        }
        fun inCall() = manager.mode == AudioManager.MODE_RINGTONE || manager.mode == AudioManager.MODE_IN_CALL ||
            manager.mode == AudioManager.MODE_IN_COMMUNICATION
        fun othersRecording() = manager.activeRecordingConfigurations
            .count { it.clientAudioSessionId != mySession.get() && !it.isClientSilenced }

        var busyReason: String? = null
        suspend fun report(reason: String?) {
            if (reason == busyReason) return
            busyReason = reason
            emit(if (reason == null) AudioEvent.Available else AudioEvent.Busy(reason))
        }

        var recorder: AudioRecord? = null
        var registered = false
        try {
            manager.registerAudioRecordingCallback(callback, Handler(Looper.getMainLooper()))
            registered = true
            while (true) {
                val reason = MicYield.beforeOpen(inCall(), othersRecording()) ?: break
                report(reason)
                // 别人停止录音有回调；通话状态没有回调，兜底每 2 秒看一次
                withTimeoutOrNull(2_000) { changed.receive() }
            }
            val record = createRecorder().also { recorder = it }
            mySession.set(record.audioSessionId)
            if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING)
                throw AudioSourceException(AudioSourceException.Reason.Busy, "麦克风没能开始录音")
            onOpenChanged(true)
            var frame = ShortArray(AudioSource.FRAME_SAMPLES)
            var filled = 0
            while (currentCoroutineContext().isActive) {
                val reason = MicYield.whileOpen(inCall(), silenced.get() || record.activeRecordingConfiguration?.isClientSilenced == true)
                report(reason)
                // 被静音时照常读出并丢弃，保持设备打开
                val n = record.read(frame, filled, frame.size - filled, AudioRecord.READ_NON_BLOCKING)
                if (n < 0) throw AudioSourceException(if (n == AudioRecord.ERROR_DEAD_OBJECT) AudioSourceException.Reason.Disconnected else AudioSourceException.Reason.Unknown, "AudioRecord read=$n")
                filled += n
                if (filled == frame.size) {
                    if (reason == null) { emit(AudioEvent.Frame(frame)); frame = ShortArray(AudioSource.FRAME_SAMPLES) }
                    filled = 0
                }
                if (n == 0) delay(5)
            }
        } catch (e: SecurityException) {
            throw AudioSourceException(AudioSourceException.Reason.NoPermission, e.message)
        } finally {
            try {
                recorder?.let { runCatching { it.stop() }; it.release() }
            } finally {
                onOpenChanged(false)
                if (registered) manager.unregisterAudioRecordingCallback(callback)
            }
        }
    }.flowOn(Dispatchers.IO)

    private fun createRecorder(): AudioRecord {
        // 先用普通 MIC：小米 14 Pro 的 VOICE_RECOGNITION 音源对非贴嘴的声音压得极低（同一段外放语音约 -50 dBFS、人声检出 0%），
        // MIC 约 -28 dBFS、检出接近 100%。
        for (source in listOf(MediaRecorder.AudioSource.MIC, MediaRecorder.AudioSource.VOICE_RECOGNITION)) {
            var record: AudioRecord? = null
            try {
                record = AudioRecord.Builder().setAudioSource(source)
                    .setAudioFormat(AudioFormat.Builder().setSampleRate(AudioSource.SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                    .setBufferSizeInBytes(maxOf(AudioRecord.getMinBufferSize(AudioSource.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT), 480 * 2 * 8)).build()
                if (record.state == AudioRecord.STATE_INITIALIZED) {
                    record.startRecording()
                    if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) return record
                }
            } catch (e: SecurityException) {
                record?.release()
                throw AudioSourceException(AudioSourceException.Reason.NoPermission, e.message)
            } catch (_: IllegalArgumentException) { /* 尝试 MIC */ }
            catch (_: IllegalStateException) { /* 尝试 MIC */ }
            record?.release()
        }
        throw AudioSourceException(AudioSourceException.Reason.Busy, "无法初始化麦克风")
    }
}
