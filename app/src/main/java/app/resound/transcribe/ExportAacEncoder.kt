package app.resound.transcribe

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.SystemClock
import java.io.File
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

/** 一次导出一个编码器；输入始终为 PCM16 / 16 kHz / mono，最多持有一个输入块。 */
internal interface ExportPcmSink : AutoCloseable {
    suspend fun append(pcm: File)
    suspend fun silence()
    suspend fun finish()
}

internal class ExportAacEncoder(target: File) : ExportPcmSink {
    private val codec = MediaCodec.createEncoderByType("audio/mp4a-latm")
    private var muxer: MediaMuxer? = null
    private var started = false
    private var track = -1
    private var samples = 0L
    private var lastProgress = SystemClock.elapsedRealtime()
    private val info = MediaCodec.BufferInfo()
    init {
        try {
            muxer = MediaMuxer(target.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val format = MediaFormat.createAudioFormat("audio/mp4a-latm", 16000, 1).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, 64000)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 8192)
            }
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
        } catch (e: Exception) { close(); throw e }
    }
    override suspend fun append(pcm: File) {
        pcm.inputStream().buffered().use { input ->
            val bytes = ByteArray(8192)
            while (true) {
                coroutineContext.ensureActive()
                val n = input.read(bytes)
                if (n < 0) break
                require(n % 2 == 0)
                feed(bytes, n)
            }
        }
    }
    override suspend fun silence() = feed(ByteArray(16000), 16000) // 8000 个样本 = 0.5 秒
    private suspend fun feed(bytes: ByteArray, size: Int) {
        var offset = 0
        while (offset < size) {
            alive()
            val index = codec.dequeueInputBuffer(10_000)
            if (index >= 0) {
                val buffer = codec.getInputBuffer(index)!!.apply { clear() }
                val count = minOf(buffer.remaining(), size - offset) / 2 * 2
                check(count > 0)
                buffer.put(bytes, offset, count)
                codec.queueInputBuffer(index, 0, count, samples * 1_000_000 / 16000, 0)
                samples += count / 2; offset += count
                lastProgress = SystemClock.elapsedRealtime()
            }
            drain()
        }
    }
    override suspend fun finish() {
        while (true) {
            alive()
            val index = codec.dequeueInputBuffer(10_000)
            if (index >= 0) {
                codec.queueInputBuffer(index, 0, 0, samples * 1_000_000 / 16000, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                break
            }
            drain()
        }
        while (!drain()) alive()
        check(started) { "音频编码器没有生成音轨" }
        muxer!!.stop(); started = false
    }
    private suspend fun alive() {
        coroutineContext.ensureActive()
        check(SystemClock.elapsedRealtime() - lastProgress < 15_000) { "音频编码超时" }
    }
    private fun drain(): Boolean {
        when (val index = codec.dequeueOutputBuffer(info, 10_000)) {
            MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                check(!started)
                track = muxer!!.addTrack(codec.outputFormat)
                muxer!!.start(); started = true
                lastProgress = SystemClock.elapsedRealtime()
            }
            in 0..Int.MAX_VALUE -> {
                try {
                    if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                        check(started)
                        val buffer = codec.getOutputBuffer(index)!!
                        buffer.position(info.offset); buffer.limit(info.offset + info.size)
                        muxer!!.writeSampleData(track, buffer, info)
                    }
                    lastProgress = SystemClock.elapsedRealtime()
                    return info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                } finally { codec.releaseOutputBuffer(index, false) }
            }
        }
        return false
    }
    override fun close() {
        runCatching { codec.stop() }; runCatching { codec.release() }
        if (started) runCatching { muxer?.stop() }
        runCatching { muxer?.release() }
    }
}
