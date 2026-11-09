/*
 * [INPUT]: 单个录音卡订阅及普通款 Opus 数据。
 * [OUTPUT]: AudioSource 的 16kHz / 480 样本 PCM 与可恢复等待事件。
 * [POS]: CaptureService 与 SDK 之间的适配器，取消时释放解码器并停止卡录音。
 * [PROTOCOL]: 变更时更新此头部，然后检查 CLAUDE.md
 */
package app.resound.sonicloud

import app.resound.audio.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

internal class RecorderAudioSource(
    private val recorder: SonicloudRecorder,
    override val id: String,
    override val displayName: String,
) : AudioSource {
    override fun events(): Flow<AudioEvent> = flow {
        val packets = Channel<RecorderPacket>(128)
        var decoder: RecorderOpus? = null
        var assembler = RecorderFrames()
        var converter: PcmInputConverter? = null
        var format: Pair<Int, Int>? = null
        var waiting = true
        try {
            recorder.attach(packets, id)
            for (packet in packets) when (packet) {
                is RecorderPacket.Gap -> {
                    decoder?.close(); decoder = null
                    assembler = RecorderFrames(); converter = null; format = null
                    waiting = true
                    emit(AudioEvent.Busy(packet.reason))
                }
                is RecorderPacket.Audio -> {
                    if (decoder == null) decoder = RecorderOpus()
                    val frames = mutableListOf<ShortArray>()
                    assembler.accept(packet.bytes) { opus ->
                        decoder!!.accept(opus) { pcm ->
                            val current = pcm.rate to pcm.channels
                            check(format == null || format == current) { "实时音频格式中途改变" }
                            format = current
                            if (converter == null) converter = PcmInputConverter(pcm.rate, pcm.channels)
                            frames.addAll(converter!!.accept(pcm.samples, pcm.samples.size))
                        }
                    }
                    if (frames.isNotEmpty() && waiting) { emit(AudioEvent.Available); waiting = false }
                    for (frame in frames) emit(AudioEvent.Frame(frame))
                }
            }
            error("录音卡音频订阅已结束")
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            withContext(NonCancellable) { recorder.detach(packets) }
            emit(AudioEvent.Busy(e.message ?: "录音卡音频处理失败，请停止后重试"))
            awaitCancellation()
        } finally {
            try { decoder?.close() }
            finally { withContext(NonCancellable) { recorder.detach(packets) } }
        }
    }.flowOn(Dispatchers.IO)
}
