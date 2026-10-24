package app.resound.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.*
import android.os.Build
import android.os.SystemClock
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class ExternalAudioSource(
    private val context: Context,
    private val device: InputDevice,
    private val port: AudioDeviceInfo,
    private val onOpenChanged: (Boolean) -> Unit = {},
) : AudioSource {
    override val id = device.key
    override val displayName = device.name
    override fun events(): Flow<AudioEvent> = RoutedRecording(context, { port }, device.kind, onOpenChanged).events()
}

/** 手机和外接共用礼让/释放逻辑，只有设备、音源及原生格式不同。 */
internal class RoutedRecording(
    private val context: Context,
    private val target: () -> AudioDeviceInfo?,
    private val kind: InputKind,
    private val onOpenChanged: (Boolean) -> Unit,
) {
    private val bluetooth = kind == InputKind.BluetoothCall || kind == InputKind.BluetoothLe
    fun events(): Flow<AudioEvent> = flow {
        fun permitted(permission: String) = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        if (!permitted(Manifest.permission.RECORD_AUDIO) ||
            bluetooth && Build.VERSION.SDK_INT >= 31 && !permitted(Manifest.permission.BLUETOOTH_CONNECT))
            throw AudioSourceException(AudioSourceException.Reason.NoPermission)
        val manager = context.getSystemService(AudioManager::class.java)
        var record: AudioRecord? = null
        var ownsCommunication = false
        var requestedCommunication = false
        var previousMode = AudioManager.MODE_NORMAL
        var reason: String? = null
        suspend fun report(next: String?) {
            if (next != reason) { reason = next; emit(if (next == null) AudioEvent.Available else AudioEvent.Busy(next)) }
        }
        fun inCall(): Boolean = MicYield.inCall(manager.mode, ownsCommunication)
        fun others(): Int = manager.activeRecordingConfigurations.count {
            it.clientAudioSessionId != record?.audioSessionId && !it.isClientSilenced
        }
        try {
            while (true) {
                val wait = MicYield.beforeOpen(inCall(), others()) ?: break
                report(wait); delay(200)
            }
            val port = target() ?: throw AudioSourceException(AudioSourceException.Reason.Disconnected, "输入已断开")
            if (bluetooth) {
                previousMode = manager.mode
                manager.mode = AudioManager.MODE_IN_COMMUNICATION
                ownsCommunication = true
                requestedCommunication = true
                if (Build.VERSION.SDK_INT >= 31) {
                    // API 要求传输出端口，系统自动配对输入端口，不能把输入 id 传进去。
                    val sink = manager.availableCommunicationDevices.firstOrNull { candidate ->
                        candidate.type == port.type && if (port.address.isNotBlank()) candidate.address == port.address
                        else candidate.productName.toString() == port.productName.toString()
                    } ?: throw AudioSourceException(AudioSourceException.Reason.Disconnected, "没有匹配的蓝牙通话设备")
                    if (!manager.setCommunicationDevice(sink))
                        throw AudioSourceException(AudioSourceException.Reason.Busy, "蓝牙通话路由未获接受")
                } else {
                    @Suppress("DEPRECATION")
                    manager.startBluetoothSco()
                }
            }
            val opened = open(port, bluetooth)
            record = opened
            onOpenChanged(true)
            val converter = PcmInputConverter(opened.sampleRate, opened.channelCount)
            val buffer = ShortArray(maxOf(960, opened.sampleRate * opened.channelCount / 50))
            var verified = false
            var routeDeadline = SystemClock.elapsedRealtime() + 2_000
            var lastDataAt = SystemClock.elapsedRealtime()
            var othersAt = 0L
            var otherCount = 0
            while (currentCoroutineContext().isActive) {
                val elapsed = SystemClock.elapsedRealtime()
                if (elapsed - othersAt >= 200) { otherCount = others(); othersAt = elapsed }
                val silenced = opened.activeRecordingConfiguration?.isClientSilenced == true
                val wait = MicYield.whileOpen(inCall(), silenced || otherCount > 0)
                val wasBusy = reason != null
                report(wait)
                if (wait != null) {
                    converter.reset(); verified = false
                    routeDeadline = SystemClock.elapsedRealtime() + 2_000
                    lastDataAt = SystemClock.elapsedRealtime()
                } else if (wasBusy) routeDeadline = SystemClock.elapsedRealtime() + 2_000
                val n = opened.read(buffer, 0, buffer.size, AudioRecord.READ_NON_BLOCKING)
                if (n < 0) throw AudioSourceException(if (n == AudioRecord.ERROR_DEAD_OBJECT)
                    AudioSourceException.Reason.Disconnected else AudioSourceException.Reason.Unknown, "AudioRecord read=$n")
                val routed = opened.routedDevice
                val matches = if (kind == InputKind.Phone) routed?.type == AudioDeviceInfo.TYPE_BUILTIN_MIC else routed?.id == port.id
                if (wait == null) {
                    if (!matches) {
                        converter.reset()
                        if (verified || SystemClock.elapsedRealtime() >= routeDeadline)
                            throw AudioSourceException(AudioSourceException.Reason.Disconnected, "实际输入不是指定麦克风")
                    } else {
                        verified = true
                        if (n > 0) {
                            lastDataAt = SystemClock.elapsedRealtime()
                            for (frame in converter.accept(buffer, n)) emit(AudioEvent.Frame(frame))
                        }
                        if (SystemClock.elapsedRealtime() - lastDataAt > 2_000)
                            throw AudioSourceException(AudioSourceException.Reason.Disconnected, "输入设备没有送来声音")
                    }
                }
                if (n == 0 || wait != null || !matches) delay(5)
            }
        } catch (e: SecurityException) {
            throw AudioSourceException(AudioSourceException.Reason.NoPermission, e.message)
        } catch (e: IllegalArgumentException) {
            throw AudioSourceException(AudioSourceException.Reason.Unknown, e.message)
        } catch (e: IllegalStateException) {
            // CancellationException 继承 IllegalStateException，必须原样传播。
            if (e is CancellationException) throw e
            throw AudioSourceException(AudioSourceException.Reason.Unknown, e.message)
        } finally {
            try {
                record?.let { runCatching { it.stop() }; it.release() }
            } finally {
                onOpenChanged(false)
                if (requestedCommunication) {
                    if (Build.VERSION.SDK_INT >= 31) runCatching { manager.clearCommunicationDevice() }
                    else { @Suppress("DEPRECATION") runCatching { manager.stopBluetoothSco() } }
                }
                if (ownsCommunication && manager.mode == AudioManager.MODE_IN_COMMUNICATION) runCatching { manager.mode = previousMode }
            }
        }
    }.flowOn(Dispatchers.IO)

    private fun open(port: AudioDeviceInfo, bluetooth: Boolean): AudioRecord {
        val rates = (listOf(AudioSource.SAMPLE_RATE) + port.sampleRates.toList() + listOf(48_000, 44_100, 32_000, 8_000)).distinct().filter { it > 0 }
        val channels = (listOf(1) + port.channelCounts.toList() + listOf(2)).distinct().filter { it in 1..2 }
        val sources = if (bluetooth) listOf(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
            else listOf(MediaRecorder.AudioSource.MIC, MediaRecorder.AudioSource.VOICE_RECOGNITION)
        for (source in sources) for (rate in rates) for (count in channels) {
            var record: AudioRecord? = null
            var keep = false
            try {
                val mask = if (count == 1) AudioFormat.CHANNEL_IN_MONO else AudioFormat.CHANNEL_IN_STEREO
                val minimum = AudioRecord.getMinBufferSize(rate, mask, AudioFormat.ENCODING_PCM_16BIT)
                if (minimum <= 0) continue
                record = AudioRecord.Builder().setAudioSource(source).setAudioFormat(AudioFormat.Builder()
                    .setSampleRate(rate).setChannelMask(mask).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                    .setBufferSizeInBytes(maxOf(minimum, rate * count * 2 / 5)).build()
                if (record.state != AudioRecord.STATE_INITIALIZED || !record.setPreferredDevice(port)) continue
                OwnRecordingSessions.add(record.audioSessionId)
                record.startRecording()
                if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) { keep = true; return record }
            } catch (_: IllegalArgumentException) { /* 下一种原生格式 */ }
            catch (_: IllegalStateException) { /* 下一种原生格式 */ }
            finally { if (!keep) record?.let { runCatching { it.stop() }; it.release() } }
        }
        throw AudioSourceException(AudioSourceException.Reason.Busy, "无法打开输入设备")
    }
}
