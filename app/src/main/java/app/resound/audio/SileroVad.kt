package app.resound.audio

import android.content.Context
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import app.resound.capture.CaptureTuning
import java.nio.FloatBuffer
import java.nio.LongBuffer
import kotlin.math.sqrt

/** 官方 v6.2 流式协议：512 新样本 + 64 上下文，state=[2,1,128]。 */
class SileroVad(context: Context) : AutoCloseable {
    private val env = OrtEnvironment.getEnvironment()
    private val session = OrtSession.SessionOptions().use { options ->
        options.setIntraOpNumThreads(1); options.setInterOpNumThreads(1)
        env.createSession(context.assets.open("silero_vad.onnx").use { it.readBytes() }, options)
    }
    private var state = FloatArray(256)
    private var history = FloatArray(64)
    private val pending = ShortArray(512)
    private var size = 0

    fun accept(frame: ShortArray, consume: (ShortArray, Boolean, Float) -> Unit) {
        var offset = 0
        while (offset < frame.size) {
            val count = minOf(512 - size, frame.size - offset)
            frame.copyInto(pending, size, offset, offset + count)
            offset += count; size += count
            if (size == 512) {
                val pcm = pending.copyOf()
                val rms = sqrt(pcm.sumOf { val n = it / 32768.0; n * n } / pcm.size).toFloat()
                val input = FloatArray(576)
                history.copyInto(input)
                pcm.forEachIndexed { i, value -> input[64 + i] = value / 32768f }
                val probability = if (rms < CaptureTuning.RMS_GATE) {
                    // 被门限跳过的时间不能留下陈旧的循环网络状态。
                    state.fill(0f)
                    0f
                } else infer(input)
                history = input.copyOfRange(512, 576)
                consume(pcm, probability >= CaptureTuning.SPEECH_THRESHOLD, rms.coerceIn(0f, 1f))
                size = 0
            }
        }
    }

    /** 离线文件尾部补零只用于 VAD 推理，写出的仍是实际样本，避免丢尾音或延长时间。 */
    fun finish(consume: (ShortArray, Boolean, Float) -> Unit) {
        if (size == 0) return
        val actual = size
        accept(ShortArray(512 - size)) { frame, speech, rms -> consume(frame.copyOf(actual), speech, rms) }
    }

    private fun infer(input: FloatArray): Float {
        OnnxTensor.createTensor(env, FloatBuffer.wrap(input), longArrayOf(1, 576)).use { x ->
            OnnxTensor.createTensor(env, FloatBuffer.wrap(state), longArrayOf(2, 1, 128)).use { h ->
                OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(16000)), longArrayOf()).use { sr ->
                    session.run(mapOf("input" to x, "state" to h, "sr" to sr)).use { output ->
                        val next = (output.get("stateN").get() as OnnxTensor).floatBuffer
                        next.get(state)
                        return (output.get("output").get() as OnnxTensor).floatBuffer.get(0)
                    }
                }
            }
        }
    }
    override fun close() { session.close() }
}
