package app.resound.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.*
import android.os.Build
import android.os.Handler
import android.os.Looper
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
        val signals = MicSignals(manager)
        fun yieldReason(): String? = MicYield.whileOpen(signals.silenced) { MicYield.inCall(manager.mode, ownsCommunication) }
        try {
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
            signals.attach(opened)
            onOpenChanged(true)
            val converter = PcmInputConverter(opened.sampleRate, opened.channelCount)
            // 每次读满约 20 毫秒再返回：循环跟着声音的节奏走，不空转
            val buffer = ShortArray(maxOf(960, opened.sampleRate * opened.channelCount / 50))
            var verified = false
            var routeDeadline = SystemClock.elapsedRealtime() + 2_000
            var lastDataAt = SystemClock.elapsedRealtime()
            val lastReturnAt = java.util.concurrent.atomic.AtomicLong(SystemClock.elapsedRealtime())
            val stalled = java.util.concurrent.atomic.AtomicBoolean(false)
            coroutineScope {
                // 阻塞读在设备不出声时会一直等；看门狗 2 秒没返回就停掉录音让它返回。取消时同样靠它解开阻塞。
                val watchdog = launch {
                    try {
                        while (true) {
                            delay(500)
                            val now = SystemClock.elapsedRealtime()
                            if (yieldReason() != null) lastReturnAt.set(now)
                            else if (now - lastReturnAt.get() > 2_000) { stalled.set(true); runCatching { opened.stop() }; break }
                        }
                    } finally { if (!stalled.get()) runCatching { opened.stop() } }
                }
                try {
                    while (currentCoroutineContext().isActive) {
                        signals.refresh()
                        val wait = yieldReason()
                        val wasBusy = reason != null
                        report(wait)
                        if (wait != null) {
                            converter.reset(); verified = false
                            routeDeadline = SystemClock.elapsedRealtime() + 2_000
                            lastDataAt = SystemClock.elapsedRealtime()
                        } else if (wasBusy) routeDeadline = SystemClock.elapsedRealtime() + 2_000
                        val n = opened.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
                        lastReturnAt.set(SystemClock.elapsedRealtime())
                        if (stalled.get()) throw AudioSourceException(AudioSourceException.Reason.Disconnected, "输入设备没有送来声音")
                        if (n < 0) throw AudioSourceException(if (n == AudioRecord.ERROR_DEAD_OBJECT)
                            AudioSourceException.Reason.Disconnected else AudioSourceException.Reason.Unknown, "AudioRecord read=$n")
                        val routed = signals.routed
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
                        // 正常情况下阻塞读自己控制节奏；个别系统读到 0 不等待时才歇一下，防止空转
                        if (n == 0) delay(10)
                    }
                } finally { watchdog.cancel() }
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
                signals.close()
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

/**
 * 被系统静音、实际路由两项由系统回调推送，收音循环只读缓存，不每圈去问系统。
 * 每 2 秒再主动核对一次，防个别系统漏发回调。
 */
private class MicSignals(private val manager: AudioManager) : AutoCloseable {
    @Volatile var silenced = false
        private set
    @Volatile var routed: AudioDeviceInfo? = null
        private set
    private var record: AudioRecord? = null
    private var checkedAt = 0L
    private val handler = Handler(Looper.getMainLooper())
    private val recordings = object : AudioManager.AudioRecordingCallback() {
        override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>) {
            val id = record?.audioSessionId ?: return
            configs.firstOrNull { it.clientAudioSessionId == id }?.let { silenced = it.isClientSilenced }
        }
    }
    private val routing = AudioRouting.OnRoutingChangedListener { routed = it.routedDevice }
    init { manager.registerAudioRecordingCallback(recordings, handler) }
    fun attach(opened: AudioRecord) {
        record = opened
        opened.addOnRoutingChangedListener(routing, handler)
        checkedAt = 0L; refresh()
    }
    fun refresh() {
        val opened = record ?: return
        val now = SystemClock.elapsedRealtime()
        if (now - checkedAt < 2_000) return
        checkedAt = now
        silenced = opened.activeRecordingConfiguration?.isClientSilenced == true
        routed = opened.routedDevice
    }
    override fun close() {
        manager.unregisterAudioRecordingCallback(recordings)
        record?.removeOnRoutingChangedListener(routing)
    }
}
