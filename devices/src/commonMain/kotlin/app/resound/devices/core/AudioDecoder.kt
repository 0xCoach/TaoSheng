package app.resound.devices.core

import app.resound.audio.PcmInputConverter
import app.resound.devices.Codec
import app.resound.devices.OpusPacking

/** 解码器必须报告实际输出格式；Android Opus 通常输出 48 kHz，不能套用说明卡的 16 kHz。 */
data class DecodedPcm(val samples: ShortArray, val sampleRate: Int, val channels: Int)

interface OpusDecoder : AutoCloseable {
    fun decode(packet: ByteArray): List<DecodedPcm>
    fun finish(): List<DecodedPcm> = emptyList()
    fun reset() {}
    override fun close() {}
}

/** 每条声音流独立使用。Opus 由调用方注入，纯逻辑单测无需安卓。 */
class AudioDecoder(
    private val codec: Codec,
    private val sampleRate: Int = 16000,
    private val channels: Int = 1,
    private val packing: OpusPacking = OpusPacking.One,
    private val opus: OpusDecoder? = null,
) : AutoCloseable {
    init {
        require(sampleRate in 1000..192000 && channels in 1..2) { "声音：采样率或声道数不支持" }
        require(codec !in listOf(Codec.Opus10, Codec.Opus20) || opus != null) { "声音：Opus 格式必须提供解码器" }
    }
    private var converter = PcmInputConverter(sampleRate, channels)
    private var actualRate = sampleRate
    private var actualChannels = channels
    private var lowByte: Int? = null
    private var closed = false
    private var finished = false

    /** 返回零个或多个 16 kHz、单声道、480 采样的 PCM16 帧；跨调用保留不足一帧的余量。 */
    fun accept(payload: ByteArray): List<ShortArray> {
        check(!closed && !finished) { "声音：解码器已结束" }
        if (payload.isEmpty()) return emptyList()
        return when (codec) {
            Codec.Pcm16 -> {
                val pcm = ArrayList<Short>((payload.size + 1) / 2)
                for (b in payload) {
                    val lo = lowByte
                    if (lo == null) lowByte = b.toInt() and 255
                    else { pcm += (lo or ((b.toInt() and 255) shl 8)).toShort(); lowByte = null }
                }
                convert(DecodedPcm(pcm.toShortArray(), sampleRate, channels))
            }
            Codec.Pcm8 -> convert(DecodedPcm(ShortArray(payload.size) { ((payload[it].toInt() and 255) - 128 shl 8).toShort() }, sampleRate, channels))
            Codec.Mulaw -> convert(DecodedPcm(ShortArray(payload.size) { mulaw(payload[it]) }, sampleRate, channels))
            Codec.Opus10, Codec.Opus20 -> splitOpus(payload).flatMap { opus!!.decode(it).flatMap(::convert) }
        }
    }

    /** 正常结束时取出平台解码器缓存；末尾不足 480 的 PCM 不补零、不虚构声音。 */
    fun finish(): List<ShortArray> {
        check(!closed && !finished) { "声音：解码器已结束" }
        finished = true
        return opus?.finish()?.flatMap(::convert).orEmpty()
    }

    /** 丢包或重连时清空字节、重采样和帧余量，避免跨缺口拼声音。 */
    fun reset() {
        check(!closed) { "声音：解码器已关闭" }
        opus?.reset(); converter.reset(); lowByte = null; finished = false
    }

    override fun close() { if (!closed) { closed = true; opus?.close() } }

    private fun convert(pcm: DecodedPcm): List<ShortArray> {
        require(pcm.sampleRate in 1000..192000 && pcm.channels in 1..2) { "声音：解码结果采样率或声道数不支持" }
        if (actualRate != pcm.sampleRate || actualChannels != pcm.channels) {
            converter = PcmInputConverter(pcm.sampleRate, pcm.channels)
            actualRate = pcm.sampleRate; actualChannels = pcm.channels
        }
        return converter.accept(pcm.samples, pcm.samples.size)
    }

    private fun splitOpus(payload: ByteArray): List<ByteArray> {
        if (packing == OpusPacking.One) return listOf(payload)
        val result = mutableListOf<ByteArray>()
        var offset = 0
        // 先验证完整封装，坏的末帧不能造成前半包已解码、后半包抛错。
        while (offset < payload.size) {
            val length = payload[offset++].toInt() and 255
            require(length > 0 && length <= payload.size - offset) { "声音.opusPerPacket：长度前缀为零或帧不完整（偏移 ${offset - 1}）" }
            result += payload.copyOfRange(offset, offset + length); offset += length
        }
        return result
    }

    private fun mulaw(byte: Byte): Short {
        val u = byte.toInt().inv() and 255
        val magnitude = (((u and 15) shl 3) + 0x84) shl ((u ushr 4) and 7)
        return (if (u and 0x80 != 0) 0x84 - magnitude else magnitude - 0x84).toShort()
    }
}
