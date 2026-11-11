package app.resound.devices.plugins.d3200

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaFormat
import app.resound.audio.PcmInputConverter
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Android's Opus decoder outputs native PCM; reuse TaoSheng's continuous resampler. */
internal class D3200Opus : AutoCloseable {
    private var codec: MediaCodec? = null
    private var channels = 0
    private var rate = 48000
    private var outputChannels = 0
    private var converter: PcmInputConverter? = null
    private var pts = 0L
    private val info = MediaCodec.BufferInfo()
    fun decode(packet: ByteArray): List<ShortArray> {
        require(packet.isNotEmpty())
        val count = if (packet[0].toInt() and 4 != 0) 2 else 1
        if (codec == null) {
            channels = count; outputChannels = count
            val header = ByteBuffer.allocate(19).order(ByteOrder.LITTLE_ENDIAN).apply {
                put("OpusHead".toByteArray(Charsets.US_ASCII)); put(1); put(count.toByte()); putShort(0)
                putInt(48000); putShort(0); put(0)
            }.array()
            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, 48000, count).apply {
                setByteBuffer("csd-0", ByteBuffer.wrap(header))
                setByteBuffer("csd-1", ByteBuffer.wrap(ByteArray(8)))
                setByteBuffer("csd-2", ByteBuffer.wrap(ByteArray(8)))
                setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            }
            val created = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS)
            codec = created
            created.configure(format, null, null, 0); created.start()
            converter = PcmInputConverter(48000, count)
        }
        require(count == channels) { "录音豆声道发生变化，请重新连接" }
        val opened = requireNotNull(codec)
        val result = mutableListOf<ShortArray>()
        fun drain(wait: Long) {
            var timeout = wait
            while (true) {
                val index = opened.dequeueOutputBuffer(info, timeout); timeout = 0
                when {
                    index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val f = opened.outputFormat
                        rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE); outputChannels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        require(!f.containsKey(MediaFormat.KEY_PCM_ENCODING) || f.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_16BIT)
                        converter = PcmInputConverter(rate, outputChannels)
                    }
                    index >= 0 -> try {
                        val b = requireNotNull(opened.getOutputBuffer(index)).order(ByteOrder.LITTLE_ENDIAN)
                        b.position(info.offset); b.limit(info.offset + info.size)
                        result += requireNotNull(converter).accept(ShortArray(info.size / 2) { b.short }, info.size / 2)
                    } finally { opened.releaseOutputBuffer(index, false) }
                    else -> return
                }
            }
        }
        drain(0)
        val index = opened.dequeueInputBuffer(100_000)
        require(index >= 0) { "Opus 解码器没有及时接收音频" }
        requireNotNull(opened.getInputBuffer(index)).apply { clear(); put(packet) }
        opened.queueInputBuffer(index, 0, packet.size, pts, 0); pts += 20_000
        drain(10_000)
        return result
    }
    override fun close() { codec?.let { runCatching { it.stop() }; it.release() }; codec = null }
}
