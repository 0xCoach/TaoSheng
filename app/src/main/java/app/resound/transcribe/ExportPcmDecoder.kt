package app.resound.transcribe

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import kotlinx.coroutines.ensureActive
import java.io.File
import java.nio.ByteOrder
import kotlin.coroutines.coroutineContext

/** 导出专用流式解码；每块最多约 8 KiB，PCM 不落盘。 */
internal object ExportPcmDecoder {
    suspend fun decode(source: File, emit: suspend (ByteArray) -> Unit) {
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
            var total = 0L
            val bytes = java.io.ByteArrayOutputStream(8192)
            fun writeSample(value: Int) { bytes.write(value and 255); bytes.write((value shr 8) and 255) }
            suspend fun flush() {
                if (bytes.size() > 0) {
                    val chunk = bytes.toByteArray()
                    total += chunk.size
                    emit(chunk)
                    bytes.reset()
                    // 通道背压不是解码器卡死。
                    progress = android.os.SystemClock.elapsedRealtime()
                }
            }
            run {
                while (!outputDone) {
                    coroutineContext.ensureActive()
                    check(android.os.SystemClock.elapsedRealtime() - progress < 15_000)
                    var fed = false
                    if (!inputDone) {
                        val index = decoder.dequeueInputBuffer(0)
                        if (index >= 0) {
                            val buffer = decoder.getInputBuffer(index)!!
                            buffer.clear()
                            val size = extractor.readSampleData(buffer, 0)
                            if (size < 0) { decoder.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true }
                            else { decoder.queueInputBuffer(index, 0, size, extractor.sampleTime, 0); extractor.advance() }
                            progress = android.os.SystemClock.elapsedRealtime()
                            fed = true
                        }
                    }
                    // 把现成的输出全部取走；这一轮既没喂进去也没取到时才短暂等一下，不再每块都空等
                    var timeout = if (fed) 0L else 10_000L
                    while (!outputDone) {
                        val index = decoder.dequeueOutputBuffer(info, timeout)
                        timeout = 0
                        if (index == MediaCodec.INFO_TRY_AGAIN_LATER) break
                        if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                            val decoded = decoder.outputFormat
                            check(!decoded.containsKey(MediaFormat.KEY_PCM_ENCODING) || decoded.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_16BIT)
                            channels = decoded.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                            rate = decoded.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            check(channels > 0 && rate > 0)
                            resampler = PcmResampler(rate)
                            progress = android.os.SystemClock.elapsedRealtime()
                        } else if (index >= 0) {
                            try {
                                if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                                    val data = decoder.getOutputBuffer(index)!!.order(ByteOrder.LITTLE_ENDIAN)
                                    data.position(info.offset); data.limit(info.offset + info.size)
                                    check(info.size % (channels * 2) == 0)
                                    if (channels == 1 && rate == 16000) {
                                        // 录音本来就是 16kHz 单声道：整块照搬，不逐个样本处理
                                        val raw = ByteArray(info.size).also { data.get(it) }
                                        var at = 0
                                        while (at < raw.size) {
                                            val n = minOf(8192 - bytes.size(), raw.size - at)
                                            bytes.write(raw, at, n); at += n
                                            if (bytes.size() >= 8192) flush()
                                        }
                                    } else while (data.remaining() >= channels * 2) {
                                        var sum = 0L
                                        repeat(channels) { sum += data.short }
                                        resampler.accept((sum / channels).toInt()) { sample ->
                                            writeSample(sample)
                                        }
                                        if (bytes.size() >= 8192) flush()
                                    }
                                    flush()
                                }
                                outputDone = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                            } finally { decoder.releaseOutputBuffer(index, false) }
                            progress = android.os.SystemClock.elapsedRealtime()
                        }
                    }
                }
                resampler.finish { sample ->
                    writeSample(sample)
                }
            }
            flush()
            check(total > 0) { "录音没有可解码的样本" }
        } finally {
            codec?.let { runCatching { it.stop() }; it.release() }
            extractor.release()
        }
    }
}
