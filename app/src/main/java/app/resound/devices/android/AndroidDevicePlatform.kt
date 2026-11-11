package app.resound.devices.android

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import android.os.SystemClock
import app.resound.devices.AndroidDeviceLink
import app.resound.devices.Codec
import app.resound.devices.ManagedDevices
import app.resound.devices.core.AndroidOpusDecoder
import app.resound.devices.driver.*
import app.resound.devices.hub.*
import app.resound.devices.replay.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.TimeZone

class AndroidClockPort : ClockPort {
    override fun nowEpochMillis() = System.currentTimeMillis()
    override fun monotonicMillis() = SystemClock.elapsedRealtime()
    override fun zoneOffsetMinutes() = TimeZone.getDefault().getOffset(nowEpochMillis()) / 60_000
}

/** 平台只包装原 MediaCodec 解码器；按实际 PCM 格式适配请求采样率，保留交错声道。 */
internal class AndroidDriverOpusDecoder(private val sampleRate: Int, private val channels: Int) : OpusDecoder {
    init { if (sampleRate !in setOf(8000, 12000, 16000, 24000, 48000) || channels !in 1..2) throw DeviceException(ErrorCode.Unsupported) }
    private val decoder = AndroidOpusDecoder(Codec.Opus20, channels)
    private var phase = 0
    private val sums = LongArray(channels)
    override fun decode(packet: ByteArray): ShortArray {
        val result = mutableListOf<Short>()
        decoder.decode(packet).forEach { pcm ->
            if (pcm.channels != channels || pcm.sampleRate % sampleRate != 0 || pcm.samples.size % channels != 0)
                throw DeviceException(ErrorCode.Unsupported)
            val divisor = pcm.sampleRate / sampleRate
            if (divisor < 1) throw DeviceException(ErrorCode.Unsupported)
            for (offset in pcm.samples.indices step channels) {
                repeat(channels) { sums[it] += pcm.samples[offset + it] }
                phase++
                if (phase == divisor) {
                    repeat(channels) { result += (sums[it] / divisor).toShort(); sums[it] = 0 }
                    phase = 0
                }
            }
        }
        return result.toShortArray()
    }
    override fun close() = decoder.close()
}

/** 由 AppGraph 保存唯一容器；卡 141 注入 DeviceHub 即可，当前只共享旧扫描入口。 */
class AndroidDevicePlatform(private val context: Context, private val scope: CoroutineScope) {
    val clock: ClockPort = AndroidClockPort()
    private val preferences = context.getSharedPreferences("device-drivers", Context.MODE_PRIVATE)
    val repository: HubRepository = AndroidHubRepository(preferences)
    private val activeCaptures = MutableStateFlow<Set<String>>(emptySet())
    private val captureFilesMutex = Mutex()
    var captureEnabled: Boolean
        get() = preferences.getBoolean("captureEnabled", false)
        set(value) { preferences.edit().putBoolean("captureEnabled", value).apply() }
    val bluetooth = AndroidBluetoothCenter(context, scope, clock)
    val extras: PlatformExtras = object : PlatformExtras { override val classicSerial: ClassicSerialPort? = null }
    val toolbox: Toolbox = object : Toolbox {
        override val crypto: CryptoPort = JcaCryptoPort()
        override fun opusDecoder(sampleRate: Int, channels: Int): OpusDecoder = AndroidDriverOpusDecoder(sampleRate, channels)
    }
    @SuppressLint("MissingPermission")
    fun connector(mtuPreference: (String) -> Int? = { null }): HubConnector = HubConnector { handle ->
        platformCall {
            val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: throw DeviceException(ErrorCode.Disconnected)
            val link = AndroidDeviceLink.connect(context, adapter.getRemoteDevice(handle), mtuPreference(handle))
            val raw = AndroidGattLink(link)
            // 开关只影响之后新建的连接；驱动编号来自中枢已经保存的选择。
            val capture = try {
                if (captureEnabled) repository.selections().firstOrNull { it.platformHandle == handle }
                    ?.let { createCapture(it.driverId) } else null
            } catch (e: CancellationException) {
                withContext(NonCancellable) { link.close() }
                throw e
            } catch (_: Exception) { null }
            val recording = capture?.let { RecordingGattLink(raw, clock, it::line) }
            recording?.maxWriteLength(true)
            recording?.maxWriteLength(false)
            val observer = recording?.let { recorder -> scope.launch(start = CoroutineStart.UNDISPATCHED) {
                try { link.disconnections.first(); recorder.recordDisconnected() }
                finally { capture.finish() }
            } }
            object : HubLink {
                override val gatt: GattLink = recording ?: raw
                override val disconnected = link.disconnections
                override suspend fun close() {
                    try { link.close() }
                    finally {
                        recording?.recordDisconnected()
                        observer?.cancel()
                        capture?.finish()
                        withContext(NonCancellable) { observer?.join(); capture?.job?.join() }
                    }
                }
            }
        }
    }

    /** 仅返回私有目录中的文件名；卡 144 用这些名称列出、删除或导出。活动录包禁止删除。 */
    suspend fun listCaptures(): List<DeviceCaptureFile> = withContext(Dispatchers.IO) {
        captureFilesMutex.withLock {
            captureDirectory().listFiles().orEmpty().filter { it.isFile && validCaptureName(it.name) }
                .map { DeviceCaptureFile(it.name, it.length(), it.lastModified(), it.name in activeCaptures.value) }
                .sortedByDescending { it.modifiedAtMillis }
        }
    }
    suspend fun deleteCapture(name: String): Boolean = withContext(Dispatchers.IO) {
        require(validCaptureName(name)) { "非法录包文件名" }
        captureFilesMutex.withLock {
            if (name in activeCaptures.value) false else File(captureDirectory(), name).delete()
        }
    }
    private fun captureDirectory() = File(context.filesDir, "device-captures")
    private fun validCaptureName(name: String) = Regex("[a-z0-9:-]{1,64}-[0-9]+\\.txt").matches(name)

    private class CaptureWriter(val queue: Channel<String>, val job: Job) {
        fun line(value: String) {
            // 队列满时停止此文件；不阻塞 GATT，也不留下静默丢包的可疑录包。
            if (queue.trySend(value).isFailure) queue.close(CaptureQueueFull())
        }
        fun finish() { queue.close() }
    }
    private class CaptureQueueFull : Exception()

    private suspend fun createCapture(driver: String): CaptureWriter? {
        var created: CaptureWriter? = null
        try {
            return withContext(Dispatchers.IO) {
                captureFilesMutex.withLock { openCapture(driver).also { created = it } }
            }
        } catch (e: CancellationException) {
            // withContext 交回结果时也可能取消，不能遗失已经启动的写盘任务。
            created?.finish()
            withContext(NonCancellable) { created?.job?.join() }
            throw e
        }
    }

    private fun openCapture(driver: String): CaptureWriter? {
        var file: File? = null
        try {
            val header = CaptureHeader(driver, clock.nowEpochMillis())
            val directory = captureDirectory()
            check(directory.isDirectory || directory.mkdirs())
            var stamp = header.recordedAt
            do { file = File(directory, "$driver-${stamp++}.txt") } while (!file!!.createNewFile())
            val target = file!!
            activeCaptures.update { it + target.name }
            val queue = Channel<String>(256)
            queue.trySend(header.line())
            val job = scope.launch(Dispatchers.IO) {
                try {
                    target.outputStream().buffered().use { output ->
                        val sink = LimitedCaptureSink(output::write)
                        try {
                            for (line in queue) {
                                sink.line(line)
                                output.flush()
                                if (sink.stopped) { queue.cancel(); break }
                            }
                        } catch (_: CaptureQueueFull) { sink.line("# capture-stopped queue-full") }
                        catch (e: CancellationException) {
                            sink.line("# capture-stopped cancelled")
                            throw e
                        }
                    }
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { /* 文件不可写不能影响收音；保留已落盘的部分。 */ }
                finally { queue.cancel(); activeCaptures.update { it - target.name } }
            }
            job.invokeOnCompletion { activeCaptures.update { it - target.name } }
            return CaptureWriter(queue, job)
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { file?.let { activeCaptures.update { names -> names - it.name } }; return null }
    }
    companion object {
        fun legacyIdentity(address: String) = DeviceIdentity(DeviceId("android:" + ManagedDevices.deviceKey(address)), IdScope.PlatformLocal)
    }
}

data class DeviceCaptureFile(val name: String, val bytes: Long, val modifiedAtMillis: Long, val active: Boolean)
