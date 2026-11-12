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
    private val readDurationMillis: () -> Int = { CaptureReadPolicy.INTERACTIVE_MS },
) : AudioSource, AudioTimeline {
    override val id = device.key
    override val displayName = device.name
    override var frameEpochMillis: Long? = null
        private set
    override fun events(): Flow<AudioEvent> {
        val recording = RoutedRecording(context, { port }, device.kind, readDurationMillis, onOpenChanged)
        return recording.events().onEach { frameEpochMillis = recording.frameEpochMillis }
    }
    override val frameArchiveId: String? get() = null
    override fun processed(samples: Int) {}
    override fun finished(discarded: Boolean) {}
}

/** 手机和外接共用礼让/释放逻辑，只有设备、音源及原生格式不同。 */
internal class RoutedRecording(
    private val context: Context,
    private val target: () -> AudioDeviceInfo?,
    private val kind: InputKind,
    private val readDurationMillis: () -> Int = { CaptureReadPolicy.INTERACTIVE_MS },
    private val onOpenChanged: (Boolean) -> Unit,
) {
    private val bluetooth = kind == InputKind.BluetoothCall || kind == InputKind.BluetoothLe
    // 时间跟帧一起跨 flowOn 通道；只在消费线程暴露当前帧，不能让 IO 线程提前覆盖。
    private data class TimedEvent(val event: AudioEvent, val epochMillis: Long? = null)
    var frameEpochMillis: Long? = null
        private set
    fun events(): Flow<AudioEvent> = timedEvents().map { timed ->
        frameEpochMillis = timed.epochMillis
        timed.event
    }
    private fun timedEvents(): Flow<TimedEvent> = flow {
        fun permitted(permission: String) = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        if (!permitted(Manifest.permission.RECORD_AUDIO) ||
            bluetooth && Build.VERSION.SDK_INT >= 31 && !permitted(Manifest.permission.BLUETOOTH_CONNECT))
            throw AudioSourceException(AudioSourceException.Reason.NoPermission)
        val manager = context.getSystemService(AudioManager::class.java)
        // 卡 112：蓝牙要占通话模式，占着时别的应用（如语音输入法）拿不到话筒。
        // 发现别人要录音就整轮让出（释放录音和通话路由），等别人用完再接回来；手机麦克风由系统自动礼让，不需要这样做。
        while (true) {
            if (bluetooth) while (CaptureHealth.othersRecording(manager)) { report(MicYield.OTHER_APP); delay(1_000) }
            val yielded = recordOnce(manager)
            if (!yielded) return@flow
        }
    }.flowOn(Dispatchers.IO)

    /** 录一轮；蓝牙为别的应用让出话筒时返回 true（外层等别人用完再来），其余结束方式照旧抛异常或返回 false。 */
    private suspend fun FlowCollector<TimedEvent>.recordOnce(manager: AudioManager): Boolean {
        var record: AudioRecord? = null
        var ownsCommunication = false
        var requestedCommunication = false
        val signals = MicSignals(manager)
        fun yieldReason(): String? = MicYield.whileOpen(signals.silenced) { MicYield.inCall(manager.mode, ownsCommunication) }
        try {
            val port = target() ?: throw AudioSourceException(AudioSourceException.Reason.Disconnected, "输入已断开")
            if (bluetooth) {
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
            // 只分配一次最大缓冲，每圈读取当前订阅状态；从批量切回声波最多迟 200 ms。
            val buffer = ShortArray(CaptureReadPolicy.samples(opened.sampleRate, opened.channelCount, CaptureReadPolicy.BATCH_MS))
            val clock = PcmBatchClock(opened.sampleRate, opened.channelCount, System::currentTimeMillis)
            var verified = false
            var routeDeadline = SystemClock.elapsedRealtime() + 2_000
            var lastDataAt = SystemClock.elapsedRealtime()
            val zeros = CaptureHealth.ZeroWatch()
            val lastReturnAt = java.util.concurrent.atomic.AtomicLong(SystemClock.elapsedRealtime())
            val stalled = java.util.concurrent.atomic.AtomicBoolean(false)
            var yielded = false
            coroutineScope {
                // 阻塞读在设备不出声时会一直等；看门狗超时没返回就停掉录音让它返回。取消时同样靠它解开阻塞。
                // 卡 112：让麦期间也要有上限，否则读卡死时永远出不来（原来让麦期间不计时）。
                val watchdog = launch {
                    try {
                        while (true) {
                            delay(500)
                            val limit = if (yieldReason() != null) CaptureHealth.STALL_YIELDING_MS else CaptureHealth.STALL_MS
                            if (SystemClock.elapsedRealtime() - lastReturnAt.get() > limit) { stalled.set(true); runCatching { opened.stop() }; break }
                        }
                    } finally { if (!stalled.get()) runCatching { opened.stop() } }
                }
                try {
                    while (currentCoroutineContext().isActive) {
                        signals.refresh()
                        if (bluetooth && signals.othersRecording) { yielded = true; break }
                        val wait = yieldReason()
                        val wasBusy = reported != null
                        report(wait)
                        if (wait != null) {
                            converter.reset(); clock.reset(); verified = false; zeros.reset()
                            routeDeadline = SystemClock.elapsedRealtime() + 2_000
                            lastDataAt = SystemClock.elapsedRealtime()
                        } else if (wasBusy) routeDeadline = SystemClock.elapsedRealtime() + 2_000
                        val count = CaptureReadPolicy.samples(opened.sampleRate, opened.channelCount, readDurationMillis())
                        val n = opened.read(buffer, 0, count, AudioRecord.READ_BLOCKING)
                        if (n > 0 && wait == null) clock.readCompleted(n)
                        lastReturnAt.set(SystemClock.elapsedRealtime())
                        if (stalled.get()) throw AudioSourceException(AudioSourceException.Reason.Disconnected, "输入设备没有送来声音")
                        if (n < 0) throw AudioSourceException(if (n == AudioRecord.ERROR_DEAD_OBJECT)
                            AudioSourceException.Reason.Disconnected else AudioSourceException.Reason.Unknown, "AudioRecord read=$n")
                        val routed = signals.routed
                        val matches = if (kind == InputKind.Phone) routed?.type == AudioDeviceInfo.TYPE_BUILTIN_MIC else routed?.id == port.id
                        if (wait == null) {
                            if (!matches) {
                                converter.reset(); clock.reset(); zeros.reset()
                                if (verified || SystemClock.elapsedRealtime() >= routeDeadline)
                                    throw AudioSourceException(AudioSourceException.Reason.Disconnected, "实际输入不是指定麦克风")
                            } else {
                                verified = true
                                if (n > 0) {
                                    val now = SystemClock.elapsedRealtime()
                                    lastDataAt = now
                                    // 卡 112：读到数据不等于有声音。连续全零超过上限按故障处理，交给采集服务重开。
                                    if (zeros.accept(buffer, n, now))
                                        throw AudioSourceException(AudioSourceException.Reason.Disconnected, CaptureHealth.ZERO_PCM)
                                    for (frame in converter.accept(buffer, n)) emit(TimedEvent(AudioEvent.Frame(frame), clock.nextFrame(frame.size)))
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
            return yielded
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
                // MODE_NORMAL 释放本进程的模式请求；不能恢复读到的全局模式，
                // 否则前一轮残留的通信模式会被重新申请，回放持续走听筒。
                if (ownsCommunication) runCatching { manager.mode = AudioManager.MODE_NORMAL }
                signals.close()
            }
        }
    }

    /** 最近一次报给采集服务的让麦原因；每次 events() 都新建 RoutedRecording，所以按实例记即可 */
    private var reported: String? = null
    private suspend fun FlowCollector<TimedEvent>.report(next: String?) {
        if (next != reported) { reported = next; emit(TimedEvent(if (next == null) AudioEvent.Available else AudioEvent.Busy(next))) }
    }

    @androidx.annotation.RequiresPermission(Manifest.permission.RECORD_AUDIO)
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
                    .setBufferSizeInBytes(maxOf(minimum, rate * count * 2)).build()
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
    /** 卡 112：有别的应用在录音（不含本应用自己的录音和系统唤醒词） */
    @Volatile var othersRecording = false
        private set
    private var record: AudioRecord? = null
    private var checkedAt = 0L
    private val handler = Handler(Looper.getMainLooper())
    private val recordings = object : AudioManager.AudioRecordingCallback() {
        override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>) {
            val id = record?.audioSessionId ?: return
            configs.firstOrNull { it.clientAudioSessionId == id }?.let { silenced = it.isClientSilenced }
            othersRecording = CaptureHealth.others(configs)
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
        // 卡 112：查不到配置（null）不等于没被静音，保留回调给的值，免得刚被占就误报“已恢复”
        opened.activeRecordingConfiguration?.let { silenced = it.isClientSilenced }
        routed = opened.routedDevice
        othersRecording = CaptureHealth.othersRecording(manager)
    }
    override fun close() {
        manager.unregisterAudioRecordingCallback(recordings)
        record?.removeOnRoutingChangedListener(routing)
    }
}
