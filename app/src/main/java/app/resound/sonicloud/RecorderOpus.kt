/*
 * [INPUT]: 普通款 A 的 40 字节 Opus 帧与 Android MediaCodec。
 * [OUTPUT]: 连续 PCM 块及可被现有导入器读取的 WAV。
 * [POS]: 实时与文件共用的解码边界；蓝牙分包不能当作编码帧边界。
 * [PROTOCOL]: 变更时更新此头部，然后检查 CLAUDE.md
 */
package app.resound.sonicloud

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.SystemClock
import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal class RecorderFrames {
    private val remainder = ByteArray(40)
    private var count = 0
    fun accept(bytes: ByteArray, emit: (ByteArray) -> Unit) {
        for (byte in bytes) {
            remainder[count++] = byte
            if (count == 40) { emit(remainder.copyOf()); count = 0 }
        }
    }
    fun finish() { require(count == 0) { "录音卡音频不完整，末尾不足一帧" } }
}

internal data class RecorderPcm(val samples: ShortArray, val rate: Int, val channels: Int)

internal class RecorderOpus : Closeable {
    private val codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS)
    private val info = MediaCodec.BufferInfo()
    private var frames = 0L
    private var rate = 48000
    private var channels = 1
    private var ended = false
    init {
        try {
            val header = ByteBuffer.allocate(19).order(ByteOrder.LITTLE_ENDIAN)
                .put("OpusHead".toByteArray(Charsets.US_ASCII)).put(1).put(1).putShort(0)
                .putInt(16000).putShort(0).put(0).array()
            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, 16000, 1).apply {
                setByteBuffer("csd-0", ByteBuffer.wrap(header))
                setByteBuffer("csd-1", ByteBuffer.allocate(8).order(ByteOrder.nativeOrder()).putLong(0).apply { flip() })
                setByteBuffer("csd-2", ByteBuffer.allocate(8).order(ByteOrder.nativeOrder()).putLong(80_000_000).apply { flip() })
                setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            }
            codec.configure(format, null, null, 0)
            codec.start()
        } catch (e: Exception) { codec.release(); throw e }
    }
    fun accept(packet: ByteArray, emit: (RecorderPcm) -> Unit) {
        require(packet.size == 40)
        queue(packet, 0, emit)
        drain(0, emit)
    }
    private fun queue(bytes: ByteArray, flags: Int, emit: (RecorderPcm) -> Unit) {
        val deadline = SystemClock.elapsedRealtime() + 5000
        while (true) {
            val index = codec.dequeueInputBuffer(10_000)
            if (index >= 0) {
                codec.getInputBuffer(index)!!.apply { clear(); put(bytes) }
                codec.queueInputBuffer(index, 0, bytes.size, frames * 20_000, flags)
                if (bytes.isNotEmpty()) frames++
                return
            }
            drain(0, emit)
            check(SystemClock.elapsedRealtime() < deadline) { "Opus 解码器输入超时" }
        }
    }
    private fun drain(wait: Long, emit: (RecorderPcm) -> Unit) {
        while (true) {
            when (val index = codec.dequeueOutputBuffer(info, wait)) {
                MediaCodec.INFO_TRY_AGAIN_LATER -> return
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val format = codec.outputFormat
                    rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    require(channels in 1..2 && rate > 0)
                    require(!format.containsKey(MediaFormat.KEY_PCM_ENCODING) ||
                        format.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_16BIT) { "不支持的 Opus PCM 格式" }
                }
                else -> if (index >= 0) {
                    try {
                        if (info.size > 0) {
                            val buffer = codec.getOutputBuffer(index)!!.duplicate().order(ByteOrder.LITTLE_ENDIAN)
                            buffer.position(info.offset); buffer.limit(info.offset + info.size)
                            val samples = ShortArray(info.size / 2)
                            buffer.asShortBuffer().get(samples)
                            emit(RecorderPcm(samples, rate, channels))
                        }
                        ended = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    } finally { codec.releaseOutputBuffer(index, false) }
                    if (ended) return
                }
            }
        }
    }
    fun finish(emit: (RecorderPcm) -> Unit) {
        queue(byteArrayOf(), MediaCodec.BUFFER_FLAG_END_OF_STREAM, emit)
        val deadline = SystemClock.elapsedRealtime() + 5000
        while (!ended) {
            drain(10_000, emit)
            check(ended || SystemClock.elapsedRealtime() < deadline) { "Opus 解码器结束超时" }
        }
    }
    override fun close() { try { codec.stop() } finally { codec.release() } }
}

internal suspend fun recorderWav(raw: File, wav: File) {
    val context = currentCoroutineContext()
    val frames = RecorderFrames()
    var rate = 0
    var channels = 0
    RandomAccessFile(wav, "rw").use { out ->
        out.setLength(0); out.write(ByteArray(44))
        val emit: (RecorderPcm) -> Unit = { pcm ->
            context.ensureActive()
            if (rate == 0) { rate = pcm.rate; channels = pcm.channels }
            check(rate == pcm.rate && channels == pcm.channels) { "音频格式中途改变" }
            val bytes = ByteBuffer.allocate(pcm.samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
            bytes.asShortBuffer().put(pcm.samples)
            out.write(bytes.array())
        }
        RecorderOpus().use { decoder ->
            raw.inputStream().use { input ->
                val bytes = ByteArray(8192)
                while (true) {
                    context.ensureActive()
                    val n = input.read(bytes)
                    if (n < 0) break
                    frames.accept(bytes.copyOf(n)) { decoder.accept(it, emit) }
                }
            }
            frames.finish()
            decoder.finish(emit)
        }
        val size = out.length() - 44
        require(size > 0 && size <= Int.MAX_VALUE - 36) { "录音为空或超过 WAV 长度限制" }
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            .put("RIFF".toByteArray()).putInt(size.toInt() + 36).put("WAVEfmt ".toByteArray())
            .putInt(16).putShort(1).putShort(channels.toShort()).putInt(rate)
            .putInt(rate * channels * 2).putShort((channels * 2).toShort()).putShort(16)
            .put("data".toByteArray()).putInt(size.toInt())
        out.seek(0); out.write(header.array()); out.fd.sync()
    }
}
