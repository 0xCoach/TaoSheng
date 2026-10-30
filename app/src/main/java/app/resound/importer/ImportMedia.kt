package app.resound.importer

import android.content.Context
import android.media.*
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.nio.ByteOrder
import java.time.*
import java.time.format.DateTimeFormatter

internal class ImportFailure(message: String) : Exception(message)
internal data class SharedAudio(val name: String, val modified: Long?, val source: String = "")
internal data class AudioMetadata(val duration: Long, val date: String?, val channels: Int = 1)

internal fun suggestedStart(date: String?, modified: Long?, duration: Long): Long? {
    val recorded = date?.let { value ->
        runCatching { Instant.parse(value).toEpochMilli() }.getOrNull()
            ?: runCatching { OffsetDateTime.parse(value).toInstant().toEpochMilli() }.getOrNull()
            ?: listOf("yyyyMMdd'T'HHmmss.SSSX", "yyyyMMdd'T'HHmmssX", "yyyy-MM-dd HH:mm:ss").firstNotNullOfOrNull { pattern ->
                runCatching {
                    val parsed = DateTimeFormatter.ofPattern(pattern).parse(value)
                    runCatching { Instant.from(parsed) }.getOrElse { LocalDateTime.from(parsed).atZone(ZoneId.systemDefault()).toInstant() }.toEpochMilli()
                }.getOrNull()
            }
    }
    return recorded ?: modified?.takeIf { it > 0 }?.minus(duration)
}

internal class ImportMedia(private val context: Context) {
    fun shared(uri: Uri): SharedAudio {
        var name = "导入录音"
        var modified: Long? = null
        // 不强制 projection：部分分享 provider 不接受 last_modified 列。
        runCatching { context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let {
                    name = cursor.getString(it)?.replace(Regex("[\\r\\n/\\\\]"), "_")?.take(160)?.ifBlank { "导入录音" } ?: name
                }
                cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED).takeIf { it >= 0 }?.let {
                    if (!cursor.isNull(it)) modified = cursor.getLong(it)
                }
                if (modified == null) cursor.getColumnIndex("date_modified").takeIf { it >= 0 }?.let {
                    if (!cursor.isNull(it)) modified = cursor.getLong(it) * 1000 // MediaStore 是秒。
                }
            }
        } }
        return SharedAudio(name, modified, Uri.decode(uri.toString()))
    }
    fun inspect(file: File): AudioMetadata {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.path)
            val format = (0 until extractor.trackCount).map(extractor::getTrackFormat)
                .firstOrNull { it.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
                ?: throw ImportFailure("这个文件里没找到声音")
            var duration = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) / 1000 else 0
            var date: String? = null
            val retriever = MediaMetadataRetriever()
            try {
                runCatching {
                    retriever.setDataSource(file.path)
                    date = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE)
                    if (duration <= 0) duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
                }
            } finally { retriever.release() }
            if (duration <= 0) throw ImportFailure("暂不支持这种格式")
            return AudioMetadata(duration, date, format.getInteger(MediaFormat.KEY_CHANNEL_COUNT))
        } catch (e: ImportFailure) { throw e }
        catch (_: Exception) { throw ImportFailure("暂不支持这种格式") }
        finally { extractor.release() }
    }
}

/** 跨 buffer 保持相位和最后一个样本；按声道平均，线性插值到 16 kHz。 */
internal class StreamingResampler(private val rate: Int, private val channels: Int, private val selectedChannel: Int? = null) {
    init { require(rate > 0 && channels > 0) }
    private var inputIndex = -1L
    private var outputIndex = 0L
    private var previous = 0.0
    private var sum = 0.0
    private var channel = 0
    fun accept(samples: ShortArray): ShortArray {
        val output = ArrayList<Short>()
        samples.forEach { sample ->
            if (selectedChannel == null || channel == selectedChannel) sum += sample
            if (++channel == channels) {
                val mono = if (selectedChannel == null) sum / channels else sum
                inputIndex++
                while (outputIndex * rate.toLong() <= inputIndex * 16000L) {
                    val position = outputIndex * rate.toDouble() / 16000
                    val fraction = position - (inputIndex - 1)
                    output += (if (inputIndex == 0L) mono else previous + (mono - previous) * fraction)
                        .toInt().coerceIn(-32768, 32767).toShort()
                    outputIndex++
                }
                previous = mono; sum = 0.0; channel = 0
            }
        }
        return output.toShortArray()
    }
    fun finish(): ShortArray {
        val output = ArrayList<Short>()
        while (outputIndex * rate.toLong() < (inputIndex + 1) * 16000L) {
            output += previous.toInt().coerceIn(-32768, 32767).toShort(); outputIndex++
        }
        return output.toShortArray()
    }
}

internal class ImportDecoder {
    suspend fun decode(file: File, channel: Int? = null, maxMillis: Long? = null, observe: (ShortArray, Int) -> Unit = { _, _ -> }, consume: suspend (ShortArray) -> Unit) {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(file.path)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw ImportFailure("这个文件里没找到声音")
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val decoder = MediaCodec.createDecoderByType(requireNotNull(format.getString(MediaFormat.KEY_MIME)))
            codec = decoder
            decoder.configure(format, null, null, 0); decoder.start()
            var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var resampler = StreamingResampler(rate, channels, channel)
            var encoding = AudioFormat.ENCODING_PCM_16BIT
            var decodedFrames = 0L
            var inputDone = false
            var outputDone = false
            val info = MediaCodec.BufferInfo()
            var lastOutput = android.os.SystemClock.elapsedRealtime()
            while (!outputDone) {
                currentCoroutineContext().ensureActive()
                if (android.os.SystemClock.elapsedRealtime() - lastOutput > 30_000) throw ImportFailure("音频解码超时，请换个格式重试")
                if (!inputDone) {
                    val index = decoder.dequeueInputBuffer(10_000)
                    if (index >= 0) {
                        val buffer = requireNotNull(decoder.getInputBuffer(index)).apply { clear() }
                        val count = extractor.readSampleData(buffer, 0)
                        if (count < 0) {
                            decoder.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true
                        } else {
                            decoder.queueInputBuffer(index, 0, count, extractor.sampleTime, 0); extractor.advance()
                        }
                    }
                }
                val index = decoder.dequeueOutputBuffer(info, 10_000)
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val out = decoder.outputFormat
                    val nextRate = out.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    val nextChannels = out.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    if (nextRate != rate || nextChannels != channels) {
                        consume(resampler.finish()); rate = nextRate; channels = nextChannels
                        resampler = StreamingResampler(rate, channels, channel)
                    }
                    encoding = if (out.containsKey(MediaFormat.KEY_PCM_ENCODING)) out.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT
                    if (encoding !in setOf(AudioFormat.ENCODING_PCM_16BIT, AudioFormat.ENCODING_PCM_FLOAT)) throw ImportFailure("暂不支持这种格式")
                } else if (index >= 0) {
                    lastOutput = android.os.SystemClock.elapsedRealtime()
                    try {
                        if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                            val out = requireNotNull(decoder.getOutputBuffer(index)).order(ByteOrder.LITTLE_ENDIAN)
                            out.position(info.offset); out.limit(info.offset + info.size)
                            val samples = if (encoding == AudioFormat.ENCODING_PCM_FLOAT) ShortArray(info.size / 4) {
                                (out.float.coerceIn(-1f, 1f) * 32767).toInt().toShort()
                            } else ShortArray(info.size / 2) { out.short }
                            require(channel == null || channel in 0 until channels) { "声道不存在" }
                            observe(samples, channels)
                            decodedFrames += samples.size / channels
                            consume(resampler.accept(samples))
                        }
                        outputDone = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0 || (maxMillis != null && decodedFrames * 1000 / rate >= maxMillis)
                    } finally { decoder.releaseOutputBuffer(index, false); lastOutput = android.os.SystemClock.elapsedRealtime() }
                }
            }
            consume(resampler.finish())
        } finally { codec?.let { runCatching { it.stop() }; it.release() }; extractor.release() }
    }
}
