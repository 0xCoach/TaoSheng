package app.resound.devices.core

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaFormat
import app.resound.devices.Codec
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** 唯一依赖安卓的解码类。放在工作线程串行调用；结束时 close，重连时重新创建或 reset。 */
class AndroidOpusDecoder(private val codec: Codec, private val channels: Int = 1) : OpusDecoder {
    init {
        require(codec == Codec.Opus10 || codec == Codec.Opus20)
        require(channels in 1..2)
    }
    private var decoder = createDecoder()
    private var outputRate = 48000
    private var outputChannels = channels
    private var timeUs = 0L
    private var ended = false
    private var closed = false
    private var outputEnded = false
    private val info = MediaCodec.BufferInfo()

    private fun createDecoder(): MediaCodec {
        val result = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS)
        try {
            val header = ByteBuffer.allocate(19).order(ByteOrder.LITTLE_ENDIAN).apply {
                put("OpusHead".toByteArray(Charsets.US_ASCII)); put(1); put(channels.toByte())
                putShort(0); putInt(48000); putShort(0); put(0)
            }.array()
            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, 48000, channels).apply {
                setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 64 * 1024)
                setByteBuffer("csd-0", ByteBuffer.wrap(header))
                setByteBuffer("csd-1", ByteBuffer.allocate(8).order(ByteOrder.nativeOrder()).putLong(0).apply { flip() })
                setByteBuffer("csd-2", ByteBuffer.allocate(8).order(ByteOrder.nativeOrder()).putLong(80_000_000).apply { flip() })
            }
            result.configure(format, null, null, 0); result.start()
            return result
        } catch (e: Exception) { result.release(); throw e }
    }

    override fun decode(packet: ByteArray): List<DecodedPcm> {
        check(!closed && !ended) { "Opus：解码器已结束" }
        require(packet.isNotEmpty() && packet.size <= 64 * 1024) { "Opus：帧为空或过大" }
        val output = mutableListOf<DecodedPcm>()
        queue(packet, false, output)
        timeUs += if (codec == Codec.Opus10) 10000 else 20000
        drain(output, 10000)
        return output
    }

    override fun finish(): List<DecodedPcm> {
        check(!closed && !ended) { "Opus：解码器已结束" }
        val output = mutableListOf<DecodedPcm>()
        queue(ByteArray(0), true, output); ended = true
        val deadline = System.nanoTime() + 2_000_000_000L
        while (!outputEnded && System.nanoTime() < deadline) drain(output, 10000)
        check(outputEnded) { "Opus：等待末帧超时" }
        return output
    }

    private fun queue(packet: ByteArray, eos: Boolean, output: MutableList<DecodedPcm>) {
        val deadline = System.nanoTime() + 1_000_000_000L
        while (System.nanoTime() < deadline) {
            val index = decoder.dequeueInputBuffer(10000)
            if (index >= 0) {
                val buffer = requireNotNull(decoder.getInputBuffer(index)) { "Opus：没有输入缓冲区" }
                buffer.clear()
                require(packet.size <= buffer.remaining()) { "Opus：输入帧超过平台缓冲区" }
                buffer.put(packet)
                decoder.queueInputBuffer(index, 0, packet.size, timeUs, if (eos) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0)
                return
            }
            drain(output, 0)
        }
        error("Opus：等待输入缓冲区超时")
    }

    private fun drain(output: MutableList<DecodedPcm>, waitUs: Long) {
        var wait = waitUs
        while (true) {
            when (val index = decoder.dequeueOutputBuffer(info, wait)) {
                MediaCodec.INFO_TRY_AGAIN_LATER -> return
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val format = decoder.outputFormat
                    outputRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    outputChannels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    check(!format.containsKey(MediaFormat.KEY_PCM_ENCODING) || format.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_16BIT) {
                        "Opus：平台未输出 PCM16"
                    }
                }
                else -> if (index >= 0) {
                    try {
                        if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                            check(info.size % 2 == 0) { "Opus：PCM16 输出字节数为奇数" }
                            val buffer = requireNotNull(decoder.getOutputBuffer(index)).duplicate().order(ByteOrder.LITTLE_ENDIAN)
                            buffer.position(info.offset); buffer.limit(info.offset + info.size)
                            val pcm = ShortArray(info.size / 2); buffer.asShortBuffer().get(pcm)
                            output += DecodedPcm(pcm, outputRate, outputChannels)
                        }
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputEnded = true
                    } finally { decoder.releaseOutputBuffer(index, false) }
                }
            }
            if (outputEnded) return
            wait = 0
        }
    }

    override fun reset() {
        check(!closed) { "Opus：解码器已关闭" }
        decoder.release()
        closed = true
        decoder = createDecoder()
        closed = false; ended = false; outputEnded = false; timeUs = 0; outputRate = 48000; outputChannels = channels
    }
    override fun close() { if (!closed) { closed = true; decoder.release() } }
}
