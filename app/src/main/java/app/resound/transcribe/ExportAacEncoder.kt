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
    suspend fun append(pcm: ByteArray)
    suspend fun silence()
    suspend fun finish()
}

internal class ExportAacEncoder(target: File) : ExportPcmSink {
    private val codec = MediaCodec.createEncoderByType("audio/mp4a-latm")
    private var muxer: MediaMuxer? = null
    private var started = false
    private var track = -1
    private var samples = 0L
    private val watchdog = ExportCodecWatchdog()
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
    override suspend fun append(pcm: ByteArray) {
        require(pcm.size % 2 == 0)
        feed(pcm, pcm.size)
    }
    override suspend fun silence() = feed(ByteArray(16000), 16000) // 8000 个样本 = 0.5 秒
    private suspend fun feed(bytes: ByteArray, size: Int) {
        var offset = 0
        while (offset < size) {
            alive()
            val before = SystemClock.elapsedRealtime()
            val index = codec.dequeueInputBuffer(10_000)
            watchdog.waited(SystemClock.elapsedRealtime() - before)
            if (index >= 0) {
                val buffer = codec.getInputBuffer(index)!!.apply { clear() }
                val count = minOf(buffer.remaining(), size - offset) / 2 * 2
                check(count > 0)
                buffer.put(bytes, offset, count)
                codec.queueInputBuffer(index, 0, count, samples * 1_000_000 / 16000, 0)
                samples += count / 2; offset += count
            }
            drain()
        }
    }
    override suspend fun finish() {
        while (true) {
            alive()
            val before = SystemClock.elapsedRealtime()
            val index = codec.dequeueInputBuffer(10_000)
            watchdog.waited(SystemClock.elapsedRealtime() - before)
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
        watchdog.checkAlive()
    }
    private fun drain(): Boolean {
        val before = SystemClock.elapsedRealtime()
        val index = codec.dequeueOutputBuffer(info, 10_000)
        watchdog.waited(SystemClock.elapsedRealtime() - before)
        when (index) {
            MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                check(!started)
                track = muxer!!.addTrack(codec.outputFormat)
                muxer!!.start(); started = true
            }
            in 0..Int.MAX_VALUE -> {
                watchdog.output()
                try {
                    if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                        check(started)
                        val buffer = codec.getOutputBuffer(index)!!
                        buffer.position(info.offset); buffer.limit(info.offset + info.size)
                        muxer!!.writeSampleData(track, buffer, info)
                    }
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

/** 只累计 dequeue 的等待；生产者解码、通道等待、磁盘写入均不计时。输入不重置无输出计时。 */
internal class ExportCodecWatchdog {
    private var waitingMillis = 0L
    fun waited(millis: Long) { waitingMillis += millis.coerceAtLeast(0) }
    fun output() { waitingMillis = 0 }
    fun checkAlive() { check(waitingMillis < 30_000) { "音频编码超时（编码器等待 30 秒无输出）" } }
}
