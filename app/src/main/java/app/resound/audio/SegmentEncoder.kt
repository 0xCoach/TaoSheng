package app.resound.audio

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import app.resound.capture.CaptureTuning
import java.io.File
import java.nio.ByteOrder

/** 离线流式编码暂存 PCM，Opus/OGG 的完整配置或写入失败均回退 AAC/M4A。 */
object SegmentEncoder {
    fun encode(pcm: File, base: File): File {
        var opusError: Exception? = null
        for ((mime, ext, container) in listOf(
            Triple(MediaFormat.MIMETYPE_AUDIO_OPUS, "ogg", MediaMuxer.OutputFormat.MUXER_OUTPUT_OGG),
            Triple(MediaFormat.MIMETYPE_AUDIO_AAC, "m4a", MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4))) {
            val output = File(base.path + ".$ext")
            val partial = File(output.path + ".part")
            try {
                encodeOne(pcm, partial, mime, container)
                check(partial.length() > 0 && partial.renameTo(output)) { "编码文件落盘失败" }
                return output
            } catch (e: Exception) {
                partial.delete()
                if (opusError == null) opusError = e else { e.addSuppressed(opusError); throw e }
            }
        }
        error("无可用编码器")
    }
    private fun encodeOne(pcm: File, target: File, mime: String, container: Int) {
        val codec = MediaCodec.createEncoderByType(mime)
        var muxer: MediaMuxer? = null
        var started = false
        try {
            val format = MediaFormat.createAudioFormat(mime, 16_000, 1).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, CaptureTuning.BIT_RATE)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 8192)
                if (mime == MediaFormat.MIMETYPE_AUDIO_AAC) setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            }
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            muxer = MediaMuxer(target.path, container)
            val info = MediaCodec.BufferInfo()
            var track = -1; var eosIn = false; var eosOut = false; var samples = 0L
            var lastProgress = android.os.SystemClock.elapsedRealtime()
            pcm.inputStream().buffered().use { input ->
                val bytes = ByteArray(8192)
                while (!eosOut) {
                    check(android.os.SystemClock.elapsedRealtime() - lastProgress < 15_000) { "编码器无进展" }
                    if (!eosIn) {
                        val index = codec.dequeueInputBuffer(10_000)
                        if (index >= 0) {
                            val buffer = codec.getInputBuffer(index)!!
                            buffer.clear()
                            buffer.order(ByteOrder.LITTLE_ENDIAN)
                            val n = input.read(bytes, 0, minOf(bytes.size, buffer.remaining()) and -2)
                            if (n < 0) { codec.queueInputBuffer(index, 0, 0, samples * 1_000_000 / 16_000, MediaCodec.BUFFER_FLAG_END_OF_STREAM); eosIn = true }
                            else { buffer.put(bytes, 0, n); codec.queueInputBuffer(index, 0, n, samples * 1_000_000 / 16_000, 0); samples += n / 2 }
                            lastProgress = android.os.SystemClock.elapsedRealtime()
                        }
                    }
                    val index = codec.dequeueOutputBuffer(info, 10_000)
                    if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        check(!started)
                        track = muxer.addTrack(codec.outputFormat); muxer.start(); started = true
                        lastProgress = android.os.SystemClock.elapsedRealtime()
                    } else if (index >= 0) {
                        try {
                            if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                                check(started)
                                val data = codec.getOutputBuffer(index)!!
                                data.position(info.offset); data.limit(info.offset + info.size)
                                muxer.writeSampleData(track, data, info)
                            }
                            eosOut = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        } finally { codec.releaseOutputBuffer(index, false) }
                        lastProgress = android.os.SystemClock.elapsedRealtime()
                    }
                }
            }
            check(started) { "编码器未输出格式" }
            muxer.stop(); started = false
        } finally {
            runCatching { codec.stop() }; codec.release()
            if (started) runCatching { muxer?.stop() }
            muxer?.release()
        }
    }
}
