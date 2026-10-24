package app.resound.transcribe

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.nio.ByteOrder
import kotlin.coroutines.coroutineContext

/** OGG 保持原始页；AAC 回退在本地解码、混单声道并重采样至 PCM16/16k。 */
class AsrAudioReader(private val cacheDir: File) {
    data class Prepared(val file: File, val format: String, val temporary: Boolean) : AutoCloseable {
        override fun close() { if (temporary) file.delete() }
    }
    suspend fun prepare(audio: AsrAudio): Prepared = withContext(Dispatchers.IO) {
        if (!audio.file.isFile || audio.file.length() == 0L) throw AsrException(AsrError.Audio)
        when (audio.format) {
            "ogg", "pcm" -> Prepared(audio.file, audio.format, false)
            "m4a" -> {
                val target = File.createTempFile("asr-", ".pcm", cacheDir)
                try { decode(audio.file, target); Prepared(target, "pcm", true) }
                catch (e: Exception) { target.delete(); if (e is kotlinx.coroutines.CancellationException) throw e; throw AsrException(AsrError.Audio) }
            }
            else -> throw AsrException(AsrError.Audio)
        }
    }
    /** 声纹专用：任何支持的压缩录音都在本机解码，临时 PCM 用完立即删。 */
    suspend fun readPcm(source: File): ShortArray = withContext(Dispatchers.IO) {
        require(source.isFile && source.length() > 0) { "录音已过期或不存在" }
        val target = File.createTempFile("asr-voiceprint-", ".pcm", cacheDir)
        try {
            decode(source, target)
            val bytes = target.readBytes()
            val buffer = java.nio.ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            ShortArray(buffer.remaining()).also { buffer.get(it) }
        } finally { target.delete() }
    }
    private suspend fun decode(source: File, target: File) {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(source.path)
            val track = (0 until extractor.trackCount).first { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            format.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            val decoder = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            codec = decoder
            decoder.configure(format, null, null, 0); decoder.start()
            val info = MediaCodec.BufferInfo()
            var inputDone = false; var outputDone = false
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var resampler = PcmResampler(rate)
            var progress = android.os.SystemClock.elapsedRealtime()
            target.outputStream().buffered().use { output ->
                while (!outputDone) {
                    coroutineContext.ensureActive()
                    check(android.os.SystemClock.elapsedRealtime() - progress < 15_000)
                    if (!inputDone) {
                        val index = decoder.dequeueInputBuffer(10_000)
                        if (index >= 0) {
                            val buffer = decoder.getInputBuffer(index)!!
                            buffer.clear()
                            val size = extractor.readSampleData(buffer, 0)
                            if (size < 0) { decoder.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true }
                            else { decoder.queueInputBuffer(index, 0, size, extractor.sampleTime, 0); extractor.advance() }
                            progress = android.os.SystemClock.elapsedRealtime()
                        }
                    }
                    when (val index = decoder.dequeueOutputBuffer(info, 10_000)) {
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val decoded = decoder.outputFormat
                            check(!decoded.containsKey(MediaFormat.KEY_PCM_ENCODING) || decoded.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_16BIT)
                            channels = decoded.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                            rate = decoded.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            check(channels > 0 && rate > 0)
                            resampler = PcmResampler(rate)
                            progress = android.os.SystemClock.elapsedRealtime()
                        }
                        in 0..Int.MAX_VALUE -> {
                            try {
                                if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                                    val data = decoder.getOutputBuffer(index)!!.order(ByteOrder.LITTLE_ENDIAN)
                                    data.position(info.offset); data.limit(info.offset + info.size)
                                    check(info.size % (channels * 2) == 0)
                                    while (data.remaining() >= channels * 2) {
                                        var sum = 0L
                                        repeat(channels) { sum += data.short }
                                        resampler.accept((sum / channels).toInt()) { sample ->
                                            output.write(sample and 255); output.write((sample shr 8) and 255)
                                        }
                                    }
                                }
                                outputDone = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                            } finally { decoder.releaseOutputBuffer(index, false) }
                            progress = android.os.SystemClock.elapsedRealtime()
                        }
                    }
                }
                resampler.finish { sample ->
                    output.write(sample and 255); output.write((sample shr 8) and 255)
                }
            }
            check(target.length() > 0)
        } finally {
            codec?.let { runCatching { it.stop() }; it.release() }
            extractor.release()
        }
    }
    companion object {
        suspend fun chunks(prepared: Prepared, send: suspend (ByteArray, Boolean) -> Unit) {
            prepared.file.inputStream().buffered().use { input ->
                var current = chunk(input, prepared.format) ?: throw AsrException(AsrError.Audio)
                while (true) {
                    val next = chunk(input, prepared.format)
                    send(current, next == null)
                    if (next == null) break
                    current = next
                }
            }
        }
        private fun read(input: InputStream, count: Int): ByteArray {
            val data = ByteArray(count)
            var offset = 0
            while (offset < count) {
                val n = input.read(data, offset, count - offset)
                if (n < 0) throw AsrException(AsrError.Audio)
                offset += n
            }
            return data
        }
        private fun chunk(input: InputStream, format: String): ByteArray? {
            val first = input.read()
            if (first < 0) return null
            if (format != "ogg") {
                val bytes = ByteArray(6400); bytes[0] = first.toByte()
                var size = 1
                while (size < bytes.size) {
                    val n = input.read(bytes, size, bytes.size - size)
                    if (n < 0) break
                    size += n
                }
                if (size % 2 != 0) throw AsrException(AsrError.Audio)
                return bytes.copyOf(size)
            }
            val header = byteArrayOf(first.toByte()) + read(input, 26)
            if (header.copyOfRange(0, 4).toString(Charsets.US_ASCII) != "OggS" || header[4].toInt() != 0) throw AsrException(AsrError.Audio)
            val laces = read(input, header[26].toInt() and 255)
            return header + laces + read(input, laces.sumOf { it.toInt() and 255 })
        }
    }
}

/** 以全局样本位置线性插值，跨解码缓冲区保留相位。 */
internal class PcmResampler(private val sourceRate: Int) {
    private var index = 0L
    private var next = 0L
    private var previous = 0
    fun accept(sample: Int, output: (Int) -> Unit) {
        while (next * sourceRate <= index * 16000L) {
            val fraction = (next * sourceRate - (index - 1) * 16000L) / 16000.0
            output(if (index == 0L) sample else (previous + (sample - previous) * fraction).toInt().coerceIn(-32768, 32767))
            next++
        }
        previous = sample; index++
    }
    fun finish(output: (Int) -> Unit) {
        while (next * sourceRate < index * 16000L) { output(previous); next++ }
    }
}
