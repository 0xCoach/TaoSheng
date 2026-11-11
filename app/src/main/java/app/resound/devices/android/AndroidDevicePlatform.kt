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
import kotlinx.coroutines.CoroutineScope
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
class AndroidDevicePlatform(private val context: Context, scope: CoroutineScope) {
    val clock: ClockPort = AndroidClockPort()
    val repository: HubRepository = AndroidHubRepository(context.getSharedPreferences("device-drivers", Context.MODE_PRIVATE))
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
            object : HubLink {
                override val gatt = AndroidGattLink(link)
                override val disconnected = link.disconnections
                override suspend fun close() = link.close()
            }
        }
    }
    companion object {
        fun legacyIdentity(address: String) = DeviceIdentity(DeviceId("android:" + ManagedDevices.deviceKey(address)), IdScope.PlatformLocal)
    }
}
